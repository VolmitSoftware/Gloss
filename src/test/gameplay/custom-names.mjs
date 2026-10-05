import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'gloss-custom-names',
  description: 'Verify EcoMobs and CraftEngine protocol-visible display names.',
  async run(context) {
    const { bot } = context
    context.expect(context.server.instance === 'gloss-custom-names-qa', 'This scenario requires its isolated fixture instance')
    const originals = new Map()
    const editDocument = async (file, transform) => {
      const current = JSON.parse(await readFile(file, 'utf8'))
      if (!originals.has(file)) originals.set(file, structuredClone(current))
      current.revision++
      transform(current)
      await writeFile(file, JSON.stringify(current, null, 2))
    }
    const names = new Map()
    const received = []
    const metadata = packet => {
      const text = JSON.stringify(packet.metadata)
      if (packet.metadata.some(entry => entry.key === 23)) names.set(packet.entityId, {text, type: bot.entities[packet.entityId]?.name})
      if (packet.metadata.some(entry => entry.key === 23)) received.push({ id: packet.entityId, type: bot.entities[packet.entityId]?.name, metadata: packet.metadata })
    }
    bot._client.on('entity_metadata', metadata)
    const visible = text => [...names].filter(([id, value]) => (value.type === 'text_display' || bot.entities[id]?.name === 'text_display') && value.text.includes(text))
    const check = async text => {
      await context.waitUntil(() => visible(text).length > 0, { timeoutMs: 15000, label: `text display ${text}` })
      context.report.customNames ??= []
      context.report.customNames.push({ name: text, entities: visible(text).map(([id]) => id) })
    }
    const send = async text => { await context.command(text); await context.sleep(600) }
    try {
      await context.step('prepare observation area', async () => {
        await send('/gamemode creative')
        await context.waitUntil(() => bot.game.gameMode === 'creative', {timeoutMs:5000})
        await send('/fill -8 199 -8 8 199 8 minecraft:stone')
        await send('/tp @s 0.5 200 0.5')
        await context.waitUntil(() => Math.abs(bot.entity.position.y-200)<2, {timeoutMs:5000})
        await send('/kill @e[type=minecraft:item]')
        await send('/ecomobs killall gloss_names')
        names.clear()
        await send('/weather clear')
        await send('/time set noon')
      })
      await context.step('EcoMobs named condition and health substitutions', async () => {
        await bot.lookAt(bot.entity.position.offset(1.5, 1, 1.5), true)
        await context.command('/ecomobs spawn gloss_names 2 200 2', /Spawned/i, 8000)
        await context.waitUntil(() => Object.values(bot.entities).some(entity => entity.name === 'cow'), {timeoutMs:8000,label:'new EcoMobs cow spawn'})
        context.report.cows = Object.values(bot.entities).filter(entity => entity.name === 'cow').map(entity => ({id:entity.id,position:entity.position}))
        await check('OVERLAY ')
        await check('Meadow Keeper')
        context.expect(visible('Meadow Keeper').some(([,value])=>value.text.includes('40/40')), 'EcoMobs health substitution missing')
        await send('/damage @e[type=minecraft:cow,limit=1,sort=nearest] 7 minecraft:generic')
        await context.waitUntil(() => visible('Meadow Keeper').some(([,value]) => value.text.includes('33/40')), {timeoutMs:10000,label:'EcoMobs live health name update'})
      })
      for (const [id, label] of [['server_name','Amber Ticket'],['server_custom','Emerald Pass']]) {
        await context.step(`CraftEngine ${id} dropped label`, async () => {
          await send('/clear @s')
          await send(`/ce item get gloss_names:${id} 1`)
          await context.waitUntil(() => bot.inventory.items().some(item => item.name==='paper'), {timeoutMs:5000,label:'CraftEngine item inventory'})
          const item = bot.inventory.items().find(item => item.name==='paper')
          await bot.tossStack(item)
          await check(label)
        })
      }
      await context.step('CraftEngine anvil rename takes precedence', async () => {
        await send('/clear @s')
        await send('/ce item get gloss_names:server_name 1')
        await send('/setblock 1 200 0 minecraft:anvil')
        const position=bot.entity.position.floored().offset(1,0,0)
        await context.waitUntil(()=>bot.blockAt(position)?.name==='anvil',{timeoutMs:5000})
        const anvil=await bot.openAnvil(bot.blockAt(position))
        try {
          const slot=anvil.slots.findIndex((item,index)=>index>=anvil.inventoryStart && item?.name==='paper')
          context.expect(slot>=0,'CraftEngine item absent from anvil inventory')
          await bot.clickWindow(slot,0,0)
          await bot.clickWindow(0,0,0)
          bot._client.write('name_item',{name:'Anvil Ticket'})
          await context.waitUntil(()=>anvil.slots[2]?.name==='paper',{timeoutMs:6000,label:'anvil renamed output'})
          await bot.putAway(2)
        } finally {anvil.close()}
        await context.waitUntil(()=>bot.inventory.items().some(item=>item.name==='paper'),{timeoutMs:5000})
        await bot.tossStack(bot.inventory.items().find(item=>item.name==='paper'))
        await check('Anvil Ticket')
      })
      await context.step('vanilla item-name component dropped label', async () => {
        await send('/clear @s')
        await send('/give @s minecraft:paper[minecraft:item_name={text:"Fixed Ticket"}] 1')
        await context.waitUntil(() => bot.inventory.items().some(item => item.name==='paper'), {timeoutMs:5000})
        await bot.tossStack(bot.inventory.items().find(item=>item.name==='paper'))
        await check('Fixed Ticket')
      })
      await context.step('reload retains compatibility', async () => {
        const file=path.join(context.server.directory,'plugins','Gloss','entity-overlays','default.json')
        names.clear()
        await editDocument(file, document => {document.lines[0].text='RELOADED {name}'})
        await check('RELOADED ')
        await check('Meadow Keeper')
      })
      await context.step('item display-name opt-out uses material label', async () => {
        const file=path.join(context.server.directory,'plugins','Gloss','real-drops','default.json')
        const original=await readFile(file,'utf8')
        const document=JSON.parse(original)
        document.revision++
        document.presentation.labels.useItemDisplayNames=false
        try {
          await writeFile(file,JSON.stringify(document,null,2))
          await context.sleep(5000)
          await send('/kill @e[type=minecraft:item]')
          names.clear()
          await send('/clear @s')
          await send('/ce item get gloss_names:server_name 1')
          await context.waitUntil(()=>bot.inventory.items().some(item=>item.name==='paper'),{timeoutMs:5000})
          await bot.tossStack(bot.inventory.items().find(item=>item.name==='paper'))
          await check('Paper')
        } finally {
          document.revision++
          document.presentation.labels.useItemDisplayNames=true
          await writeFile(file,JSON.stringify(document,null,2))
          await context.sleep(5000)
        }
      })
      context.report.customNamePackets = received
      context.report.observedNames = [...names]
    } finally {
      for (const [file, original] of originals) {
        original.revision = JSON.parse(await readFile(file, 'utf8')).revision + 1
        await writeFile(file, JSON.stringify(original, null, 2))
      }
      bot._client.removeListener('entity_metadata', metadata)
      context.report.customNamePackets = received
      context.report.observedNames = [...names]
    }
  }
}
