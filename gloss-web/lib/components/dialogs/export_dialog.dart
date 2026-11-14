/// Export dialog: the runtime file name, downloads and install steps for
/// every transferable Gloss document kind. The id is renamed in the library,
/// so the file name here is read only.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ListenableBuilder;

import '../../config/links.dart';
import '../../services/clipboard.dart';
import '../../services/file_transfer.dart';
import '../../services/image_library.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/technical_input.dart';
import '../../logic/image_paths.dart' show huiFormatBytes, huiUsedImagePaths;

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ExportDialog extends StatefulWidget {
  const ExportDialog({
    required this.store,
    required this.isOpen,
    required this.onClose,
    this.images,
    super.key,
  });

  final EditorStore store;

  /// Defaults to the library the store already holds.
  final ImageLibrary? images;
  final bool isOpen;
  final VoidCallback onClose;

  @override
  State<ExportDialog> createState() => _ExportDialogState();
}

class _ExportDialogState extends State<ExportDialog> {
  bool _zipping = false;

  EditorStore get _store => component.store;

  ImageLibrary? get _images => component.images ?? _store.images;

  String get _documentId => _store.menuId;

  /// Relative to the plugin folder, from the kind table the plugin writes.
  String get _dataPath =>
      _store.docType.dataPath(_documentId) ?? '$_documentId.json';

  String get _fileName => _dataPath.split('/').last;

  /// Kinds whose runtime file name never follows the document id.
  bool get _fixedFileName => _store.docType.fixedRuntimeId != null;

  String get _installPath => '$huiPluginFolder$_dataPath';

  void _downloadJson() {
    downloadText(_fileName, _store.exportJson());
    toast.success(
      huiText("Saved {fileName}", <String, Object?>{'fileName': _fileName}),
    );
  }

  Future<void> _copyJson() async {
    final bool copied = await copyText(_store.exportJson());
    if (!mounted) return;
    if (copied) {
      toast.success(
        huiText("{noun} JSON copied to the clipboard", <String, Object?>{
          'noun': _store.docType.noun,
        }),
      );
    } else {
      toast.error(
        huiText('The browser refused clipboard access. Use Download instead.'),
      );
    }
  }

