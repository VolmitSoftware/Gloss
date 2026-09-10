import { readFile, writeFile, rm } from 'node:fs/promises'
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
  if (value.type && 'value' in value) return plain(value.value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'entity-overlays',
  description: 'Verify Gloss mob overlays, React stack counts, and Adapt Insight through live entity metadata.',
  async run(context) {
    const { bot } = context
    const instancePath = process.env.GLOSS_QA_INSTANCE_PATH
    context.expect(instancePath && path.basename(instancePath) === context.server.instance,
      'GLOSS_QA_INSTANCE_PATH must name the current isolated QA instance')
    const adaptationFile = path.join(instancePath, 'plugins/Adapt/adaptations/discovery-insight.toml')
    const originalAdaptation = await readFile(adaptationFile, 'utf8')
    const overlayFile = path.join(instancePath, 'plugins/Gloss/entity-overlays/default.json')
    const originalOverlay = await readFile(overlayFile, 'utf8')
    const glossFile = path.join(instancePath, 'plugins/Gloss/gloss.toml')
    const originalGloss = await readFile(glossFile, 'utf8')
    const metadataKeys = bot.registry.entitiesByName.text_display.metadataKeys
    const textIndex = metadataKeys.indexOf('text')
    const animationFile = path.join(instancePath, 'plugins/Gloss/animations/overlayqa.json')
    const defaults = JSON.parse(originalOverlay)
    let paneParticles = 0
    const particleSamples = []
    const onParticles = packet => {
      if (particleSamples.length < 5) particleSamples.push(packet)
      if (Math.abs(packet.x) < 4 && packet.y > 192 && Math.abs(packet.z - 5) < 4) paneParticles++
    }
    bot._client.on('world_particles', onParticles)
    const allDisplays = () => Object.values(bot.entities).filter(entity => entity.name === 'text_display')
    const displays = () => allDisplays()
      .map(entity => ({ id: entity.id, position: entity.position, text: plain(entity.metadata[textIndex]) }))
      .filter(entity => /ATK.*ARM/.test(entity.text))
    const near = (x, z) => displays().filter(entity => Math.abs(entity.position.x - x) < 1.5
      && Math.abs(entity.position.z - z) < 1.5)
    const textAt = (x, z) => near(x, z).map(entity => entity.text).join('\n')
    const waitFor = async (predicate, message, timeout = 10000) => {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        const value = predicate()
        if (value) return value
        await context.sleep(50)
      }
      context.expect(false, message, { displays: displays(), particleSamples })
    }
    const command = async (value, pattern = /./) => context.command(value, pattern, 10000)
    const clearStacks = async () => {
      for (let attempt = 0; attempt < 11; attempt++) {
        const response = await command('/kill @e[type=minecraft:silverfish,distance=..40]', /killed|found/i)
        if (/no entity/i.test(response)) return
        await context.sleep(100)
      }
      context.expect(false, 'The bounded cleanup did not empty React stacks')
    }
    const inspect = async enabled => command(`/adapt determine discovery:discovery-insight ${enabled} true 5`, /insight|adaptation|learn|assign|level/i)
    const lookAt = async (x, z) => {
      const target = Object.values(bot.entities).find(entity => entity.name === 'husk'
        && Math.abs(entity.position.x - x) < 1 && Math.abs(entity.position.z - z) < 1)
      context.expect(target, 'The intended inspection target is present', { x, z })
      await bot.lookAt(target.position.offset(0, 1, 0), true)
    }
    const restriction = async value => {
      const raw = await readFile(adaptationFile, 'utf8')
      context.expect(/restrictGlossToInsight\s*=\s*(true|false)/.test(raw), 'Insight restriction option exists')
      await writeFile(adaptationFile, raw.replace(/restrictGlossToInsight\s*=\s*(true|false)/,
        `restrictGlossToInsight = ${value}`))
    }
    const overlaySettings = async changes => {
      const settings = JSON.parse(await readFile(overlayFile, 'utf8'))
      await writeFile(overlayFile, JSON.stringify({ ...settings, ...changes, revision: settings.revision + 1 }, null, 2))
    }
    const enableInsight = async value => {
      const raw = await readFile(adaptationFile, 'utf8')
      await writeFile(adaptationFile, raw.replace(/^enabled\s*=\s*(true|false)/m, `enabled = ${value}`))
    }
    const enableHolograms = async value => {
      const raw = await readFile(glossFile, 'utf8')
      await writeFile(glossFile, raw.replace(/^holograms\s*=\s*(true|false)/m, `holograms = ${value}`))
    }
    context.report.overlayEvidence = {}
    const evidence = context.report.overlayEvidence
    try {
      await context.step('Prepare a controlled mob platform', async () => {
        await context.command('/gamemode creative @s')
        await waitFor(() => bot.game.gameMode === 'creative', 'Creative mode was not applied')
        await command('/gamerule minecraft:spawn_mobs false', /false/i)
        await command('/time set day', /time/i)
        await command('/difficulty peaceful', /difficulty/i)
        await context.sleep(500)
        await command('/difficulty normal', /difficulty/i)
        await command('/fill -30 190 -30 30 190 30 minecraft:stone', /filled|changed|placed|blocks/i)
        await command('/tp @s 0 191 0 0 0', /teleport/i)
        await waitFor(() => Math.abs(bot.entity.position.y - 191) < 0.1, 'Bot did not reach the mob platform')
        await context.sleep(250)
        context.expect(Math.abs(bot.entity.position.y - 191) < 0.1, 'Bot fell before the platform was ready')
        await clearStacks()
        await command('/kill @e[tag=overlayqa]', /killed|found|entity/i)
        await inspect(false)
        await restriction(false)
        await command('/summon minecraft:husk 0 191 5 {CustomName:"Sentinel",NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["overlayqa","sentinel"]}', /summon/i)
        await command('/summon minecraft:husk 0 191 21 {CustomName:"Distant",NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["overlayqa","distant"]}', /summon/i)
        await context.sleep(2000)
      })
      await context.step('Unlearned player sees named segmented health and combat stats by default', async () => {
        await waitFor(() => /Sentinel/.test(textAt(0, 5)) && /20\/20/.test(textAt(0, 5))
          && /ATK.*ARM/.test(textAt(0, 5)), 'Default mob overlay is missing')
        const text = textAt(0, 5)
        context.expect((text.match(/\|/g) ?? []).length >= 10, 'Segmented health bar is absent', { text })
        context.expect(!text.includes('Speed'), 'Unlearned viewer received Insight details', { text })
        context.expect(near(0, 21).length === 0, 'Ordinary overlays must obey the 16-block default radius')
        evidence.default = text
      })
      await context.step('Watched overlay settings disable, reenable, and change health segments', async () => {
        await overlaySettings({ enabled: false })
        await waitFor(() => near(0, 5).length === 0, 'Disabling the overlay document did not remove displays', 15000)
        await overlaySettings({ enabled: true, healthSegments: 6 })
        await waitFor(() => {
          const health = textAt(0, 5).split('\n').find(line => line.includes('20/20')) ?? ''
          return (health.match(/\|/g) ?? []).length === 6
        }, 'Hotloaded healthSegments did not reach entity metadata', 15000)
        evidence.hotloaded = textAt(0, 5)
        await overlaySettings({ healthSegments: 10 })
        await waitFor(() => {
          const health = textAt(0, 5).split('\n').find(line => line.includes('20/20')) ?? ''
          return (health.match(/\|/g) ?? []).length === 10
        }, 'The health segment setting did not restore', 15000)
      })
      await context.step('Ordered authored rows, expressions, style, box, and animation reach the shared engine', async () => {
        await writeFile(animationFile, JSON.stringify({ schemaVersion: 1, revision: 1,
          mode: 'ascend', frameIntervalMs: 40, frames: ['FRAME_A', 'FRAME_B'] }))
        const rows = [
          defaults.lines.find(line => line.id === 'stats'),
          { id: 'custom', type: 'text', text: '<gold><particles:probe>Engine {{ entity.health + 1 }} {{ player.name }}</particles></gold>', show: true },
          { id: 'animation', type: 'text', text: '|animation.overlayqa|', show: true },
          { id: 'hidden', type: 'text', text: 'HIDDEN_ROW', show: 'entity.health < 1' },
          ...defaults.lines.filter(line => line.id !== 'stats'),
        ]
        context.expect(rows.every(Boolean), 'The default layout must include an editable stats row')
        await overlaySettings({ lines: rows, style: { ...defaults.style, shadow: true, textAlignment: 'left',
          backgroundArgb: '#00223344', scaleX: 0.8, scaleY: 0.9, scaleZ: 1 },
          box: { enabled: true, padding: 6, borderWidth: 2,
            backgroundArgb: '#B31B1B22', borderArgb: '#FFAAAAAA' },
          particleLayers: [{ id: 'probe', target: { scope: 'span', name: 'probe' },
            geometry: { type: 'outline', spacing: 0.2 }, particle: { key: 'minecraft:flame' },
            emission: { intervalTicks: 1, pattern: 'steady' } }] })
        await waitFor(() => textAt(0, 5).startsWith('ATK')
          && textAt(0, 5).includes(`Engine 21 ${bot.username}`)
          && /FRAME_[AB]/.test(textAt(0, 5)), 'Reordered authored rows did not render through Gloss', 15000)
        const text = textAt(0, 5)
        context.expect(!text.includes('<gold>') && !text.includes('HIDDEN_ROW'),
          'Authored MiniMessage or show conditions were not applied', { text })
        const firstFrame = /FRAME_[AB]/.exec(text)?.[0]
        await waitFor(() => /FRAME_[AB]/.exec(textAt(0, 5))?.[0] !== firstFrame,
          'The native animation did not advance', 5000)
        const panePosition = near(0, 5).find(entity => /Engine/.test(entity.text)).position.clone()
        const panes = () => allDisplays().filter(entity => entity.position.distanceTo(panePosition) < 0.001)
        await waitFor(() => panes().length === 6, 'The box did not create its panel and four border edges', 10000)
        evidence.layout = { text: textAt(0, 5), metadata: panes().map(entity => ({ id: entity.id,
          metadata: Object.fromEntries(metadataKeys.map((key, index) => [key, entity.metadata[index]])) })) }
        const content = panes().find(entity => /Engine/.test(plain(entity.metadata[textIndex])))
        const flagsIndex = metadataKeys.indexOf('style_flags')
        context.expect(flagsIndex >= 0 && (content.metadata[flagsIndex] & 9) === 9,
          'Text shadow and left alignment did not reach metadata')
        const scale = content.metadata[metadataKeys.indexOf('scale')]
        context.expect(Math.abs(scale.x - 0.8) < 0.001 && Math.abs(scale.y - 0.9) < 0.001
          && Math.abs(scale.z - 1) < 0.001, 'Independent pane scales did not reach metadata', { scale })
        const colors = panes().map(entity => entity.metadata[metadataKeys.indexOf('background_color')] >>> 0)
        context.expect(colors.filter(color => color === 0xB31B1B22).length === 1
          && colors.filter(color => color === 0xFFAAAAAA).length === 4,
          'Panel alpha and four opaque border edges did not remain independent', { colors })
        await waitFor(() => paneParticles > 0, 'The authored particle span did not emit through the shared engine', 5000)
        evidence.layout.particlePackets = paneParticles
        await overlaySettings({ show: false })
        await waitFor(() => panes().length === 0, 'Root show left the pane or its decorations visible', 15000)
        await overlaySettings({ ...defaults })
        await waitFor(() => /Sentinel/.test(textAt(0, 5)) && panes().length === 1,
          'Restoring the layout retained decorations or lost text', 15000)
      })
      await context.step('Damage updates health and briefly displays the hit', async () => {
        await command('/damage @e[tag=sentinel,limit=1] 4 minecraft:generic', /damage/i)
        await waitFor(() => /-\d/.test(textAt(0, 5)) && !/20\/20/.test(textAt(0, 5)),
          'A hit did not update the overlay', 3000)
        evidence.hit = textAt(0, 5)
        await waitFor(() => !/-\d/.test(textAt(0, 5)), 'The transient hit label did not expire', 5000)
      })
      await context.step('React merges real silverfish and contributes their count to Gloss', async () => {
        for (let index = 0; index < 3; index++) {
          await command(`/summon minecraft:silverfish ${5 + index * 0.3} 191 3 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["overlayqa","stackqa"]}`, /summon/i)
        }
        await waitFor(() => displays().some(entity => /x3/.test(entity.text)), 'React stack count x3 did not reach Gloss', 20000)
        let response = 'Folia: vanilla data command is unavailable; entity count and overlay metadata are asserted.'
        if (context.report.bot.serverBrand !== 'Folia') {
          response = await command('/data get entity @e[type=minecraft:silverfish,tag=stackqa,limit=1] BukkitValues', /react-stack-count/i)
          context.expect(/react-stack-count[^\n]*3/.test(response), 'The count was not backed by React stack data', { response })
        }
        const merged = Object.values(bot.entities).filter(entity => entity.name === 'silverfish'
          && entity.position.distanceTo(bot.entity.position) < 12)
        context.expect(merged.length === 1, 'React did not merge the three silverfish into one entity', { count: merged.length })
        evidence.stack = { response, displays: displays().filter(entity => /x3/.test(entity.text)) }
      })
      await context.step('Learned Insight enriches the selected mob and keeps other global overlays', async () => {
        await command('/gamemode survival @s', /survival/i)
        await inspect(true)
        await lookAt(0, 5)
        await waitFor(() => /Speed/.test(textAt(0, 5)), 'Learned Insight details did not reach Gloss')
        context.expect(displays().some(entity => /x3/.test(entity.text)), 'Insight unexpectedly removed unrelated nearby overlays')
        evidence.insight = textAt(0, 5)
      })
      await context.step('The Insight block can move before health and accept authored decoration', async () => {
        await overlaySettings({ lines: [
          { id: 'insight', type: 'insight', text: '<aqua>Detail:</aqua> {insight}', show: true },
          ...defaults.lines.filter(line => line.type !== 'insight'),
        ] })
        await waitFor(() => textAt(0, 5).startsWith('Detail:') && /Speed/.test(textAt(0, 5)),
          'The Insight expansion did not follow the authored row order', 15000)
        evidence.orderedInsight = textAt(0, 5)
        await overlaySettings({ lines: defaults.lines })
        await waitFor(() => textAt(0, 5).startsWith('Sentinel') && /Speed/.test(textAt(0, 5)),
          'The default Insight order did not restore', 15000)
      })
      if (context.report.bot.serverBrand !== 'Folia') {
        await context.step('Shared hologram switch stops and restores entity overlays', async () => {
          await enableHolograms(false)
          await waitFor(() => displays().length === 0, 'The disabled shared engine retained entity overlays', 15000)
          await enableHolograms(true)
          await waitFor(() => /Speed/.test(textAt(0, 5)), 'Insight did not resume after the shared engine returned', 15000)
          evidence.engineRestored = textAt(0, 5)
        })
      }
      await context.step('Adapt inspection range extends beyond the global 16-block radius', async () => {
        await command('/tp @s 3 191 0', /teleport/i)
        await waitFor(() => Math.abs(bot.entity.position.x - 3.5) < 0.1, 'The ranged inspection teleport did not finish')
        await context.sleep(250)
        await lookAt(0, 21)
        evidence.rangedPose = { position: bot.entity.position, yaw: bot.entity.yaw, pitch: bot.entity.pitch }
        if (context.report.bot.serverBrand !== 'Folia') {
          evidence.rangedRotation = await command('/data get entity @s Rotation', /entity data/i)
        }
        await waitFor(() => /Distant/.test(textAt(0, 21)) && /Speed/.test(textAt(0, 21)),
          'A target inside level-five Insight range but outside Gloss radius is missing')
        evidence.range = textAt(0, 21)
      })
      await context.step('Exclusive Insight policy hides global overlays and survives document hotload', async () => {
        await restriction(true)
        await waitFor(() => /Speed/.test(textAt(0, 21)) && near(0, 5).length === 0
          && !displays().some(entity => /x3/.test(entity.text)), 'Exclusive Insight policy did not suppress nearby overlays', 15000)
        await overlaySettings({ healthSegments: 7 })
        await waitFor(() => {
          const text = textAt(0, 21)
          const health = text.split('\n').find(line => line.includes('20/20')) ?? ''
          return (health.match(/\|/g) ?? []).length === 7 && /Speed/.test(text)
            && near(0, 5).length === 0 && !displays().some(entity => /x3/.test(entity.text))
        }, 'Hotloading overlay settings lost the exclusive Insight policy or did not update the health bar', 15000)
        evidence.exclusive = displays()
        await overlaySettings({ healthSegments: defaults.healthSegments })
        await waitFor(() => {
          const health = textAt(0, 21).split('\n').find(line => line.includes('20/20')) ?? ''
          return (health.match(/\|/g) ?? []).length === defaults.healthSegments
            && /Speed/.test(textAt(0, 21)) && near(0, 5).length === 0
            && !displays().some(entity => /x3/.test(entity.text))
        }, 'Restoring health segments lost the exclusive Insight policy', 15000)
        await inspect(false)
        await waitFor(() => !displays().some(entity => /ATK.*ARM/.test(entity.text)), 'Unlearned viewer retained an exclusive overlay', 15000)
      })
      await context.step('Disabling Insight releases its exclusive policy', async () => {
        await enableInsight(false)
        await waitFor(() => /Sentinel/.test(textAt(0, 5)), 'Disabling Insight retained its global restriction', 15000)
        context.expect(!displays().some(entity => /Speed/.test(entity.text)), 'Disabled Insight still supplied detail lines')
        evidence.disabledInsight = textAt(0, 5)
        await enableInsight(true)
        await waitFor(() => displays().length === 0, 'Reenabled Insight did not restore its configured restriction', 15000)
      })
      await context.step('Disabling the restriction restores defaults and death cleans displays', async () => {
        await restriction(false)
        await waitFor(() => /Sentinel/.test(textAt(0, 5)), 'Global overlays did not return after policy release', 15000)
        await command('/kill @e[type=minecraft:silverfish,distance=..40]', /killed/i)
        await waitFor(() => displays().some(entity => /x2/.test(entity.text))
          && !displays().some(entity => /x3/.test(entity.text)), 'A stack death did not update Gloss from x3 to x2')
        evidence.stackAfterDeath = displays().filter(entity => /x2/.test(entity.text))
        await clearStacks()
        await command('/kill @e[tag=overlayqa]', /killed/i)
        await waitFor(() => near(0, 5).length === 0 && near(0, 21).length === 0
          && !displays().some(entity => /x3/.test(entity.text)), 'Dead mobs left stale Gloss displays', 10000)
        evidence.cleaned = displays()
      })
      await context.step('Maintain the connection after cleanup', async () => {
        await context.sleep(3000)
        context.expect(bot.entity != null, 'Bot entity was lost after cleanup')
      })
    } finally {
      await writeFile(adaptationFile, originalAdaptation)
      await writeFile(overlayFile, originalOverlay)
      await writeFile(glossFile, originalGloss)
      await rm(animationFile, { force: true })
      bot._client.removeListener('world_particles', onParticles)
      bot.chat('/kill @e[tag=overlayqa]')
    }
  }
}
