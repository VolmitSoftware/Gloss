/// Image manager: the local library that backs textImage and
/// animatedTextImage icons.
///
/// The stored path is the JSON value, the zip entry name and the file name on
/// the server at once, so it is editable here and validated with the rules
/// the plugin applies. Renaming repoints the icons that use the old path;
/// otherwise the menu would render missing-icon placeholders.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show ListenableBuilder;

import '../../config/links.dart';
import '../../model/model.dart';
import '../../services/editor_sync_controller.dart' show SyncImageWeight;
import '../../services/file_transfer.dart';
import '../../services/image_library.dart';
import '../../services/player_skin_source.dart';
import '../../services/storage_service.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/dialog.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/skeleton.dart';
import '../../ui/technical_input.dart';
import '../../logic/image_paths.dart'
    show huiFormatBytes, huiRepointImagePaths, huiUsedImagePaths;
import 'image_picker.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ImageManagerDialog extends StatefulWidget {
  const ImageManagerDialog({
    required this.store,
    required this.images,
    required this.isOpen,
    required this.onClose,
    this.onUseImage,
    this.onUseAnimation,
    this.serverWeight,
    super.key,
  });

  final EditorStore store;
  final ImageLibrary images;
  final bool isOpen;
  final VoidCallback onClose;
  final void Function(StoredImage image)? onUseImage;
  final void Function(List<StoredImage> frames)? onUseAnimation;

  /// The paired server's image count and bytes (from its index); null while
  /// not paired.
  final SyncImageWeight? serverWeight;

  @override
  State<ImageManagerDialog> createState() => _ImageManagerDialogState();
}

class _ImageManagerDialogState extends State<ImageManagerDialog> {
  final Map<String, String> _drafts = <String, String>{};
  final Map<String, ImageLocalizedMessage> _errors =
      <String, ImageLocalizedMessage>{};
  final PlayerSkinSource _skins = PlayerSkinSource();
  String _username = '';
  bool _busy = false;
  bool _fetching = false;

  EditorStore get _store => component.store;

  ImageLibrary get _library => component.images;

