import { readFile, writeFile, readdir } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'integration-snapshots',
  description: 'Verify baseline API linkage and live Adapt, React, and Iris snapshot publication across plugin classloaders.',
  async run(context) {
    const { bot } = context
    bot.physicsEnabled = false
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Integration acceptance requires an isolated instance')
    const root = path.join(context.server.directory, 'plugins/Gloss')
    const configFile = path.join(root, 'gloss.toml')
    const original = await readFile(configFile, 'utf8')
    const messages = []
    const samples = { before: {}, after: {} }
    let configChanged = false
    const providers = ['adapt', 'react', 'iris']
    const listener = message => messages.push(message.toString())
    bot.on('message', listener)
    const failFast = () => context.expect(!messages.some(message => message.includes('GLOSS_INTEGRATION_FAIL')),
      'External API probe failed; inspect the server stacktrace', messages)
    const poll = async phase => {
      let processed = messages.length
      await context.waitUntil(async () => {
        failFast()
        await context.command(`/glossintegration ${phase}`)
        await context.sleep(250)
        failFast()
        for (const message of messages.slice(processed)) {
          const match = message.match(/GLOSS_INTEGRATION_OK phase=(\w+) provider=(\w+) value=([-+.\deE]+) generation=(\d+) captured=(\d+) sampled=(\d+)/)
          if (!match || match[1] !== phase) continue
          samples[phase][match[2]] = { value: Number(match[3]), generation: Number(match[4]),
            captured: Number(match[5]), sampled: Number(match[6]) }
        }
        processed = messages.length
        return providers.every(provider => {
          const current = samples[phase][provider]
          const previous = samples.before[provider]
          return current && Number.isFinite(current.value) && (phase === 'before'
            || current.generation > previous.generation && current.sampled > previous.sampled)
        })
      }, { timeoutMs: 40000, intervalMs: 1000, label: `${phase}: all three providers publish fresh snapshots through the public facade` })
    }
    const modes = async phase => {
      const directory = path.join(root, 'debug')
      const files = async () => {
        try { return await readdir(directory) }
        catch (error) { if (error.code === 'ENOENT') return []; throw error }
      }
      const previous = new Set(await files())
      await context.command('/gloss debug dump upload=false')
      await context.waitUntil(async () => {
        for (const name of await files()) {
          if (previous.has(name) || !name.endsWith('.txt')) continue
          const text = await readFile(path.join(directory, name), 'utf8')
          const block = text.match(/"samplingModes"\s*:\s*\{([^}]+)\}/)?.[1]
          if (!block || !providers.every(provider => new RegExp(`"${provider}"\\s*:\\s*"SNAPSHOT"`).test(block))) continue
          context.report[`snapshotDiagnostic_${phase}`] = name
          return true
        }
        return false
      }, { timeoutMs: 15000, intervalMs: 200, label: `${phase}: Gloss negotiated SNAPSHOT for all providers` })
    }
    try {
      await context.step('resolve baseline-compiled public API and demand live provider samples', async () => {
        await poll('before')
        await modes('before')
      })
      await context.step('reload integration cadence and observe new samples without generation regression', async () => {
        const section = original.match(/(\[integration\][\s\S]*?)(?=\n\[|$)/)
        context.expect(section, 'Missing integration configuration section')
        const current = section[1].match(/^sampleIntervalTicks\s*=\s*(\d+)/m)
        context.expect(current, 'Missing integration sample cadence')
        const ticks = Number(current[1]) === 1 ? 2 : 1
        const changed = section[1].replace(/^sampleIntervalTicks\s*=.*$/m, `sampleIntervalTicks = ${ticks}`)
        const start = messages.length
        configChanged = true
        await writeFile(configFile, original.replace(section[1], changed))
        await context.waitUntil(() => messages.slice(start).some(message => message.includes('Hotloaded gloss.toml')),
          { timeoutMs: 20000, label: 'supported configuration reload completed' })
        await poll('after')
        await modes('after')
        context.report.integrationSnapshots = samples
      })
    } finally {
      try {
        if (configChanged) {
          const start = messages.length
          await writeFile(configFile, original)
          await context.waitUntil(() => messages.slice(start).some(message => message.includes('Hotloaded gloss.toml')),
            { timeoutMs: 20000, label: 'original integration configuration restored' })
        }
      } finally {
        bot.removeListener('message', listener)
      }
    }
  }
}
