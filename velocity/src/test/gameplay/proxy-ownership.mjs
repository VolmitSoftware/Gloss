import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'gloss-proxy-ownership',
  description: 'Verify proxy feature handoff and backend restoration with Gloss installed on both sides.',
  async run(context) {
    const require = createRequire(process.env.GLOSS_QA_PACKAGE)
    const mineflayer = require('mineflayer')
    const protocol = require('minecraft-protocol')
    const port = Number(process.env.GLOSS_QA_PORT)
    const backendPort = Number(process.env.GLOSS_QA_BACKEND_PORT)
    const file = path.join(process.env.GLOSS_QA_DATA, 'proxy.json')
    const original = await readFile(file, 'utf8')
    const config = JSON.parse(original)
    const fatal = []
    const state = { spawned: false, header: '', sidebar: '', titles: new Map(), packets: [] }
    let closing = false
    let bot
    const wait = async (predicate, label, timeout = 18000) => {
      const deadline = Date.now() + timeout
      while (!predicate()) {
        if (fatal.length) throw fatal[0]
        if (Date.now() > deadline) throw new Error(`Timed out: ${label}; ${JSON.stringify({ ...state, titles: [...state.titles] })}`)
        await context.sleep(50)
      }
      if (fatal.length) throw fatal[0]
    }
    const ping = target => new Promise((resolve, reject) => protocol.ping({ host: '127.0.0.1', port: target, version: '26.1', closeTimeout: 5000 }, (error, value) => error ? reject(error) : resolve(value)))
    const reload = async flags => {
      Object.assign(config, flags)
      await writeFile(file, JSON.stringify(config, null, 2))
      execFileSync('tmux', ['send-keys', '-t', process.env.GLOSS_QA_SESSION, 'gloss reload', 'Enter'])
    }
    const waitMotd = async expected => {
      for (let i = 0; i < 18; i++) {
        const response = await ping(backendPort)
        if (JSON.stringify(response.description).includes('Backend MOTD') === expected) return
        if (fatal.length) throw fatal[0]
        await context.sleep(1000)
      }
      throw new Error(`Backend MOTD expected local ownership=${expected}`)
    }
    try {
      await context.step('backend MOTD available without a carrier', async () => {
        await waitMotd(true)
      })
      bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'GlossOwnership', version: '26.1', auth: 'offline' })
      bot.on('error', error => { if (!closing) fatal.push(error) })
      bot.on('kicked', reason => { if (!closing) fatal.push(new Error(`Kicked: ${JSON.stringify(reason)}`)) })
      bot.on('end', reason => { if (!closing) fatal.push(new Error(`Disconnected: ${reason}`)) })
      bot.on('spawn', () => { state.spawned = true })
      bot._client.on('packet', (packet, meta) => {
        if (meta.name === 'playerlist_header') state.header = JSON.stringify(packet.header)
        if (meta.name === 'scoreboard_objective') {
          if (packet.action === 1) state.titles.delete(packet.name)
          else state.titles.set(packet.name, JSON.stringify(packet.displayText))
        }
        if (meta.name === 'scoreboard_display_objective' && packet.position === 1) state.sidebar = packet.name
        if (meta.name === 'scoreboard_objective' || meta.name === 'playerlist_header') {
          state.packets.push({ name: meta.name, packet })
          if (state.packets.length > 30) state.packets.shift()
        }
      })
      await wait(() => state.spawned, 'player spawn')
      await context.step('proxy claims tablist scoreboard and backend MOTD', async () => {
        await waitMotd(false)
        await wait(() => state.header.includes('Proxy Header') && state.sidebar === 'gloss_proxy', 'proxy surfaces')
        await context.sleep(6000)
        context.expect(state.header.includes('Proxy Header') && state.sidebar === 'gloss_proxy', 'Backend overwrote proxy surfaces', state)
        context.expect(![...state.titles.values()].some(title => title.includes('Backend Ownership')), 'Backend board remains attached', [...state.titles])
        const response = await ping(port)
        context.expect(JSON.stringify(response.description).includes('Proxy MOTD'), 'Proxy MOTD absent', response)
      })
      await context.step('disabled proxy tablist resumes backend only for tablist', async () => {
        await reload({ tablist: { enabled: false } })
        await wait(() => state.header.includes('Backend Header'), 'local tablist resumed')
        context.expect(state.sidebar === 'gloss_proxy', 'Scoreboard ownership unexpectedly released', state)
        await waitMotd(false)
      })
      await context.step('disabled proxy scoreboard and MOTD resume backend', async () => {
        await reload({ scoreboards: { enabled: false }, motd: { enabled: false } })
        await wait(() => (state.titles.get(state.sidebar) ?? '').includes('Backend Ownership'), 'local sidebar resumed')
        await waitMotd(true)
      })
      await context.step('reenabling proxy features retires backend displays', async () => {
        await reload({ tablist: { enabled: true }, scoreboards: { enabled: true }, motd: { enabled: true } })
        await waitMotd(false)
        await wait(() => state.header.includes('Proxy Header') && state.sidebar === 'gloss_proxy', 'proxy reclaim')
        await context.sleep(6000)
        context.expect(![...state.titles.values()].some(title => title.includes('Backend Ownership')), 'Backend board returned after reclaim', [...state.titles])
      })
    } finally {
      closing = true
      if (bot) bot.quit('Ownership QA complete')
      await writeFile(file, original)
      execFileSync('tmux', ['send-keys', '-t', process.env.GLOSS_QA_SESSION, 'gloss reload', 'Enter'])
    }
  }
}
