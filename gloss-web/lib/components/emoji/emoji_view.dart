/// The emoji surface: a sample chat line before and after
/// `EmojiReplacer.apply`, in the game screen's chat region, which is the one
/// place a player sees an emoji document's work. The replacement reads
/// [EditorStore.workspaceEmoji]: the shipped catalog with the workspace's
/// emoji documents layered over it by id.
///
/// Glyphs render with the browser's font fallback stack, not the Minecraft
/// client font, so metrics and coverage differ in game (the status tip says
/// so).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_text.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../gloss/gloss_game_screen.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class EmojiView extends StatefulWidget {
  const EmojiView({required this.store, super.key});

  final EditorStore store;

  @override
  State<EmojiView> createState() => _EmojiViewState();
}

class _EmojiViewState extends State<EmojiView> {
  EditorStore get _store => component.store;

  @override
  void initState() {
    super.initState();
    _store.addListener(_onStoreChanged);
  }

  @override
  void didUpdateWidget(covariant EmojiView oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) {
      oldComponent.store.removeListener(_onStoreChanged);
      component.store.addListener(_onStoreChanged);
    }
  }

  @override
  void dispose() {
    _store.removeListener(_onStoreChanged);
    super.dispose();
  }

  void _onStoreChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final GlossEmojiDoc? doc = _store.emojiDoc;
    if (doc == null) {
      return glossGameEmpty(
        anchor: GlossGameAnchor.chat,
        label: huiText('Emoji in chat'),
      );
    }
    final String token = ':${_store.menuId}:';
    return GlossGameScreen(
      anchor: GlossGameAnchor.chat,
      label: huiText('Emoji in chat'),
      status: _summary(doc, token),
      statusTip: huiText(
        'Glyphs render with the browser font here; coverage and metrics '
        'differ from the Minecraft client font in game.',
      ),
      child: _chatPreview(doc, token),
    );
  }

  String _summary(GlossEmojiDoc doc, String token) {
    if (!doc.enabled) {
      return huiText(
        'Disabled: neither {token} nor the trigger substitutes in game.',
        <String, Object?>{'token': token},
      );
    }
    return doc.trigger.isEmpty
        ? huiText('{token} substitutes; no trigger.', <String, Object?>{
            'token': token,
          })
        : huiText('{token} and "{trigger}" both substitute.', <String, Object?>{
            'token': token,
            'trigger': doc.trigger,
          });
  }

  /// The open document's substitution, replayed on a sample chat line the
  /// way `TextPipeline.chat` would after the emoji filter: what the player
  /// typed, then what chat shows.
  Widget _chatPreview(GlossEmojiDoc doc, String token) {
    final String sample = doc.trigger.isEmpty
        ? huiText('{player} smells >.< {token}', <String, Object?>{
            'player': 'SwiftSwamp',
            'token': token,
          })
        : huiText('{player} smells >.< {token} {trigger}', <String, Object?>{
            'player': 'SwiftSwamp',
            'token': token,
            'trigger': doc.trigger,
          });
    final String substituted = glossApplyEmoji(sample, _store.workspaceEmoji);
    return dom.div(classes: 'hui-emoji-chat', <Widget>[
      dom.div(classes: 'hui-emoji-chat-line is-raw', <Widget>[
        const dom.span(classes: 'hui-emoji-chat-speaker', <Widget>[
          Text('<Magic_Psycho>'),
        ]),
        Text(huiText(" {sample}", <String, Object?>{'sample': sample})),
      ]),
      dom.div(classes: 'hui-emoji-chat-line', <Widget>[
        const dom.span(classes: 'hui-emoji-chat-speaker', <Widget>[
          Text('<Magic_Psycho>'),
        ]),
        Text(
          huiText(" {substituted}", <String, Object?>{
            'substituted': substituted,
          }),
        ),
      ]),
    ]);
  }
}
