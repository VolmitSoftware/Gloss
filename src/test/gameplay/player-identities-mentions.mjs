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
  if (value.type && 'value' in value) return plain(value.value)
  if ('text' in value || 'extra' in value) return plain(value.text) + plain(value.extra)
  return Object.values(value).map(plain).join('')
}

export default {
  name: 'player-identities-mentions',
  description: 'Verify permission-selected player identities and recipient-specific mention messages and sound packets.',
  async run(context) {
    const instance = process.env.GLOSS_QA_INSTANCE_PATH
    context.expect(instance && path.basename(instance) === context.server.instance,
      'GLOSS_QA_INSTANCE_PATH must name this isolated instance')
    const root = path.join(instance, 'plugins/Gloss')
    const originals = new Map()
    const listeners = []
    const evidence = context.report.identityMentionEvidence = {}
    const save = async (relative, value) => {
      const file = path.join(root, relative)
      if (!originals.has(file)) {
        try { originals.set(file, await readFile(file, 'utf8')) }
        catch (error) { if (error.code !== 'ENOENT') throw error; originals.set(file, null) }
      }
      await mkdir(path.dirname(file), { recursive: true })
      await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2))
    }
    const observe = actor => {
      const state = { messages: [], sounds: [] }
      const message = value => state.messages.push({ text: value.toString(), json: value.json })
      const sound = packet => {
        const name = packet.sound?.data?.soundName ?? actor.bot.registry.sounds[packet.sound?.soundId]?.name
        if (name === 'minecraft:block.note_block.bell' || name === 'block.note_block.bell') state.sounds.push(packet)
      }
      actor.bot.on('message', message)
      actor.bot._client.on('sound_effect', sound)
      listeners.push(() => actor.bot.removeListener('message', message),
        () => actor.bot._client.removeListener('sound_effect', sound))
      return state
    }
    const until = (predicate, label, timeoutMs = 20000) => context.waitUntil(predicate, { timeoutMs, label })
    const command = (value, pattern = /./) => context.command(value, pattern, 10000)
    let target
    let reader
    try {
      await context.step('Enable channel and permission-selected identity documents', async () => {
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/^(nametags|nameplates|channels)\s*=\s*false/gm, '$1 = true'))
        await save('nametags/identity-qa.json', {
          schemaVersion: 1, revision: 1, select: { priority: 1000, permission: 'gloss.qa.identity' },
          presentation: { prefix: '<green>[Member] </green>', color: 'green' },
          variants: [{ id: 'staff', priority: 100, permission: 'gloss.qa.staff',
            presentation: { prefix: '<gold>[Staff] </gold>', suffix: ' <gold>*</gold>', color: 'gold' } }]
        })
        await save('nameplates/identity-qa.json', {
          schemaVersion: 1, revision: 1, select: { priority: 1000, permission: 'gloss.qa.identity' },
          presentation: { lines: [{ text: 'PLAYER_PLATE {{ subject.name }}', show: true }],
            offset: 0.3, hideSneaking: true, relations: [] }, variants: []
        })
        const channel = JSON.parse(await readFile(new URL('../../main/resources/defaults/channels/global.json', import.meta.url), 'utf8'))
        channel.revision++
        channel.format = '<white>NORMAL {{ sender.name }}: {{ message }}</white>'
        channel.mentions = { enabled: true, pattern: '@{name}',
          render: '<gold><bold>@{{ mention.name }}</bold></gold>',
          messageFormat: '<yellow>MENTION {{ sender.name }}: {{ message }}</yellow>',
          sound: 'minecraft:block.note_block.bell', permission: 'gloss.chat.mention' }
        await save('channels/global.json', channel)
        await context.sleep(5000)
        target = await context.connectActor('GlossTarget')
        reader = await context.connectActor('GlossReader')
        context.bot.chat('/gamemode creative @a')
        await until(() => [context, target, reader].every(actor => actor.bot.game.gameMode === 'creative'), 'all actors in creative mode')
        const position = context.bot.entity.position
        await command(`/tp GlossTarget ${position.x + 3} ${position.y} ${position.z + 3}`, /teleport/i)
        await command(`/tp GlossReader ${position.x + 5} ${position.y} ${position.z}`, /teleport/i)
      })
      const senderState = observe(context)
      const targetState = observe(target)
      const readerState = observe(reader)
      const states = [senderState, targetState, readerState]
      const reset = () => { for (const state of states) { state.messages.length = 0; state.sounds.length = 0 } }
      const send = async (actor, text) => {
        reset()
        actor.bot.chat(text)
        await until(() => states.every(state => state.messages.some(message => message.text.includes(text.split(' ')[0]))),
          `all recipients receive ${text}`)
        await context.sleep(750)
      }
      await context.step('Mention target receives highlighted format and one sound, other viewers remain ordinary', async () => {
        await send(context, 'MENTION_QA @glosstarget @GlossTarget')
        const targetMessage = targetState.messages.find(message => message.text.includes('MENTION_QA'))
        context.expect(targetMessage.text.startsWith('MENTION '), 'The target did not receive its message format', targetMessage)
        context.expect(JSON.stringify(targetMessage.json).includes('yellow'), 'Target message lacks configured highlight color', targetMessage)
        context.expect(targetState.sounds.length === 1, 'Repeated mentions must send one ding packet', targetState.sounds)
        for (const state of [senderState, readerState]) {
          context.expect(state.messages.some(message => message.text.startsWith('NORMAL ')), 'A non-target received the highlighted format', state)
          context.expect(state.sounds.length === 0, 'A non-target received the ding packet', state.sounds)
        }
        evidence.mention = { target: targetMessage, sound: targetState.sounds[0], reader: [...readerState.messages] }
      })
      await context.step('Mention enable switch restores literal ordinary chat with no ding', async () => {
        const channel = JSON.parse(await readFile(path.join(root, 'channels/global.json'), 'utf8'))
        await save('channels/global.json', { ...channel, revision: channel.revision + 1,
          mentions: { ...channel.mentions, enabled: false } })
        await context.sleep(3500)
        await send(context, 'DISABLED_QA @GlossTarget')
        context.expect(targetState.messages.some(message => message.text.startsWith('NORMAL ')), 'Disabled mentions retained highlight')
        context.expect(states.every(state => state.sounds.length === 0), 'Disabled mentions still sent a sound')
        evidence.disabled = [...targetState.messages]
      })
      await context.step('Granting subject permissions selects the highest-priority nametag and player nameplate', async () => {
        await send(target, 'BEFORE_PERMISSION_QA')
        context.expect(readerState.messages.some(message => message.text.includes('NORMAL GlossTarget:')), 'Unpermitted subject received a styled identity', readerState.messages)
        await command('/op GlossTarget', /operator/i)
        await context.sleep(2500)
        await send(target, 'AFTER_PERMISSION_QA')
        context.expect(readerState.messages.some(message => message.text.includes('[Staff] GlossTarget *')), 'Subject permission variant did not reach sender.name', readerState.messages)
        const textIndex = context.bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text')
        let plate
        try {
          plate = await until(() => Object.values(context.bot.entities).find(entity => entity.name === 'text_display'
            && plain(entity.metadata[textIndex]).includes('PLAYER_PLATE [Staff] GlossTarget *')), 'styled nameplate for permitted subject')
        } catch (error) {
          context.expect(false, error.message, { textIndex,
            displays: Object.values(context.bot.entities).filter(entity => entity.name === 'text_display')
              .map(entity => ({ text: plain(entity.metadata[textIndex]), position: entity.position, metadata: entity.metadata })) })
        }
        const tab = await until(() => Object.values(reader.bot.players).find(player => player.username === 'GlossTarget'
          && plain(player.displayName?.json ?? player.displayName).includes('[Staff] GlossTarget *')), 'styled tab list name')
        evidence.identity = { messages: [...readerState.messages], plate: plain(plate.metadata[textIndex]),
          tab: plain(tab.displayName?.json ?? tab.displayName) }
        await command('/deop GlossTarget', /operator/i)
        await context.sleep(2500)
        await send(target, 'REMOVED_PERMISSION_QA')
        context.expect(readerState.messages.some(message => message.text.includes('NORMAL GlossTarget:')), 'Removing permission retained the styled identity', readerState.messages)
        await until(() => !Object.values(context.bot.entities).some(entity => entity.name === 'text_display'
          && plain(entity.metadata[textIndex]).includes('PLAYER_PLATE')
          && plain(entity.metadata[textIndex]).includes('GlossTarget')), 'permission-selected plate removed')
      })
      await context.step('Config hot reload restarts channels and all actors stay connected', async () => {
        const config = await readFile(path.join(root, 'gloss.toml'), 'utf8')
        await save('gloss.toml', config.replace(/^channels\s*=\s*true/gm, 'channels = false'))
        await context.sleep(3500)
        await save('gloss.toml', config)
        await context.sleep(3500)
        await send(context, 'RELOAD_QA')
        context.expect([context, target, reader].every(actor => actor.bot._client.state === 'play'), 'An actor disconnected during reload')
      })
    } finally {
      for (const release of listeners) release()
      for (const [file, content] of originals) {
        if (content === null) await rm(file, { force: true })
        else await writeFile(file, content)
      }
    }
  }
}
