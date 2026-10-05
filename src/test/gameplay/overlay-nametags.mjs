import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

function plain(value) {
  if (value == null) return ''
  if (typeof value === 'string') return value.replace(/\u00a7./g, '')
  if (Array.isArray(value)) return value.map(plain).join('')
  if (typeof value !== 'object') return ''
  if (value.type && 'value' in value) return plain(value.value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'overlay-nametags',
  description: 'Verify per-viewer native mob nametag suppression and restoration while entity overlays change.',
  async run(context) {
    const { bot } = context
    context.expect(context.server.instance === 'gloss-overlay-nametags-qa', 'Use the isolated nametag fixture instance')
    const file = path.join(context.server.directory, 'plugins/Gloss/entity-overlays/default.json')
    const original = JSON.parse(await readFile(file, 'utf8'))
    const observations = []
    const attachments = []
    let peer
    const observe = actor => {
      const native = new Map()
      const history = []
      const teams = new Map()
      const teamPackets = []
      const onTeam = packet => {
        teamPackets.push(packet)
        if (packet.mode === 'remove') { teams.delete(packet.team); return }
        const team = teams.get(packet.team) ?? { players: new Set(), visibility: 'always' }
        if (packet.nameTagVisibility !== undefined) team.visibility = packet.nameTagVisibility
        if (packet.mode === 'add') team.players.clear()
        for (const entry of packet.players ?? []) {
          if (packet.mode === 'leave') team.players.delete(entry)
          else {
            for (const other of teams.values()) other.players.delete(entry)
            team.players.add(entry)
          }
        }
        teams.set(packet.team, team)
      }
      actor._client.on('teams', onTeam)
      const listener = packet => {
        const state = native.get(packet.entityId) ?? {}
        for (const entry of packet.metadata) {
          if (entry.key === 2) state.name = plain(entry.value)
          if (entry.key === 3) state.visible = Boolean(entry.value)
          if (entry.key === 23) state.text = plain(entry.value)
        }
        native.set(packet.entityId, state)
        history.push({ id: packet.entityId, name: state.name, visible: state.visible, at: Date.now() })
      }
      actor._client.on('entity_metadata', listener)
      attachments.push([actor, listener, onTeam])
      const cow = () => Object.values(actor.entities).find(entity => entity.name === 'cow' && Math.abs(entity.position.x - 0.5) < 0.5 && Math.abs(entity.position.z - 5.5) < 0.5)
      const state = () => { const entity = cow(); return { name: plain(entity?.metadata?.[2]), visible: Boolean(entity?.metadata?.[3]), ...(native.get(entity?.id) ?? {}) } }
      const suppressed = () => [...teams.values()].some(team => team.visibility === 'never' && team.players.has(cow()?.uuid))
      const pane = text => Object.values(actor.entities).some(entity => entity.name === 'text_display'
        && plain(entity.metadata[23]).includes(text))
      return { actor, native, history, cow, state, pane, suppressed, teamPackets }
    }
    const primary = observe(bot)
    const wait = (predicate, label) => context.waitUntil(predicate, { timeoutMs: 15000, label })
    const command = async text => { await context.command(text); await context.sleep(350) }
    const settings = async changes => {
      const current = JSON.parse(await readFile(file, 'utf8'))
      await writeFile(file, JSON.stringify({ ...current, ...changes, revision: current.revision + 1 }, null, 2))
    }
    const hidden = watcher => watcher.suppressed()
    const restored = (watcher, name, visible) => !watcher.suppressed() && watcher.state().name.includes(name) && watcher.state().visible === visible
    const record = phase => observations.push({ phase, primary: { ...primary.state(), suppressed: primary.suppressed() }, peer: peer ? { ...peer.state(), suppressed: peer.suppressed() } : null })
    try {
      await context.step('prepare named cow with native and overlay labels', async () => {
        await command('/gamemode creative @s')
        await command('/fill -5 199 -5 35 199 12 minecraft:stone')
        await command('/tp @s 0.5 200 0.5')
        await command('/kill @e[tag=nametagqa]')
        await settings({ overrideNametag: false, show: true, range: 12, lines: [
          { id: 'name', type: 'text', text: 'PANE {name}', show: true },
          { id: 'health', type: 'text', text: 'HEALTH {health}/{max_health}', show: true }
        ] })
        await command('/summon minecraft:cow 0.5 200 5.5 {CustomName:"Nametag One",CustomNameVisible:1b,NoAI:1b,PersistenceRequired:1b,Tags:["nametagqa"]}')
        await wait(() => restored(primary, 'Nametag One', true), 'native name visible by default')
        await wait(() => primary.pane('PANE Nametag One') && primary.pane('HEALTH 10/10'), 'name and health overlay visible')
        peer = observe((await context.connectActor('NamePeer')).bot)
        await command('/gamemode creative NamePeer')
        await command('/tp NamePeer 25.5 200 5.5')
        await wait(() => restored(peer, 'Nametag One', true), 'distant viewer sees native name')
        record('default-false')
      })
      await context.step('type-name hotfix preserves native names and unnamed mob labels', async () => {
        await settings({ lines: [
          { id: 'name', type: 'text', text: '&f{typeName}', show: '!entity.named' },
          { id: 'health', type: 'text', text: 'HEALTH {health}/{max_health}', show: true }
        ] })
        await wait(() => !primary.pane('PANE Nametag One') && !primary.pane('Cow') && primary.pane('HEALTH 10/10'), 'named mob retains health without duplicate name row')
        context.expect(restored(primary, 'Nametag One', true), 'Configuration hotfix changed native nametag')
        await command('/summon minecraft:cow 3.5 200 5.5 {NoAI:1b,PersistenceRequired:1b,Tags:["nametagqa-anon"]}')
        await wait(() => primary.pane('Cow'), 'unnamed mob displays readable type name')
        await command('/kill @e[tag=nametagqa-anon]')
        await settings({ lines: [
          { id: 'name', type: 'text', text: 'PANE {name}', show: true },
          { id: 'health', type: 'text', text: 'HEALTH {health}/{max_health}', show: true }
        ] })
        await wait(() => primary.pane('PANE Nametag One'), 'restore named overlay row')
        record('type-name-hotfix')
      })
      await context.step('override hides native label only for viewer with overlay', async () => {
        await settings({ overrideNametag: true })
        await wait(() => hidden(primary) && primary.pane('PANE Nametag One') && primary.pane('HEALTH 10/10'), 'near viewer native name suppressed while overlay content remains')
        context.expect(restored(peer, 'Nametag One', true), 'Suppression leaked to distant viewer')
        record('near-hidden-peer-native')
      })
      await context.step('renaming suppressed mob preserves server name and latest visibility', async () => {
        await command('/data merge entity @e[tag=nametagqa,limit=1] {CustomName:"Nametag Two",CustomNameVisible:0b}')
        await wait(() => primary.pane('PANE Nametag Two'), 'overlay refreshed renamed mob')
        await wait(() => restored(peer, 'Nametag Two', false), 'distant native metadata updated')
        await context.sleep(1000)
        context.expect(hidden(primary), 'Rename exposed suppressed native name')
        context.expect(primary.state().name.includes('Nametag Two') && primary.state().visible === false, 'Native entity metadata was modified by suppression')
        await context.command('/data get entity @e[tag=nametagqa,limit=1] CustomName', /Nametag Two/, 5000)
        record('renamed-while-hidden')
      })
      await context.step('option false restores latest native metadata', async () => {
        await settings({ overrideNametag: false })
        await wait(() => restored(primary, 'Nametag Two', false), 'latest native name and visibility restored')
        await command('/data merge entity @e[tag=nametagqa,limit=1] {CustomNameVisible:1b}')
        await wait(() => restored(primary, 'Nametag Two', true), 'native visibility updates after restoration')
        record('option-false-restored')
      })
      await context.step('hidden pane restores native name', async () => {
        await settings({ overrideNametag: true })
        await wait(() => hidden(primary), 'native name suppressed again')
        await settings({ show: false })
        await wait(() => restored(primary, 'Nametag Two', true) && !primary.pane('PANE Nametag Two'), 'pane hide restores native name')
        await settings({ show: true })
        await wait(() => hidden(primary) && primary.pane('PANE Nametag Two'), 'pane show suppresses native name again')
        record('pane-show-restored')
      })
      await context.step('range transitions independently restore both viewers', async () => {
        await command('/tp @s 25.5 200 5.5')
        await wait(() => restored(primary, 'Nametag Two', true), 'leaving overlay range restores native name')
        await command('/tp NamePeer 0.5 200 0.5')
        await wait(() => hidden(peer) && peer.pane('PANE Nametag Two'), 'entering viewer receives overlay and suppressed name')
        context.expect(restored(primary, 'Nametag Two', true), 'Second viewer suppression changed first viewer')
        await command('/tp NamePeer 25.5 200 5.5')
        await wait(() => restored(peer, 'Nametag Two', true), 'second viewer leaving restores name')
        record('range-restored')
      })
      if (context.report.plugins.some(plugin => plugin.filename.toLowerCase().startsWith('ecomobs'))) {
        await context.step('EcoMobs packet names remain suppressed through live updates', async () => {
          await command('/kill @e[tag=nametagqa]')
          await command('/ecomobs killall gloss_names')
          await command('/tp @s 0.5 200 0.5')
          await settings({ overrideNametag: true, show: true })
          await command('/ecomobs spawn gloss_names 0.5 200 5.5')
          await wait(() => hidden(primary) && primary.pane('PANE Meadow Keeper'), 'EcoMobs packet nametag suppressed beside overlay')
          await wait(() => primary.state().name.includes('Meadow Keeper'), 'EcoMobs native packet name remains intact')
          await command('/damage @e[type=minecraft:cow,limit=1,sort=nearest] 7 minecraft:generic')
          await wait(() => primary.pane('33/40') && primary.state().name.includes('33/40'), 'EcoMobs native and overlay health names update')
          context.expect(hidden(primary), 'EcoMobs metadata update bypassed team suppression')
          await settings({ overrideNametag: false })
          await wait(() => !hidden(primary) && primary.state().name.includes('33/40'), 'EcoMobs native name restored')
          record('ecomobs-restored')
        })
      }
    } finally {
      context.report.nametagObservations = observations
      context.report.overlayTexts = Object.values(bot.entities).filter(entity => entity.name === 'text_display').map(entity => ({id: entity.id,text: plain(entity.metadata[23])}))
      context.report.nametagMetadata = { primary: primary.history, peer: peer?.history }
      context.report.nametagTeamPackets = { primary: primary.teamPackets, peer: peer?.teamPackets }
      original.revision = JSON.parse(await readFile(file, 'utf8')).revision + 1
      await writeFile(file, JSON.stringify(original, null, 2))
      for (const [actor, listener, onTeam] of attachments) {
        actor._client.removeListener('entity_metadata', listener)
        actor._client.removeListener('teams', onTeam)
      }
    }
  }
}
