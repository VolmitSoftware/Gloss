import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'

function plain(value) {
  if (value == null) return ''
  if (typeof value === 'string') {
    try { return plain(JSON.parse(value)) } catch { return value.replace(/\u00a7./g, '') }
  }
  if (Array.isArray(value)) return value.map(plain).join('')
  if (typeof value !== 'object') return String(value)
  if ('value' in value) return plain(value.value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'board-condition-scopes',
  description: 'Verify literal page rotation and current viewer row/page conditions across document reloads.',
  async run(context) {
    const { bot } = context
    bot.physicsEnabled = false
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Board condition acceptance requires an isolated instance')
    const root = path.join(context.server.directory, 'plugins/Gloss')
    const originals = new Map()
    const titles = new Map()
    const scores = new Map()
    const observed = []
    const listen = (packet, metadata) => {
      if (metadata.name === 'scoreboard_objective') {
        if (packet.action === 1) {
          titles.delete(packet.name)
          scores.delete(packet.name)
        } else titles.set(packet.name, plain(packet.displayText))
      }
      if (metadata.name === 'scoreboard_score') {
        if (!scores.has(packet.scoreName)) scores.set(packet.scoreName, new Map())
        if (packet.action === 1) scores.get(packet.scoreName).delete(packet.itemName)
        else scores.get(packet.scoreName).set(packet.itemName, packet.display_name)
      }
      if (metadata.name === 'reset_score') {
        for (const [name, rows] of scores) {
          if (packet.objective_name == null || name === packet.objective_name) rows.delete(packet.entity_name)
        }
      }
    }
    bot._client.on('packet', listen)
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file)) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n')
    }
    const current = () => [...titles].filter(([, title]) => title.startsWith('SCOPE_')).map(([name, title]) => ({
      title, rows: [...(scores.get(name) ?? new Map())].map(([entry, display]) => display == null
        ? plain(bot.teamMap[entry]?.displayName(entry)?.json ?? entry) : plain(display))
    }))
    const expectFrame = async (title, present, absent = []) => {
      const frame = await context.waitUntil(() => current().find(value => value.title === title
        && present.every(text => value.rows.some(row => row.includes(text)))
        && absent.every(text => value.rows.every(row => !row.includes(text)))),
      { timeoutMs: 15000, label: `${title} rows ${present.join(',')} without ${absent.join(',')}` })
      observed.push({ ...frame, x: bot.entity.position.x })
    }
    const literal = revision => ({ schemaVersion: 2, revision,
      select: { priority: 1000000, when: 'true' }, variants: [], presentation: {
        title: `SCOPE_BASE_${revision}`, lines: ['SCOPE_FALLBACK'], hideNumbers: true, layout: { pages: [
          { id: 'a', title: `SCOPE_LITERAL_A_${revision}`, durationTicks: 10, show: '(true)',
            lines: ['SCOPE_A', { text: 'SCOPE_HIDDEN', show: false }] },
          { id: 'b', title: `SCOPE_LITERAL_B_${revision}`, durationTicks: 10, show: true, lines: ['SCOPE_B'] }
        ] } } })
    const dynamic = { schemaVersion: 2, revision: 2, select: { priority: 1000000, when: 'true' }, variants: [],
      presentation: { title: 'SCOPE_DYNAMIC_BASE', lines: ['SCOPE_FALLBACK'], hideNumbers: true, layout: { pages: [
        { id: 'left', title: 'SCOPE_LEFT_PAGE', show: 'viewer.x < 4', durationTicks: 10,
          lines: ['SCOPE_LEFT', { id: 'gated', text: 'SCOPE_GATED', show: 'viewer.x < 2', value: '10', format: 'fixed' }] },
        { id: 'right', title: 'SCOPE_RIGHT_PAGE', show: 'viewer.x >= 4', durationTicks: 10,
          lines: [{ text: 'SCOPE_RIGHT', show: 'viewer.x >= 4' }] }
      ] } } }
    const move = async x => {
      await context.command(`/tp ${bot.username} ${x} 81 0.5`, /teleported/i, 10000)
      await context.waitUntil(() => Math.abs(bot.entity.position.x - x) < 0.1,
        { timeoutMs: 5000, label: `viewer reaches x=${x}` })
    }
    try {
      await context.step('literal pages rotate and suppress false rows', async () => {
        if (bot.game.gameMode !== 'creative') {
          await context.command(`/gamemode creative ${bot.username}`, /game mode|creative/i, 10000)
        }
        await move(0.5)
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/^boards\s*=\s*false/gm, 'boards = true'))
        await save('boards/condition-scopes.json', literal(1))
        await expectFrame('SCOPE_LITERAL_A_1', ['SCOPE_A'], ['SCOPE_HIDDEN', 'SCOPE_B'])
        await expectFrame('SCOPE_LITERAL_B_1', ['SCOPE_B'], ['SCOPE_HIDDEN', 'SCOPE_A'])
      })
      await context.step('hotloaded dynamic page and row conditions track both transitions', async () => {
        await save('boards/condition-scopes.json', dynamic)
        await expectFrame('SCOPE_LEFT_PAGE', ['SCOPE_LEFT', 'SCOPE_GATED'], ['SCOPE_RIGHT'])
        await move(2.5)
        await expectFrame('SCOPE_LEFT_PAGE', ['SCOPE_LEFT'], ['SCOPE_GATED', 'SCOPE_RIGHT'])
        await move(8.5)
        await expectFrame('SCOPE_RIGHT_PAGE', ['SCOPE_RIGHT'], ['SCOPE_LEFT', 'SCOPE_GATED'])
        await move(0.5)
        await expectFrame('SCOPE_LEFT_PAGE', ['SCOPE_LEFT', 'SCOPE_GATED'], ['SCOPE_RIGHT'])
      })
      await context.step('literal replacement reload restores independent rotation', async () => {
        await move(8.5)
        await save('boards/condition-scopes.json', literal(3))
        await expectFrame('SCOPE_LITERAL_A_3', ['SCOPE_A'], ['SCOPE_HIDDEN', 'SCOPE_RIGHT'])
        await expectFrame('SCOPE_LITERAL_B_3', ['SCOPE_B'], ['SCOPE_HIDDEN', 'SCOPE_LEFT'])
      })
      context.report.boardConditionFrames = observed
      context.report.renderingVerified = false
    } finally {
      bot._client.removeListener('packet', listen)
      for (const [file, original] of [...originals].reverse()) {
        if (original == null) await rm(file, { force: true })
        else await writeFile(file, original)
      }
    }
  }
}
