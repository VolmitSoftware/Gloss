import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'audit-prompts',
  description: 'Verify authored prompt labels and initial values through chat, sign, and anvil packets.',
  async run(context) {
    const { bot } = context
    context.expect(/^isolated=true$/m.test(await readFile(path.join(context.server.directory, '.server-source'), 'utf8')),
      'Prompt acceptance requires an isolated instance')
    const file = path.join(context.server.directory, 'plugins/Gloss/menus/audit-prompts.json')
    let original
    try { original = await readFile(file, 'utf8') }
    catch (error) { if (error.code !== 'ENOENT') throw error }
    const messages = []
    const packets = []
    const messageListener = message => messages.push(message.toString())
    const packetListener = (data, metadata) => packets.push({ name: metadata.name, data })
    bot.on('message', messageListener)
    bot._client.on('packet', packetListener)
    const until = (predicate, label) => context.waitUntil(predicate, { timeoutMs: 20000, label })
    let revision = 1
    const open = async kind => {
      messages.length = 0
      packets.length = 0
      bot.chat('/gloss menu close')
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, JSON.stringify({ revision: revision++, offset: [0, 1.5, 3],
        lockPosition: false, followPlayer: false, closeOnDeath: false, closeOnTeleport: false,
        components: [{ id: 'prompt', offset: [0, 0, 0], data: { type: 'button',
          icon: { type: 'text', text: `AUDIT_PROMPT_${kind}` },
          hitbox: { width: 4, height: 4 },
          actions: [{ type: 'message', message: 'AUDIT_CLICK' }, { type: 'prompt', kind,
            label: `AUDIT_LABEL_${kind}`, initial: `AUDIT_INITIAL_${kind}`,
            then: [{ type: 'message', message: 'AUDIT_ANSWER {{ input.value }}' }] }] } }] }))
      const keys = bot.registry.entitiesByName.text_display.metadataKeys
      let display
      await context.waitUntil(() => {
        display = Object.values(bot.entities).find(entity => entity.name === 'text_display'
          && JSON.stringify(entity.metadata[keys.indexOf('text')]).includes(`AUDIT_PROMPT_${kind}`))
        if (display) return true
        bot.chat('/gloss menu open menu=audit-prompts args=qa=audit')
        return false
      }, { timeoutMs: 20000, intervalMs: 1000, label: kind + ' menu hotload' })
      await bot.lookAt(display.position, true)
      await bot.waitForTicks(5)
      await context.waitUntil(async () => {
        if (messages.some(value => value.includes('AUDIT_CLICK'))) return true
        await bot.lookAt(display.position, true)
        bot.activateItem()
        return false
      }, { timeoutMs: 12000, intervalMs: 1000, label: kind + ' click dispatch' })
    }
    try {
      bot.chat(`/give ${bot.username} minecraft:stick`)
      await until(() => bot.inventory.items().some(item => item.name === 'stick'), 'interaction item')
      await bot.equip(bot.inventory.items().find(item => item.name === 'stick'), 'hand')
      await context.step('Chat prompt sends its label and initial suggestion', async () => {
        await open('chat')
        await until(() => messages.some(value => value.includes('AUDIT_LABEL_chat'))
          && messages.some(value => value.includes('AUDIT_INITIAL_chat')), 'chat prompt authored text')
        context.expect(packets.some(packet => packet.name === 'system_chat'
          && JSON.stringify(packet.data).includes('suggest_command')
          && JSON.stringify(packet.data).includes('AUDIT_INITIAL_chat')), 'Initial chat value has no suggestion click event')
        bot.chat('AUDIT_SUBMITTED')
        await until(() => messages.some(value => value.includes('AUDIT_ANSWER AUDIT_SUBMITTED')), 'chat prompt completion')
      })
      await context.step('Sign prompt sends its label and initial sign text', async () => {
        await open('sign')
        await until(() => packets.some(packet => packet.name === 'open_sign_entity'), 'sign editor open')
        await until(() => messages.some(value => value.includes('AUDIT_LABEL_sign')), 'sign prompt label')
        context.expect(packets.some(packet => packet.name === 'tile_entity_data'
          && JSON.stringify(packet.data).includes('AUDIT_INITIAL_sign')), 'Initial sign text was not sent')
        const sign = packets.find(packet => packet.name === 'open_sign_entity').data
        bot._client.write('update_sign', { location: sign.location, isFrontText: true,
          text1: 'AUDIT_SIGN_SUBMITTED', text2: '', text3: '', text4: '' })
        await until(() => messages.some(value => value.includes('AUDIT_ANSWER AUDIT_SIGN_SUBMITTED')), 'sign prompt completion')
      })
      await context.step('Anvil prompt sends its label and initial item name', async () => {
        await open('anvil')
        await until(() => Boolean(bot.currentWindow), 'anvil window open')
        context.expect(JSON.stringify(bot.currentWindow.title).includes('AUDIT_LABEL_anvil'), 'Anvil title omitted the label')
        await until(() => packets.some(packet => ['window_items', 'set_slot'].includes(packet.name)
          && JSON.stringify(packet.data).includes('AUDIT_INITIAL_anvil')), 'anvil initial item name')
        context.report.anvilTitle = bot.currentWindow.title
        bot.closeWindow(bot.currentWindow)
        await until(() => !bot.currentWindow, 'anvil close')
      })
    } finally {
      if (!context.signal.aborted) bot.chat('/gloss menu close')
      bot.removeListener('message', messageListener)
      bot._client.removeListener('packet', packetListener)
      if (original == null) await rm(file, { force: true })
      else await writeFile(file, original)
    }
  }
}
