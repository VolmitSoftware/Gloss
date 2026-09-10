import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'

function plain(value) {
  if (value == null) return ''
  if (typeof value === 'string') {
    if (value.startsWith('{') || value.startsWith('[')) {
      try { return plain(JSON.parse(value)) } catch {}
    }
    return value.replace(/\u00a7./g, '')
  }
  if (Array.isArray(value)) return value.map(plain).join('')
  if (typeof value !== 'object') return ''
  if (value.type && 'value' in value) return plain(value.value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  return Object.values(value).map(plain).join('')
}

const style = { billboard: 'center', shadow: true, seeThrough: true, textAlignment: 'left',
  backgroundArgb: '#40224466', textOpacity: 200, lineWidth: 180, blockLight: 8, skyLight: 12,
  viewRange: 1, scaleX: 1.2, scaleY: 0.7, scaleZ: 0.9 }
const box = { enabled: true, padding: 7, borderWidth: 2,
  backgroundArgb: '#B3123456', borderArgb: '#FFABCDEF' }

export default {
  name: 'display-authoring',
  description: 'Verify authored styles, boxes, visibility and cleanup for shared Gloss text surfaces.',
  async run(context) {
    const { bot } = context
    const instancePath = process.env.GLOSS_QA_INSTANCE_PATH
    context.expect(instancePath && path.basename(instancePath) === context.server.instance,
      'GLOSS_QA_INSTANCE_PATH must name the current isolated instance')
    const root = path.join(instancePath, 'plugins/Gloss')
    const originals = new Map()
    let actionBar = ''
    const onActionBar = message => { actionBar = message.toString().replace(/\u00a7./g, '') }
    bot.on('actionBar', onActionBar)
    const keys = bot.registry.entitiesByName.text_display.metadataKeys
    const displays = () => Object.values(bot.entities).filter(entity => entity.name === 'text_display'
      && entity.position.y > 190 && Math.abs(entity.position.x) < 20 && Math.abs(entity.position.z) < 20)
    const text = entity => plain(entity.metadata[keys.indexOf('text')])
    const marked = marker => displays().find(entity => text(entity).toLowerCase().includes(marker.toLowerCase()))
    const allBoxParts = () => displays().filter(entity =>
      [0x123456, 0xABCDEF].includes(entity.metadata[keys.indexOf('background_color')] & 0xFFFFFF))
    const until = async (predicate, message, timeout = 12000) => {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        const value = predicate()
        if (value) return value
        await context.sleep(50)
      }
      context.expect(false, message, { displays: displays().map(entity => ({ id: entity.id, text: text(entity),
        position: entity.position, metadata: entity.metadata })) })
    }
    const command = (value, pattern = /./) => context.command(value, pattern, 10000)
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      let current = null
      try { current = await readFile(file, 'utf8') }
      catch (error) { if (error.code !== 'ENOENT') throw error }
      if (!originals.has(file)) originals.set(file, current)
      const content = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
      if (content === current) return
      const kind = relative.split('/')[0]
      await until(() => !actionBar.includes('Hotloaded'), 'The previous hotload notice did not clear', 10000)
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, content)
      await until(() => actionBar.includes('Hotloaded') && actionBar.includes(kind),
        `${relative} did not report a successful automatic hotload`, 20000)
      context.report.hotloads.push({ file: relative, notice: actionBar })
    }
    const expectStyle = async marker => {
      const entity = await until(() => marked(marker), `${marker} text did not appear`)
      await until(() => allBoxParts().length === 5, `${marker} did not own one panel and four edges`)
      const scale = entity.metadata[keys.indexOf('scale')]
      const flags = entity.metadata[keys.indexOf('style_flags')]
      context.expect(Math.abs(scale.x - style.scaleX) < 0.001 && Math.abs(scale.y - style.scaleY) < 0.001
        && Math.abs(scale.z - style.scaleZ) < 0.001, `${marker} lost independent scales`, { scale })
      context.expect((flags & 11) === 11, `${marker} lost shadow, see-through, or alignment`, { flags })
      context.expect((entity.metadata[keys.indexOf('background_color')] >>> 0) === 0x40224466,
        `${marker} lost the independent text background`)
      context.report.styles.push({ marker, text: text(entity), scale, flags,
        boxIds: allBoxParts().map(part => part.id) })
      return entity
    }
    context.report.styles = []
    context.report.hotloads = []
    try {
      await context.step('Prepare isolated display controls', async () => {
        bot.chat('/gamemode creative @s')
        await until(() => bot.game.gameMode === 'creative', 'Creative mode did not reach the client')
        bot.creative.startFlying()
        await command('/gamerule minecraft:spawn_mobs false', /false/i)
        await command('/tp @s 0.5 196 0.5', /teleported/i)
        await until(() => bot.entity.position.y > 195, 'The test platform was not reached')
        bot.creative.startFlying()
        await until(() => bot.blockAt(bot.entity.position.clone().set(-8, 195, -8))
          && bot.blockAt(bot.entity.position.clone().set(8, 195, 12)), 'The platform chunks did not arrive')
        await command('/fill -8 195 -8 8 195 12 minecraft:stone', /filled|blocks|no blocks/i)
        await command('/kill @e[type=minecraft:item,x=-20,y=190,z=-20,dx=40,dy=30,dz=40]', /killed|found/i)
        const overlay = JSON.parse(await readFile(path.join(root, 'entity-overlays/default.json'), 'utf8'))
        await save('entity-overlays/default.json', { ...overlay, enabled: false, revision: overlay.revision + 1 })
        await until(() => bot.entity.position.y > 195, 'The test platform was not reached')
      })
      await context.step('Persistent holograms retain full style and resize boxes', async () => {
        const holo = { schemaVersion: 3, revision: 1, anchor: { world: 'world', position: [0, 198, 5] },
          lines: ['<aqua>AUTHOR_HOLOGRAM</aqua>'], style, box, particleLayers: [], show: true }
        await save('holograms/authoring-qa.json', holo)
        const hologram = await expectStyle('AUTHOR_HOLOGRAM')
        context.expect(!text(hologram).includes('<aqua>'), 'Persistent authored text retained literal rich markup')
        const firstWidth = allBoxParts().find(entity =>
          (entity.metadata[keys.indexOf('background_color')] & 0xFFFFFF) === 0x123456).metadata[keys.indexOf('scale')].x
        await save('holograms/authoring-qa.json', { ...holo, revision: 2,
          lines: ['AUTHOR_HOLOGRAM_LONGER_CONTENT_FOR_RESIZE', 'Second line'] })
        await until(() => marked('LONGER_CONTENT'), 'Persistent text did not hot reload')
        await until(() => allBoxParts().some(entity =>
          (entity.metadata[keys.indexOf('background_color')] & 0xFFFFFF) === 0x123456 &&
          entity.metadata[keys.indexOf('scale')].x > firstWidth), 'Persistent box did not resize')
        await save('holograms/authoring-qa.json', { ...holo, revision: 3, show: false })
        await until(() => !marked('AUTHOR_HOLOGRAM') && allBoxParts().length === 0,
          'Hidden hologram retained text or decoration')
      })
      await context.step('Real Drops labels share style, box and audience', async () => {
        const naming = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', naming.replace(/^nameFormat\s*=.*$/m,
          'nameFormat = "AUTHOR_DROP {{ player.name }} {count}x {type}"'))
        const drop = JSON.parse(await readFile(path.join(root, 'real-drops/default.json'), 'utf8'))
        drop.presentation.labels.style = style
        drop.presentation.labels.box = box
        drop.revision++
        await save('real-drops/default.json', drop)
        await command('/summon minecraft:item 3 196 4 {Item:{id:"minecraft:diamond",count:16},NoGravity:1b,PickupDelay:32767s,Tags:["author_drop"]}', /summoned/i)
        const diamond = await expectStyle('Diamond')
        context.expect(text(diamond).includes(bot.username), 'Real Drops label lost its viewer expression', { text: text(diamond) })
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/realDrops\s*=\s*true/, 'realDrops = false'))
        await until(() => Object.values(bot.entities).some(entity => entity.name === 'item'
          && entity.position.y > 190 && Math.abs(entity.position.x - 3) < 2
          && Math.abs(entity.position.z - 4) < 2), 'Disabling boxed Real Drops left the collectible item hidden')
        await save('gloss.toml', config)
        await expectStyle('Diamond')
        await save('real-drops/default.json', { ...drop, revision: drop.revision + 1, show: false })
        await until(() => !marked('Diamond') && allBoxParts().length === 0,
          'Hidden Real Drops retained their labels or boxes')
        await command('/kill @e[tag=author_drop]', /killed/i)
      })
      await context.step('Preserved custom names stay literal through item merges', async () => {
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/preserveCustomNames\s*=\s*false/, 'preserveCustomNames = true'))
        const drop = JSON.parse(await readFile(path.join(root, 'real-drops/default.json'), 'utf8'))
        await save('real-drops/default.json', { ...drop, revision: drop.revision + 1, show: true })
        const summon = '/summon minecraft:item 3 196 4 {Item:{id:"minecraft:paper",count:1},CustomName:{text:"<red>AUTHOR_LITERAL</red>"},CustomNameVisible:1b,NoGravity:1b,PickupDelay:600s,Tags:["author_literal"]}'
        await command(summon, /summoned/i)
        const literal = await expectStyle('AUTHOR_LITERAL')
        context.expect(text(literal) === '<red>AUTHOR_LITERAL</red>', 'Preserved custom name was interpreted as authored formatting')
        await command(summon, /summoned/i)
        await context.sleep(4000)
        await until(() => allBoxParts().length === 5, 'The custom items did not merge into one presentation')
        const merged = await until(() => marked('AUTHOR_LITERAL'), 'Merging the item removed its preserved label')
        context.expect(text(merged) === '<red>AUTHOR_LITERAL</red>', 'Merged custom name changed text mode')
        await command('/kill @e[tag=author_literal]', /^Killed <red>AUTHOR_LITERAL<\/red>$/)
        await until(() => !marked('AUTHOR_LITERAL') && allBoxParts().length === 0, 'Removed custom item retained its label')
      })
      await context.step('Standalone drop names retain the same authored engine controls', async () => {
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        context.expect(/realDrops\s*=\s*true/.test(config), 'Expected enabled Real Drops feature')
        await save('gloss.toml', config.replace(/realDrops\s*=\s*true/, 'realDrops = false')
          .replace(/^nameFormat\s*=.*$/m, 'nameFormat = "AUTHOR_DROP {{ player.name }} {count}x {type}"'))
        const drop = JSON.parse(await readFile(path.join(root, 'real-drops/default.json'), 'utf8'))
        await save('real-drops/default.json', { ...drop, revision: drop.revision + 1, show: true })
        await command('/summon minecraft:item 3 196 4 {Item:{id:"minecraft:emerald",count:7},NoGravity:1b,PickupDelay:32767s,Tags:["author_drop"]}', /summoned/i)
        const emerald = await expectStyle('Emerald')
        context.expect(text(emerald).includes(bot.username), 'Standalone label lost its viewer expression', { text: text(emerald) })
        await command('/tp @s 600 196 0.5', /teleported/i)
        await until(() => !marked('Emerald') && allBoxParts().length === 0, 'Leaving the drop retained personalized displays')
        await command('/tp @s 0.5 196 0.5', /teleported/i)
        const returned = await expectStyle('Emerald')
        context.expect(text(returned).includes(bot.username), 'Returning to the drop lost personalized text')
        await command('/kill @e[tag=author_drop]', /killed/i)
        await until(() => !marked('Emerald') && allBoxParts().length === 0, 'Removed drop retained a decoration')
      })
      await context.step('Bubbles use independent style and decorative boxes', async () => {
        const bubble = JSON.parse(await readFile(path.join(root, 'bubbles/default.json'), 'utf8'))
        bubble.revision++
        bubble.prefix = '<gold>RICH_PREFIX</gold> '
        bubble.style = style
        bubble.box = box
        bubble.hideOwn = false
        bubble.maxAliveMs = 60000
        bubble.motion = { translation: { x: '0', y: '0', z: '0' }, scale: { x: '1', y: '1', z: '1' },
          rotation: { x: '0', y: '0', z: '0' }, opacity: '1' }
        bubble.shimmer.spawn = false
        bubble.shimmer.flyAway = false
        await save('bubbles/default.json', bubble)
        const bubbleStartedAt = Date.now()
        await command('AUTHOR_BUBBLE', /AUTHOR_BUBBLE/)
        const bubbleText = await expectStyle('AUTHOR_BUBBLE')
        context.expect(text(bubbleText).includes('RICH_PREFIX') && !text(bubbleText).includes('<gold>'),
          'Configured bubble prefix did not pass through rich text', { text: text(bubbleText) })
        await save('bubbles/default.json', { ...bubble, revision: bubble.revision + 1, show: false })
        await until(() => !marked('AUTHOR_BUBBLE') && allBoxParts().length === 0, 'Bubble reload left decoration behind')
        context.expect(Date.now() - bubbleStartedAt < bubble.maxAliveMs - 1000,
          'Bubble cleanup was not observed before its natural expiry')
      })
      await context.step('Damage indicators allow authored labels without amount tokens', async () => {
        const indicators = JSON.parse(await readFile(path.join(root, 'damage-indicators/default.json'), 'utf8'))
        indicators.revision++
        indicators.limits.lifetimeMs = 5000
        indicators.damage.presentation.format = 'AUTHOR_HIT'
        indicators.damage.presentation.style = style
        indicators.damage.presentation.box = box
        indicators.damage.presentation.motion = { horizontalSpeed: 0, verticalSpeed: 0, verticalAcceleration: 0, spinDegreesPerSecond: 0 }
        indicators.damage.presentation.transform = { startScale: 1, endScale: 1, fadeStartFraction: 1 }
        await save('damage-indicators/default.json', indicators)
        await command('/summon husk 1 196 5 {NoAI:1b,Silent:1b,Tags:["author_target"]}', /summoned/i)
        await command('/damage @e[tag=author_target,limit=1] 2 minecraft:generic', /damage/i)
        await expectStyle('AUTHOR_HIT')
        await until(() => !marked('AUTHOR_HIT') && allBoxParts().length === 0, 'Expired indicator retained decoration', 10000)
        await command('/kill @e[tag=author_target]', /killed/i)
      })
      await context.step('Menu text boxes share packet styles and retire on close', async () => {
        const fixture = await readFile(new URL('./fixtures/display-authoring-menu.json', import.meta.url), 'utf8')
        await save('menus/authoring-qa.json', fixture)
        for (let attempt = 0; attempt < 12 && !marked('Authoring probe'); attempt++) {
          bot.chat('/gloss menu open authoring-qa')
          await context.sleep(1000)
        }
        const entity = await until(() => marked('Authoring probe'), 'Styled menu text did not open')
        const border = () => displays().filter(display =>
          (display.metadata[keys.indexOf('background_color')] & 0xFFFFFF) === 0x667788)
        await until(() => border().length === 4, 'Menu text did not create its four authored border edges')
        const scale = entity.metadata[keys.indexOf('scale')]
        context.expect(Math.abs(scale.x / scale.y - 1.3 / 0.8) < 0.01
          && Math.abs(scale.z / scale.y - 1.7 / 0.8) < 0.01, 'Menu text lost independent scale', { scale })
        context.expect((entity.metadata[keys.indexOf('style_flags')] & 19) === 19,
          'Menu style flags lost right alignment, shadow, or see-through')
        const ids = border().map(display => display.id)
        context.report.styles.push({ marker: 'menu', scale, borderIds: ids })
        await command('/gloss menu close', /closed|close/i)
        await until(() => !marked('Authoring probe') && ids.every(id => !bot.entities[id]),
          'Closing the menu retained text decoration packets')
      })
      await context.step('Container previews apply card chrome, label boxes and item styles', async () => {
        const fixture = await readFile(new URL('./fixtures/display-authoring-preview.json', import.meta.url), 'utf8')
        await save('previews/authoring-qa.json', fixture)
        await command('/setblock 0 196 4 minecraft:air', /changed|placed|could not/i)
        await command('/setblock 0 196 4 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:12}]}', /changed|placed/i)
        await bot.lookAt(bot.entity.position.clone().set(0.5, 196.5, 4.5), true)
        const label = await until(() => marked('Box label'), 'Authored preview label did not appear')
        const border = () => displays().filter(display =>
          (display.metadata[keys.indexOf('background_color')] & 0xFFFFFF) === 0x667788)
        await until(() => border().length === 4, 'Preview label did not create four border edges')
        context.expect(displays().some(display =>
          (display.metadata[keys.indexOf('background_color')] & 0xFFFFFF) === 0x445566),
          'Preview card border color did not reach metadata')
        const items = Object.values(bot.entities).filter(entity => entity.name === 'item_display' && entity.position.y > 190
          && Math.abs(entity.position.x) < 20 && Math.abs(entity.position.z) < 20)
        context.expect(items.length > 0, 'Preview item did not appear')
        const itemKeys = bot.registry.entitiesByName.item_display.metadataKeys
        const itemScale = items[0].metadata[itemKeys.indexOf('scale')]
        context.expect(Math.abs(itemScale.x / itemScale.y - 0.8 / 1.2) < 0.01
          && Math.abs(itemScale.z / itemScale.y - 0.6 / 1.2) < 0.01,
          'Preview item lost its independent style scales', { itemScale })
        const ids = [label.id, ...border().map(display => display.id), ...items.map(item => item.id)]
        context.report.styles.push({ marker: 'preview', itemScale, ids })
        await bot.lookAt(bot.entity.position.offset(0, 2, -5), true)
        await until(() => ids.every(id => !bot.entities[id]), 'Looking away retained preview decorations')
        await command('/setblock 0 196 4 minecraft:air', /changed|placed/i)
      })
    } finally {
      try {
        for (const [file, content] of originals) {
          if (content === null) await rm(file, { force: true })
          else await writeFile(file, content)
        }
        bot.creative.stopFlying()
        if (bot._client.state === 'play') {
          await command('/kill @e[tag=author_drop]', /killed|found/i)
          await command('/kill @e[tag=author_target]', /killed|found/i)
          await command('/kill @e[tag=author_literal]', /killed|found/i)
          await until(() => !displays().some(entity => /AUTHOR_|Authoring probe|Box label/.test(text(entity)))
            && allBoxParts().length === 0, 'Restoring files retained authored text or decoration', 20000)
        }
      } finally {
        bot.removeListener('actionBar', onActionBar)
      }
    }
  }
}
