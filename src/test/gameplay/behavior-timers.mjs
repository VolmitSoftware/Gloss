import { readFile, writeFile, mkdir, rm, readdir } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'behavior-timers',
  description: 'Verify player, playerless, and shared timer admission, completion, reconnect, and reload cancellation.',
  async run(context) {
    const { bot } = context
    bot.physicsEnabled = false
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Behavior acceptance requires an isolated instance')
    const root = path.join(context.server.directory, 'plugins/Gloss')
    const originals = new Map()
    const messages = []
    const listener = message => messages.push(message.toString())
    bot.on('message', listener)
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file)) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, value)
    }
    const count = marker => messages.filter(message => message.includes(marker)).length
    const until = (predicate, label, timeoutMs = 12000) => context.waitUntil(predicate, { timeoutMs, label })
    const fire = (entry, player = bot.username) => context.command(`/gloss behavior fire timer-contract ${entry} player=${player}`)
    const configure = async fields => {
      let config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
      for (const [key, value] of Object.entries(fields)) {
        const pattern = new RegExp(`^${key}\\s*=.*$`, 'm')
        context.expect(pattern.test(config), `Missing configuration field ${key}`)
        config = config.replace(pattern, `${key} = ${value}`)
      }
      const before = count('Hotloaded gloss.toml')
      await save('gloss.toml', config)
      await until(() => count('Hotloaded gloss.toml') > before, 'timer configuration loaded', 20000)
    }
    const broadcast = message => ({ type: 'broadcast', scope: 'server', message })
    const delayed = (name, ticks, started, completed) => ({ trigger: 'emit', name,
      do: [...(started ? [broadcast(started)] : []), { type: 'delay', ticks }, broadcast(completed)] })
    let actor
    try {
      await context.step('load bounded timer configuration and manual-only actions', async () => {
        actor = await context.connectActor('GlossTimerActor')
        actor.bot.physicsEnabled = false
        await save('behaviors/timer-contract.json', JSON.stringify({ schemaVersion: 2, revision: 1, enabled: true,
          on: [
            delayed('timer.player', 80, 'TIMER_PLAYER_STARTED', 'TIMER_PLAYER_DONE'),
            delayed('timer.player-short', 2, null, 'TIMER_PLAYER_SHORT'),
            delayed('timer.global', 120, 'TIMER_GLOBAL_STARTED', 'TIMER_GLOBAL_DONE'),
            delayed('timer.global-short', 2, null, 'TIMER_GLOBAL_SHORT'),
            delayed('timer.session-old', 160, 'TIMER_SESSION_STARTED', 'TIMER_SESSION_OLD_DONE'),
            delayed('timer.session-new', 2, null, 'TIMER_SESSION_NEW_DONE'),
            delayed('timer.reload-old', 400, 'TIMER_RELOAD_STARTED', 'TIMER_RELOAD_OLD_DONE'),
            delayed('timer.session-barrier', 180, null, 'TIMER_SESSION_BARRIER'),
            delayed('timer.reload-barrier', 420, null, 'TIMER_RELOAD_BARRIER')
          ] }))
        await configure({ maxTimersPerPlayer: 1, maxTimersGlobal: 2, maxTimersWithoutPlayer: 1, behaviors: true })
        await context.waitUntil(async () => {
          await context.command('/gloss behavior info timer-contract')
          return messages.some(message => /timer-contract.*revision.*entries.*9.*enabled.*true/.test(message))
        }, { timeoutMs: 20000, intervalMs: 1000, label: 'behavior document loaded' })
      })
      await context.step('reject per-player overflow and readmit after completion', async () => {
        await fire(1)
        await until(() => count('TIMER_PLAYER_STARTED') === 1, 'player timer started')
        await fire(2)
        await context.sleep(600)
        context.expect(count('TIMER_PLAYER_SHORT') === 0, 'Per-player cap admitted a second continuation')
        await until(() => count('TIMER_PLAYER_DONE') === 1, 'player timer completed')
        context.expect(count('TIMER_PLAYER_SHORT') === 0, 'Rejected player continuation executed later')
        await fire(2)
        await until(() => count('TIMER_PLAYER_SHORT') === 1, 'per-player capacity released')
      })
      await context.step('enforce playerless capacity independently of available shared capacity', async () => {
        await fire(3, '*')
        await until(() => count('TIMER_GLOBAL_STARTED') === 1, 'playerless timer started')
        await fire(4, '*')
        await context.sleep(600)
        context.expect(count('TIMER_GLOBAL_SHORT') === 0, 'Playerless cap admitted a second continuation')
        await until(() => count('TIMER_GLOBAL_DONE') === 1, 'playerless timer completed')
        context.expect(count('TIMER_GLOBAL_SHORT') === 0, 'Rejected playerless continuation executed later')
        await fire(4, '*')
        await until(() => count('TIMER_GLOBAL_SHORT') === 1, 'playerless capacity released')
      })
      await context.step('share global capacity across players and playerless actions', async () => {
        await fire(3, '*')
        await fire(1)
        await until(() => count('TIMER_GLOBAL_STARTED') === 2 && count('TIMER_PLAYER_STARTED') === 2,
          'both shared slots occupied')
        await fire(6, actor.bot.username)
        await context.sleep(600)
        context.expect(count('TIMER_SESSION_NEW_DONE') === 0, 'A second player bypassed the shared cap')
        await until(() => count('TIMER_PLAYER_DONE') === 2, 'player shared slot released')
        context.expect(count('TIMER_SESSION_NEW_DONE') === 0, 'Rejected shared continuation executed later')
        await fire(6, actor.bot.username)
        await until(() => count('TIMER_SESSION_NEW_DONE') === 1, 'released shared slot reused')
        await until(() => count('TIMER_GLOBAL_DONE') === 2, 'remaining shared slot released')
      })
      await context.step('cancel a disconnected session and readmit its UUID without stale execution', async () => {
        await configure({ maxTimersGlobal: 1 })
        const uuid = actor.bot.player.uuid
        await fire(5, actor.bot.username)
        await until(() => count('TIMER_SESSION_STARTED') === 1, 'old session timer started')
        const previous = actor
        actor = await previous.reconnectAfter(() => previous.bot.quit('Timer acceptance planned reconnect'), { timeoutMs: 15000 })
        actor.bot.physicsEnabled = false
        context.expect(actor.bot.player.uuid === uuid, 'Reconnect did not reuse the original player UUID')
        await fire(6, actor.bot.username)
        await until(() => count('TIMER_SESSION_NEW_DONE') === 2, 'reconnect releases native timer capacity')
        await fire(8, actor.bot.username)
        await until(() => count('TIMER_SESSION_BARRIER') === 1, 'old session timer deadline passed', 25000)
        context.expect(count('TIMER_SESSION_OLD_DONE') === 0, 'Retired session continuation executed after reconnect')
      })
      await context.step('reload cancels native player and global timers without retaining queue charges', async () => {
        await configure({ maxTimersGlobal: 2 })
        await fire(7)
        await fire(7, '*')
        await until(() => count('TIMER_RELOAD_STARTED') === 2, 'player and global timers pending')
        await configure({ behaviors: false, maxTimersGlobal: 1 })
        await configure({ behaviors: true })
        await fire(2)
        await until(() => count('TIMER_PLAYER_SHORT') === 2, 'player readmitted after reload')
        await fire(4, '*')
        await until(() => count('TIMER_GLOBAL_SHORT') === 2, 'playerless readmitted after reload')
        await fire(9, '*')
        await until(() => count('TIMER_RELOAD_BARRIER') === 1, 'old reload timer deadlines passed', 45000)
        context.expect(count('TIMER_RELOAD_OLD_DONE') === 0, 'Reloaded continuation executed after cancellation')
        context.expect(count('TIMER_PLAYER_DONE') === 2 && count('TIMER_GLOBAL_DONE') === 2
          && count('TIMER_SESSION_NEW_DONE') === 2, 'A completed continuation ran more than once')
        context.report.timerMessages = messages.filter(message => message.includes('TIMER_'))
        context.report.timerActors = { observer: bot.username, reconnecting: actor.bot.username, uuid: actor.bot.player.uuid }
      })
      await context.step('write local runtime cadence and retention diagnostics', async () => {
        const directory = path.join(root, 'debug')
        const reports = async () => {
          try { return await readdir(directory) }
          catch (error) { if (error.code === 'ENOENT') return []; throw error }
        }
        const previous = new Set(await reports())
        await context.command('/gloss debug dump upload=false')
        await context.waitUntil(async () => {
          for (const name of await reports()) {
            if (previous.has(name) || !name.endsWith('.txt')) continue
            const content = await readFile(path.join(directory, name), 'utf8')
            if (['"cadenceUnits"', '"configuredIntervalTicks"', '"samplingModes"', '"transactionRetention"']
              .every(field => content.includes(field))) {
              context.report.runtimeDiagnostic = name
              return true
            }
          }
          return false
        }, { timeoutMs: 15000, intervalMs: 200, label: 'local runtime diagnostic report contains current state' })
      })
    } finally {
      bot.removeListener('message', listener)
      for (const [file, original] of [...originals].reverse()) {
        if (original == null) await rm(file, { force: true })
        else await writeFile(file, original)
      }
    }
  }
}
