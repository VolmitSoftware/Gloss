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

export default {
  name: 'audit-bugs',
  description: 'Verify private-message recipients, expression failure output, board titles, layout names and marker lifetime.',
  async run(context) {
    const { bot } = context
    const instance = process.env.GLOSS_QA_INSTANCE_PATH
    context.expect(instance && path.basename(instance) === context.server.instance,
      'GLOSS_QA_INSTANCE_PATH must name this isolated instance')
    const root = path.join(instance, 'plugins/Gloss')
    const originals = new Map()
    const messages = []
    const objectivePackets = []
    const infoPackets = context.report.playerInfo = []
    const onInfo = packet => { if (infoPackets.length < 150) infoPackets.push(packet) }
    const onMessage = message => messages.push(message.toString())
    const onObjective = packet => objectivePackets.push(packet)
    bot.on('message', onMessage)
    bot._client.on('scoreboard_objective', onObjective)
    bot._client.on('player_info', onInfo)
    const until = (predicate, label, timeoutMs = 15000) => context.waitUntil(predicate, { timeoutMs, label })
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file, 'utf8')) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2))
    }
    const keys = bot.registry.entitiesByName.text_display.metadataKeys
    const displayText = entity => plain(entity.metadata[keys.indexOf('text')])
    const displays = () => Object.values(bot.entities).filter(entity => entity.name === 'text_display')
    const marked = value => displays().find(entity => displayText(entity).includes(value))
    let peer
    try {
      await context.step('Enable channels and connect the recipient', async () => {
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/^channels\s*=\s*false/gm, 'channels = true'))
        await context.sleep(3500)
        peer = await context.connectActor('GlossPeer')
        bot.chat('/gamemode creative @s')
        await until(() => bot.game.gameMode === 'creative', 'creative mode')
        bot.creative.startFlying()
      })
      await context.step('Both private-message copies retain the intended recipient', async () => {
        const received = []
        const listener = message => received.push(message.toString())
        peer.bot.on('message', listener)
        try {
          bot.chat('/gloss:msg GlossPeer PRIVATE_AUDIT')
          await until(() => messages.some(value => value.includes('PRIVATE_AUDIT'))
            && received.some(value => value.includes('PRIVATE_AUDIT')), 'both private-message copies')
          const expected = `[${bot.username} -> GlossPeer] PRIVATE_AUDIT`
          context.expect(messages.some(value => value.includes(expected)), 'Sender copy used wrong recipient', messages)
          context.expect(received.some(value => value.includes(expected)), 'Recipient copy used wrong recipient', received)
          context.report.privateMessage = expected
        } finally { peer.bot.removeListener('message', listener) }
      })
      const position = bot.entity.position
      const anchor = { world: 'world', position: [position.x + 2, position.y + 2, position.z + 2] }
      await context.step('Failed text expressions are hidden without dropping surrounding text', async () => {
        await save('holograms/audit-expression.json', { schemaVersion: 3, revision: 1, anchor,
          lines: ['AUDIT_EXPR {{ noSuchFunction() }} END {{ 1 + 2 }}'], show: true })
        await until(() => marked('AUDIT_EXPR'), 'expression hologram')
        const text = displayText(marked('AUDIT_EXPR'))
        context.expect(text === 'AUDIT_EXPR  END 3', 'Failed expression leaked source or lost surrounding text', { text })
        context.report.expression = text
      })
      await context.step('Explicitly blank board titles stay blank', async () => {
        await save('boards/audit-board.json', { schemaVersion: 2, revision: 1,
          select: { priority: 1000, when: 'true' }, presentation: { title: '', hideNumbers: true,
            lines: [{ text: 'AUDIT_NUMBER', format: 'number' },
              { text: 'AUDIT_STYLED', format: 'styled', value: '&c' }] }, variants: [] })
        await until(() => objectivePackets.some(packet => packet.action === 0 || packet.action === 2), 'board objective packet')
        const packet = objectivePackets.findLast(packet => packet.action === 0 || packet.action === 2)
        context.expect(plain(packet.displayText).trim() === '', 'Blank board title was replaced', packet)
        context.report.boardObjective = packet
      })
      await context.step('Layout player cells use configured names and sort weight', async () => {
        await save('tablist.json', { schemaVersion: 2, revision: 20,
          headerFooter: { enabled: true, presentation: { header: 'AUDIT_TAB', footer: '' } },
          listNames: { enabled: true, presentation: { format: 'CELL $player' }, variants: [] },
          sort: { enabled: true, weight: "subject.name == 'GlossPeer' ? 100 : 0" },
          layout: { enabled: true, columns: 1, rows: 2, show: true,
            players: { column: 0, columns: 1, rows: 2, filter: 'true', overflow: 'hide' } } })
        const cells = () => {
          context.report.observedPlayers = Object.values(bot.players).map(player => ({ username: player.username, displayName: player.displayName, ping: player.ping }))
          return Object.values(bot.players).filter(player => player.username?.startsWith(' gloss_slot_'))
        }
        await until(() => cells().some(player => plain(player.displayName).includes('CELL GlossPeer')), 'formatted layout cell')
        const first = cells().find(player => player.username === ' gloss_slot_0')
        context.expect(plain(first?.displayName) === 'CELL GlossPeer', 'Player sort weight was ignored', cells())
        context.report.layout = cells().map(player => ({ name: player.username,
          display: plain(player.displayName), ping: player.ping }))
      })
      await context.step('Marker icons render and expire with their label', async () => {
        await save('markers/audit-marker.json', { schemaVersion: 1, revision: 1, show: true,
          anchor: { world: 'world', x: position.x + 3, y: position.y + 2, z: position.z + 3 },
          label: 'AUDIT_MARKER', icon: { type: 'text', text: 'AUDIT_ICON' }, lifetimeTicks: 100 })
        await until(() => {
          context.report.markerDisplays = displays().map(entity => ({ id: entity.id, text: displayText(entity) }))
          context.report.markerSeen = {
            label: context.report.markerSeen?.label || Boolean(marked('AUDIT_MARKER')),
            icon: context.report.markerSeen?.icon || Boolean(marked('AUDIT_ICON'))
          }
          return marked('AUDIT_MARKER') && marked('AUDIT_ICON')
        }, 'marker label and icon')
        await until(() => !marked('AUDIT_MARKER') && !marked('AUDIT_ICON'), 'expired marker cleanup', 12000)
        await context.sleep(1500)
        context.expect(!marked('AUDIT_MARKER') && !marked('AUDIT_ICON'), 'Expired marker restarted')
        context.report.markerExpired = true
      })
    } finally {
      bot.removeListener('message', onMessage)
      bot._client.removeListener('scoreboard_objective', onObjective)
      bot._client.removeListener('player_info', onInfo)
      for (const [file, content] of originals) {
        if (content === null) await rm(file, { force: true })
        else await writeFile(file, content)
      }
    }
  }
}
