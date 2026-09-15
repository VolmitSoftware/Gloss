import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'gloss-proxy-surfaces',
  description: 'Verify proxy MOTD, network tablists, sidebar ownership, reload and backend switches.',
  async run(context) {
    const require = createRequire(process.env.GLOSS_QA_PACKAGE)
    const mineflayer = require('mineflayer')
    const protocol = require('minecraft-protocol')
    const data = process.env.GLOSS_QA_DATA
    const first = process.env.GLOSS_QA_FIRST
    const second = process.env.GLOSS_QA_SECOND
    const port = Number(process.env.GLOSS_QA_PORT)
    const bots = []
    const fatal = []
    const snapshots = new Map()
    const fixtures = new Map()
    const commandTimes = new Map()
    let closing = false
    const consoleCommand = (session, command) => execFileSync('tmux', ['send-keys', '-t', session, command, 'Enter'])
    const command = async (bot, value) => {
      const delay = 100 - (Date.now() - (commandTimes.get(bot) ?? 0))
      if (delay > 0) await context.sleep(delay)
      commandTimes.set(bot, Date.now())
      bot.chat(value)
    }
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
      if (!fixtures.has(name)) fixtures.set(name, await readFile(file, 'utf8'))
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2))
    }
    const connect = async (username) => {
      const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, version: '1.21.11', auth: 'offline' })
      bots.push(bot)
      const state = { packets: [], messages: [], header: '', title: '', rows: new Map(), sidebar: '', objectives: new Set(), spawned: false }
      snapshots.set(bot, state)
      bot.on('error', error => { if (!closing) fatal.push(error) })
      bot.on('kicked', reason => { if (!closing) fatal.push(new Error(`Proxy bot kicked: ${JSON.stringify(reason)}`)) })
      bot.on('end', reason => { if (!closing) fatal.push(new Error(`Proxy bot disconnected: ${reason}`)) })
      bot.on('spawn', () => { state.spawned = true })
      bot.on('messagestr', message => state.messages.push(message))
      bot._client.on('packet', (packet, meta) => {
        if (meta.name === 'playerlist_header') state.header = JSON.stringify(packet.header)
        if (meta.name.startsWith('scoreboard') || meta.name === 'reset_score') {
          state.packets.push({ name: meta.name, packet })
          if (state.packets.length > 200) state.packets.shift()
        }
        if (meta.name === 'scoreboard_objective') {
          if (packet.action === 1) {
            state.objectives.delete(packet.name)
            if (packet.name === 'gloss_proxy') { state.title = ''; state.rows.clear() }
          } else {
            state.objectives.add(packet.name)
            if (packet.name === 'gloss_proxy') state.title = JSON.stringify(packet.displayText)
          }
        }
        if (meta.name === 'scoreboard_score' && packet.scoreName === 'gloss_proxy') state.rows.set(packet.itemName, packet)
        if (meta.name === 'reset_score' && packet.objective_name === 'gloss_proxy') state.rows.delete(packet.entity_name)
        if (meta.name === 'scoreboard_display_objective' && packet.position === 1) state.sidebar = packet.name
      })
      await wait(() => state.spawned, `${username} spawn`)
      return bot
    }
    const board = {
      schemaVersion: 2, revision: 1, show: true, select: { priority: 0, when: 'true' },
      presentation: { title: '&aProxy QA', lines: ['&f$player', '&7$server', { text: 'Value', value: '&642', format: 'fixed' }], hideNumbers: true },
      variants: [{ id: 'second', priority: 10, when: `subject.server == '${second}'`, presentation: { title: '&bSecond Backend', lines: ['&f$player', '&7$server'], hideNumbers: true } }]
    }
    try {
      await save('boards/default.json', board)
      await save('motd.json', { schemaVersion: 1, revision: 1, show: true, entries: [{ lines: ['&dProxy MOTD QA', '&7$online online'], online: '23', max: '99', sample: ['Proxy sample'], version: 'Gloss QA' }] })
      reload()
      await context.step('MOTD description counts sample and version', async () => {
        await context.sleep(500)
        const response = await new Promise((resolve, reject) => protocol.ping({ host: '127.0.0.1', port, version: '1.21.11', closeTimeout: 5000 }, (error, value) => error ? reject(error) : resolve(value)))
        context.expect(JSON.stringify(response.description).includes('Proxy MOTD QA'), 'Proxy MOTD missing', response)
        context.expect(response.players.online === 23 && response.players.max === 99, 'MOTD counts differ', response)
        context.expect(response.players.sample[0].name === 'Proxy sample', 'MOTD sample missing', response)
        context.expect(response.version.name === 'Gloss QA', 'MOTD version label missing', response)
      })
      const alice = await connect('GlossProxyAlice')
      await context.sleep(3200)
      const bob = await connect('GlossProxyBob')
      const state = snapshots.get(alice)
      await context.step('proxy sidebar title rows and fixed value', async () => {
        await wait(() => state.title.includes('Proxy QA') && state.rows.size === 3 && state.sidebar === 'gloss_proxy', 'sidebar initial')
        context.expect(JSON.stringify([...state.rows.values()]).includes('42'), 'Fixed score value missing', [...state.rows.values()])
      })
      await context.step('network tablist spans backend servers', async () => {
        await command(bob, `/server ${second}`)
        await wait(() => snapshots.get(bob).title.includes('Second Backend'), 'second bot switch')
        await wait(() => Object.values(alice.players).some(player => player.username === 'GlossProxyBob' && JSON.stringify(player.displayName).includes(second)), 'remote tablist entry')
        context.expect(state.header.includes('Gloss'), 'Tab header missing', state.header)
      })
      await context.step('backend sidebar cannot overwrite proxy sidebar', async () => {
        consoleCommand(process.env.GLOSS_QA_BACKEND_SESSION, 'scoreboard objectives add backendqa dummy')
        consoleCommand(process.env.GLOSS_QA_BACKEND_SESSION, 'scoreboard objectives setdisplay sidebar backendqa')
        await wait(() => state.objectives.has('backendqa'), 'backend objective')
        await context.sleep(750)
        context.expect(state.sidebar === 'gloss_proxy', 'Backend replaced the proxy sidebar', state.packets)
      })
      await context.step('scoreboard toggle restores backend sidebar', async () => {
        await command(alice, '/gloss board toggle')
        await wait(() => !state.objectives.has('gloss_proxy') && state.sidebar === 'backendqa', 'sidebar toggle off')
        await command(alice, '/gloss board toggle')
        await wait(() => state.objectives.has('gloss_proxy') && state.sidebar === 'gloss_proxy', 'sidebar toggle on')
      })
      await context.step('server switch reapplies conditional sidebar and tablist', async () => {
        await command(alice, `/server ${second}`)
        await wait(() => state.title.includes('Second Backend') && state.rows.size === 2 && state.sidebar === 'gloss_proxy', 'conditional sidebar after switch')
        await wait(() => Object.values(alice.players).some(player => player.username === 'GlossProxyAlice' && JSON.stringify(player.displayName).includes(second)), 'tablist server token after switch')
        await command(alice, `/server ${first}`)
        await wait(() => state.title.includes('Proxy QA') && state.rows.size === 3, 'return switch sidebar')
      })
      await context.step('reload replaces display and malformed reload retains previous state', async () => {
        board.presentation.title = '&eReload QA'
        await save('boards/default.json', board)
        reload()
        await wait(() => state.title.includes('Reload QA'), 'valid reload')
        await save('boards/default.json', '{invalid')
        reload()
        await context.sleep(1000)
        context.expect(state.title.includes('Reload QA') && state.rows.size === 3, 'Invalid reload discarded current config', state.packets)
        await save('boards/default.json', { ...board, presentation: { ...board.presentation, title: '{{ 1 + }}' } })
        reload()
        await context.sleep(750)
        context.expect(state.title.includes('Reload QA'), 'Malformed expression replaced valid display', state.packets)
        await save('boards/default.json', board)
        reload()
      })
      await context.step('feature disable cleans owned displays and reenable restores them', async () => {
        const settings = JSON.parse(await readFile(path.join(data, 'proxy.json'), 'utf8'))
        await save('proxy.json', { ...settings, tablist: { enabled: false }, scoreboards: { enabled: false } })
        reload()
        await wait(() => !state.objectives.has('gloss_proxy') && !state.header.includes('Gloss'), 'disabled display cleanup')
        await wait(() => !Object.values(alice.players).some(player => player.username === 'GlossProxyBob'), 'remote entry cleanup')
        context.expect(Object.values(alice.players).some(player => player.username === 'GlossProxyAlice'), 'Disable removed local player entry')
        await save('proxy.json', settings)
        reload()
        await wait(() => state.title.includes('Reload QA') && state.header.includes('Gloss'), 'reenabled display')
      })
      context.report.proxy = { port, first, second, packets: state.packets.slice(-30) }
      context.expect(fatal.length === 0, 'Unexpected proxy disconnect or error', fatal.map(error => error.message))
    } finally {
      context.report.proxy = { port, first, second, bots: bots.map(bot => { const state = snapshots.get(bot); return { username: bot.username, title: state.title, sidebar: state.sidebar, rows: [...state.rows.values()], packets: state.packets.slice(-40), messages: state.messages } }) }
      closing = true
      for (const bot of bots) bot.quit()
      await Promise.all(bots.map(bot => new Promise(resolve => {
        if (bot._client.ended) return resolve()
        bot.once('end', resolve)
        setTimeout(() => { bot._client.end(); resolve() }, 2000)
      })))
      for (const [name, content] of fixtures) await writeFile(path.join(data, name), content)
      reload()
      consoleCommand(process.env.GLOSS_QA_BACKEND_SESSION, 'scoreboard objectives remove backendqa')
    }
  }
}
