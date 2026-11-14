/// Inspector body for a Gloss connection-message document: the document
/// visibility, then the join and leave blocks with their own enable switch,
/// visibility, audience, message and conditional variants.
///
/// No `$player` in any placeholder on purpose. That token belongs to the tab
/// list; a connection message is rendered per recipient
/// (`ConnectionsService.render`), so the scoped names are the ones that work:
/// `{{ subject.name }}` for whoever connected, `{{ viewer.name }}` for
/// whoever is reading.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/connections_validation.dart';
import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/technical_input.dart';
import '../gloss/gloss_text_line.dart';
import 'gloss_visibility_editor.dart';
import 'line_list_section.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ConnectionsInspector extends StatelessWidget {
  const ConnectionsInspector({required this.store, super.key});

  final EditorStore store;

  static final ComponentSize _size = controlSize(Density.dense);

  List<HuiIssue> _issuesFor(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) {
    final GlossConnectionsDoc? doc = store.connectionsDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-connections',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Connection messages'),
          title: store.menuId,
          tip: huiText(
            'The join and leave lines. Each one is rendered separately for '
            'every player who reads it.',
          ),
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        for (final String key in glossConnectionsSectionKeys)
          _section(doc, key),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: doc.extras['show'],
            sectionKey: 'connections.visibility',
            issues: store.issues
                .where((HuiIssue issue) => issue.path == r'$.show')
                .toList(),
            onChanged: (Object? value) => store.mutateConnections(
              'visibility',
              (GlossConnectionsDoc edited) =>
                  setGlossShow(edited.extras, value),
            ),
          ),
        ),
      ],
    );
  }

  String _sectionTitle(String key) => switch (key) {
    'leave' => huiText('Leave message'),
    'firstJoin' => huiText('First join message'),
    _ => huiText('Join message'),
  };

  void _addBlock(String key) => store.mutateConnections(
    'add connection message',
    (GlossConnectionsDoc edited) {
      final GlossConnectionsSection added = edited.section(key)
        ..present = true
        ..enabled = true;
      if (added.presentation.text.isEmpty) {
        added.presentation.text = key == 'leave'
            ? r'&c- &f{{ subject.name }} &7left'
            : r'&a+ &f{{ subject.name }} &7joined';
      }
    },
  );

  void _addVariant(String key) => store.mutateConnections(
    'add connection message variant',
    (GlossConnectionsDoc edited) {
      final GlossConnectionsSection target = edited.section(key);
      target.variants.add(
        GlossConnectionsVariant(
          priority: 10,
          when: "inGroup('subject', 'staff')",
          presentation: target.presentation.copy(),
        ),
      );
    },
  );

  Widget _section(GlossConnectionsDoc doc, String key) {
    final GlossConnectionsSection section = doc.section(key);
    final String path = r'$.' + key;
    if (!section.present) {
      return AppSection(
        title: _sectionTitle(key),
        memoryKey: 'connections.$key',
        description: huiText('No block in the file, so nothing is sent.'),
        children: <Widget>[
          ActionRow(
            dense: true,
            secondary: <RowAction>[
              RowAction(
                huiText('Add the block'),
                () => _addBlock(key),
                icon: ArcaneIcon.plus(),
              ),
            ],
          ),
        ],
      );
    }
    return AppSection(
      title: _sectionTitle(key),
      memoryKey: 'connections.$key',
      children: <Widget>[
        SwitchField(
          label: huiText('Enabled'),
          docKey: 'connections.enabled',
          value: section.enabled,
          onChanged: (bool value) => store.mutateConnections(
            'connection message enabled',
            (GlossConnectionsDoc edited) => edited.section(key).enabled = value,
          ),
        ),
        Field(
          label: huiText('Audience'),
          docKey: 'connections.audience',
          problems: _issuesFor('$path.audience'),
          child: ToggleChoice<String>(
            label: huiText('Audience'),
            value: section.audience,
            options: <ChoiceOption<String>>[
              ChoiceOption<String>(
                glossConnectionsAudienceNetwork,
                huiText('Whole network'),
              ),
              ChoiceOption<String>(
                glossConnectionsAudienceServer,
                huiText('This server only'),
              ),
            ],
            onChanged: (String value) => store.mutateConnections(
              'connection message audience',
              (GlossConnectionsDoc edited) =>
                  edited.section(key).audience = value,
            ),
          ),
        ),
        _messageField(
          id: 'connections-$key-text',
          value: section.presentation.text,
          path: '$path.presentation.text',
          onChanged: (String value) => store.mutateConnections(
            'connection message text',
            (GlossConnectionsDoc edited) =>
                edited.section(key).presentation.text = value,
          ),
        ),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: section.extras['show'],
            sectionKey: 'connections.$key.visibility',
            issues: _issuesFor('$path.show'),
            onChanged: (Object? value) => store.mutateConnections(
              'visibility',
              (GlossConnectionsDoc edited) =>
                  setGlossShow(edited.section(key).extras, value),
            ),
          ),
        ),
        for (int index = 0; index < section.variants.length; index++)
          _variant(section, key, path, index),
        ActionRow(
          dense: true,
          secondary: <RowAction>[
            RowAction(
              huiText('Add a conditional message'),
              () => _addVariant(key),
              icon: ArcaneIcon.plus(),
            ),
          ],
        ),
      ],
    );
  }

  Widget _variant(
    GlossConnectionsSection section,
    String key,
    String path,
    int index,
  ) {
    final GlossConnectionsVariant variant = section.variants[index];
    final String variantPath = '$path.variants[$index]';
    final String priorityId = 'connections-$key-variant-$index-priority';
    final String whenId = 'connections-$key-variant-$index-when';
    return AppSection(
      title: huiText('Conditional message {index}', <String, Object?>{
        'index': index + 1,
      }),
      memoryKey: 'connections.$key.variant.$index',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete conditional message'),
          onPressed: () => store.mutateConnections(
            'delete connection message variant',
            (GlossConnectionsDoc edited) {
              final GlossConnectionsSection target = edited.section(key);
              if (index < target.variants.length) {
                target.variants.removeAt(index);
              }
            },
          ),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('When'),
          forId: whenId,
          docKey: 'connections.variants.when',
          problems: _issuesFor('$variantPath.when'),
          child: TextInput(
            id: whenId,
            value: variant.when,
            size: _size,
            fullWidth: true,
            placeholder: huiText("inGroup('subject', 'staff')"),
            onChanged: (String value) => store.mutateConnections(
              'connection message variant condition',
              (GlossConnectionsDoc edited) =>
                  edited.section(key).variants[index].when = value,
            ),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ),
        AdvancedOnly(
          child: Field(
            label: huiText('Priority'),
            forId: priorityId,
            docKey: 'connections.variants.priority',
            problems: _issuesFor('$variantPath.priority'),
            child: NumberField(
              id: priorityId,
              value: variant.priority.toDouble(),
              integer: true,
              step: 1,
              onChanged: (double value) => store.mutateConnections(
                'connection message variant priority',
                (GlossConnectionsDoc edited) =>
                    edited.section(key).variants[index].priority = value
                        .round(),
              ),
            ),
          ),
        ),
        _messageField(
          id: 'connections-$key-variant-$index-text',
          value: variant.presentation.text,
          path: '$variantPath.presentation.text',
          onChanged: (String value) => store.mutateConnections(
            'connection message variant text',
            (GlossConnectionsDoc edited) =>
                edited.section(key).variants[index].presentation.text = value,
          ),
        ),
      ],
    );
  }

  Widget _messageField({
    required String id,
    required String value,
    required String path,
    required void Function(String) onChanged,
  }) => Field(
    label: huiText('Message'),
    forId: id,
    docKey: 'connections.text',
    problems: _issuesFor(path),
    child: Component.fragment(<Widget>[
      TextInput(
        id: id,
        value: value,
        size: _size,
        fullWidth: true,
        placeholder: huiText(r'&a+ &f{{ subject.name }} &7joined'),
        onChanged: onChanged,
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
      ),
      GlossTextLine(
        render: renderGlossLine(
          value,
          animations: store.workspaceAnimations,
          emoji: store.workspaceEmoji,
          expressionSamples: glossConnectionsSamples,
        ),
      ),
      ...huiMissingAnimationChips(
        value,
        store.workspaceAnimations,
        where: 'in chat',
      ),
    ]),
  );
}
