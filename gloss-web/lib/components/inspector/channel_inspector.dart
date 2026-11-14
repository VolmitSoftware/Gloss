/// Inspector body for a Gloss chat channel: the channel and its audience,
/// player mentions, the shared message blocks, and the conditional variants
/// that override them. A variant is one level (a titled group) with its own
/// override switches inline, so nothing nests deeper than section, variant,
/// fields.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import 'channel_fields_editor.dart';
import 'gloss_visibility_editor.dart';

const List<String> _scopes = <String>[
  'global',
  'world',
  'radius',
  'permission',
  'direct',
];

class ChannelInspector extends StatelessWidget {
  const ChannelInspector({required this.store, super.key});
  final EditorStore store;

  static final ComponentSize _size = controlSize(Density.dense);

  void _edit(String label, void Function(GlossChannelDoc) edit) =>
      store.mutateGloss(label, (GlossDoc doc) {
        if (doc is GlossChannelDoc) edit(doc);
      });

  List<HuiIssue> _issuesAt(String path) =>
      store.issues.where((HuiIssue issue) => issue.path == path).toList();

  Widget _text({
    required String id,
    required String label,
    required String value,
    required String undoLabel,
    required void Function(GlossChannelDoc, String) edit,
    String? path,
    String? hint,
    String? tip,
  }) => Field(
    label: label,
    forId: id,
    hint: hint,
    tip: tip,
    problems: path == null ? const <HuiIssue>[] : _issuesAt(path),
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      onChanged: (String value) =>
          _edit(undoLabel, (GlossChannelDoc doc) => edit(doc, value)),
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );

