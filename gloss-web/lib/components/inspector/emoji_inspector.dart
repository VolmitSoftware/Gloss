/// Inspector body for a Gloss emoji document: the token identity, the emoji
/// value with its resolved glyph, the optional trigger, and the enabled
/// switch.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_show.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import 'gloss_visibility_editor.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class EmojiInspector extends StatelessWidget {
  const EmojiInspector({required this.store, super.key});

  final EditorStore store;

  List<HuiIssue> _issuesFor(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) {
    final GlossEmojiDoc? doc = store.emojiDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    // The inspector pane is dense; its text inputs match the kit fields.
    final ComponentSize size = controlSize(Density.dense);
    return DenseBox(
      classes: 'hui-inspector-body is-emoji',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Emoji'),
          title: ':${store.menuId}:',
          docKey: 'emoji.id',
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        AppSection(
          title: huiText('Glyph'),
          memoryKey: 'emoji.glyph',
          children: <Widget>[
            Field(
              label: huiText('Emoji'),
              forId: 'emoji-value',
              docKey: 'emoji.emoji',
              required: true,
              problems: _issuesFor(r'$.emoji'),
              trailing: dom.span(classes: 'hui-emoji-resolved-glyph', <Widget>[
                Text(doc.resolvedGlyph.isEmpty ? '·' : doc.resolvedGlyph),
              ]),
              child: TextInput(
                id: 'emoji-value',
                value: doc.emoji,
                size: size,
                fullWidth: true,
                placeholder: huiText('U+2764;'),
                attributes: technicalInputAttributes,
                onChanged: (String value) => store.mutateEmoji(
                  'emoji value',
                  (GlossEmojiDoc edited) => edited.emoji = value,
                ),
              ),
            ),
            Field(
              label: huiText('Trigger'),
              forId: 'emoji-trigger',
              docKey: 'emoji.trigger',
              problems: _issuesFor(r'$.trigger'),
              child: TextInput(
                id: 'emoji-trigger',
                value: doc.trigger,
                size: size,
                fullWidth: true,
                placeholder: '<3',
                attributes: technicalInputAttributes,
                onChanged: (String value) => store.mutateEmoji(
                  'emoji trigger',
                  (GlossEmojiDoc edited) => edited.trigger = value,
                ),
              ),
            ),
            SwitchField(
              label: huiText('Enabled'),
              docKey: 'emoji.enabled',
              value: doc.enabled,
              problems: _issuesFor(r'$.enabled'),
              onChanged: (bool value) => store.mutateEmoji(
                'emoji enabled',
                (GlossEmojiDoc edited) => edited.enabled = value,
              ),
            ),
          ],
        ),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: doc.extras['show'],
            sectionKey: 'emoji.visibility',
            issues: store.issues
                .where((HuiIssue issue) => issue.path == r'$.show')
                .toList(),
            onChanged: (Object? value) => store.mutateEmoji(
              'visibility',
              (GlossEmojiDoc edited) => setGlossShow(edited.extras, value),
            ),
          ),
        ),
      ],
    );
  }
}
