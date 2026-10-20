import { readFile, rename, writeFile } from 'node:fs/promises'
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
  name: 'gloss-drop-item-names',
  description: 'Verify item-name precedence, literal text, material fallback, and label-setting reloads through client metadata.',
  async run(context) {
    const { bot } = context
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Drop-name acceptance requires an isolated instance')
    const file = path.join(context.server.directory, 'plugins', 'Gloss', 'real-drops', 'default.json')
    const original = JSON.parse(await readFile(file, 'utf8'))
    context.expect(original.presentation.labels.useItemDisplayNames === true,
      'The shipped document must enable item display names')
    const textKey = bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text')
    context.expect(textKey >= 0, 'The client protocol does not expose TextDisplay text')
    const packets = []
    const metadata = packet => {
      for (const entry of packet.metadata) {
        if (entry.key === textKey) packets.push({ entityId: packet.entityId, text: plain(entry.value) })
      }
    }
    bot._client.on('entity_metadata', metadata)
    const displays = () => Object.values(bot.entities).filter(entity => entity.name === 'text_display'
      && Math.abs(entity.position.x - 4.5) < 4 && Math.abs(entity.position.y - 201) < 4
      && Math.abs(entity.position.z - 0.5) < 4)
    const visible = expected => displays().find(entity => plain(entity.metadata[textKey]) === expected)
    const check = async expected => {
      const entity = await context.waitUntil(() => visible(expected),
        { timeoutMs: 20000, label: `drop label ${expected}` })
      context.expect(packets.some(packet => packet.entityId === entity.id && packet.text === expected),
        'The expected name was not delivered in client metadata')
      context.report.dropItemNames ??= []
      context.report.dropItemNames.push({ expected, entityId: entity.id, text: plain(entity.metadata[textKey]) })
    }
    const clear = async () => {
      await context.command('/kill @e[type=minecraft:item,tag=gloss_drop_names_qa]', /Killed|No entity/i, 5000)
      await context.waitUntil(() => displays().length === 0,
        { timeoutMs: 10000, label: 'previous drop label removed' })
    }
    const spawn = async components => {
      await clear()
      const componentData = Object.entries(components).map(([key, value]) =>
        `${JSON.stringify(`minecraft:${key}`)}:{text:${JSON.stringify(value)}}`).join(',')
      const item = `{id:"minecraft:firework_star",count:1,components:{${componentData}}}`
      await context.command(`/summon minecraft:item 4.5 201 0.5 {Tags:["gloss_drop_names_qa"],NoGravity:1b,PickupDelay:32767,Item:${item}}`,
        /Summoned/i, 10000)
    }
    const configure = async enabled => {
      const document = JSON.parse(await readFile(file, 'utf8'))
      document.revision++
      if (enabled === undefined) delete document.presentation.labels.useItemDisplayNames
      else document.presentation.labels.useItemDisplayNames = enabled
      const reloaded = context.waitForMessage('Hotloaded real-drops', 15000)
      await writeFile(`${file}.tmp`, `${JSON.stringify(document, null, 2)}\n`)
      await rename(`${file}.tmp`, file)
      await reloaded
    }
    try {
      await context.step('prepare isolated drop observation area', async () => {
        await context.command('/gamemode spectator', /game mode|spectator/i, 10000)
        await context.command('/tp @s 0.5 200 0.5', /Teleported/i, 10000)
        await context.waitUntil(() => Math.abs(bot.entity.position.y - 200) < 1,
          { timeoutMs: 10000, label: 'player reaches observation area' })
        await context.waitUntil(() => bot.blockAt(bot.entity.position.floored().offset(-8, -1, -8)) != null
          && bot.blockAt(bot.entity.position.floored().offset(8, -1, 8)) != null,
        { timeoutMs: 15000, label: 'observation chunks reach the client' })
        await context.command('/fill -8 199 -8 8 199 8 minecraft:stone', /filled|changed|No blocks/i, 10000)
        await context.command('/gamemode creative', /game mode|creative/i, 10000)
      })
      await context.step('named core replaces Firework Star by default', async () => {
        await spawn({ custom_name: 'Angler Core' })
        await check('1x Angler Core')
      })
      await context.step('custom name takes precedence over item-name component', async () => {
        await spawn({ custom_name: 'Renamed Angler Core', item_name: 'Base Core' })
        await check('1x Renamed Angler Core')
      })
      await context.step('item-name component supplies the label without a custom name', async () => {
        await spawn({ item_name: 'Lumber Core' })
        await check('1x Lumber Core')
      })
      await context.step('unnamed item uses its readable material name', async () => {
        await spawn({})
        await check('1x Firework Star')
      })
      await context.step('custom item text remains literal', async () => {
        await spawn({ custom_name: '<red>{{ player.name }} %player_name% |animation.fast|</red>' })
        await check('1x <red>{{ player.name }} %player_name% |animation.fast|</red>')
      })
      await context.step('explicit false reload uses material names for named drops', async () => {
        await configure(false)
        await spawn({ custom_name: 'Angler Core' })
        await check('1x Firework Star')
      })
      await context.step('omitted setting reload uses item names for named drops', async () => {
        await configure(undefined)
        await spawn({ custom_name: 'Angler Core' })
        await check('1x Angler Core')
      })
    } finally {
      try {
        await clear()
      } finally {
        bot._client.removeListener('entity_metadata', metadata)
        context.report.dropNameMetadata = packets
        original.revision = JSON.parse(await readFile(file, 'utf8')).revision + 1
        await writeFile(`${file}.tmp`, `${JSON.stringify(original, null, 2)}\n`)
        await rename(`${file}.tmp`, file)
      }
    }
  }
}