  @override
  Widget build(BuildContext context) {
    final GlossDoc? active = store.glossDoc;
    if (active is! GlossChannelDoc) return const dom.div(<Widget>[]);
    final GlossChannelDoc doc = active;
    return DenseBox(
      classes: 'hui-inspector-body is-channel',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Chat channel'),
          title: store.menuId,
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        _channel(doc),
        _mentions(doc),
        ChannelFieldsEditor(
          value: doc.toJson(),
          mentions: false,
          issues: store.issues,
          onChanged: (Map<String, Object?> value) =>
              _edit('channel settings', (GlossChannelDoc edited) {
                final GlossChannelDoc next = GlossChannelDoc.fromJson(value);
                edited.card = next.card;
                edited.items = next.items;
                edited.links = next.links;
                edited.filters = next.filters;
                edited.throttle = next.throttle;
                edited.filtering = next.filtering;
              }),
        ),
        _variants(doc),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: doc.show,
            sectionKey: 'channel.visibility',
            issues: _issuesAt(r'$.show'),
            onChanged: (Object? value) => _edit(
              'channel visibility',
              (GlossChannelDoc doc) => doc.show = value,
            ),
          ),
        ),
      ],
    );
  }

  Widget _channel(GlossChannelDoc doc) => AppSection(
    title: huiText('Channel'),
    memoryKey: 'channel.channel',
    children: <Widget>[
      _text(
        id: 'channel-name',
        label: huiText('Channel name'),
        value: doc.channel.name,
        path: r'$.channel.name',
        undoLabel: 'channel name',
        edit: (GlossChannelDoc doc, String value) => doc.channel.name = value,
      ),
      SwitchField(
        label: huiText('Default chat channel'),
        value: doc.channel.defaultChannel,
        onChanged: (bool value) => _edit(
          'default channel',
          (GlossChannelDoc doc) => doc.channel.defaultChannel = value,
        ),
      ),
      _text(
        id: 'channel-aliases',
        label: huiText('Aliases'),
        hint: huiText('Comma separated'),
        value: doc.channel.aliases.join(', '),
        undoLabel: 'channel aliases',
        edit: (GlossChannelDoc doc, String value) => doc.channel.aliases = value
            .split(',')
            .map((String alias) => alias.trim())
            .where((String alias) => alias.isNotEmpty)
            .toList(),
      ),
      Field(
        label: huiText('Audience'),
        forId: 'channel-scope',
        problems: _issuesAt(r'$.channel.scope'),
        child: ArcaneSelect(
          id: 'channel-scope',
          value: doc.channel.scope,
          size: _size,
          fullWidth: true,
          options: <ArcaneSelectOption>[
            ArcaneSelectOption(value: 'global', label: huiText('global')),
            ArcaneSelectOption(value: 'world', label: huiText('world')),
            ArcaneSelectOption(value: 'radius', label: huiText('radius')),
            ArcaneSelectOption(
              value: 'permission',
              label: huiText('permission'),
            ),
            ArcaneSelectOption(value: 'direct', label: huiText('direct')),
            if (!_scopes.contains(doc.channel.scope))
              ArcaneSelectOption(
                value: doc.channel.scope,
                label: doc.channel.scope,
              ),
          ],
          onChanged: (String value) => _edit(
            'channel audience',
            (GlossChannelDoc doc) => doc.channel.scope = value,
          ),
        ),
      ),
      if (doc.channel.scope == 'permission')
        _text(
          id: 'channel-permission',
          label: huiText('Audience permission'),
          value: doc.channel.permission,
          path: r'$.channel.permission',
          undoLabel: 'channel permission',
          edit: (GlossChannelDoc doc, String value) =>
              doc.channel.permission = value,
        ),
      if (doc.channel.scope == 'radius')
        Field(
          label: huiText('Radius in blocks'),
          forId: 'channel-radius',
          problems: _issuesAt(r'$.channel.radius'),
          child: NumberField(
            id: 'channel-radius',
            value: doc.channel.radius.toDouble(),
            integer: true,
            step: 1,
            onChanged: (double value) => _edit(
              'channel radius',
              (GlossChannelDoc doc) => doc.channel.radius = value.round(),
            ),
          ),
        ),
      _text(
        id: 'channel-format',
        label: huiText('Normal message format'),
        tip: huiText(
          '{{ sender.name }} is the sender; {{ message }} is the chat text.',
        ),
        value: doc.format,
        path: r'$.format',
        undoLabel: 'channel format',
        edit: (GlossChannelDoc doc, String value) => doc.format = value,
      ),
    ],
  );

  Widget _mentions(GlossChannelDoc doc) => AppSection(
    title: huiText('Player mentions'),
    memoryKey: 'channel.mentions',
    children: <Widget>[
      SwitchField(
        label: huiText('Enable @mentions'),
        value: doc.mentions.enabled,
        onChanged: (bool value) => _edit(
          'mentions enabled',
          (GlossChannelDoc doc) => doc.mentions.enabled = value,
        ),
      ),
      _text(
        id: 'channel-mention-permission',
        label: huiText('Sender permission'),
        value: doc.mentions.permission,
        undoLabel: 'mention permission',
        edit: (GlossChannelDoc doc, String value) =>
            doc.mentions.permission = value,
      ),
      _text(
        id: 'channel-mention-pattern',
        label: huiText('Mention pattern'),
        tip: huiText('Include {name} once. Matching uses the account name.'),
        value: doc.mentions.pattern,
        path: r'$.mentions.pattern',
        undoLabel: 'mention pattern',
        edit: (GlossChannelDoc doc, String value) =>
            doc.mentions.pattern = value,
      ),
      _text(
        id: 'channel-mention-render',
        label: huiText('Tagged name style'),
        tip: huiText('{{ mention.name }} is the tagged player.'),
        value: doc.mentions.render,
        path: r'$.mentions.render',
        undoLabel: 'mention style',
        edit: (GlossChannelDoc doc, String value) =>
            doc.mentions.render = value,
      ),
      _text(
        id: 'channel-mention-format',
        label: huiText('Tagged message format'),
        tip: huiText(
          'Only the tagged player sees this format. Use {{ sender.name }} '
          'and {{ message }}.',
        ),
        value: doc.mentions.messageFormat,
        path: r'$.mentions.messageFormat',
        undoLabel: 'mention format',
        edit: (GlossChannelDoc doc, String value) =>
            doc.mentions.messageFormat = value,
      ),
      _text(
        id: 'channel-mention-sound',
        label: huiText('Mention sound'),
        tip: huiText('Minecraft sound key. Leave blank for silent mentions.'),
        value: doc.mentions.sound,
        undoLabel: 'mention sound',
        edit: (GlossChannelDoc doc, String value) => doc.mentions.sound = value,
      ),
    ],
  );

  Widget _variants(GlossChannelDoc doc) => AppSection(
    title: huiText('Conditional variants'),
    memoryKey: 'channel.variants',
    count: doc.variants.length,
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add variant'),
        onPressed: () => _edit('add channel variant', (GlossChannelDoc edited) {
          int suffix = edited.variants.length + 1;
          while (edited.variants.any(
            (GlossChannelVariant variant) => variant.id == 'variant-$suffix',
          )) {
            suffix++;
          }
          edited.variants.add(GlossChannelVariant(id: 'variant-$suffix'));
        }),
      ),
    ],
    children: <Widget>[
      for (int index = 0; index < doc.variants.length; index++)
        _variant(doc, index),
    ],
  );

  /// One variant: its condition (id and priority in Advanced) and the
  /// blocks it overrides, each behind its own switch.
  Widget _variant(GlossChannelDoc doc, int index) {
    final GlossChannelVariant variant = doc.variants[index];
    final String path = '\$.variants[$index]';
    return AppSection(
      title: variant.id,
      memoryKey: 'channel.variant.$index',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Remove variant'),
          onPressed: () => _edit(
            'remove channel variant',
            (GlossChannelDoc edited) => edited.variants.removeAt(index),
          ),
        ),
      ],
      children: <Widget>[
        AdvancedOnly(
          child: _text(
            id: 'channel-variant-$index-id',
            label: huiText('Variant id'),
            value: variant.id,
            path: '$path.id',
            undoLabel: 'channel variant id',
            edit: (GlossChannelDoc edited, String value) =>
                edited.variants[index].id = value,
          ),
        ),
        AdvancedOnly(
          child: Field(
            label: huiText('Priority'),
            forId: 'channel-variant-$index-priority',
            child: NumberField(
              id: 'channel-variant-$index-priority',
              value: variant.priority.toDouble(),
              integer: true,
              step: 1,
              onChanged: (double value) => _edit(
                'channel variant priority',
                (GlossChannelDoc edited) =>
                    edited.variants[index].priority = value.round(),
              ),
            ),
          ),
        ),
        _text(
          id: 'channel-variant-$index-when',
          label: huiText('Condition'),
          value: variant.when,
          path: '$path.when',
          undoLabel: 'channel variant condition',
          edit: (GlossChannelDoc edited, String value) =>
              edited.variants[index].when = value,
        ),
        ChannelFieldsEditor(
          value: variant.toJson(),
          fallback: doc.toJson(),
          inherited: true,
          idPrefix: 'channel-variant-$index',
          issues: store.issues,
          path: path,
          onChanged: (Map<String, Object?> value) => _edit(
            'channel variant settings',
            (GlossChannelDoc edited) =>
                edited.variants[index] = GlossChannelVariant.fromJson(value),
          ),
        ),
      ],
    );
  }
}
