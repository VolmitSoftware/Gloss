/// Inspector body for a Gloss MOTD document: the document icon, the pause-menu
/// server links, and the entry list with per-entry line editing (1..2 lines
/// each), hover sample, counts, version label, add/remove/duplicate, pipeline
/// previews with missing-reference chips, and the animation reference picker.
///
/// No placeholder picker on purpose: `MotdService` renders through
/// `renderStatic`, which never expands PlaceholderAPI tokens (a ping has no
/// viewer), so offering the picker here would teach a token that stays
/// literal in the server list.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/mc_text.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/image_library.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/tone.dart';
import '../gloss/gloss_text_line.dart';
import 'animation_reference_picker.dart';
import 'gloss_visibility_editor.dart';
import 'image_picker_grid.dart';
import 'line_list_section.dart';
import 'motd_policy_editor.dart';
import 'preview_expr_field.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class MotdInspector extends StatefulWidget {
  const MotdInspector({required this.store, super.key});

  final EditorStore store;

  @override
  State<MotdInspector> createState() => _MotdInspectorState();
}

class _MotdInspectorState extends State<MotdInspector> {
  /// Where the reference picker inserts: the last focused (entry, line).
  int _focusedEntry = 0;
  int _focusedLine = 0;

  EditorStore get _store => component.store;

  GlossMotdDoc? get _doc => _store.motdDoc;

  /// Control size, read under the inspector's dense scope in [_build].
  ComponentSize _size = ComponentSize.sm;

  /// Issues under [path], minus the [except] subtrees: the entry section
  /// shows the favicon, sample and ping-extra issues next to their own
  /// fields, so its catch-all lines must not repeat them.
  List<HuiIssue> _issuesFor(
    String path, {
    List<String> except = const <String>[],
  }) => _store.issues
      .where(
        (HuiIssue issue) =>
            issue.path.startsWith(path) &&
            !except.any((String excluded) => issue.path.startsWith(excluded)),
      )
      .toList();

  /// Issues on [path] itself. A link that names neither a type nor a label is
  /// reported on the link rather than on a field, so the row that chooses
  /// between them shows it, without swallowing the type's own issue, which
  /// its own field already shows.
  List<HuiIssue> _issuesAt(String path) =>
      _store.issues.where((HuiIssue issue) => issue.path == path).toList();

  @override
  Widget build(BuildContext context) =>
      DensityScope.dense(child: Builder(builder: _build));

