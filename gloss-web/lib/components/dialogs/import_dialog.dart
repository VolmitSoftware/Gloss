/// Import dialog: a file or pasted JSON, and a preview before the open
/// document is replaced.
///
/// The document is only touched when the user presses Replace document. The
/// pasted or picked JSON is auto-detected across every transferable Gloss
/// document kind. Validation is shown first but never blocks an import; only
/// malformed JSON or a document its detected codec cannot decode does.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../doctype/document_type.dart';
import '../../doctype/document_type_registry.dart';
import '../../logic/canvas_scene.dart'
    show CanvasScene, ImageCharCache, McTextCache, buildCanvasScene;
import '../../logic/gloss_text.dart'
    show GlossAnimationResolver, GlossEmojiResolver;
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart' show HuiCatalogs;
import '../../services/file_transfer.dart';
import '../../services/image_library.dart' show ImageLibrary;
import '../../state/editor_store.dart';
import '../../state/workspace.dart' show WorkspaceDoc;
import '../../state/workspace_panel.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/dialog.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/skeleton.dart';
import '../../ui/technical_input.dart';
import 'import_undo.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ImportDialog extends StatefulWidget {
  const ImportDialog({
    required this.store,
    required this.isOpen,
    required this.onClose,
    super.key,
  });

  final EditorStore store;
  final bool isOpen;
  final VoidCallback onClose;

  @override
  State<ImportDialog> createState() => _ImportDialogState();
}

class _ImportDialogState extends State<ImportDialog> {
  String _text = '';
  String _sourceName = '';
  String Function()? _parseError;
  Object? _parsedDocument;
  DocumentTypeAdapter? _parsedType;
  List<HuiIssue> _issues = const <HuiIssue>[];
  bool _picking = false;

  /// Bumped whenever the text is replaced from code; the textarea remounts so
  /// the browser shows the new value instead of the user's dirty one.
  int _generation = 0;

  /// Shared by every parse in this dialog: text and image measurement is
  /// re-run on each keystroke, and a menu import measures the whole document.
  final McTextCache _textCache = McTextCache();
  final ImageCharCache _charCache = ImageCharCache();

  EditorStore get _store => component.store;

