/// The File menu's workspace questions: replace this workspace with a
/// bundle, and erase every local document and image. Neither can be undone,
/// so both ask first.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/dialog.dart';

String _documents(int count) => huiPlural(
  'workspace.document_count',
  count,
  oneEnglish: '{count} document',
  otherEnglish: '{count} documents',
);

String _folders(int count) => huiPlural(
  'workspace.folder_count',
  count,
  oneEnglish: '{count} folder',
  otherEnglish: '{count} folders',
);

/// A picked bundle, shown before it replaces the workspace.
class WorkspaceBundleDialog extends StatelessWidget {
  const WorkspaceBundleDialog({
    required this.open,
    required this.onConfirm,
    required this.onClose,
    this.fileName,
    this.documentCount = 0,
    this.folderCount = 0,
    this.imageCount = 0,
    this.warning,
    this.error,
    this.importing = false,
    super.key,
  });

  final bool open;
  final void Function() onConfirm;
  final void Function() onClose;
  final String? fileName;
  final int documentCount;
  final int folderCount;
  final int imageCount;
  final String? warning;

  /// The bundle could not be read; nothing to confirm.
  final String? error;
  final bool importing;

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-workspace-bundle-dialog',
    open: open,
    alert: error == null,
    title: error == null
        ? (fileName ?? huiText('Workspace bundle'))
        : huiText('Workspace import failed'),
    onClose: onClose,
    footer: error != null
        ? DialogFooter(cancel: RowAction(huiText('Dismiss'), onClose))
        : DialogFooter(
            cancel: RowAction(huiText('Cancel'), importing ? null : onClose),
            primary: RowAction(
              huiText('Replace workspace'),
              importing ? null : onConfirm,
              destructive: true,
              loading: importing,
            ),
          ),
    children: <Widget>[
      if (error != null)
        AppAlert(tone: AlertTone.error, message: error!)
      else ...<Widget>[
        dom.p(<Widget>[
          Text(
            huiText('{documents}, {folders}, {images}', <String, Object?>{
              'documents': _documents(documentCount),
              'folders': _folders(folderCount),
              'images': huiPlural(
                'workspace.image_count',
                imageCount,
                oneEnglish: '{count} image',
                otherEnglish: '{count} images',
              ),
            }),
          ),
        ]),
        if (warning != null)
          AppAlert(tone: AlertTone.warning, message: warning!),
        dom.p(classes: 'ui-section-description', <Widget>[
          Text(
            huiText(
              'Importing replaces this local workspace after confirmation.',
            ),
          ),
        ]),
      ],
    ],
  );
}

/// Erase every document, folder and image this browser holds.
class EraseDataDialog extends StatelessWidget {
  const EraseDataDialog({
    required this.open,
    required this.documentCount,
    required this.folderCount,
    required this.onExportBackup,
    required this.onConfirm,
    required this.onClose,
    this.erasing = false,
    super.key,
  });

  final bool open;
  final int documentCount;
  final int folderCount;
  final void Function() onExportBackup;
  final void Function() onConfirm;
  final void Function() onClose;
  final bool erasing;

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-erase-data-dialog',
    open: open,
    alert: true,
    title: huiText('Erase all local editor data?'),
    onClose: onClose,
    footer: DialogFooter(
      secondary: <RowAction>[
        RowAction(
          huiText('Export backup'),
          erasing ? null : onExportBackup,
          icon: ArcaneIcon.download(),
        ),
      ],
      cancel: RowAction(huiText('Cancel'), erasing ? null : onClose),
      primary: RowAction(
        huiText('Erase everything'),
        erasing ? null : onConfirm,
        destructive: true,
        loading: erasing,
      ),
    ),
    children: <Widget>[
      dom.p(<Widget>[
        Text(
          huiText(
            '{documents}, {folders}, and every stored image will be removed from this browser.',
            <String, Object?>{
              'documents': _documents(documentCount),
              'folders': _folders(folderCount),
            },
          ),
        ),
      ]),
      dom.p(classes: 'ui-section-description', <Widget>[
        Text(
          huiText('Export a workspace bundle first if anything must be kept.'),
        ),
      ]),
    ],
  );
}
