import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import zlib from 'node:zlib'

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

const crc = (buffer) => {
  if (typeof zlib.crc32 === 'function') return zlib.crc32(buffer) >>> 0
  let value = 0xffffffff
  for (const byte of buffer) {
    value ^= byte
    for (let bit = 0; bit < 8; bit++) value = value & 1 ? (value >>> 1) ^ 0xedb88320 : value >>> 1
  }
  return (value ^ 0xffffffff) >>> 0
}

const chunk = (type, data) => {
  const header = Buffer.alloc(4)
  header.writeUInt32BE(data.length, 0)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const checksum = Buffer.alloc(4)
  checksum.writeUInt32BE(crc(body), 0)
  return Buffer.concat([header, body, checksum])
}

/** A 64x64 truecolor PNG, the only size a Velocity favicon accepts. */
const favicon = (size) => {
  const raw = Buffer.alloc((size * 3 + 1) * size)
  for (let y = 0; y < size; y++) {
    const row = y * (size * 3 + 1)
    raw[row] = 0
    for (let x = 0; x < size; x++) {
      const pixel = row + 1 + x * 3
      raw[pixel] = (x * 4) & 0xff
      raw[pixel + 1] = (y * 4) & 0xff
      raw[pixel + 2] = ((x ^ y) * 4) & 0xff
    }
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header[8] = 8
  header[9] = 2
  return Buffer.concat([PNG_SIGNATURE, chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}

/** Concatenates every {@code text} leaf of a decoded NBT chat component, in traversal order. */
const plain = (node, out = []) => {
  if (!node || typeof node !== 'object') return out
  if (Array.isArray(node)) {
    for (const item of node) plain(item, out)
    return out
  }
  for (const [key, value] of Object.entries(node)) {
    if (key === 'text' && typeof value === 'string') out.push(value)
    else if (key === 'text' && value && typeof value === 'object' && value.type === 'string') out.push(String(value.value))
    else plain(value, out)
  }
  return out
}

const textOf = (node) => {
  if (typeof node === 'string') return node
  if (node && typeof node === 'object' && node.type === 'string') return String(node.value)
  return plain(node).join('')
}

export default {
  name: 'gloss-proxy-features',
  description: 'Verify proxy MOTD favicon, server links, emoji and animated text, surfaces and connection messages.',
  async run(context) {
    const require = createRequire(process.env.GLOSS_QA_PACKAGE)
    const mineflayer = require('mineflayer')
    const protocol = require('minecraft-protocol')
    const data = process.env.GLOSS_QA_DATA
    const first = process.env.GLOSS_QA_FIRST
    const second = process.env.GLOSS_QA_SECOND
    const port = Number(process.env.GLOSS_QA_PORT)
    const proxyLog = process.env.GLOSS_QA_PROXY_LOG
    const bots = []
    const fatal = []
    const snapshots = new Map()
    const fixtures = new Map()
    const created = new Set()
    const results = []
    let closing = false
    const consoleCommand = (session, command) => execFileSync('tmux', ['send-keys', '-t', session, command, 'Enter'])
    const reload = () => consoleCommand(process.env.GLOSS_QA_SESSION, 'gloss reload')
    const wait = async (predicate, label, timeout = 15000) => {
      const deadline = Date.now() + timeout
      while (!predicate()) {
        if (fatal.length) throw fatal[0]
        if (Date.now() > deadline) throw new Error(`Timed out: ${label}`)
        await context.sleep(50)
      }
      if (fatal.length) throw fatal[0]
    }
    const save = async (name, value) => {
      const file = path.join(data, name)
      if (!fixtures.has(name) && !created.has(name)) {
        try {
          fixtures.set(name, await readFile(file, 'utf8'))
        } catch (error) {
          if (error.code !== 'ENOENT') throw error
          created.add(name)
        }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2))
    }
    const document = async (name) => JSON.parse(await readFile(path.join(data, name), 'utf8'))
    const ping = () => new Promise((resolve, reject) => protocol.ping(
      { host: '127.0.0.1', port, version: '1.21.11', closeTimeout: 5000 },
      (error, value) => error ? reject(error) : resolve(value)))
    let lastConnect = 0
    const connect = async (username) => {
      // Velocity rate-limits logins from one address; spacing the bots keeps the proxy from kicking them.
      const throttle = 3500 - (Date.now() - lastConnect)
      if (throttle > 0) await context.sleep(throttle)
      lastConnect = Date.now()
      const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, version: '1.21.11', auth: 'offline' })
      bots.push(bot)
      const state = {
        spawned: false, quiet: false, header: '', messages: [], names: new Set(),
        links: [], actionBars: [], bossBars: [], titles: []
      }
      snapshots.set(bot, state)
      bot.on('error', error => { if (!closing && !state.quiet) fatal.push(error) })
      bot.on('kicked', reason => { if (!closing && !state.quiet) fatal.push(new Error(`${username} kicked: ${JSON.stringify(reason)}`)) })
      bot.on('end', reason => { if (!closing && !state.quiet) fatal.push(new Error(`${username} disconnected: ${reason}`)) })
      bot.on('spawn', () => { state.spawned = true })
      bot.on('messagestr', message => state.messages.push({ at: Date.now(), message }))
      bot._client.on('packet', (packet, meta) => {
        state.names.add(meta.name)
        if (meta.name === 'playerlist_header') state.header = textOf(packet.header)
        if (meta.name.toLowerCase().includes('server_links') || meta.name.toLowerCase().includes('serverlinks')) {
          state.links.push({ name: meta.name, packet })
        }
        if (meta.name === 'action_bar') state.actionBars.push({ at: Date.now(), text: textOf(packet.text) })
        if (meta.name === 'boss_bar') state.bossBars.push({ at: Date.now(), action: packet.action, title: textOf(packet.title) })
        if (meta.name === 'set_title_text') state.titles.push({ at: Date.now(), text: textOf(packet.text) })
        for (const list of [state.actionBars, state.bossBars, state.titles, state.links]) {
          if (list.length > 400) list.shift()
        }
      })
      await wait(() => state.spawned, `${username} spawn`, 30000)
      return bot
    }
    const disconnect = async (bot) => {
      snapshots.get(bot).quiet = true
      bot.quit()
      await new Promise(resolve => {
        if (bot._client.ended) return resolve()
        bot.once('end', resolve)
        setTimeout(resolve, 3000)
      })
    }
    const stage = async (name, action) => {
      try {
        const evidence = await context.step(name, action)
        results.push({ step: name, status: 'passed', evidence })
      } catch (error) {
        results.push({ step: name, status: 'failed', error: error.message, details: error.details })
      }
    }

    let alice
    try {
      await stage('MOTD favicon is served from the proxy images folder', async () => {
        created.add('images/lab.png')
        await mkdir(path.join(data, 'images'), { recursive: true })
        await writeFile(path.join(data, 'images', 'lab.png'), favicon(64))
        await save('motd.json', {
          schemaVersion: 1, revision: 1, show: true, favicon: 'lab.png',
          entries: [{ lines: ['&dGloss Lab MOTD', '&7$online online'] }]
        })
        reload()
        await context.sleep(1200)
        const response = await ping()
        context.expect(typeof response.favicon === 'string' && response.favicon.startsWith('data:image/png;base64,'),
          'Ping favicon is not an inline PNG', { favicon: response.favicon, description: response.description })
        context.expect(JSON.stringify(response.description).includes('Gloss Lab MOTD'),
          'MOTD description missing', response.description)
        return {
          faviconPrefix: response.favicon.slice(0, 32),
          faviconBytes: response.favicon.length,
          description: JSON.stringify(response.description)
        }
      })

      await stage('server links reach a 1.21.11 client after login', async () => {
        const motd = await document('motd.json')
        motd.links = [
          { type: 'website', url: 'https://example.org' },
          { label: '&dLab $player', url: 'https://example.org/lab' }
        ]
        await save('motd.json', motd)
        reload()
        await context.sleep(1000)
        alice = await connect('GlossLabA')
        const state = snapshots.get(alice)
        try {
          await wait(() => state.links.length > 0, 'server links packet', 12000)
        } catch (error) {
          throw Object.assign(new Error(`${error.message}; observed packets after login: ${[...state.names].sort().join(', ')}`),
            { details: { observedPacketNames: [...state.names].sort() } })
        }
        const record = state.links[state.links.length - 1]
        const rendered = JSON.stringify(record.packet)
        context.expect(rendered.includes('https://example.org'), 'Server link url missing', record)
        context.expect(rendered.includes('example.org/lab'), 'Labelled server link missing', record)
        return { packetName: record.name, packet: record.packet, rendered }
      })

      if (!alice) alice = await connect('GlossLabA')

      await stage('emoji and animation render inside the proxy tablist header', async () => {
        const tablist = await document('tablist.json')
        tablist.headerFooter.presentation.header = '&d:heart: |animation.marquee|'
        await save('tablist.json', tablist)
        reload()
        const state = snapshots.get(alice)
        state.header = ''
        await wait(() => state.header.includes('\u2764'), 'tablist heart', 12000)
        const samples = []
        for (let index = 0; index < 8; index++) {
          samples.push(state.header)
          await context.sleep(800)
        }
        const frames = samples.map(sample => sample.replace('\u2764', '').trim()).filter(frame => frame.length > 0)
        const distinct = [...new Set(frames)]
        context.expect(frames.length === samples.length, 'Animation frame was empty in a sample', samples)
        context.expect(distinct.length > 1, 'Animation frame never changed', samples)
        return { heart: '\\u2764', samples, distinctFrames: distinct }
      })

      await stage('action bar, boss bar and title surfaces render on the proxy', async () => {
        await save('surfaces/lab-actionbar.json', {
          schemaVersion: 1, revision: 1, surface: 'actionbar', show: 'true',
          select: { priority: 1, when: 'true' }, presentation: { text: '&aLab action bar $player' }, variants: []
        })
        await save('surfaces/lab-bossbar.json', {
          schemaVersion: 1, revision: 1, surface: 'bossbar', show: 'true',
          select: { priority: 1, when: 'true' },
          presentation: { title: '&bLab boss $online', progress: '0.5', color: 'blue', style: 'segmented_6' },
          variants: []
        })
        await save('surfaces/lab-title.json', {
          schemaVersion: 1, revision: 1, surface: 'title', show: 'true',
          select: { priority: 1, when: 'true' },
          presentation: { title: '&eLab title', subtitle: '&7sub', trigger: 'select' }, variants: []
        })
        const state = snapshots.get(alice)
        state.actionBars.length = 0
        state.bossBars.length = 0
        state.titles.length = 0
        reload()
        await wait(() => state.actionBars.some(entry => entry.text.includes('Lab action bar')), 'action bar surface')
        await wait(() => state.bossBars.some(entry => entry.action === 0 && entry.title.includes('Lab boss')), 'boss bar surface')
        await wait(() => state.titles.some(entry => entry.text.includes('Lab title')), 'title surface')
        return {
          actionBar: state.actionBars.find(entry => entry.text.includes('Lab action bar')).text,
          bossBarAdd: state.bossBars.find(entry => entry.action === 0 && entry.title.includes('Lab boss')),
          title: state.titles.find(entry => entry.text.includes('Lab title')).text
        }
      })

      await stage('disabling surfaces removes the proxy boss bar', async () => {
        const state = snapshots.get(alice)
        const settings = await document('proxy.json')
        state.bossBars.length = 0
        await save('proxy.json', { ...settings, surfaces: { enabled: false } })
        reload()
        await wait(() => state.bossBars.some(entry => entry.action === 1), 'boss bar removal')
        const removal = state.bossBars.find(entry => entry.action === 1)
        await save('proxy.json', settings)
        reload()
        await wait(() => state.bossBars.some(entry => entry.action === 0), 'boss bar restored')
        return { removalPacket: removal }
      })

      await stage('network connection messages announce join, switch and leave', async () => {
        const state = snapshots.get(alice)
        state.messages.length = 0
        const bob = await connect('GlossLabB')
        await wait(() => state.messages.some(entry => entry.message.includes('GlossLabB joined the network')), 'join message')
        const joined = state.messages.find(entry => entry.message.includes('GlossLabB joined the network')).message
        bob.chat(`/server ${second}`)
        await wait(() => state.messages.some(entry => entry.message.includes(`GlossLabB moved to ${second}`)), 'switch message')
        const switched = state.messages.find(entry => entry.message.includes(`GlossLabB moved to ${second}`)).message
        await disconnect(bob)
        await wait(() => state.messages.some(entry => entry.message.includes('GlossLabB left the network')), 'leave message')
        const left = state.messages.find(entry => entry.message.includes('GlossLabB left the network')).message
        return { joined, switched, left }
      })

      await stage('a server audience keeps a join line off another backend', async () => {
        const state = snapshots.get(alice)
        const connections = await document('connections.json')
        connections.join.audience = 'server'
        await save('connections.json', connections)
        reload()
        await context.sleep(800)
        state.messages.length = 0
        alice.chat(`/server ${second}`)
        await wait(() => state.messages.some(entry => entry.message.includes(`GlossLabA moved to ${second}`)), 'alice switch')
        state.messages.length = 0
        const carol = await connect('GlossLabC')
        await context.sleep(3000)
        const leaked = state.messages.filter(entry => entry.message.includes('GlossLabC'))
        context.expect(leaked.length === 0, 'Server-audience join line leaked to another backend', leaked)
        const carolState = snapshots.get(carol)
        return {
          aliceServer: second,
          carolServer: first,
          aliceMessagesAfterCarolJoin: state.messages.map(entry => entry.message),
          carolOwnMessages: carolState.messages.map(entry => entry.message)
        }
      })

      await stage('an invalid surface document is rejected and the live display survives', async () => {
        const state = snapshots.get(alice)
        const before = proxyLog ? (await readFile(proxyLog, 'utf8')).length : 0
        await save('surfaces/lab-invalid.json', {
          schemaVersion: 1, revision: 1, surface: 'hologram', show: 'true',
          select: { priority: 9, when: 'true' }, presentation: { text: '&cbroken' }, variants: []
        })
        state.actionBars.length = 0
        reload()
        await context.sleep(1500)
        const tail = proxyLog ? (await readFile(proxyLog, 'utf8')).slice(before) : ''
        const reported = tail.split('\n').filter(line => line.includes('Gloss proxy reload failed')
          || line.includes('surface lab-invalid') || line.includes('must be one of'))
        context.expect(reported.length > 0, 'Proxy log did not report the invalid surface document', tail.slice(-2000))
        await wait(() => state.actionBars.some(entry => entry.text.includes('Lab action bar')),
          'action bar after a failed reload')
        return {
          logLines: reported.slice(0, 6).map(line => line.trim()),
          actionBarAfterFailedReload: state.actionBars.find(entry => entry.text.includes('Lab action bar')).text
        }
      })

      context.expect(fatal.length === 0, 'Unexpected proxy disconnect or error', fatal.map(error => error.message))
      context.expect(results.every(entry => entry.status === 'passed'), 'Gloss proxy feature steps failed',
        results.filter(entry => entry.status !== 'passed'))
    } finally {
      context.report.gloss = {
        proxy: { port, first, second },
        steps: results,
        bots: bots.map(bot => {
          const state = snapshots.get(bot)
          return {
            username: bot.username,
            header: state.header,
            messages: state.messages.slice(-25).map(entry => entry.message),
            packetNames: [...state.names].sort()
          }
        })
      }
      closing = true
      for (const bot of bots) bot.quit()
      await Promise.all(bots.map(bot => new Promise(resolve => {
        if (bot._client.ended) return resolve()
        bot.once('end', resolve)
        setTimeout(() => { bot._client.end(); resolve() }, 2000)
      })))
      for (const name of created) await rm(path.join(data, name), { force: true })
      for (const [name, content] of fixtures) await writeFile(path.join(data, name), content)
      reload()
    }
  }
}
