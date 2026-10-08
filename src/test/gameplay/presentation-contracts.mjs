import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'

function plain(value) {
  if (value == null) return ''
  if (typeof value === 'string') {
    try { return plain(JSON.parse(value)) } catch { return value.replace(/\u00a7./g, '') }
  }
  if (Array.isArray(value)) return value.map(plain).join('')
  if (typeof value !== 'object') return String(value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  if ('type' in value && 'value' in value) return plain(value.value)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'presentation-contracts',
  description: 'Verify inherited sidebar hotload, value-only updates and stable native objective identity.',
  async run(context) {
    const { bot } = context
    bot.physicsEnabled = false
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Presentation acceptance requires an isolated instance')
    const root = path.join(context.server.directory, 'plugins', 'Gloss')
    const originals = new Map()
    const objectives = []
    const scores = []
    context.report.observedObjectives = objectives
    context.report.observedScores = scores
    const listen = (packet, metadata) => {
      if (metadata.name === 'scoreboard_objective') objectives.push(packet)
      if (metadata.name === 'scoreboard_score') scores.push(packet)
    }
    bot._client.on('packet', listen)
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file)) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, JSON.stringify(value, null, 2))
    }
    const catalog = title => ({ schemaVersion: 1, revision: 1,
      presets: { boards: { acceptance: { values: {
        select: { priority: 100000, when: 'true' }, variants: [],
        presentation: { title, hideNumbers: true,
          lines: [{ id: 'clock', text: 'STABLE_LABEL', value: 'VALUE {{ floor(time.seconds) }}', format: 'fixed' }],
          layout: { refresh: { titleTicks: 20, textTicks: 20, valueTicks: 1 } } }
      } } } } })
    try {
      await context.step('load an inherited sidebar', async () => {
        await save('presets.json', catalog('PRESET_INITIAL'))
        await save('boards/presentation-contract.json', { schemaVersion: 2, revision: 1, preset: 'acceptance' })
        await context.waitUntil(() => objectives.some(packet => plain(packet.displayText).includes('PRESET_INITIAL')),
          { timeoutMs: 30000, label: 'inherited sidebar title' })
        await context.waitUntil(() => scores.some(packet => plain(packet).includes('VALUE')),
          { timeoutMs: 15000, label: 'fixed number format value' })
      })
      await context.step('update only the value without recreating the objective', async () => {
        await context.sleep(1500)
        const objectiveStart = objectives.length
        const scoreStart = scores.length
        await context.sleep(3500)
        const values = scores.slice(scoreStart).filter(packet => plain(packet).includes('VALUE'))
        context.expect(new Set(values.map(packet => plain(packet))).size >= 2,
          'Changing values did not produce distinct score updates', values)
        context.expect(!objectives.slice(objectiveStart).some(packet => packet.action === 0 || packet.action === 1),
          'Value-only changes recreated or removed the objective', objectives.slice(objectiveStart))
        context.report.valueUpdates = values
      })
      await context.step('reload inherited values without rewriting the document', async () => {
        await save('presets.json', catalog('PRESET_RELOADED'))
        await context.waitUntil(() => objectives.some(packet => plain(packet.displayText).includes('PRESET_RELOADED')),
          { timeoutMs: 30000, label: 'preset change reaches existing sidebar' })
        const document = JSON.parse(await readFile(path.join(root, 'boards/presentation-contract.json'), 'utf8'))
        context.expect(document.preset === 'acceptance' && !('presentation' in document),
          'Hotload expanded the authored sparse document', document)
      })
    } finally {
      bot._client.removeListener('packet', listen)
      for (const [file, original] of [...originals].reverse()) {
        if (original == null) await rm(file, { force: true })
        else await writeFile(file, original)
      }
    }
  }
}