  Future<void> _downloadImages() async {
    final ImageLibrary? library = _images;
    if (library == null || library.images.isEmpty) return;
    setState(() => _zipping = true);
    final List<int> bytes = await library.exportZipBytes();
    if (!mounted) return;
    setState(() => _zipping = false);
    downloadBytes('images.zip', bytes, mime: 'application/zip');
    toast.success(
      huiText("Saved images.zip ({huiFormatBytes})", <String, Object?>{
        'huiFormatBytes': huiFormatBytes(bytes.length),
      }),
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _store,
    builder: (BuildContext inner) {
      final bool transferable = _store.canTransferDocument;
      final bool open = component.isOpen && transferable;
      // The export text is only built while the dialog shows: every edit
      // notifies the store, and a closed dialog has nothing to draw.
      final Set<String> used = open && _store.isMenuDoc
          ? huiUsedImagePaths(_store.menu)
          : const <String>{};
      final ImageLibrary? library = _images;
      final bool hasImages = library != null && library.images.isNotEmpty;
      return AppDialog(
        id: 'hui-export-dialog',
        open: open,
        onClose: component.onClose,
        wide: true,
        title: huiText("Export {noun}", <String, Object?>{
          'noun': _store.docType.noun,
        }),
        footer: DialogFooter(
          secondary: <RowAction>[
            RowAction(
              huiText('Copy JSON'),
              open ? _copyJson : null,
              icon: ArcaneIcon.clipboardCopy(),
            ),
            if (used.isNotEmpty)
              RowAction(
                huiText('Download images.zip'),
                hasImages && !_zipping ? _downloadImages : null,
                icon: ArcaneIcon.fileArchive(),
                loading: _zipping,
              ),
          ],
          cancel: RowAction(huiText('Close'), component.onClose),
          primary: RowAction(
            huiText('Download'),
            open ? _downloadJson : null,
            icon: ArcaneIcon.download(),
          ),
        ),
        children: open ? _body(used) : const <Widget>[],
      );
    },
  );

  List<Widget> _body(Set<String> used) {
    final ImageLibrary? library = _images;
    final int missing = library == null
        ? used.length
        : used.where((String path) => !library.contains(path)).length;
    final bool menu = _store.isMenuDoc;
    final bool preview = _store.isPreviewDoc;
    return <Widget>[
      if (_store.hasErrors && !preview)
        AppAlert(
          tone: AlertTone.error,
          title: menu
              ? huiPlural(
                  'export.menu_error_count',
                  _store.errorCount,
                  oneEnglish: '{count} error in this menu',
                  otherEnglish: '{count} errors in this menu',
                )
              : huiPlural(
                  'export.document_error_count',
                  _store.errorCount,
                  oneEnglish: '{count} error in this {document}',
                  otherEnglish: '{count} errors in this {document}',
                  arguments: <String, Object?>{'document': _store.docType.noun},
                ),
          message: huiText(
            'Gloss may refuse to load it. Check Validation first.',
          ),
        ),
      if (missing > 0)
        AppAlert(
          tone: AlertTone.warning,
          message: huiPlural(
            'export.missing_in_browser',
            missing,
            oneEnglish: '{count} image is not in this browser, so images.zip leaves it out.',
            otherEnglish: '{count} images are not in this browser, so images.zip leaves them out.',
          ),
        ),
      Field(
        label: huiText('File'),
        forId: 'hui-export-file',
        tip: _fixedFileName
            ? huiText(
                'Gloss uses a fixed runtime file name for this document type.',
              )
            : huiText('Rename the document in the library to change it.'),
        // Read only and selectable, so the path can be copied; as the first
        // field it also takes the dialog's initial focus.
        child: TextInput(
          id: 'hui-export-file',
          value: _installPath,
          readOnly: true,
          fullWidth: true,
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
      ),
      FieldGroup(
        title: huiText('Install on your server'),
        tip: menu
            ? huiText(
                'Gloss loads new and changed menus within about a second, '
                'with no restart. gloss.open.<id> is not declared in '
                'plugin.yml, so grant it explicitly. /gloss menu move '
                're-anchors an open menu.',
              )
            : preview
            ? huiText(
                'A preview draws by itself over the blocks and entities it '
                'matches. Changes apply right away; subfolders are not read.',
              )
            : huiText(
                'Check the server console after replacing the file. A parse failure leaves the last working {document} active.',
                <String, Object?>{'document': _store.docType.noun},
              ),
        children: <Widget>[
          // The theme resets list markers, so the numbers are part of the text.
          dom.ol(<Widget>[
            for (final (int index, String step) in _steps(
              used.isNotEmpty,
            ).indexed)
              dom.li(<Widget>[Component.text('${index + 1}. $step')]),
          ]),
        ],
      ),
      FieldGroup(
        title: huiText('JSON'),
        children: <Widget>[
          dom.pre(
            classes: 'hui-codeblock is-scroll',
            attributes: const <String, String>{'dir': 'ltr'},
            <Widget>[Component.text(_store.exportJson())],
          ),
        ],
      ),
    ];
  }

  List<String> _steps(bool usesImages) => <String>[
    huiText('Drop {fileName} at {installPath}.', <String, Object?>{
      'fileName': _fileName,
      'installPath': _installPath,
    }),
    if (_store.isMenuDoc) ...<String>[
      if (usesImages)
        huiText(
          'Unzip images.zip into {imageFolder} so every icon path '
          'resolves.',
          <String, Object?>{'imageFolder': huiImageFolder},
        ),
      huiText(
        'Grant gloss.menus.open and gloss.open.{documentId}.',
        <String, Object?>{'documentId': _documentId},
      ),
      huiText(
        'Test it with /gloss menu open {documentId} '
        '(aliases: gl, glo, gg).',
        <String, Object?>{'documentId': _documentId},
      ),
    ] else if (_store.isPreviewDoc)
      huiText('Grant gloss.preview to viewers. Operators have it by default.')
    else
      huiText('Check the server console for errors.'),
  ];
}
