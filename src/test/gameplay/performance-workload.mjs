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
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  if ('value' in value) return plain(value.value)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'performance-workload',
  description: 'Run bounded, comparable Gloss HUD and display workloads with protocol assertions and traffic totals.',
  async run(context) {
    const phase = process.env.GLOSS_WORKLOAD_PHASE ?? 'static'
    const seconds = Number(process.env.GLOSS_WORKLOAD_SECONDS ?? 60)
    const population = Number(process.env.GLOSS_WORKLOAD_PLAYERS ?? 8)
    context.expect(['static', 'animated', 'crowded', 'spread', 'reload', 'combat', 'churn'].includes(phase), 'Unsupported workload phase')
    context.expect(Number.isInteger(seconds) && seconds >= 10 && seconds <= 600, 'Workload duration must be 10..600 seconds')
    context.expect(Number.isInteger(population) && population >= 2 && population <= 16, 'Workload population must be 2..16')
    const instance = context.server.directory
    context.expect(/^isolated=true$/m.test(await readFile(path.join(instance, '.server-source'), 'utf8')),
      'Performance workloads require an isolated instance')
    const root = path.join(instance, 'plugins/Gloss')
    const originals = new Map()
    const actors = [context]
    const listeners = []
    const counters = new Map()
    const updates = []
    let combatDebounceMs = 0
    let combatRegeneration
    let primaryFailure
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file)) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2))
    }
    const observe = actor => {
      actor.bot.physicsEnabled = false
      const counts = counters.get(actor.bot.username)
        ?? { packets: 0, objectives: 0, metadata: 0, teams: 0, bytes: 0, board: false, animationFrames: new Set() }
      const client = actor.bot._client
      const socket = client.socket
      const packet = (data, meta) => {
        counts.packets++
        if (phase === 'animated' && counts.animationFrames.size < 4
          && ['entity_metadata', 'scoreboard_score'].includes(meta.name)) {
          for (const frame of plain(data).matchAll(/Frame ([A-D])/g)) counts.animationFrames.add(frame[1])
        }
        if (meta.name === 'scoreboard_objective') {
          counts.objectives++
          const title = plain(data.displayText)
          if (title.includes('WORKLOAD')) counts.board = true
          if (actor === context) {
            const revision = title.match(/^WORKLOAD (\d+)$/)
            const update = revision && updates.find(value => value.revision === Number(revision[1]) && !value.observed)
            if (update) {
              update.milliseconds = Date.now() - update.began
              update.observed = true
            }
          }
        }
        if (meta.name === 'entity_metadata') counts.metadata++
        if (meta.name === 'teams') counts.teams++
      }
      const bytes = chunk => { counts.bytes += chunk.length }
      client.on('packet', packet)
      socket.on('data', bytes)
      listeners.push(() => {
        client.removeListener('packet', packet)
        socket.removeListener('data', bytes)
      })
      counters.set(actor.bot.username, counts)
    }
    const marker = (actor, text = 'WORKLOAD_DISPLAY', previousIds = null) => {
      const textKey = actor.bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text')
      return Object.values(actor.bot.entities).some(entity => entity.name === 'text_display'
        && (previousIds == null || !previousIds.has(entity.id)) && plain(entity.metadata[textKey]).includes(text))
    }
    const combatPulse = async () => {
      const victim = actors[1]
      const pulse = { began: Date.now(), damageHealthElapsedMs: null, damageMarkerElapsedMs: null,
        healCommandElapsedMs: null, healMarkerElapsedMs: null }
      context.report.combatPulses ??= []
      context.report.combatPulses.push(pulse)
      context.expect(victim.bot.health === 20, 'Combat actor must start each attack fully healed')
      const target = context.bot.players[victim.bot.username]?.entity
      context.expect(target != null, 'Combat target is not visible')
      const beforeAttack = new Set(Object.values(context.bot.entities).map(entity => entity.id))
      context.bot.attack(target)
      await context.waitUntil(() => victim.bot.health < 20,
        { timeoutMs: 5000, label: 'attack changes victim health' })
      pulse.damageHealthElapsedMs = Date.now() - pulse.began
      pulse.healthAfterDamage = victim.bot.health
      await context.waitUntil(() => marker(context, 'WORKLOAD_DAMAGE', beforeAttack),
        { timeoutMs: 5000, label: 'attack publishes a fresh damage indicator' })
      pulse.damageMarkerElapsedMs = Date.now() - pulse.began
      context.expect(victim.bot.health > 0, 'Combat actor died')
      await context.sleep(combatDebounceMs + 50)
      context.expect(victim.bot.health < 20, 'Combat actor must still need healing after the debounce interval')
      const beforeHealing = new Set(Object.values(context.bot.entities).map(entity => entity.id))
      pulse.healCommandElapsedMs = Date.now() - pulse.began
      pulse.healthBeforeHealing = victim.bot.health
      await context.command(`/effect give ${victim.bot.username} minecraft:instant_health 1 5 true`)
      await context.waitUntil(() => victim.bot.health === 20 && marker(context, 'WORKLOAD_HEAL', beforeHealing),
        { timeoutMs: 5000, label: 'healing restores health and publishes an indicator' })
      pulse.healMarkerElapsedMs = Date.now() - pulse.began
    }
    const board = revision => ({ schemaVersion: 2, revision,
      select: { priority: 10000, when: 'true' },
      presentation: { title: `WORKLOAD ${revision}`, hideNumbers: true,
        lines: ['Viewer {{ player.name }}', `Revision ${revision}`, 'Stable row',
          phase === 'animated' ? '|animation.workload|' : 'Stable value'] }, variants: [] })
    observe(context)
    try {
      await context.step('install compatible fixture and join workload population', async () => {
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/^nametags\s*=\s*false/gm, 'nametags = true')
          .replace(/^damageIndicators\s*=\s*false/gm, 'damageIndicators = true'))
        if (phase === 'combat') {
          const indicators = JSON.parse(await readFile(path.join(root, 'damage-indicators/default.json'), 'utf8'))
          combatDebounceMs = indicators.limits.debounceMs
          context.expect(Number.isFinite(combatDebounceMs) && combatDebounceMs >= 0,
            'Combat fixture requires a nonnegative damage/healing debounce interval')
          const regeneration = await context.command('/gamerule minecraft:natural_health_regeneration',
            /natural_health_regeneration.*\b(?:true|false)\b/i)
          combatRegeneration = /\btrue\b/i.test(regeneration)
          await context.command('/gamerule minecraft:natural_health_regeneration false',
            /natural_health_regeneration.*\bfalse\b/i)
          indicators.damage.presentation.format = 'WORKLOAD_DAMAGE {amount}'
          indicators.healing.presentation.format = 'WORKLOAD_HEAL {amount}'
          indicators.audience.when = 'true'
          await save('damage-indicators/default.json', indicators)
        }
        await save('animations/workload.json', { schemaVersion: 1, revision: 1,
          mode: 'ascend', frameIntervalMs: 100, frames: ['Frame A', 'Frame B', 'Frame C', 'Frame D'] })
        await save('boards/workload.json', board(1))
        await save('nametags/workload.json', { schemaVersion: 1, revision: 1,
          select: { priority: 10000, when: 'true' }, presentation: {
            prefix: '[Workload] ', suffix: '', color: 'white', nameTagVisibility: 'always', collision: 'never' }, variants: [] })
        for (let index = 1; index < population; index++) {
          await context.sleep(1200)
          const actor = await context.connectActor(`Workload${index}`)
          actors.push(actor)
          observe(actor)
        }
        for (let index = 0; index < actors.length; index++) {
          const x = phase === 'spread' ? index * 512 + 0.5 : index % 4 + 0.5
          const z = phase === 'spread' ? 0.5 : Math.floor(index / 4) + 0.5
          await context.command(`/fill ${Math.floor(x) - 4} 200 ${Math.floor(z) - 4} ${Math.floor(x) + 4} 200 ${Math.floor(z) + 4} minecraft:stone`)
          await context.command(`/gamemode creative ${actors[index].bot.username}`)
          await context.command(`/tp ${actors[index].bot.username} ${x} 201 ${z}`)
          const count = phase === 'crowded' ? 8 : 1
          for (let display = 0; display < count; display++) {
            await save(`holograms/workload-${index}-${display}.json`, { schemaVersion: 3, revision: 1,
              anchor: { world: 'world', position: [x + 2, 203 + display * 0.35, z + 2] },
              lines: [`WORKLOAD_DISPLAY ${index}/${display}`,
                phase === 'animated' ? '|animation.workload|' : 'Prepared static content'], show: true })
          }
        }
        await context.waitUntil(() => actors.every(actor => marker(actor)
          && (counters.get(actor.bot.username).board || Object.values(actor.bot.scoreboards ?? {}).some(value => plain(value.title).includes('WORKLOAD')))), { timeoutMs: 45000, label: 'all viewers receive display and sidebar' })
        await context.sleep(10000)
        if (phase === 'combat') {
          await context.command(`/gamemode survival ${actors[1].bot.username}`)
          await context.waitUntil(() => actors[1].bot.game.gameMode === 'survival',
            { timeoutMs: 5000, label: 'combat actor enters survival mode' })
          if (actors[1].bot.health < 20) {
            await context.command(`/effect give ${actors[1].bot.username} minecraft:instant_health 1 5 true`)
            await context.waitUntil(() => actors[1].bot.health === 20,
              { timeoutMs: 5000, label: 'combat actor heals before workload' })
          }
          await combatPulse()
          await context.sleep(1000)
        }
      })
      await context.step(`record ${phase} workload`, async () => {
        const before = new Map([...counters].map(([name, values]) => [name, { ...values }]))
        const started = Date.now()
        const actions = []
        console.log(`Gloss workload active: phase=${phase}, players=${population}, duration=${seconds}s`)
        for (let elapsed = 0; elapsed < seconds; elapsed++) {
          const iterationStarted = Date.now()
          if (phase === 'combat') {
            const began = Date.now()
            await combatPulse()
            actions.push({ kind: 'combat', milliseconds: Date.now() - began })
          }
          if (phase === 'churn' && elapsed % 5 === 0) {
            const index = 1 + (elapsed / 5) % (actors.length - 1)
            const previous = actors[index]
            const uuid = previous.bot.player.uuid
            const began = Date.now()
            const actor = await previous.reconnectAfter(
              () => previous.bot.quit('Workload planned reconnect'), { timeoutMs: 15000 })
            actors[index] = actor
            observe(actor)
            context.expect(actor.bot.player.uuid === uuid, 'Churn must preserve player identity')
            await context.command(`/tp ${actor.bot.username} ${index % 4 + 0.5} 201 ${Math.floor(index / 4) + 0.5}`)
            await context.waitUntil(() => marker(actor)
              && Object.values(actor.bot.scoreboards ?? {}).some(value => plain(value.title).includes('WORKLOAD')),
            { timeoutMs: 15000, label: 'reconnected viewer receives restored display and sidebar' })
            actions.push({ kind: 'reconnect', player: actor.bot.username, milliseconds: Date.now() - began })
          }
          if (phase === 'reload' && elapsed % 5 === 0) {
            const revision = elapsed / 5 + 2
            const began = Date.now()
            updates.push({ revision, began, milliseconds: null, observed: false })
            await save('boards/workload.json', board(revision))
          }
          const pause = Math.max(0, 1000 - (Date.now() - iterationStarted))
          if (pause > 0) await context.sleep(pause)
          context.expect(actors.every(actor => marker(actor)), 'A workload display disappeared')
        }
        context.report.workload = { phase, population, clientPhysics: false, requestedSeconds: seconds,
          iterations: seconds, minimumIterationMs: 1000,
          trafficScope: 'post-spawn socket bytes and decoded packets; excludes handshake and packets received before observation attaches',
          elapsedMs: Date.now() - started, documentRefreshes: updates, actions,
          traffic: [...counters].map(([name, after]) => ({ name,
            ...Object.fromEntries(['packets', 'objectives', 'metadata', 'teams', 'bytes']
              .map(key => [key, after[key] - before.get(name)[key]])) })) }
        context.expect(context.report.workload.traffic.every(value => value.packets > 0 && value.bytes > 0),
          'Every workload connection must receive traffic')
        context.expect(updates.every(update => update.observed), 'Every sidebar revision must be published', updates)
        if (phase === 'animated') {
          context.report.workload.animationFrames = [...counters].map(([name, values]) => ({
            name, frames: [...values.animationFrames].sort() }))
          context.expect(context.report.workload.animationFrames.every(value => value.frames.length === 4),
            'Every viewer must receive all four authored animation frames', context.report.workload.animationFrames)
        }
      })
    } catch (error) {
      primaryFailure = error
      context.report.workloadFailure = actors.map(actor => ({
        player: actor.bot.username, health: actor.bot.health,
        gameMode: actor.bot.game.gameMode, position: actor.bot.entity?.position,
        boardTitles: Object.values(actor.bot.scoreboards ?? {}).map(value => plain(value.title)),
        visiblePlayers: Object.values(actor.bot.players).filter(player => player.entity)
          .map(player => ({ name: player.username, position: player.entity.position })),
        displayText: Object.values(actor.bot.entities).filter(entity => entity.name === 'text_display')
          .map(entity => plain(entity.metadata[actor.bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text')]))
      }))
      throw error
    } finally {
      const cleanup = await Promise.allSettled([
        ...(combatRegeneration === true ? [context.command('/gamerule minecraft:natural_health_regeneration true',
          /natural_health_regeneration.*\btrue\b/i)] : []),
        ...listeners.map(async detach => detach()),
        ...[...originals].reverse().map(async ([file, original]) => {
          if (original == null) await rm(file, { force: true })
          else await writeFile(file, original)
        }),
      ])
      const failures = cleanup.filter(result => result.status === 'rejected').map(result => result.reason)
      if (failures.length > 0) {
        throw new AggregateError(primaryFailure == null ? failures : [primaryFailure, ...failures],
          primaryFailure == null ? 'Workload cleanup failed' : `Workload cleanup failed after: ${primaryFailure.message ?? String(primaryFailure)}`,
          primaryFailure == null ? undefined : { cause: primaryFailure })
      }
    }
  },
}
