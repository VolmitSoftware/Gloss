import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { createHash } from 'node:crypto'
import path from 'node:path'

export default {
  name: 'pack-retention',
  description: 'Verify retained resource-pack downloads and refused rebuilds preserve the current pack.',
  async run(context) {
    context.bot.physicsEnabled = false
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Pack acceptance requires an isolated instance')
    const root = path.join(context.server.directory, 'plugins/Gloss')
    const originals = new Map()
    const messages = []
    const listener = message => messages.push(message.toString())
    context.bot.on('message', listener)
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file)) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, value)
    }
    const hash = async () => {
      try { return (await readFile(path.join(root, 'forge/out/gloss-pack.sha1'), 'utf8')).trim() }
      catch (error) { if (error.code === 'ENOENT') return ''; throw error }
    }
    const reservation = createServer()
    await new Promise((resolve, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', resolve) })
    const port = reservation.address().port
    await new Promise((resolve, reject) => reservation.close(error => error ? reject(error) : resolve()))
    const download = async expected => {
      const response = await fetch(`http://127.0.0.1:${port}/gloss-pack-${expected}.zip`, { signal: AbortSignal.timeout(5000) })
      context.expect(response.status === 200, `Pack ${expected} did not remain downloadable`)
      const actual = createHash('sha1').update(Buffer.from(await response.arrayBuffer())).digest('hex')
      context.expect(actual === expected, 'Downloaded pack does not match its offered hash')
    }
    const space = (revision, range) => JSON.stringify({ schemaVersion: 1, revision, namespace: 'gloss', font: 'glyphs',
      glyphs: [], overlays: [], space: { enabled: true, range } })
    try {
      await context.step('enable loopback delivery with two retained artifacts', async () => {
        let config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        for (const [key, value] of Object.entries({ forge: 'true', serve: 'true', serveBind: '"127.0.0.1"',
          servePort: String(port), maxRetainedArtifacts: '2', artifactRetentionSeconds: '600', buildDebounceTicks: '1' })) {
          const pattern = new RegExp(`^${key}\\s*=.*$`, 'm')
          context.expect(pattern.test(config), `Missing configuration field ${key}`)
          config = config.replace(pattern, `${key} = ${value}`)
        }
        await save('glyphs/space.json', space(1, [-256, 256]))
        await save('gloss.toml', config)
        await context.waitUntil(() => messages.some(message => message.includes('Hotloaded gloss.toml')),
          { timeoutMs: 20000, intervalMs: 250, label: 'forge configuration loaded' })
        await context.waitUntil(async () => {
          await context.command('/gloss forge build')
          return messages.some(message => /Pack [0-9a-f]{8} built with/.test(message))
        }, { timeoutMs: 20000, intervalMs: 1000, label: 'initial pack built' })
        context.expect(/^[0-9a-f]{40}$/.test(await hash()), 'Initial pack hash is invalid')
      })
      const first = await hash()
      await download(first)
      await context.step('publish a replacement and retain the previous download', async () => {
        await save('glyphs/space.json', space(2, [-128, 128]))
        await context.waitUntil(async () => { const value = await hash(); return /^[0-9a-f]{40}$/.test(value) && value !== first },
          { timeoutMs: 20000, intervalMs: 250, label: 'replacement pack published' })
        const second = await hash()
        context.expect(/^[0-9a-f]{40}$/.test(second), 'Replacement hash is invalid')
        await download(first)
        await download(second)
        context.report.packHashes = [first, second]
      })
      await context.step('refuse a third protected artifact without replacing the current pack', async () => {
        const second = await hash()
        await save('glyphs/space.json', space(3, [-64, 64]))
        await context.waitUntil(async () => {
          await context.command('/gloss forge build')
          return messages.some(message => message.includes('Pack build failed'))
        }, { timeoutMs: 20000, intervalMs: 1000, label: 'retention budget refusal' })
        context.expect(await hash() === second, 'Refused build replaced the published hash')
        await download(first)
        await download(second)
        const canonical = await readFile(path.join(root, 'forge/out/gloss-pack.zip'))
        context.expect(createHash('sha1').update(canonical).digest('hex') === second,
          'Refused build changed the canonical ZIP')
      })
    } finally {
      context.bot.removeListener('message', listener)
      for (const [file, original] of [...originals].reverse()) {
        if (original == null) await rm(file, { force: true })
        else await writeFile(file, original)
      }
    }
  }
}
