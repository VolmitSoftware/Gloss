import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'

function plain(value) {
  if (value == null) return ''
  if (typeof value === 'string') {
    try { return plain(JSON.parse(value)) } catch { return value.replace(/\u00a7./g, '') }
  }
  if (Array.isArray(value)) return value.map(plain).join('')
  if (typeof value !== 'object') return ''
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  if ('value' in value) return plain(value.value)
  return Object.values(value).map(plain).join('')
}

const controlScope = process.env.GLOSS_DEMO_CONTROL_SCOPE ?? 'all'
if (!['all', 'prompts'].includes(controlScope)) throw new Error('GLOSS_DEMO_CONTROL_SCOPE must be all or prompts')

export default {
  name: controlScope === 'prompts' ? 'gloss-demo-prompt-lifecycle' : 'gloss-demo-controls',
  description: controlScope === 'prompts'
    ? 'Verify prompt submit, cancel, timeout and teleport lifecycles, with zero-XP anvil inventory preservation.'
    : 'Verify optional open arguments, list entry scope and native prompt protocol lifecycles.',
  async run(context) {
    const { bot } = context
    const previousPhysics = bot.physicsEnabled
    bot.physicsEnabled = false
    context.report.initialMovementState = { position: { ...bot.entity.position }, yaw: bot.entity.yaw,
      pitch: bot.entity.pitch, gameMode: bot.game.gameMode, attributes: bot.entity.attributes }
    context.report.autonomousPhysics = false
    context.report.controlScope = controlScope
    context.report.omittedChecks = controlScope === 'prompts'
      ? ['inventory optional arguments', 'list entry labels and action conditions'] : []
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Control acceptance requires an isolated managed instance')
    const root = path.join(context.server.directory, 'plugins', 'Gloss')
    const id = `demo-controls-${bot.username.toLowerCase()}`
    const files = []
    const messages = []
    const packets = []
    let hotload = ''
    let revision = 1
    const onMessage = value => messages.push(value.toString())
    const onPacket = (data, metadata) => packets.push({ name: metadata.name, data })
    const onHotload = value => { hotload = value.toString() }
    bot.on('messagestr', onMessage)
    bot.on('actionBar', onHotload)
    bot._client.on('packet', onPacket)
    const until = (predicate, label, timeoutMs = 20000) => context.waitUntil(predicate, { timeoutMs, label })
    const save = async (kind, value) => {
      const file = path.join(root, kind, `${id}.json`)
      await mkdir(path.dirname(file), { recursive: true })
      hotload = ''
      await writeFile(file, JSON.stringify(value, null, 2) + '\n', { flag: files.includes(file) ? 'w' : 'wx' })
      if (!files.includes(file)) files.push(file)
      await until(() => /hotloaded/i.test(hotload) && hotload.includes(kind), `${kind} hotload`, 25000)
    }
    const textKey = bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text')
    const display = text => Object.values(bot.entities).find(entity => entity.name === 'text_display'
      && plain(entity.metadata[textKey]).includes(text))
    let aimNumber = 0
    const aim = async (entity, label) => {
      const target = entity.position.offset(0, .125, 0)
      const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0)
      const delta = target.minus(eye)
      const yaw = Math.atan2(-delta.x, -delta.z)
      const normalized = yaw <= 0 ? yaw + Math.PI * 2 : yaw
      const pitch = Math.atan2(delta.y, Math.hypot(delta.x, delta.z))
      const degrees = value => ((value + 180) % 360 + 360) % 360 - 180
      const expectedYaw = degrees((Math.PI - normalized) * 180 / Math.PI)
      const expectedPitch = -pitch * 180 / Math.PI
      const diagnostic = { label, entityId: entity.id, target: { ...target }, eye: { ...eye },
        expectedYaw, expectedPitch, attempts: [] }
      context.report.controlAim ??= []
      context.report.controlAim.push(diagnostic)
      let attempt = 0
      await until(async () => {
        let transmitted
        const write = bot._client.write
        bot._client.write = function (name, data) {
          const result = write.call(this, name, data)
          if ((name === 'look' || name === 'position_look') && Number.isFinite(data.yaw) && Number.isFinite(data.pitch)
            && Math.abs(degrees(data.yaw - expectedYaw)) < .5 && Math.abs(data.pitch - expectedPitch) < .5) {
            transmitted = { name, yaw: data.yaw, pitch: data.pitch }
          }
          return result
        }
        try {
          await bot.look(normalized, pitch + (attempt++ % 2) * .003, true)
          await until(() => transmitted, 'matching control look packet is transmitted', 5000)
        } finally {
          bot._client.write = write
        }
        const marker = `DEMO_AIM_${++aimNumber}`
        const yawRange = `${degrees(expectedYaw - .5).toFixed(3)}..${degrees(expectedYaw + .5).toFixed(3)}`
        const pitchRange = `${(expectedPitch - .5).toFixed(3)}..${(expectedPitch + .5).toFixed(3)}`
        const current = { transmitted, serverAcceptedRotation: false }
        diagnostic.attempts.push(current)
        const selector = `@s[y_rotation=${yawRange},x_rotation=${pitchRange}]`
        const reply = context.waitForMessage(new RegExp(`${marker}_(OK|MISS)`), 5000)
        bot.chat(`/execute if entity ${selector} run tellraw @s {"text":"${marker}_OK"}`)
        bot.chat(`/execute unless entity ${selector} run tellraw @s {"text":"${marker}_MISS"}`)
        const response = await reply
        const accepted = response.includes(`${marker}_OK`)
        current.serverAcceptedRotation = accepted
        if (!accepted) {
          const tag = `gloss_demo_aim_${bot.username.toLowerCase()}_${aimNumber}`
          const previous = new Set(Object.keys(bot.entities))
          try {
            await context.command(`/execute at @s anchored eyes run summon minecraft:armor_stand ^ ^ ^1 {Invisible:1b,NoGravity:1b,Marker:1b,Tags:["${tag}"]}`, /summoned/i, 5000)
            const probe = await until(() => Object.values(bot.entities).find(value => value.name === 'armor_stand' && !previous.has(String(value.id))), 'server look-direction probe arrives', 5000)
            const direction = probe.position.minus(eye)
            current.observedServerYaw = Math.atan2(-direction.x, direction.z) * 180 / Math.PI
            current.observedServerPitch = -Math.atan2(direction.y, Math.hypot(direction.x, direction.z)) * 180 / Math.PI
          } finally {
            bot.chat(`/kill @e[type=minecraft:armor_stand,tag=${tag},distance=..4]`)
          }
        }
        return accepted
      }, 'server acknowledges current control rotation', 20000)
    }
    const openMenu = async text => until(async () => {
      const current = display(text)
      if (current) return current
      bot.chat('/gloss menu close')
      await context.sleep(150)
      await bot.look(Math.PI, 0, true)
      await context.sleep(150)
      bot.chat(`/gloss menu open menu=${id}`)
      await context.sleep(300)
      return display(text)
    }, 'current authored menu marker opens without args', 25000)
    const click = async text => {
      const entity = await until(() => display(text), `control ${text}`)
      await aim(entity, text)
      bot.swingArm('right')
    }
    const button = (text, actions) => ({ type: 'button', icon: { type: 'text', text },
      hitbox: { width: 1.6, height: .6 }, actions })
    const menu = components => ({ revision: revision++, offset: [0, 1.7, 3],
      followPlayer: false, lockPosition: false, closeOnTeleport: true, components })
    let promptNumber = 0
    const prompt = async (kind, timeoutTicks = 200) => {
      messages.length = 0
      packets.length = 0
      const marker = `DEMO_PROMPT_${++promptNumber}`
      await save('menus', menu([{ id: 'prompt', offset: [0, 0, 0], data: button(marker,
        [{ type: 'prompt', kind, var: 'name', label: marker + '_LABEL', initial: 'Garden', timeoutTicks,
          then: [{ type: 'message', message: 'DEMO_ANSWER {{ input.value }}' }] }]) }]))
      await openMenu(marker)
      await click(marker)
      if (kind === 'chat') await until(() => messages.some(value => value.includes(marker + '_LABEL')), 'chat prompt opens')
      if (kind === 'anvil') await until(() => bot.currentWindow?.type.includes('anvil'), 'anvil prompt opens')
      if (kind === 'sign') await until(() => packets.find(packet => packet.name === 'open_sign_entity'), 'sign editor packet')
      return marker
    }
    try {
      await context.step('prepare owned control arena', async () => {
        await context.command(`/gamemode spectator ${bot.username}`, /game mode/i, 10000)
        await context.command(`/tp ${bot.username} 400.5 81 400.5`, /teleported/i, 10000)
        await until(() => Math.hypot(bot.entity.position.x - 400.5, bot.entity.position.y - 81, bot.entity.position.z - 400.5) < .5, 'arena teleport reaches the client')
        await until(() => bot.blockAt(bot.entity.position.offset(-6, -1, -6)), 'arena chunk loads')
        await context.command('/fill 394 80 394 416 80 406 stone', /filled|no blocks/i, 10000)
        await context.command('/fill 394 81 394 416 88 406 air', /filled|no blocks/i, 10000)
        await until(() => bot.blockAt(bot.entity.position.offset(0, -1, 0))?.name === 'stone', 'arena floor reaches the client')
        await context.command(`/gamemode survival ${bot.username}`, /game mode/i, 10000)
        await context.sleep(200)
        context.expect(Math.hypot(bot.entity.position.x - 400.5, bot.entity.position.y - 81, bot.entity.position.z - 400.5) < .5, 'Player left the prepared arena')
        await context.command(`/give ${bot.username} stick 1`, /gave/i, 10000)
        await until(() => bot.inventory.items().find(item => item.name === 'stick'), 'interaction item')
        await bot.equip(bot.inventory.items().find(item => item.name === 'stick'), 'hand')
      })
      if (controlScope === 'all') {
        await context.step('inventory opens when optional args are omitted', async () => {
          await save('inventories', { schemaVersion: 1, revision: 1, show: true, title: 'DEMO_INVENTORY', resolution: '9x1',
            mask: ['.........'], slots: { '4': { type: 'button', icon: { type: 'item', item: 'lime_dye', name: 'Confirm' },
              actions: [{ type: 'message', message: 'DEMO_INVENTORY_OK' }] } } })
          const opening = context.waitForEvent('windowOpen', () => true, 10000)
          bot.chat(`/gloss inventory open ${id}`)
          const [window] = await opening
          context.expect(plain(window.title).includes('DEMO_INVENTORY'), 'Wrong inventory opened')
          await bot.clickWindow(4, 0, 0)
          await until(() => messages.some(value => value.includes('DEMO_INVENTORY_OK')), 'inventory action')
          bot.closeWindow(window)
          await until(() => !bot.currentWindow, 'inventory closes')
        })
        await context.step('list entries bind distinct labels and action conditions', async () => {
          await save('menus', menu([{ id: 'stock', offset: [-2.5, 0, 0], data: { type: 'list', var: 'entry',
            source: "['Oak','Birch','Spruce']", pageSize: 3, flow: { columns: 3, spacingX: 2.5, spacingY: .5 },
            template: { ...button('DEMO_ENTRY {{ entry }}', [
              { type: 'message', message: 'DEMO_TARGET {{ entry }}' },
              { type: 'message', when: "entry == 'Birch'", message: 'DEMO_SELECTED {{ entry }}' }
            ]), hitbox: { width: 1, height: .6 } } } }]))
          await openMenu('DEMO_ENTRY Oak')
          await until(() => display('DEMO_ENTRY Birch') && display('DEMO_ENTRY Spruce'), 'distinct per-entry text packets')
          context.report.controlTargets = ['Oak', 'Birch', 'Spruce'].map(entry => {
            const entity = display(`DEMO_ENTRY ${entry}`)
            return { entry, entityId: entity.id, position: { ...entity.position } }
          })
          const targets = context.report.controlTargets
          context.expect(targets.every((target, index) => targets.slice(index + 1).every(other =>
            Math.hypot(target.position.x - other.position.x, target.position.y - other.position.y,
              target.position.z - other.position.z) >= 2)), 'Rendered list targets do not have distinct centers', targets)
          await click('DEMO_ENTRY Birch')
          await until(() => messages.some(value => value.includes('DEMO_SELECTED Birch')), 'per-entry action scope')
          const count = messages.filter(value => value.includes('DEMO_SELECTED')).length
          const oakTargets = messages.filter(value => value.includes('DEMO_TARGET Oak')).length
          await click('DEMO_ENTRY Oak')
          await until(() => messages.filter(value => value.includes('DEMO_TARGET Oak')).length > oakTargets, 'negative condition targets the actual Oak entry')
          await context.sleep(300)
          context.expect(messages.filter(value => value.includes('DEMO_SELECTED')).length === count,
            'Entry condition allowed the wrong list item')
        })
      }
      await context.step('chat prompt submits the authored answer', async () => {
        await prompt('chat')
        bot.chat('Sunlit Garden')
        await until(() => messages.some(value => value.includes('DEMO_ANSWER Sunlit Garden')), 'chat submitted answer')
      })
      await context.step('anvil result submits the current rename', async () => {
        await context.command(`/experience set ${bot.username} 0 levels`, /experience/i, 10000)
        await context.command(`/experience set ${bot.username} 0 points`, /experience/i, 10000)
        await until(() => bot.experience.level === 0, 'zero-level anvil setup')
        const before = bot.inventory.items().map(item => ({ name: item.name, count: item.count, slot: item.slot }))
        await prompt('anvil')
        bot._client.write('name_item', { name: 'Sunlit Garden' })
        await context.sleep(200)
        await bot.clickWindow(2, 0, 0)
        await until(() => messages.some(value => value.includes('DEMO_ANSWER Sunlit Garden')), 'current anvil rename answer')
        await until(() => !bot.currentWindow, 'anvil result closes')
        await context.sleep(150)
        context.expect(bot.experience.level === 0 && bot.experience.points === 0, 'Anvil prompt consumed or granted XP')
        const after = bot.inventory.items().map(item => ({ name: item.name, count: item.count, slot: item.slot }))
        context.expect(JSON.stringify(after) === JSON.stringify(before), 'Anvil prompt leaked seed paper or changed inventory')
      })
      await context.step('closing an anvil cancels without executing its continuation', async () => {
        await prompt('anvil')
        bot.closeWindow(bot.currentWindow)
        await until(() => !bot.currentWindow, 'cancelled anvil closes')
        await context.sleep(150)
        context.expect(!messages.some(value => value.includes('DEMO_ANSWER')), 'Cancelled anvil executed its continuation')
        await prompt('chat')
        bot.chat('After cancellation')
        await until(() => messages.some(value => value.includes('DEMO_ANSWER After cancellation')), 'next prompt after cancellation')
      })
      await context.step('sign packets submit text and release the pending prompt', async () => {
        await prompt('sign')
        const sign = packets.find(packet => packet.name === 'open_sign_entity').data
        bot._client.write('update_sign', { location: sign.location, isFrontText: true,
          text1: 'Sunlit', text2: 'Garden', text3: '', text4: '' })
        await until(() => messages.some(value => value.includes('DEMO_ANSWER Sunlit Garden')), 'sign submitted answer')
      })
      await context.step('timed-out prompts permit a fresh prompt without a continuation', async () => {
        await prompt('chat', 20)
        await until(() => messages.some(value => value.includes('Input timed out')), 'server confirms prompt timeout')
        context.expect(!messages.some(value => value.includes('DEMO_ANSWER')), 'Timed-out chat prompt executed its continuation')
        await prompt('chat')
        bot.chat('After timeout')
        await until(() => messages.some(value => value.includes('DEMO_ANSWER After timeout')), 'next prompt after timeout')
      })
      await context.step('world transfer cancels an unfinished sign prompt', async () => {
        await prompt('sign')
        await context.command(`/gamemode spectator ${bot.username}`, /game mode/i, 10000)
        await context.command(`/execute in minecraft:the_nether run tp ${bot.username} 0.5 90 0.5`, /teleported/i, 10000)
        await until(() => String(bot.game.dimension).includes('nether') && Math.hypot(bot.entity.position.x - .5, bot.entity.position.y - 90, bot.entity.position.z - .5) < .5, 'client confirms Nether world transfer')
        context.expect(!messages.some(value => value.includes('DEMO_ANSWER')), 'World transfer submitted an unfinished sign')
        await context.command(`/execute in minecraft:overworld run tp ${bot.username} 400.5 81 400.5`, /teleported/i, 10000)
        await until(() => String(bot.game.dimension).includes('overworld') && Math.hypot(bot.entity.position.x - 400.5, bot.entity.position.y - 81, bot.entity.position.z - 400.5) < .5, 'client confirms return to the prepared arena')
        await context.command(`/gamemode survival ${bot.username}`, /game mode/i, 10000)
        await prompt('chat')
        bot.chat('After world transfer')
        await until(() => messages.some(value => value.includes('DEMO_ANSWER After world transfer')), 'fresh prompt after sign world transfer')
      })
      await context.step('teleport closes a menu with no stranded controls', async () => {
        await save('menus', menu([{ id: 'teleport', offset: [0, 0, 0], data: button('DEMO_TELEPORT', []) }]))
        await openMenu('DEMO_TELEPORT')
        await context.command(`/tp ${bot.username} 410.5 81 400.5`, /teleported/i, 10000)
        await until(() => Math.hypot(bot.entity.position.x - 410.5, bot.entity.position.y - 81, bot.entity.position.z - 400.5) < .5, 'client confirms menu teleport destination')
        context.report.menuTeleportPosition = { ...bot.entity.position }
        await until(() => !display('DEMO_TELEPORT'), 'teleport removes menu control packets')
      })
      context.report.controls = { ...(controlScope === 'all'
        ? { omittedArgs: true, listEntries: ['Oak', 'Birch', 'Spruce'], scopedAction: 'Birch' } : {}),
        prompts: ['chat', 'anvil', 'sign'], renderingVerified: false }
    } finally {
      bot.physicsEnabled = previousPhysics
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      if (!context.signal.aborted) bot.chat('/gloss menu close')
      bot.removeListener('messagestr', onMessage)
      bot.removeListener('actionBar', onHotload)
      bot._client.removeListener('packet', onPacket)
      for (const file of files) await rm(file, { force: true })
    }
  },
}
