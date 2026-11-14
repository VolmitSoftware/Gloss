/// The chat channel surface: one sample message as a chosen viewer reads it
/// in game chat, with the channel's format, mentions and filters applied.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:gloss_editor/l10n/hui_localizations.dart';

import '../../logic/channel_preview.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/toggle_choice.dart';
import '../gloss/gloss_game_screen.dart';
import '../gloss/gloss_text_line.dart';

class ChannelView extends StatefulWidget {
  const ChannelView({required this.store, super.key});
  final EditorStore store;
  @override
  State<ChannelView> createState() => _ChannelViewState();
}

class _ChannelViewState extends State<ChannelView> {
  String _message = 'Hello @Steve, come see the new build!';
  bool _allowed = true;
  String _viewer = 'Steve';
  @override
  void initState() {
    super.initState();
    component.store.addListener(_changed);
  }

  @override
  void dispose() {
    component.store.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final GlossDoc? active = component.store.glossDoc;
    if (active is! GlossChannelDoc) {
      return glossGameEmpty(
        anchor: GlossGameAnchor.chat,
        label: huiText('Chat and mention preview'),
      );
    }
    final GlossChannelDoc doc = active;
    final ChannelPreview preview = channelPreview(
      doc,
      message: _message,
      viewer: _viewer,
      allowed: _allowed,
      names: component.store.workspaceNames,
      animations: component.store.workspaceAnimations,
      emoji: component.store.workspaceEmoji,
    );
    return GlossGameScreen(
      anchor: GlossGameAnchor.chat,
      label: huiText('Chat and mention preview'),
      title: huiText('Chat'),
      toggles: <Widget>[
        ToggleChoice<String>(
          label: huiText('Viewer'),
          value: _viewer,
          options: const <ChoiceOption<String>>[
            ChoiceOption<String>('Steve', 'Steve'),
            ChoiceOption<String>('Morgan', 'Morgan'),
          ],
          onChanged: (String value) => setState(() => _viewer = value),
        ),
      ],
      controls: <Widget>[
        // A whole chat line: the field takes the strip's full width.
        dom.div(classes: 'is-wide', <Widget>[
          Field(
            label: huiText('Sample message'),
            forId: 'hui-channel-sample-message',
            child: TextInput(
              id: 'hui-channel-sample-message',
              value: _message,
              placeholder: huiText('Type a message with @Steve'),
              size: controlSize(Density.dense),
              fullWidth: true,
              onChanged: (String value) => setState(() => _message = value),
            ),
          ),
        ]),
      ],
      actions: <RowAction>[
        RowAction(
          huiText('Sender can mention players'),
          () => setState(() => _allowed = !_allowed),
          icon: ArcaneIcon.atSign(),
          iconOnly: true,
          pressed: _allowed,
        ),
      ],
      status:
          preview.filterNotice ??
          (preview.mentioned && doc.mentions.sound.isNotEmpty
              ? huiText('Tagged player sound: {sound}', <String, Object?>{
                  'sound': doc.mentions.sound,
                })
              : huiText('No mention sound for this viewer')),
      child: dom.div(classes: 'hui-connections-chat', <Widget>[
        dom.div(
          classes: 'hui-connections-line',
          attributes: <String, String>{'title': preview.hoverText},
          <Widget>[GlossTextLine(render: preview.render)],
        ),
      ]),
    );
  }
}