  Widget _build(BuildContext context) {
    _size = controlSize(DensityScope.of(context));
    final GlossMotdDoc? doc = _doc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-motd',
      children: <Widget>[
        PaneHeader(
          kind: huiText('MOTD'),
          title: _store.menuId,
          tip: huiText(
            'Each status request selects an eligible entry using the response policy.',
          ),
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        _favicon(doc),
        AdvancedOnly(child: MotdDocumentPolicies(store: _store)),
        GlossVisibilityEditor(
          raw: doc.extras['show'],
          sectionKey: 'motd.visibility',
          issues: _store.issues
              .where((HuiIssue issue) => issue.path == r'$.show')
              .toList(),
          onChanged: (Object? value) => _store.mutateMotd(
            'visibility',
            (GlossMotdDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        _links(doc),
        _entries(doc),
      ],
    );
  }

  /// The document-wide server-list icon. A path under
  /// `plugins/Gloss/images/`, the same library every menu image comes from,
  /// so the field is the plain path editor the icon inspector uses.
  Widget _favicon(GlossMotdDoc doc) => Field(
    label: huiText('Server icon'),
    forId: 'motd-favicon',
    docKey: 'motd.favicon',
    problems: _issuesFor(r'$.favicon'),
    child: Component.fragment(<Widget>[
      TextInput(
        id: 'motd-favicon',
        value: doc.favicon ?? '',
        size: _size,
        fullWidth: true,
        placeholder: huiText('server.png'),
        onChanged: (String value) => _store.mutateMotd(
          'server icon',
          (GlossMotdDoc edited) =>
              edited.favicon = value.isEmpty ? null : value,
        ),
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
      ),
      _faviconUpload(
        'motd-favicon-upload',
        (String path) => _store.mutateMotd(
          'server icon',
          (GlossMotdDoc edited) => edited.favicon = path,
        ),
      ),
    ]),
  );

  /// Server icons never go through the text-image upload path: that one fits
  /// every file into the 16x16 glyph budget, which would leave a favicon the
  /// server list refuses. [ImageLibrary.addFaviconsFromFiles] keeps the 64x64
  /// bytes and reports anything else against the field.
  Widget _faviconUpload(String inputId, void Function(String path) onPicked) {
    final ImageLibrary? images = _store.images;
    if (images == null) return const dom.div(<Widget>[]);
    return ImageUploadButton(
      images: images,
      inputId: inputId,
      multiple: false,
      label: huiText('Upload a 64x64 icon'),
      upload: images.addFaviconsFromFiles,
      onAdded: (List<String> paths) {
        if (paths.isNotEmpty) onPicked(paths.first);
      },
    );
  }

  /// Per-entry override of [_favicon]: the random pick changes the picture
  /// with the text.
  Widget _entryFavicon(GlossMotdEntry entry, int index) {
    final String id = 'motd-entry-$index-favicon';
    return Field(
      label: huiText('Icon override'),
      forId: id,
      docKey: 'motd.entries.favicon',
      problems: _issuesFor('entries[$index].favicon'),
      child: Component.fragment(<Widget>[
        TextInput(
          id: id,
          value: entry.favicon ?? '',
          size: _size,
          fullWidth: true,
          placeholder: huiText('event.png'),
          onChanged: (String value) =>
              _store.mutateMotd('entry icon', (GlossMotdDoc edited) {
                if (index < edited.entries.length) {
                  edited.entries[index].favicon = value.isEmpty ? null : value;
                }
              }),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
        _faviconUpload(
          'motd-entry-favicon-upload-$index',
          (String path) =>
              _store.mutateMotd('entry icon', (GlossMotdDoc edited) {
                if (index < edited.entries.length) {
                  edited.entries[index].favicon = path;
                }
              }),
        ),
      ]),
    );
  }

  /// The pause-menu server links. Published once per document revision
  /// (`MotdService.publishLinks`), not per ping, so the order here is the
  /// order the client lists, which is why each link moves up and down and
  /// the entries do not.
  Widget _links(GlossMotdDoc doc) => LineListSection(
    title: huiText('Server links'),
    docKey: 'motd.links',
    sectionKey: 'motd.links',
    addLabel: huiText('Add link'),
    itemCount: doc.links.length,
    issues: _issuesFor(r'$.links'),
    emptyBody: huiText(
      'The pause menu shows no Gloss links, which is what a server without '
      'this key does. Add one to publish it.',
    ),
    onAdd: () => _store.mutateMotd(
      'add link',
      (GlossMotdDoc edited) =>
          edited.links.add(GlossMotdLink(type: 'website', url: '')),
    ),
    itemBuilder: (int index) => _link(doc, index),
  );

  void _moveLink(int from, int to) =>
      _store.mutateMotd('reorder links', (GlossMotdDoc edited) {
        if (from >= edited.links.length) return;
        edited.links.insert(to, edited.links.removeAt(from));
      });

  Widget _link(GlossMotdDoc doc, int index) {
    final GlossMotdLink link = doc.links[index];
    final String path = 'links[$index]';
    return FieldOwner(
      name: huiText('Link {value}', <String, Object?>{'value': index + 1}),
      child: AppSection(
        title: huiText("Link {value}", <String, Object?>{'value': index + 1}),
        memoryKey: 'motd.link.$index',
        actions: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.arrowUp(),
            label: huiText('Move up'),
            onPressed: index == 0 ? null : () => _moveLink(index, index - 1),
          ),
          AppIconButton(
            icon: ArcaneIcon.arrowDown(),
            label: huiText('Move down'),
            onPressed: index + 1 >= doc.links.length
                ? null
                : () => _moveLink(index, index + 1),
          ),
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Delete link'),
            onPressed: () =>
                _store.mutateMotd('delete link', (GlossMotdDoc edited) {
                  if (index < edited.links.length) edited.links.removeAt(index);
                }),
          ),
        ],
        children: <Widget>[
          Field(
            label: huiText('Wording'),
            tip: huiText(
              'A type uses the client\'s own wording in the player\'s language; a '
              'label publishes yours. A link needs one or the other.',
            ),
            problems: _issuesAt(path),
            child: ToggleChoice<String>(
              label: huiText('Wording'),
              value: link.isLabelled ? 'label' : 'type',
              options: <ChoiceOption<String>>[
                ChoiceOption<String>('type', huiText('Client label')),
                ChoiceOption<String>('label', huiText('My own label')),
              ],
              onChanged: (String value) =>
                  _store.mutateMotd('link wording', (GlossMotdDoc edited) {
                    if (index >= edited.links.length) return;
                    final GlossMotdLink edit = edited.links[index];
                    if (value == 'type') {
                      edit.type = 'website';
                      edit.label = null;
                    } else {
                      edit.type = null;
                      edit.label = '';
                    }
                  }),
            ),
          ),
          if (link.isLabelled)
            _linkLabel(link, index, path)
          else
            _linkType(link, index, path),
          Field(
            label: huiText('Address'),
            forId: 'motd-link-$index-url',
            docKey: 'motd.links.url',
            problems: _issuesFor('$path.url'),
            child: TextInput(
              id: 'motd-link-$index-url',
              value: link.url,
              size: _size,
              fullWidth: true,
              placeholder: huiText('https://example.net'),
              onChanged: (String value) =>
                  _store.mutateMotd('link address', (GlossMotdDoc edited) {
                    if (index < edited.links.length) {
                      edited.links[index].url = value;
                    }
                  }),
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
            ),
          ),
        ],
      ),
    );
  }

  Widget _linkType(GlossMotdLink link, int index, String path) => Field(
    label: huiText('Type'),
    forId: 'motd-link-$index-type',
    docKey: 'motd.links.type',
    problems: _issuesFor('$path.type'),
    child: ArcaneSelect(
      id: 'motd-link-$index-type',
      value: link.type ?? '',
      size: _size,
      fullWidth: true,
      options: <ArcaneSelectOption>[
        for (final String type in glossMotdLinkTypes)
          ArcaneSelectOption(value: type, label: type),
      ],
      onChanged: (String value) =>
          _store.mutateMotd('link type', (GlossMotdDoc edited) {
            if (index < edited.links.length) {
              edited.links[index].type = value;
            }
          }),
    ),
  );

  Widget _linkLabel(GlossMotdLink link, int index, String path) => Field(
    label: huiText('Label'),
    forId: 'motd-link-$index-label',
    docKey: 'motd.links.label',
    problems: _issuesFor('$path.label'),
    child: Component.fragment(<Widget>[
      TextInput(
        id: 'motd-link-$index-label',
        value: link.label ?? '',
        size: _size,
        fullWidth: true,
        placeholder: huiText('&bStore'),
        onChanged: (String value) =>
            _store.mutateMotd('link label', (GlossMotdDoc edited) {
              if (index < edited.links.length) {
                edited.links[index].label = value;
              }
            }),
        attributes: const <String, String>{
          'autocomplete': 'off',
          'spellcheck': 'false',
        },
      ),
      if ((link.label ?? '').isNotEmpty)
        dom.div(classes: 'hui-line-row-preview', <Widget>[
          GlossTextLine(
            render: renderGlossLine(
              link.label ?? '',
              animations: _store.workspaceAnimations,
              emoji: _store.workspaceEmoji,
            ),
          ),
        ]),
    ]),
  );

  /// The hover list under the player count. Same rows as the entry lines,
  /// because it is the same pipeline; only the limit differs.
  Widget _sample(GlossMotdEntry entry, int index) => Field(
    label: huiText('Hover sample'),
    docKey: 'motd.entries.sample',
    problems: _issuesFor('entries[$index].sample'),
    trailing: AppIconButton(
      icon: ArcaneIcon.plus(),
      label: huiText('Add sample line'),
      onPressed: () =>
          _store.mutateMotd('add sample line', (GlossMotdDoc edited) {
            if (index < edited.entries.length) {
              edited.entries[index].sample.add('');
            }
          }),
    ),
    child: entry.sample.isEmpty
        ? const Component.fragment(<Widget>[])
        : ReorderList(
            label: huiText('Hover sample'),
            itemCount: entry.sample.length,
            onReorder: (int from, int to) => _store.mutateMotd(
              'reorder sample lines',
              (GlossMotdDoc edited) {
                if (index >= edited.entries.length) return;
                final List<String> sample = edited.entries[index].sample;
                if (from >= sample.length) return;
                sample.insert(to, sample.removeAt(from));
              },
            ),
            itemBuilder: (int line) => _sampleRow(entry, index, line),
          ),
  );

  Widget _sampleRow(GlossMotdEntry entry, int index, int line) {
    final String text = entry.sample[line];
    return LineRow(
      value: text,
      placeholder: huiText('&7Now playing'),
      label: huiText('Sample line {line} of entry {entry}', <String, Object?>{
        'line': line + 1,
        'entry': index + 1,
      }),
      removeLabel: huiText(
        'Delete sample line {line} of entry {entry}',
        <String, Object?>{'line': line + 1, 'entry': index + 1},
      ),
      beyondRender: line >= glossMotdMaxSampleLines,
      onChanged: (String value) =>
          _store.mutateMotd('edit sample line', (GlossMotdDoc edited) {
            if (index < edited.entries.length &&
                line < edited.entries[index].sample.length) {
              edited.entries[index].sample[line] = value;
            }
          }),
      preview: GlossTextLine(
        render: renderGlossLine(
          text,
          animations: _store.workspaceAnimations,
          emoji: _store.workspaceEmoji,
          viewerAware: false,
        ),
      ),
      chips: <Widget>[
        ...huiMissingAnimationChips(
          text,
          _store.workspaceAnimations,
          where: 'in the server list',
        ),
        if (line >= glossMotdMaxSampleLines)
          StatusBadge(
            huiText(
              'Past line {maximum}: Gloss rejects this file',
              <String, Object?>{'maximum': glossMotdMaxSampleLines},
            ),
            tone: BadgeTone.error,
          ),
      ],
      onRemove: () =>
          _store.mutateMotd('delete sample line', (GlossMotdDoc edited) {
            if (index < edited.entries.length &&
                line < edited.entries[index].sample.length) {
              edited.entries[index].sample.removeAt(line);
            }
          }),
    );
  }

  /// The three fields `applyExtras` sends beside the text. `max` is plain
  /// Bukkit; the other two need Paper's ping event, which their docs say.
  List<Widget> _pingExtras(GlossMotdEntry entry, int index) => <Widget>[
    _pingField(
      label: huiText('Online count'),
      docKey: 'motd.entries.online',
      placeholder: huiText('{{ server.online }}'),
      value: entry.online,
      path: 'entries[$index].online',
      historyLabel: 'online count',
      apply: (GlossMotdDoc edited, String? value) =>
          edited.entries[index].online = value,
      index: index,
    ),
    _pingField(
      label: huiText('Max players'),
      docKey: 'motd.entries.max',
      placeholder: huiText('{{ server.maxPlayers }}'),
      value: entry.max,
      path: 'entries[$index].max',
      historyLabel: 'max players',
      apply: (GlossMotdDoc edited, String? value) =>
          edited.entries[index].max = value,
      index: index,
    ),
    _pingField(
      label: huiText('Version label'),
      docKey: 'motd.entries.version',
      placeholder: huiText('&cOutdated'),
      value: entry.version,
      path: 'entries[$index].version',
      historyLabel: 'version label',
      apply: (GlossMotdDoc edited, String? value) =>
          edited.entries[index].version = value,
      index: index,
    ),
  ];

  Widget _pingField({
    required String label,
    required String docKey,
    required String placeholder,
    required String? value,
    required String path,
    required String historyLabel,
    required void Function(GlossMotdDoc edited, String? value) apply,
    required int index,
  }) {
    final String id = 'motd-${path.replaceAll(RegExp(r'[^A-Za-z0-9]+'), '-')}';
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: Component.fragment(<Widget>[
        TextInput(
          id: id,
          value: value ?? '',
          size: _size,
          fullWidth: true,
          placeholder: placeholder,
          onChanged: (String next) =>
              _store.mutateMotd(historyLabel, (GlossMotdDoc edited) {
                if (index < edited.entries.length) {
                  apply(edited, next.isEmpty ? null : next);
                }
              }),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
        if ((value ?? '').isNotEmpty)
          dom.div(classes: 'hui-line-row-preview', <Widget>[
            GlossTextLine(
              render: renderGlossLine(
                value ?? '',
                animations: _store.workspaceAnimations,
                emoji: _store.workspaceEmoji,
                viewerAware: false,
              ),
            ),
          ]),
      ]),
    );
  }

  Widget _entries(GlossMotdDoc doc) => LineListSection(
    title: huiText('Entries'),
    docKey: 'motd.entries',
    sectionKey: 'motd.entries',
    addLabel: huiText('Add entry'),
    itemCount: doc.entries.length,
    issues: _issuesFor(r'$.entries'),
    emptyBody: huiText(
      'Gloss rejects a MOTD file with no entries, so the server list '
      'falls back to the vanilla line. Add one to make the document '
      'loadable.',
    ),
    onAdd: () {
      final int next = doc.entries.length;
      _store.mutateMotd(
        'add entry',
        (GlossMotdDoc edited) =>
            edited.entries.add(GlossMotdEntry(lines: <String>[''])),
      );
      setState(() {
        _focusedEntry = next;
        _focusedLine = 0;
      });
    },
    tools: <Widget>[AnimationReferencePicker(store: _store, onPicked: _insert)],
    // Entries are picked at random, so their order carries no meaning and a
    // drag handle would imply one.
    itemBuilder: (int index) => _entry(doc, index),
  );

  Widget _entry(GlossMotdDoc doc, int index) {
    final GlossMotdEntry entry = doc.entries[index];
    final String path = 'entries[$index]';
    final List<String> rare = <String>[
      '$path.favicon',
      '$path.sample',
      '$path.online',
      '$path.max',
      '$path.version',
    ];
    final bool rareProblems = rare.any(
      (String prefix) => _issuesFor(prefix).isNotEmpty,
    );
    return AppSection(
      title: huiText("Entry {value}", <String, Object?>{'value': index + 1}),
      memoryKey: 'motd.entry.$index',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.copy(),
          label: huiText('Duplicate entry'),
          onPressed: () => _store.mutateMotd('duplicate entry', (
            GlossMotdDoc edited,
          ) {
            if (index < edited.entries.length) {
              edited.entries.insert(index + 1, edited.entries[index].copy());
            }
          }),
        ),
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete entry'),
          onPressed: () =>
              _store.mutateMotd('delete entry', (GlossMotdDoc edited) {
                if (index < edited.entries.length) {
                  edited.entries.removeAt(index);
                }
              }),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('Lines'),
          docKey: 'motd.lines',
          problems: _issuesFor('$path.lines'),
          trailing: entry.lines.length < glossMotdMaxLinesPerEntry
              ? AppIconButton(
                  icon: ArcaneIcon.plus(),
                  label: huiText('Add second line'),
                  onPressed: () =>
                      _store.mutateMotd('add line', (GlossMotdDoc edited) {
                        if (index < edited.entries.length) {
                          edited.entries[index].lines.add('');
                        }
                      }),
                )
              : null,
          child: dom.div(classes: 'hui-line-list', <Widget>[
            for (int line = 0; line < entry.lines.length; line++)
              _lineRow(entry, index, line),
          ]),
        ),
        Field(
          label: huiText('Weight'),
          forId: 'motd-entry-$index-weight',
          tip: huiText(
            'Relative chance among entries whose visibility condition passes.',
          ),
          problems: _issuesFor('$path.weight'),
          child: NumberField(
            id: 'motd-entry-$index-weight',
            value: entry.weight.toDouble(),
            integer: true,
            step: 1,
            onChanged: (double value) {
              final int weight = value.round();
              _store.mutateMotd(
                'entry weight',
                (GlossMotdDoc edited) => edited.entries[index].weight = weight,
              );
            },
          ),
        ),
        AdvancedOnly(
          child: PreviewExprField(
            label: huiText('Show condition'),
            raw: entry.show,
            kind: PreviewExprKind.boolean,
            showCondition: true,
            scope: glossShowVariables.toSet(),
            categoryVariableNames: glossShowVariables,
            help: huiText('Boolean or expression. Leave blank to always show.'),
            issues: _issuesFor('$path.show'),
            onChanged: (Object? value) => _store.mutateMotd(
              'entry visibility',
              (GlossMotdDoc edited) => edited.entries[index].show = value,
            ),
          ),
        ),
        MoreFields(
          memoryKey: 'motd.entry.$index.more',
          open: rareProblems,
          children: <Widget>[
            _entryFavicon(entry, index),
            _sample(entry, index),
            ..._pingExtras(entry, index),
            AdvancedOnly(
              child: MotdEntryPolicies(store: _store, index: index),
            ),
          ],
        ),
        for (final HuiIssue issue in distinctIssues(
          _issuesFor(
            path,
            except: <String>[
              ...rare,
              '$path.lines',
              '$path.weight',
              '$path.show',
              '$path.select',
              '$path.icons',
              '$path.counts',
              '$path.sampleMode',
            ],
          ),
        ))
          issueNote(issue),
      ],
    );
  }

  Widget _lineRow(GlossMotdEntry entry, int entryIndex, int lineIndex) {
    final String line = entry.lines[lineIndex];
    final bool beyondRender = lineIndex >= glossMotdMaxLinesPerEntry;
    return LineRow(
      value: line,
      placeholder: huiText('&dA glossy server'),
      label: huiText('Line {line} of entry {entry}', <String, Object?>{
        'line': lineIndex + 1,
        'entry': entryIndex + 1,
      }),
      removeLabel: huiText(
        'Delete line {line} of entry {entry}',
        <String, Object?>{'line': lineIndex + 1, 'entry': entryIndex + 1},
      ),
      beyondRender: beyondRender,
      onChanged: (String value) => _editLine(entryIndex, lineIndex, value),
      onFocus: () {
        _focusedEntry = entryIndex;
        _focusedLine = lineIndex;
      },
      preview: GlossTextLine(
        // Both lines are one description, so this line continues the style
        // the lines above it leave open.
        render: glossDisplayTail(
          renderGlossLine(
            entry.lines.take(lineIndex + 1).join('\n'),
            defaultColor: mcMotdTextColor,
            animations: _store.workspaceAnimations,
            emoji: _store.workspaceEmoji,
          ),
          line.split('\n').length,
        ),
      ),
      chips: <Widget>[
        ...huiMissingAnimationChips(
          line,
          _store.workspaceAnimations,
          where: 'in the server list',
        ),
        if (beyondRender)
          StatusBadge(
            huiText('Past line 2: Gloss rejects this entry'),
            tone: BadgeTone.error,
          ),
      ],
      onRemove: () => _store.mutateMotd('delete line', (GlossMotdDoc edited) {
        if (entryIndex < edited.entries.length &&
            lineIndex < edited.entries[entryIndex].lines.length) {
          edited.entries[entryIndex].lines.removeAt(lineIndex);
        }
      }),
    );
  }

  void _editLine(int entryIndex, int lineIndex, String value) =>
      _store.mutateMotd('edit line', (GlossMotdDoc edited) {
        if (entryIndex < edited.entries.length &&
            lineIndex < edited.entries[entryIndex].lines.length) {
          edited.entries[entryIndex].lines[lineIndex] = value;
        }
      });

  /// Inserts a picked token at the end of the focused line, or the last line
  /// of the focused entry when the focus has drifted out of range.
  void _insert(String token) {
    final GlossMotdDoc? doc = _doc;
    if (doc == null || doc.entries.isEmpty) return;
    final int entryIndex = _focusedEntry.clamp(0, doc.entries.length - 1);
    final GlossMotdEntry entry = doc.entries[entryIndex];
    if (entry.lines.isEmpty) {
      _store.mutateMotd('add line', (GlossMotdDoc edited) {
        if (entryIndex < edited.entries.length) {
          edited.entries[entryIndex].lines.add(token);
        }
      });
      return;
    }
    final int lineIndex = _focusedLine.clamp(0, entry.lines.length - 1);
    _editLine(entryIndex, lineIndex, entry.lines[lineIndex] + token);
  }
}
