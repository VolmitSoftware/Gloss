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

export default {
  name: 'gloss-public-interaction',
  description: 'Install pasteable UI fixtures, activate real inventory and holographic controls, and verify automatic hotload.',
  async run(context) {
    const { bot } = context
    context.expect(context.server.directory, 'A managed isolated instance is required')
    const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
    context.expect(/^isolated=true$/m.test(source), 'UI fixtures require an isolated instance')
    const root = path.join(context.server.directory, 'plugins', 'Gloss')
    const id = `interaction-${bot.username.toLowerCase()}`
    const owned = []
    let notice = ''
    context.report.hotloadNotices ??= []
    const onNotice = (value) => {
      notice = value.toString().replace(/\u00a7./g, '')
      if (/hotload/i.test(notice) && context.report.hotloadNotices.at(-1) !== notice) context.report.hotloadNotices.push(notice)
    }
    bot.on('actionBar', onNotice)
    const replies = []
    const onReply = (value) => { if (value.includes('GLOSS_QA_')) replies.push(value) }
    bot.on('messagestr', onReply)
    const save = async (kind, fixture, value) => {
      const filename = path.join(root, kind, `${id}.json`)
      const content = value ?? JSON.parse(await readFile(new URL(`./fixtures/${fixture}.json`, import.meta.url), 'utf8'))
      notice = ''
      await mkdir(path.dirname(filename), { recursive: true })
      await writeFile(filename, `${JSON.stringify(content, null, 2)}\n`, { flag: owned.includes(filename) ? 'w' : 'wx' })
      if (!owned.includes(filename)) owned.push(filename)
      await context.waitUntil(() => /hotloaded/i.test(notice) && notice.includes(kind), { timeoutMs: 25000, label: `${kind} hotload` })
      return content
    }
    const inventory = async (marker, revision) => {
      const window = await context.waitUntil(async () => {
        const opened = context.waitForEvent('windowOpen', () => true, 10000)
        bot.chat(`/gloss inventory open ${id} ${bot.username} args=qa=interaction`)
        const [candidate] = await opened
        if (plain(candidate.title).includes(`check r${revision} -`)) return candidate
        bot.closeWindow(candidate)
        return false
      }, { timeoutMs: 25000, intervalMs: 500, label: `inventory revision ${revision} becomes usable` })
      context.expect(plain(window.title).includes(bot.username), 'Inventory title lost viewer identity', { title: window.title, rendered: plain(window.title) })
      context.expect(window.slots[11]?.name === 'lime_dye', 'Confirmation control is missing')
      const count = replies.length
      const reply = context.waitForMessage(`${marker} ${bot.username}`, 10000)
      await bot.clickWindow(11, 0, 0)
      await reply
      await context.sleep(750)
      context.expect(replies.length === count + 1, 'One click delivered more than one action', { replies })
      context.expect(window.slots[15]?.name === 'barrier', 'Close control is missing')
      await bot.clickWindow(15, 0, 0)
      await context.waitUntil(() => !bot.currentWindow, { label: 'inventory closes through its button' })
    }
    try {
      await context.step('prepare an unobstructed interaction area', async () => {
        const setup = (command) => context.command(command, /filled|no blocks|teleported|game mode|gave/i, 10000)
        if (bot.game.gameMode !== 'spectator') await setup(`/gamemode spectator ${bot.username}`)
        await setup(`/tp ${bot.username} 0.5 81 0.5`)
        await context.waitUntil(() => bot.blockAt(bot.entity.position.offset(-6, -1, -6)) && bot.blockAt(bot.entity.position.offset(6, 6, 6)), { label: 'interaction area chunks' })
        await setup('/fill -6 80 -6 6 80 6 stone')
        await setup('/fill -6 81 -6 6 88 6 air')
        await setup(`/gamemode survival ${bot.username}`)
        await setup(`/give ${bot.username} stick 1`)
        await bot.equip(bot.inventory.items().find((item) => item.name === 'stick'), 'hand')
      })
      let document
      await context.step('paste inventory fixture and verify its actions', async () => {
        document = await save('inventories', 'interaction-inventory')
        await inventory('GLOSS_QA_CONFIRMED', 1)
      })
      await context.step('automatic hotload changes the real click result', async () => {
        document.revision += 1
        document.title = document.title.replace('check r1 -', 'check r2 -')
        document.slots['11'].actions[0].message = 'GLOSS_QA_RELOADED {{ player.name }}'
        await save('inventories', 'interaction-inventory', document)
        await inventory('GLOSS_QA_RELOADED', 2)
      })
      await context.step('paste and activate the holographic menu', async () => {
        await save('menus', 'interaction-menu')
        const keys = bot.registry.entitiesByName.text_display.metadataKeys
        const findText = (text) => Object.values(bot.entities).find((entity) => entity.name === 'text_display' && plain(entity.metadata[keys.indexOf('text')]).includes(text))
        await bot.look(Math.PI, 0, true)
        await bot.waitForTicks(2)
        bot.chat(`/gloss menu open menu=${id} args=qa=interaction`)
        const confirm = await context.waitUntil(() => findText('Confirm interaction'), { label: 'holographic control packets' })
        const aim = async (entity) => {
          const target = entity.position.offset(0, 0.1125, 0)
          const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0)
          const delta = target.minus(eye)
          const yaw = Math.atan2(-delta.x, -delta.z)
          const pitch = Math.atan2(delta.y, Math.hypot(delta.x, delta.z))
          const normalizedYaw = yaw <= 0 ? yaw + Math.PI * 2 : yaw
          const expectedYaw = (Math.PI - normalizedYaw) * 180 / Math.PI
          const expectedPitch = -pitch * 180 / Math.PI
          let attempts = 0
          const rotation = await context.waitUntil(async () => {
            await bot.look(normalizedYaw, pitch + (attempts++ % 2) * 0.003, true)
            await bot.waitForTicks(3)
            const message = await context.command(`/data get entity ${bot.username} Rotation`, /following entity data:/i, 10000)
            const values = message.match(/\[(-?[\d.]+)f?,\s*(-?[\d.]+)f?\]/i)
            context.expect(values, 'Server rotation response is unreadable', { message })
            const actual = { yaw: Number(values[1]), pitch: Number(values[2]) }
            const yawError = Math.abs(((actual.yaw - expectedYaw + 540) % 360) - 180)
            return yawError < 0.5 && Math.abs(actual.pitch - expectedPitch) < 0.5 ? actual : false
          }, { timeoutMs: 15000, label: 'server accepts holographic control aim' })
          context.report.hologramTargets ??= []
          context.report.hologramTargets.push({ position: { ...target }, eye: { ...eye }, rotation, attempts })
        }
        await aim(confirm)
        const reply = context.waitForMessage(`GLOSS_QA_HOLOGRAM ${bot.username}`, 10000)
        bot.swingArm('right')
        await reply
        const close = await context.waitUntil(() => findText('Close menu'), { label: 'holographic close control' })
        await aim(close)
        bot.swingArm('right')
        await context.waitUntil(() => !findText('Confirm interaction') && !findText('Close menu'), { label: 'holographic menu cleanup' })
      })
      context.report.ui = { fixtures: ['interaction-inventory.json', 'interaction-menu.json'], replies, renderingVerified: false }
    } finally {
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      if (!context.signal.aborted) bot.chat('/gloss menu close')
      bot.removeListener('actionBar', onNotice)
      bot.removeListener('messagestr', onReply)
      for (const filename of owned) await rm(filename, { force: true })
    }
  }
}
