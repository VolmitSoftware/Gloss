import { readFile, writeFile, mkdir, rm, readdir } from 'node:fs/promises'
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
  if (typeof value !== 'object') return String(value)
  if ('value' in value) return plain(value.value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'platform-lifecycle',
  description: 'Verify sidebar, display, inventory components and baseline API across supported feature reloads.',
  async run(context) {
    const { bot } = context
    bot.physicsEnabled = false
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Lifecycle acceptance requires an isolated instance')
    const root = path.join(context.server.directory, 'plugins/Gloss')
    const originals = new Map()
    const objectives = []
    const teams = []
    const notices = []
    const objectiveListener = packet => objectives.push(packet)
    const teamListener = packet => teams.push(packet)
    const noticeListener = value => notices.push(value.toString())
    bot._client.on('scoreboard_objective', objectiveListener)
    bot._client.on('teams', teamListener)
    bot.on('actionBar', noticeListener)
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file)) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n')
    }
    const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
    const features = enabled => config.replace(/^(boards|holograms|inventories)\s*=\s*(true|false)$/gm,
      (_, feature) => `${feature} = ${enabled}`)
    const board = revision => ({ schemaVersion: 2, revision, select: { priority: 10000, when: 'true' },
      presentation: { title: `<font:lifecycle:fixed>LIFECYCLE_BOARD_${revision}</font>`, hideNumbers: true,
        lines: ['<font:lifecycle:fixed>LIFECYCLE_ROW</font>', '{{ player.name }}'] }, variants: [] })
    const hologram = revision => ({ schemaVersion: 3, revision,
      anchor: { world: 'world', position: [2.5, 82, 0.5] },
      lines: [`LIFECYCLE_DISPLAY_${revision}`], audience: { viewDistance: 32, when: 'true' } })
    const inventory = revision => ({ schemaVersion: 1, revision, show: true,
      title: `LIFECYCLE_INVENTORY_${revision}`, resolution: '9x3', slots: {
        '11': { type: 'button', icon: { type: 'item', item: 'minecraft:paper',
          name: '<font:lifecycle:fixed>LIFECYCLE_ITEM {{ time.seconds }}</font>',
          lore: ['<font:lifecycle:fixed>LIFECYCLE_LORE</font>'] },
          actions: [{ type: 'message', message: 'LIFECYCLE_CLICK' }] }
      }, variants: [] })
    const display = revision => {
      const key = bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text')
      return Object.values(bot.entities).find(entity => entity.name === 'text_display'
        && plain(entity.metadata[key]).includes(`LIFECYCLE_DISPLAY_${revision}`))
    }
    const surfaces = async revision => {
      await context.waitUntil(() => display(revision)
        && Object.values(bot.scoreboards ?? {}).some(board => plain(board.title).includes(`LIFECYCLE_BOARD_${revision}`)),
      { timeoutMs: 30000, label: `sidebar and hologram revision ${revision}` })
      context.expect(objectives.some(packet => plain(packet.displayText).includes(`LIFECYCLE_BOARD_${revision}`)
        && JSON.stringify(packet.displayText).includes('lifecycle:fixed')), 'Sidebar title lost its font component')
      context.expect(teams.some(packet => JSON.stringify(packet).includes('LIFECYCLE_ROW')
        && JSON.stringify(packet).includes('lifecycle:fixed')), 'Sidebar row lost its font component')
    }
    const openInventory = async revision => {
      const opened = context.waitForEvent('windowOpen', () => true, 10000)
      bot.chat('/gloss inventory open lifecycle')
      const [window] = await opened
      context.expect(plain(window.title).includes(`LIFECYCLE_INVENTORY_${revision}`), 'Wrong inventory revision')
      await context.waitUntil(() => window.slots[11]?.name === 'paper',
        { timeoutMs: 10000, label: 'inventory item delivery' })
      const item = window.slots[11]
      const components = JSON.stringify(item.components)
      context.expect(plain(item.customName).includes('LIFECYCLE_ITEM'), 'Inventory item name was overwritten')
      context.expect(components.includes('LIFECYCLE_LORE') && components.includes('lifecycle:fixed')
        && !components.includes('<font'), 'Inventory rich name/lore components were flattened', item.components)
      context.report.inventoryComponents = item.components
      const reply = context.waitForMessage('LIFECYCLE_CLICK', 10000)
      await bot.clickWindow(11, 0, 0)
      await reply
      return window
    }
    const api = async () => {
      const result = await context.command('/glosslifecycle', /GLOSS_LIFECYCLE_API_(OK|FAIL)/, 10000)
      context.expect(result.includes('GLOSS_LIFECYCLE_API_OK'), 'Baseline API probe failed', result)
    }
    try {
      await context.step('install fixture and receive rich sidebar, display and inventory', async () => {
        if (bot.game.gameMode !== 'creative') {
          await context.command(`/gamemode creative ${bot.username}`, /game mode|creative/i, 10000)
        }
        await context.command(`/tp ${bot.username} 0.5 81 0.5`, /teleported/i, 10000)
        await save('boards/lifecycle.json', board(1))
        await save('holograms/lifecycle.json', hologram(1))
        await save('inventories/lifecycle.json', inventory(1))
        await save('gloss.toml', features(true))
        await surfaces(1)
        await api()
        await context.waitUntil(() => notices.some(notice => /hotloaded.*inventories/i.test(notice)),
          { timeoutMs: 15000, label: 'inventory document hotload completed' })
        await openInventory(1)
      })
      await context.step('immediate reopen survives the previous window close callback', async () => {
        const opened = context.waitForEvent('windowOpen', () => true, 10000)
        await context.command('/glosslifecycle reopen', /GLOSS_LIFECYCLE_(REOPEN_OK|API_FAIL)/, 10000)
        const [window] = await opened
        await context.waitUntil(() => window.slots[11]?.name === 'paper',
          { timeoutMs: 10000, label: 'reopened inventory item delivery' })
        const initialName = plain(window.slots[11].customName)
        await context.waitUntil(() => bot.currentWindow === window
          && plain(window.slots[11]?.customName).includes('LIFECYCLE_ITEM')
          && plain(window.slots[11]?.customName) !== initialName,
        { timeoutMs: 8000, label: 'reopened inventory continues refreshing after delayed close callback' })
        const reply = context.waitForMessage('LIFECYCLE_CLICK', 10000)
        await bot.clickWindow(11, 0, 0)
        await reply
        context.report.immediateReopen = { refreshSurvived: true, clickSurvived: true }
      })
      await context.step('supported configuration reload removes active surfaces and window', async () => {
        const objectiveStart = objectives.length
        await save('gloss.toml', features(false))
        await context.waitUntil(() => {
          const state = { inventoryOpen: Boolean(bot.currentWindow), displayPresent: Boolean(display(1)),
            objectiveRemovals: objectives.slice(objectiveStart).filter(packet => packet.action === 1).length }
          context.report.disabledFeatureState = state
          return !state.inventoryOpen && !state.displayPresent && state.objectiveRemovals > 0
        },
        { timeoutMs: 30000, label: 'disabled features remove sidebar, hologram and inventory' })
        await api()
        await context.command('/gloss inventory open lifecycle', /No inventory menu/i, 10000)
        context.expect(!bot.currentWindow, 'Disabled inventory feature accepted a new open')
      })
      await context.step('re-enable restores current documents and component delivery', async () => {
        await save('boards/lifecycle.json', board(2))
        await save('holograms/lifecycle.json', hologram(2))
        await save('inventories/lifecycle.json', inventory(2))
        await save('gloss.toml', features(true))
        await surfaces(2)
        await api()
        const window = await openInventory(2)
        bot.closeWindow(window)
        await context.waitUntil(() => !bot.currentWindow, { timeoutMs: 5000, label: 'inventory closes cleanly' })
      })
      await context.step('write local lifecycle diagnostics without uploading', async () => {
        const directory = path.join(root, 'debug')
        const previous = new Set(await readdir(directory).catch(error => {
          if (error.code === 'ENOENT') return []
          throw error
        }))
        await context.command('/gloss debug dump upload=false')
        const file = await context.waitUntil(async () => {
          try {
            return (await readdir(directory)).find(name => name.endsWith('.txt') && !previous.has(name))
          } catch (error) {
            if (error.code === 'ENOENT') return false
            throw error
          }
        }, { timeoutMs: 15000, label: 'local debug dump created' })
        const text = await readFile(path.join(directory, file), 'utf8')
        context.expect(text.includes('Gloss') && text.includes('configured'), 'Lifecycle diagnostics incomplete')
        context.report.debugDump = file
      })
      context.report.lifecycle = { sidebar: true, richInventoryComponents: true, textDisplay: true,
        baselineApiLinked: true, featureReloadRestored: true, renderingVerified: false }
    } finally {
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      bot._client.removeListener('scoreboard_objective', objectiveListener)
      bot._client.removeListener('teams', teamListener)
      bot.removeListener('actionBar', noticeListener)
      for (const [file, original] of [...originals].reverse()) {
        if (original == null) await rm(file, { force: true })
        else await writeFile(file, original)
      }
    }
  }
}
