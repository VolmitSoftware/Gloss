import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'

function plain(value) {
  if (value == null) return ''
  if (typeof value === 'string') {
    try { return plain(JSON.parse(value)) } catch { return value.replace(/\u00a7./g, '') }
  }
  if (Array.isArray(value)) return value.map(plain).join('')
  if (typeof value !== 'object') return ''
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  if ('value' in value) return plain(value.value)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'audit-documents',
  description: 'Verify names, document settings, per-viewer presentations, page visibility, and ordered object lines.',
  async run(context) {
    const { bot } = context
    const instance = context.server.directory
    context.expect(/^isolated=true$/m.test(await readFile(path.join(instance, '.server-source'), 'utf8')),
      'Document acceptance requires an isolated instance')
    const root = path.join(instance, 'plugins', 'Gloss')
    const originals = new Map()
    const save = async (relative, document) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file, 'utf8')) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, JSON.stringify(document, null, 2))
    }
    const keys = bot.registry.entitiesByName.text_display.metadataKeys
    const displays = () => Object.values(bot.entities).filter(entity => entity.name === 'text_display')
    const text = entity => plain(entity.metadata[keys.indexOf('text')])
    const find = marker => displays().find(entity => text(entity).includes(marker))
    const until = (predicate, label) => context.waitUntil(predicate, { timeoutMs: 35000, label })
    const names = { schemaVersion: 1, revision: 1, materials: { oak_log: 'AUDIT_TIMBER' } }
    const drop = JSON.parse(await readFile(path.join(root, 'real-drops/default.json'), 'utf8'))
    const component = marker => ({ id: 'label', offset: [0, 0, 0],
      data: { type: 'decoration', icon: { type: 'text', text: marker } } })
    let peer
    try {
      await context.step('Catalog names and raw keys resolve together', async () => {
        await save('names.json', names)
        const position = bot.entity.position
        await save('holograms/audit-names.json', { schemaVersion: 3, revision: 1,
          anchor: { world: 'world', position: [position.x + 2, position.y + 2, position.z + 2] },
          lines: ["AUDIT_NAME {{ name('materials', 'minecraft:oak_log') }}"], show: true })
        await until(() => find('AUDIT_NAME AUDIT_TIMBER'), 'catalog name expression')
        context.report.initialName = text(find('AUDIT_NAME AUDIT_TIMBER'))
      })
      await context.step('Names hotload updates an existing hologram and drop label', async () => {
        bot.chat('/gamemode creative @s')
        await until(() => bot.game.gameMode === 'creative', 'creative mode')
        const position = bot.entity.position
        drop.presentation.labels.enabled = true
        drop.presentation.labels.show = true
        drop.presentation.labels.names = {}
        drop.presentation.labels.format = 'AUDIT_DROP {type}'
        drop.revision++
        await save('real-drops/default.json', drop)
        bot.chat(`/summon minecraft:item ${position.x + 3} ${position.y + 1} ${position.z + 3} {Tags:["gloss_audit_document"],PickupDelay:32767s,Item:{id:"minecraft:oak_log",count:2}}`)
        await until(() => find('AUDIT_DROP AUDIT_TIMBER'), 'drop catalog name')
        names.materials.oak_log = 'AUDIT_RENAMED'
        names.revision++
        await save('names.json', names)
        await until(() => find('AUDIT_NAME AUDIT_RENAMED') && find('AUDIT_DROP AUDIT_RENAMED'),
          'catalog hotload refreshes both surfaces')
      })
      await context.step('Drop document names override the catalog and show controls visibility', async () => {
        drop.presentation.labels.names = { OAK_LOG: 'AUDIT_LOCAL' }
        drop.revision++
        await save('real-drops/default.json', drop)
        await until(() => find('AUDIT_DROP AUDIT_LOCAL'), 'surface name override')
        drop.presentation.labels.show = false
        drop.revision++
        await save('real-drops/default.json', drop)
        await until(() => !find('AUDIT_DROP'), 'document label show=false')
        context.expect(Boolean(find('AUDIT_NAME AUDIT_RENAMED')), 'Names hologram disappeared with drop label')
      })
      await context.step('An open menu changes variants when viewer state changes', async () => {
        await save('menus/audit-variants.json', { revision: 1, offset: [0, 1.7, 3], show: true,
          lockPosition: false, followPlayer: false, closeOnDeath: false, closeOnTeleport: false,
          components: [component('AUDIT_MENU_BASE')], particleLayers: [],
          variants: [{ id: 'creative', priority: 10, when: "viewer.gameMode == 'creative'",
            components: [component('AUDIT_MENU_CREATIVE')] }] })
        await context.waitUntil(async () => {
          if (find('AUDIT_MENU_CREATIVE')) return true
          bot.chat('/gloss menu open menu=audit-variants args=qa=audit')
          return false
        }, { timeoutMs: 35000, intervalMs: 1000, label: 'creative menu variant' })
        bot.chat('/gamemode survival @s')
        await until(() => find('AUDIT_MENU_BASE') && !find('AUDIT_MENU_CREATIVE'), 'live base menu variant')
        context.report.menuVariant = text(find('AUDIT_MENU_BASE'))
        bot.chat('/gloss menu close')
        await until(() => !find('AUDIT_MENU_BASE'), 'menu cleanup')
      })
      await context.step('Hologram variants differ between viewers and react to state changes', async () => {
        peer = (await context.connectActor('GlossVariants')).bot
        bot.chat(`/tp ${peer.username} ${bot.username}`)
        await until(() => peer.entity.position.distanceTo(bot.entity.position) < 4, 'peer nearby')
        bot.chat('/gamemode creative @s')
        bot.chat(`/gamemode survival ${peer.username}`)
        await until(() => bot.game.gameMode === 'creative' && peer.game.gameMode === 'survival', 'distinct viewer states')
        const position = bot.entity.position
        const peerDisplays = () => Object.values(peer.entities).filter(entity => entity.name === 'text_display')
        const peerFind = marker => peerDisplays().find(entity => text(entity).includes(marker))
        await save('holograms/audit-variant.json', { schemaVersion: 3, revision: 1,
          anchor: { world: 'world', position: [position.x + 2, position.y + 2, position.z] },
          lines: ['AUDIT_HOLO_BASE'], refreshTicks: 1,
          variants: [{ id: 'creative', priority: 10, when: "viewer.gameMode == 'creative'",
            presentation: { lines: ['AUDIT_HOLO_CREATIVE'] } }] })
        await until(() => find('AUDIT_HOLO_CREATIVE') && peerFind('AUDIT_HOLO_BASE'), 'independent hologram winners')
        context.expect(!peerFind('AUDIT_HOLO_CREATIVE'), 'Creative hologram variant leaked to survival viewer')
        bot.chat('/gamemode survival @s')
        await until(() => find('AUDIT_HOLO_BASE') && !find('AUDIT_HOLO_CREATIVE'), 'hologram variant changes with gamemode')
        context.report.hologramVariants = { primary: text(find('AUDIT_HOLO_BASE')), peer: text(peerFind('AUDIT_HOLO_BASE')) }
      })
      await context.step('Hidden hologram pages are skipped during navigation', async () => {
        const position = bot.entity.position
        await save('holograms/audit-pages.json', { schemaVersion: 3, revision: 1,
          anchor: { world: 'world', position: [position.x + 3, position.y + 2, position.z] }, refreshTicks: 1,
          pages: [{ id: 'hidden', show: false, lines: ['AUDIT_PAGE_HIDDEN'] },
            { id: 'first', lines: ['AUDIT_PAGE_FIRST'] }, { id: 'second', lines: ['AUDIT_PAGE_SECOND'] }] })
        await until(() => find('AUDIT_PAGE_FIRST'), 'first visible page')
        bot.chat(`/gloss hologram page id=audit-pages page=next player=${bot.username}`)
        await until(() => find('AUDIT_PAGE_SECOND'), 'next visible page')
        bot.chat(`/gloss hologram page id=audit-pages page=next player=${bot.username}`)
        await until(() => find('AUDIT_PAGE_FIRST'), 'page navigation wraps past hidden page')
        context.expect(!find('AUDIT_PAGE_HIDDEN'), 'Hidden page text was delivered')
      })
      await context.step('Mixed hologram object rows preserve order and viewer visibility', async () => {
        const position = bot.entity.position
        const anchor = [position.x + 4, position.y + 3, position.z]
        bot.chat('/gamemode creative @s')
        await until(() => bot.game.gameMode === 'creative', 'creative object viewer')
        const existing = new Map([bot, peer].map(viewer => [viewer,
          new Set(Object.values(viewer.entities).map(entity => entity.id))]))
        const objects = viewer => Object.values(viewer.entities).filter(entity =>
          !existing.get(viewer).has(entity.id) && ['cow', 'pig'].includes(entity.name) &&
          Math.abs(entity.position.x - anchor[0]) < 1.25 &&
          Math.abs(entity.position.z - anchor[2]) < 1.25 &&
          entity.position.y >= anchor[1] - 0.05 && entity.position.y < anchor[1] + 5)
        const describe = entity => ({ id: entity.id, name: entity.name,
          position: { x: entity.position.x, y: entity.position.y, z: entity.position.z } })
        context.report.objectOrder = { anchor, primary: [], peer: [] }
        await save('holograms/audit-order.json', { schemaVersion: 3, revision: 1,
          anchor: { world: 'world', position: anchor }, refreshTicks: 1,
          style: { billboard: 'fixed', scaleX: 1, scaleY: 1, scaleZ: 1 },
          lines: [{ entity: 'minecraft:cow', scale: 1, show: "viewer.gameMode == 'creative'" },
            'AUDIT_ORDER_MIDDLE', { entity: 'minecraft:pig', scale: 1 }] })
        await until(() => {
          context.report.objectOrder.primary = objects(bot).map(describe)
          context.report.objectOrder.peer = objects(peer).map(describe)
          return objects(bot).some(entity => entity.name === 'cow') &&
            objects(bot).some(entity => entity.name === 'pig') && find('AUDIT_ORDER_MIDDLE')
        }, 'ordered object spawns')
        context.expect(objects(bot).length === 2, 'Mixed hologram spawned duplicate object rows')
        const cow = objects(bot).find(entity => entity.name === 'cow')
        const pig = objects(bot).find(entity => entity.name === 'pig')
        const renderedRows = text(find('AUDIT_ORDER_MIDDLE')).split('\n')
        const middleIndex = renderedRows.findIndex(row => row.includes('AUDIT_ORDER_MIDDLE'))
        const middleBottom = anchor[1] + (renderedRows.length - middleIndex - 1) * 0.25
        const middleTop = middleBottom + 0.25
        const textTop = anchor[1] + renderedRows.length * 0.25
        context.expect(cow.position.y >= middleTop - 0.05 && cow.position.y + 1.4 <= textTop + 0.05 &&
          pig.position.y >= anchor[1] - 0.05 && pig.position.y + 0.9 <= middleBottom + 0.05,
          'Native cow and pig bounds did not occupy their authored rows above and below the middle text')
        await until(() => objects(peer).some(entity => entity.name === 'pig'), 'peer object visibility')
        context.expect(objects(peer).length === 1 && objects(peer)[0].name === 'pig',
          'Conditional cow row leaked to survival viewer or object rows duplicated')
        const peerPigId = objects(peer)[0].id
        Object.assign(context.report.objectOrder, { primary: objects(bot).map(describe),
          peer: objects(peer).map(describe), textRows: renderedRows.length, middleBottom, middleTop })
        bot.chat('/gamemode survival @s')
        await until(() => !bot.entities[cow.id] && bot.entities[pig.id] &&
          !objects(bot).some(entity => entity.name === 'cow'), 'object show updates')
        context.expect(Boolean(peer.entities[peerPigId]), 'Other viewer lost its unchanged pig row')
      })
      await context.step('Entity overlay variants use each viewer state', async () => {
        const position = bot.entity.position
        bot.chat('/gamemode creative @s')
        await until(() => bot.game.gameMode === 'creative', 'creative overlay viewer')
        await save('entity-overlays/default.json', { schemaVersion: 2, revision: 200,
          enabled: true, includePlayers: false, range: 16, updateIntervalTicks: 1,
          lines: [{ id: 'base', text: 'AUDIT_OVERLAY_BASE {typeName}' }],
          variants: [{ id: 'creative', priority: 10, when: "viewer.gameMode == 'creative'",
            presentation: { lines: [{ id: 'creative', text: 'AUDIT_OVERLAY_CREATIVE {typeName}' }] } }] })
        bot.chat(`/summon minecraft:zombie ${position.x + 2} ${position.y} ${position.z + 2} {Tags:["gloss_audit_overlay"],NoAI:1b,Silent:1b,Invulnerable:1b}`)
        const peerFind = marker => Object.values(peer.entities).find(entity => entity.name === 'text_display' && text(entity).includes(marker))
        await until(() => find('AUDIT_OVERLAY_CREATIVE') && peerFind('AUDIT_OVERLAY_BASE'), 'independent overlay variants')
        context.expect(!peerFind('AUDIT_OVERLAY_CREATIVE'), 'Creative overlay variant leaked to survival viewer')
        context.report.overlayVariants = { primary: text(find('AUDIT_OVERLAY_CREATIVE')), peer: text(peerFind('AUDIT_OVERLAY_BASE')) }
        await save('entity-overlays/default.json', { schemaVersion: 2, revision: 201,
          enabled: true, includePlayers: false, range: 16, updateIntervalTicks: 1,
          lines: [{ id: 'name', text: 'AUDIT_OVERLAY_STILL {typeName}' }] })
        await until(() => find('AUDIT_OVERLAY_STILL Zombie') && peerFind('AUDIT_OVERLAY_STILL Zombie'), 'stationary shared overlay')
        names.entities = { zombie: 'AUDIT_WALKER' }
        names.revision++
        await save('names.json', names)
        await until(() => find('AUDIT_OVERLAY_STILL AUDIT_WALKER') && peerFind('AUDIT_OVERLAY_STILL AUDIT_WALKER'), 'stationary overlay catalog hotload')
        context.report.overlayCatalogReload = text(find('AUDIT_OVERLAY_STILL AUDIT_WALKER'))
      })
    } finally {
      if (!context.signal.aborted) {
        bot.chat('/gloss menu close')
        bot.chat('/kill @e[type=minecraft:item,tag=gloss_audit_document]')
        bot.chat('/kill @e[tag=gloss_audit_overlay]')
      }
      for (const [file, original] of originals) {
        if (original == null) await rm(file, { force: true })
        else await writeFile(file, original)
      }
    }
  }
}