  @override
  void didUpdateWidget(ImportDialog oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!oldComponent.isOpen && component.isOpen) {
      _reset();
    }
  }

  void _reset() {
    _text = '';
    _sourceName = '';
    _parseError = null;
    _parsedDocument = null;
    _parsedType = null;
    _issues = const <HuiIssue>[];
    _generation++;
  }

  void _parse(String raw) {
    _parseError = null;
    _parsedDocument = null;
    _parsedType = null;
    _issues = const <HuiIssue>[];
    if (raw.trim().isEmpty) return;

    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } catch (_) {
      _parseError = () => huiText('That is not valid JSON.');
      return;
    }

    try {
      final DocumentTypeAdapter type = DocumentTypeRegistry.detectTransferable(
        decoded,
      );
      final Object document = type.decodeSnapshot(raw);
      _parsedType = type;
      _parsedDocument = document;
      _issues = _validate(type, document);
    } on HuiFormatException catch (e) {
      _parseError = () => huiText('{message} (at {path})', <String, Object?>{
        'message': e.message,
        'path': e.path,
      });
    } catch (_) {
      _parseError = () =>
          huiText('That JSON is not a supported Gloss runtime document.');
    }
  }

  /// Validates through the detected kind's adapter, so the import preview
  /// reports exactly what the document reports once it is open: one dispatch
  /// instead of a second per-kind switch that can drift from
  /// [DocumentTypeAdapter.validate].
  List<HuiIssue> _validate(DocumentTypeAdapter type, Object document) =>
      type.validate(
        _ImportedDocumentState(
          store: _store,
          document: document,
          textCache: _textCache,
          charCache: _charCache,
        ),
      );

  void _onText(String raw) {
    setState(() {
      _text = raw;
      _parse(raw);
    });
  }

  Future<void> _pickFile() async {
    setState(() => _picking = true);
    final (String, String)? picked = await pickJsonFile();
    if (!mounted) return;
    setState(() {
      _picking = false;
      if (picked != null) {
        _sourceName = picked.$1;
        _text = picked.$2;
        _generation++;
        _parse(_text);
      }
    });
  }

  bool get _hasParsed => _parsedDocument != null && _parsedType != null;

  void _replace() {
    if (!_hasParsed) return;
    final String name = _sourceName.isEmpty ? _store.menuId : _sourceName;
    // The store re-runs the same auto-detection; the dialog's own parse above
    // exists only to preview the result before this commits.
    _store.importJson(name, _text);
    component.onClose();
  }

  String get _targetId =>
      _sourceName.isEmpty ? _store.menuId : menuIdFromFileName(_sourceName);

  /// Whether Undo brings the open document back after the replace.
  bool get _keepsUndo {
    final DocumentTypeAdapter? type = _parsedType;
    if (type == null) return true;
    final String? fixedId = type.fixedRuntimeId;
    return importKeepsUndo(
      incomingIsMenu: _parsedDocument is HuiMenu,
      incomingHasFixedFile: fixedId != null,
      openIsMenu: _store.hasActiveDocument && _store.isMenuDoc,
      fixedDocumentExists:
          fixedId != null &&
          _store.workspace.docs.any(
            (WorkspaceDoc doc) =>
                doc.kind == type.kind && doc.runtimeId == fixedId,
          ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final bool keepsUndo = _keepsUndo;
    // With nothing open there is nothing to replace: the store adds the
    // import as a new document.
    final bool replaces = _store.hasActiveDocument;
    return AppDialog(
      id: 'hui-import-dialog',
      open: component.isOpen,
      onClose: component.onClose,
      wide: true,
      title: huiText('Import JSON'),
      footer: DialogFooter(
        secondary: <RowAction>[
          RowAction(
            huiText('Choose a .json file'),
            _picking ? null : _pickFile,
            icon: ArcaneIcon.folderOpen(),
            loading: _picking,
          ),
        ],
        cancel: RowAction(huiText('Cancel'), component.onClose),
        primary: RowAction(
          replaces ? huiText('Replace document') : huiText('Import'),
          _hasParsed ? _replace : null,
          icon: ArcaneIcon.upload(),
          destructive: replaces && _hasParsed && !keepsUndo,
        ),
      ),
      children: <Widget>[
        dom.p(classes: 'ui-section-description', <Widget>[
          Component.text(
            !replaces
                ? huiText('Adds a new document.')
                : _hasParsed
                ? '${huiText('Replaces the open document.')} '
                      '${keepsUndo ? huiText('Undo brings it back.') : huiText('Undo cannot bring it back.')}'
                : huiText('Replaces the open document.'),
          ),
        ]),
        Field(
          label: huiText('JSON'),
          forId: 'hui-import-json',
          hint: _sourceName.isEmpty ? null : _sourceName,
          tip: huiText(
            'Every Gloss document kind works; the JSON decides which. '
            'Dropping a file on the editor makes a new document instead.',
          ),
          // A textarea takes no attributes of its own; direction and
          // spellcheck inherit from this wrapper.
          child: dom.div(attributes: technicalInputAttributes, <Widget>[
            TextArea(
              key: ValueKey<int>(_generation),
              id: 'hui-import-json',
              value: _text,
              rows: 8,
              fullWidth: true,
              styles: technicalInputStyles,
              placeholder: huiText(
                '{ "offset": [0, 1.7, 2.5], "components": [] }',
              ),
              onChanged: _onText,
            ),
          ]),
        ),
        FieldGroup(
          title: huiText('Preview'),
          tip: huiText(
            'Problems show for reference. They never block an import.',
          ),
          children: <Widget>[_preview()],
        ),
      ],
    );
  }

  Widget _preview() {
    // The picker is a real wait: the browser dialog, then a full file read.
    if (_picking) {
      return Skeleton(label: huiText('Reading the file'), rows: 4);
    }
    final String? error = _parseError?.call();
    if (error != null) {
      return AppAlert(
        tone: AlertTone.error,
        title: huiText('Could not read that file'),
        message: error,
      );
    }
    final Object? document = _parsedDocument;
    if (document is HuiPreviewDoc) return _previewDocSummary(document);
    if (document is HuiMenu) return _menuSummary(document);
    if (document is GlossDoc) return _glossSummary(document);
    return EmptyState(
      message: huiText('Nothing to import yet'),
      icon: ArcaneIcon.fileCode(),
    );
  }

  Widget _summary(List<String> facts) => dom.div(<Widget>[
    dom.p(<Widget>[Component.text(facts.join(' · '))]),
    _issueList(),
  ]);

  Widget _menuSummary(HuiMenu menu) => _summary(<String>[
    huiText('menu document'),
    huiText('id {id}', <String, Object?>{'id': _targetId}),
    huiPlural(
      'import.components.count',
      menu.components.length,
      oneEnglish: '{count} component',
      otherEnglish: '{count} components',
    ),
    menu.followPlayer ? huiText('follows player') : huiText('fixed in place'),
    if (menu.maxDistance != null)
      huiText('maxDistance {distance}', <String, Object?>{
        'distance': menu.maxDistance,
      })
    else
      huiText('unlimited range'),
    _errorCountLabel(_count(HuiSeverity.error)),
    _warningCountLabel(_count(HuiSeverity.warning)),
  ]);

  Widget _previewDocSummary(HuiPreviewDoc doc) => _summary(<String>[
    huiText('container-preview document'),
    huiText('id {id}', <String, Object?>{'id': _targetId}),
    huiPlural(
      'import.elements.count',
      doc.elements.length,
      oneEnglish: '{count} element',
      otherEnglish: '{count} elements',
    ),
    huiPlural(
      'import.variants.count',
      doc.variants.length,
      oneEnglish: '{count} variant',
      otherEnglish: '{count} variants',
    ),
    doc.card == null
        ? huiText('no card (bare content)')
        : huiText('has a card'),
    huiPlural(
      'import.expression_errors.count',
      _issues.length,
      oneEnglish: '{count} expression error',
      otherEnglish: '{count} expression errors',
    ),
  ]);

  Widget _glossSummary(GlossDoc doc) => _summary(<String>[
    _glossDocumentKindLabel(doc),
    huiText('id {id}', <String, Object?>{'id': _targetId}),
    huiText('schema {version}', <String, Object?>{
      'version': doc.schemaVersion,
    }),
    huiText('revision {revision}', <String, Object?>{'revision': doc.revision}),
    ..._documentFacts(doc),
    _errorCountLabel(_count(HuiSeverity.error)),
    _warningCountLabel(_count(HuiSeverity.warning)),
  ]);

  int _count(HuiSeverity severity) =>
      _issues.where((HuiIssue issue) => issue.severity == severity).length;

  List<String> _documentFacts(GlossDoc doc) => switch (doc) {
    final GlossHologramDoc hologram => <String>[
      _lineCountLabel(hologram.lines.length),
    ],
    final GlossAnimationDoc animation => <String>[
      _frameCountLabel(animation.frames.length),
      _animationModeLabel(animation.mode),
    ],
    final GlossScoreboardDoc scoreboard => <String>[
      _lineCountLabel(scoreboard.presentation.lines.length),
      huiPlural(
        'import.variants.count',
        scoreboard.variants.length,
        oneEnglish: '{count} variant',
        otherEnglish: '{count} variants',
      ),
    ],
    final GlossMotdDoc motd => <String>[
      huiPlural(
        'import.entries.count',
        motd.entries.length,
        oneEnglish: '{count} entry',
        otherEnglish: '{count} entries',
      ),
    ],
    final GlossEmojiDoc emoji => <String>[
      emoji.enabled ? huiText('enabled') : huiText('disabled'),
    ],
    final GlossBubbleStyleDoc bubble => <String>[
      huiPlural(
        'import.character_wrap.count',
        bubble.wordWrapChars,
        oneEnglish: '{count} character wrap',
        otherEnglish: '{count} character wrap',
      ),
    ],
    final GlossTablistDoc tablist => <String>[
      huiPlural(
        'import.variants.count',
        tablist.headerFooter.variants.length +
            tablist.listNames.variants.length,
        oneEnglish: '{count} variant',
        otherEnglish: '{count} variants',
      ),
    ],
    final GlossRealDropSettingsDoc realDrops => <String>[
      huiText('{speed}x tumble speed', <String, Object?>{
        'speed': realDrops.presentation.motion.speedMultiplier.toStringAsFixed(
          2,
        ),
      }),
      huiPlural(
        'import.models_per_stack.count',
        realDrops.presentation.limits.maxVisualsPerStack,
        oneEnglish: '{count} model per stack',
        otherEnglish: '{count} models per stack',
      ),
    ],
    final GlossDamageIndicatorsDoc indicators => <String>[
      huiText('{rate}/s global cap', <String, Object?>{
        'rate': indicators.limits.maxPerSecond,
      }),
      huiText('{duration} ms lifetime', <String, Object?>{
        'duration': indicators.limits.lifetimeMs,
      }),
    ],
    _ => const <String>[],
  };

  String _glossDocumentKindLabel(GlossDoc doc) => switch (doc) {
    GlossHologramDoc() => huiText('hologram document'),
    GlossAnimationDoc() => huiText('animation document'),
    GlossScoreboardDoc() => huiText('scoreboard document'),
    GlossMotdDoc() => huiText('MOTD document'),
    GlossEmojiDoc() => huiText('emoji document'),
    GlossBubbleStyleDoc() => huiText('bubble-style document'),
    GlossTablistDoc() => huiText('tablist document'),
    GlossRealDropSettingsDoc() => huiText('real-drop settings document'),
    GlossDamageIndicatorsDoc() => huiText('damage-indicator settings document'),
    _ => huiText('runtime document'),
  };

  String _animationModeLabel(String mode) => switch (mode) {
    'ascend' => huiText('ascend'),
    'descend' => huiText('descend'),
    'ascend_descend' => huiText('ascend then descend'),
    'random' => huiText('random'),
    _ => mode,
  };

  String _lineCountLabel(int count) => huiPlural(
    'import.lines.count',
    count,
    oneEnglish: '{count} line',
    otherEnglish: '{count} lines',
  );

  String _frameCountLabel(int count) => huiPlural(
    'import.frames.count',
    count,
    oneEnglish: '{count} frame',
    otherEnglish: '{count} frames',
  );

  String _errorCountLabel(int count) => huiPlural(
    'import.errors.count',
    count,
    oneEnglish: '{count} error',
    otherEnglish: '{count} errors',
  );

  String _warningCountLabel(int count) => huiPlural(
    'import.warnings.count',
    count,
    oneEnglish: '{count} warning',
    otherEnglish: '{count} warnings',
  );

  /// The first dozen issues, grouped by severity under small headings.
  Widget _issueList() {
    if (_issues.isEmpty) {
      return dom.p(<Widget>[
        Component.text(huiText('No issues found in the imported document.')),
      ]);
    }
    final List<HuiIssue> shown = _issues.take(12).toList();
    return dom.div(<Widget>[
      for (final HuiSeverity severity in HuiSeverity.values)
        if (shown.any(
          (HuiIssue issue) => issue.severity == severity,
        )) ...<Widget>[
          dom.h4(classes: 'ui-section-title', <Widget>[
            Component.text(
              huiText("{severityLabel} ({length})", <String, Object?>{
                'severityLabel': _severityLabel(severity),
                'length': _count(severity),
              }),
            ),
          ]),
          dom.ul(<Widget>[
            for (final HuiIssue issue in shown)
              if (issue.severity == severity)
                dom.li(<Widget>[
                  Component.text('${issue.message} '),
                  dom.code(
                    attributes: const <String, String>{'dir': 'ltr'},
                    <Widget>[Component.text(issue.path)],
                  ),
                ]),
          ]),
        ],
      if (_issues.length > shown.length)
        dom.p(<Widget>[
          Component.text(
            huiText("and {value} more…", <String, Object?>{
              'value': _issues.length - shown.length,
            }),
          ),
        ]),
    ]);
  }

  String _severityLabel(HuiSeverity severity) => switch (severity) {
    HuiSeverity.error => huiText('Errors'),
    HuiSeverity.warning => huiText('Warnings'),
    HuiSeverity.info => huiText('Notes'),
  };
}

/// The parsed-but-not-yet-adopted document, presented as the document state
/// its [DocumentTypeAdapter] expects.
///
/// The store still owns the catalogs, image library and workspace animation /
/// emoji resolvers (validation of an imported document resolves references
/// against the workspace it is about to join), but the document slots carry
/// the candidate, never the open document.
final class _ImportedDocumentState implements DocumentStateView {
  @override
  WorkspacePanelData? get panelDoc => null;
  _ImportedDocumentState({
    required this.store,
    required this.document,
    required this.textCache,
    required this.charCache,
  });

  final EditorStore store;
  final Object document;
  final McTextCache textCache;
  final ImageCharCache charCache;

  CanvasScene? _scene;

  /// Only the menu adapter reads this, and only for a [HuiMenu] document; the
  /// store's menu is an unreachable fallback that keeps the getter total.
  @override
  HuiMenu get menu => document is HuiMenu ? document as HuiMenu : store.menu;

  @override
  HuiPreviewDoc? get previewDoc =>
      document is HuiPreviewDoc ? document as HuiPreviewDoc : null;

  @override
  GlossDoc? get glossDoc => document is GlossDoc ? document as GlossDoc : null;

  @override
  GlossAnimationResolver get workspaceAnimations => store.workspaceAnimations;

  @override
  GlossEmojiResolver get workspaceEmoji => store.workspaceEmoji;

  @override
  HuiCatalogs get catalogs => store.catalogs;

  @override
  ImageLibrary? get images => store.images;

  /// Overlap reporting needs the candidate's own scene, not the open
  /// document's. Cached because the adapter may ask more than once.
  @override
  CanvasScene resolveValidationScene() => _scene ??= buildCanvasScene(
    menu: menu,
    uiScale: 1,
    trueRender: false,
    togglePreview: store.togglePreviewFor,
    textCache: textCache,
    images: store.images,
    catalogs: store.catalogs,
    charCache: charCache,
    animations: store.workspaceAnimations,
    emoji: store.workspaceEmoji,
  );
}