  @override
  void didUpdateWidget(ImageManagerDialog oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!oldComponent.isOpen && component.isOpen) {
      _drafts.clear();
      _errors.clear();
    }
  }

  @override
  void dispose() {
    _skins.close();
    super.dispose();
  }

  Future<void> _upload() async {
    setState(() => _busy = true);
    final List<Object> files = await pickImageFiles();
    if (!mounted) return;
    if (files.isEmpty) {
      setState(() => _busy = false);
      return;
    }
    final ImageAddOutcome outcome = await _library.addFromFiles(files);
    if (!mounted) return;
    setState(() => _busy = false);
    _reportOutcome(outcome);
  }

  Future<void> _uploadPlayerHeads() async {
    setState(() => _busy = true);
    final List<Object> files = await pickImageFiles();
    if (!mounted) return;
    if (files.isEmpty) {
      setState(() => _busy = false);
      return;
    }
    final ImageAddOutcome outcome = await _library.addPlayerHeadsFromFiles(
      files,
    );
    if (!mounted) return;
    setState(() => _busy = false);
    _reportOutcome(outcome);
  }

  /// Fetches a skin by name and stores the head it composes to.
  ///
  /// Every failure path ends here with a message and an untouched workspace:
  /// the fetch either returns bytes or a sentence saying which host said what,
  /// and the library only writes once the skin has actually composed.
  Future<void> _fetchHeadByName() async {
    final String username = _username.trim();
    if (_fetching) return;
    setState(() => _fetching = true);
    final PlayerSkinFetch fetched = await _skins.fetch(username);
    if (!mounted) return;
    if (!fetched.isSuccess) {
      setState(() => _fetching = false);
      toast.error(fetched.message!);
      return;
    }
    final ImageAddOutcome outcome = _library.addPlayerHeadFromSkin(
      username: fetched.username,
      skinPngDataUri: fetched.pngDataUri!,
    );
    if (!mounted) return;
    setState(() {
      _fetching = false;
      if (outcome.isSuccess) _username = '';
    });
    if (outcome.isSuccess) {
      toast.success(
        huiText("Added {path} from {host}", <String, Object?>{
          'path': outcome.added.single.path,
          'host': fetched.host,
        }),
      );
      return;
    }
    _reportOutcome(outcome);
  }

  void _reportOutcome(ImageAddOutcome outcome) {
    for (final String error in outcome.errors) {
      toast.error(error);
    }
    for (final String warning in outcome.warnings) {
      toast.warning(warning);
    }
    if (outcome.added.isNotEmpty) {
      toast.success(
        huiPlural(
          'images.added_count_short',
          outcome.added.length,
          oneEnglish: 'Added {count} image',
          otherEnglish: 'Added {count} images',
        ),
      );
    }
  }

  void _commitRename(String path) {
    final String draft = _drafts[path] ?? path;
    if (draft == path) {
      setState(() {
        _drafts.remove(path);
        _errors.remove(path);
      });
      return;
    }
    // The library sanitizes the path and forces a .png extension because every
    // upload is re-encoded as PNG; mirror that so the preview, the validation
    // message and the repointed icons all agree.
    final String next = _expectedPath(draft);
    final String? problem = validateImagePath(next);
    if (problem != null) {
      setState(
        () =>
            _errors[path] = () =>
                validateImagePath(next) ?? huiText('Rename failed.'),
      );
      return;
    }
    final bool renamed = _library.rename(path, next);
    if (!renamed) {
      final ImageLocalizedMessage failure =
          _library.lastErrorMessage ?? () => huiText('Rename failed.');
      setState(() => _errors[path] = failure);
      return;
    }
    _store.mutate('rename image', (HuiMenu menu) {
      huiRepointImagePaths(menu, path, next);
    });
    setState(() {
      _drafts.remove(path);
      _errors.remove(path);
    });
  }

  String _expectedPath(String raw) {
    final String path = sanitizeImagePath(raw);
    final int dot = path.lastIndexOf('.');
    final int slash = path.lastIndexOf('/');
    if (dot > slash && dot > 0) return '${path.substring(0, dot)}.png';
    return '$path.png';
  }

  /// Undo does not bring a deleted image back, so this asks first.
  Future<void> _confirmRemove(String path) async {
    final bool confirmed = await askToConfirm(
      title: huiText('Delete {path}?', <String, Object?>{'path': path}),
      message: huiText('Icons that use it show a missing image.'),
      confirmLabel: huiText('Delete'),
      destructive: true,
    );
    if (!confirmed || !mounted) return;
    _library.remove(path);
    setState(() {
      _drafts.remove(path);
      _errors.remove(path);
    });
  }

  void _downloadOne(StoredImage image) {
    final List<int>? bytes = decodeDataUriBytes(image.dataUri);
    if (bytes == null) {
      toast.error(huiText('That image could not be decoded.'));
      return;
    }
    final int slash = image.path.lastIndexOf('/');
    final String name = slash >= 0
        ? image.path.substring(slash + 1)
        : image.path;
    downloadBytes(name, bytes, mime: 'image/png');
  }

  void _useImage(StoredImage image) {
    component.onUseImage?.call(image);
    component.onClose();
  }

  void _useAnimation(List<StoredImage> frames) {
    component.onUseAnimation?.call(frames);
    component.onClose();
  }

  Future<void> _downloadZip() async {
    setState(() => _busy = true);
    final List<int> bytes = await _library.exportZipBytes();
    if (!mounted) return;
    setState(() => _busy = false);
    downloadBytes('images.zip', bytes, mime: 'application/zip');
    toast.success(
      huiText("Saved images.zip ({huiFormatBytes})", <String, Object?>{
        'huiFormatBytes': huiFormatBytes(bytes.length),
      }),
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _library,
    builder: (BuildContext inner) {
      final bool hasImages = _library.images.isNotEmpty;
      return AppDialog(
        id: 'hui-image-dialog',
        open: component.isOpen,
        onClose: component.onClose,
        wide: true,
        title: huiText('Images'),
        footer: DialogFooter(
          secondary: <RowAction>[
            RowAction(
              huiText('Import skin heads'),
              _busy ? null : _uploadPlayerHeads,
              icon: ArcaneIcon.userRound(),
            ),
            RowAction(
              huiText('Download images.zip'),
              hasImages && !_busy ? _downloadZip : null,
              icon: ArcaneIcon.fileArchive(),
            ),
          ],
          cancel: RowAction(huiText('Close'), component.onClose),
          primary: RowAction(
            huiText('Upload images'),
            _busy ? null : _upload,
            icon: ArcaneIcon.upload(),
            loading: _busy,
          ),
        ),
        children: component.isOpen ? _body() : const <Widget>[],
      );
    },
  );

  List<Widget> _body() {
    final List<StoredImage> images = _library.images;
    final Set<String> used = huiUsedImagePaths(_store.menu);
    return <Widget>[
      if (_library.lastError != null)
        AppAlert(tone: AlertTone.error, message: _library.lastError!),
      ..._storage(),
      Field(
        label: huiText('Head by username'),
        forId: 'hui-image-username',
        tip: huiText(
          'Fetches the skin from minotar.net, or mc-heads.net when it is '
          'down. Only the name is sent. An unknown name gets a default skin.',
        ),
        child: ActionRow(
          leading: <Widget>[
            TextInput(
              id: 'hui-image-username',
              value: _username,
              fullWidth: true,
              placeholder: 'Notch',
              disabled: _fetching,
              onChanged: (String value) => setState(() => _username = value),
              onSubmitted: (String _) => _fetchHeadByName(),
              styles: technicalInputStyles,
              attributes: <String, String>{
                ...technicalInputAttributes,
                'maxlength': '16',
              },
            ),
          ],
          secondary: <RowAction>[
            RowAction(
              huiText('Fetch head'),
              _busy || _fetching ? null : _fetchHeadByName,
              icon: ArcaneIcon.download(),
              loading: _fetching,
            ),
          ],
        ),
      ),
      FieldGroup(
        title: huiPlural(
          'settings.images.count',
          images.length,
          oneEnglish: '{count} image',
          otherEnglish: '{count} images',
        ),
        tip: huiText(
          'Uploads shrink to fit {dimension}x{dimension}; skin heads use the '
          '8x8 face and hat. Drop images anywhere in the editor to add them. '
          'Unzip images.zip into {folder} on the server.',
          <String, Object?>{
            'dimension': huiRecommendedMaxImageDimension,
            'folder': huiImageFolder,
          },
        ),
        children: <Widget>[
          // Every upload is decoded, re-encoded as PNG and measured before it
          // is listed, which is long enough to see on a batch.
          if (images.isEmpty && !_busy)
            EmptyState(
              message: huiText('No images yet'),
              icon: ArcaneIcon.images(),
            )
          else
            dom.div(
              attributes: const <String, String>{'role': 'list'},
              <Widget>[
                for (int index = 0; index < images.length; index++)
                  _row(
                    index,
                    images[index],
                    used.contains(images[index].path),
                    _library.animationFramesFor(images[index].path),
                  ),
              ],
            ),
          if (_busy) Skeleton(label: huiText('Reading images'), rows: 2),
        ],
      ),
    ];
  }

  List<Widget> _storage() {
    final int used = StorageService.estimateUsageBytes();
    const int quota = StorageService.approximateQuotaBytes;
    final double ratio = quota <= 0 ? 0 : (used / quota).clamp(0, 1).toDouble();
    final int percent = (ratio * 100).round();
    return <Widget>[
      if (percent >= 60)
        AppAlert(
          tone: percent >= 80 ? AlertTone.error : AlertTone.warning,
          message: huiText(
            'Storage is filling up. Download images.zip and delete unused images.',
          ),
        ),
      Field(
        label: huiText('Browser storage'),
        tip: huiText(
          'Images live in this browser only. Keep them at or under {dimension}x{dimension} pixels: Gloss draws one text display per row and one character per pixel.',
          <String, Object?>{'dimension': huiRecommendedMaxImageDimension},
        ),
        hint: huiText(
          "{huiFormatBytes} of about {huiFormatBytes2} ({percent}%)",
          <String, Object?>{
            'huiFormatBytes': huiFormatBytes(used),
            'huiFormatBytes2': huiFormatBytes(quota),
            'percent': percent,
          },
        ),
        child: ArcaneProgressBar(
          value: percent.toDouble(),
          variant: percent >= 80
              ? ProgressVariant.error
              : (percent >= 60
                    ? ProgressVariant.warning
                    : ProgressVariant.primary),
        ),
      ),
      if (component.serverWeight case final SyncImageWeight weight)
        Field(
          label: huiText('Server'),
          child: Text('${weight.count} (${huiFormatBytes(weight.bytes)})'),
        ),
    ];
  }

  Widget _row(
    int index,
    StoredImage image,
    bool inUse,
    List<StoredImage> animationFrames,
  ) {
    final String path = image.path;
    final String draft = _drafts[path] ?? path;
    final String? error = _errors[path]?.call();
    final bool firstAnimationFrame =
        animationFrames.isNotEmpty && animationFrames.first.path == path;
    final String details = huiText(
      '{dimensions} px · {size}',
      <String, Object?>{
        'dimensions': '${image.width}x${image.height}',
        'size': huiFormatBytes(image.approximateBytes),
      },
    );
    return dom.div(
      attributes: const <String, String>{'role': 'listitem'},
      <Widget>[
        ActionRow(
          leading: <Widget>[
            dom.img(
              src: image.dataUri,
              alt: path,
              width: 32,
              height: 32,
              classes: 'hui-checkerboard',
              styles: const dom.Styles(
                raw: <String, String>{'image-rendering': 'pixelated'},
              ),
            ),
            if (inUse) StatusBadge(huiText('used in this menu')),
          ],
          secondary: <RowAction>[
            if (component.onUseImage != null)
              RowAction(
                huiText('Use image'),
                () => _useImage(image),
                icon: ArcaneIcon.plus(),
              ),
            if (component.onUseAnimation != null && firstAnimationFrame)
              RowAction(
                huiPlural(
                  'image_manager.use_frames',
                  animationFrames.length,
                  oneEnglish: 'Use {count} frame',
                  otherEnglish: 'Use {count} frames',
                ),
                () => _useAnimation(animationFrames),
                icon: ArcaneIcon.play(),
              ),
            RowAction(
              huiText("Download {path}", <String, Object?>{'path': path}),
              () => _downloadOne(image),
              icon: ArcaneIcon.download(),
              iconOnly: true,
            ),
            RowAction(
              huiText("Delete {path}", <String, Object?>{'path': path}),
              () => _confirmRemove(path),
              icon: ArcaneIcon.trash2(),
              iconOnly: true,
              destructive: true,
            ),
          ],
        ),
        Field(
          label: huiText('Path'),
          forId: 'hui-image-path-$index',
          error: error,
          hint: image.isOversized
              ? huiText(
                  '{dimensions} px · {size} · larger than recommended',
                  <String, Object?>{
                    'dimensions': '${image.width}x${image.height}',
                    'size': huiFormatBytes(image.approximateBytes),
                  },
                )
              : details,
          child: TextInput(
            id: 'hui-image-path-$index',
            value: draft,
            fullWidth: true,
            onChanged: (String value) => setState(() => _drafts[path] = value),
            onBlur: () => _commitRename(path),
            onSubmitted: (String _) => _commitRename(path),
            styles: technicalInputStyles,
            attributes: <String, String>{
              ...technicalInputAttributes,
              'aria-label': huiText("Path for {path}", <String, Object?>{
                'path': path,
              }),
            },
          ),
        ),
      ],
    );
  }
}
