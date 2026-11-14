/// The image library in the image icon editors: a wrapping set of image
/// toggles to pick from, and the upload button.
///
/// Paths are exactly what Gloss reads from `plugins/Gloss/images/`, so a
/// tile is named by its path, not a friendly name.
library;

import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, EventCallback;
import 'package:web/web.dart' as web;

import '../../services/image_library.dart';
import '../../ui/density.dart';
import '../../ui/empty_state.dart';
import 'dom_bridge.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

/// [image] drawn pixelated, scaled by a whole factor to fit a [box]-pixel
/// square (shrunk to fit when larger).
Widget huiPixelImage(StoredImage image, {required int box, String alt = ''}) {
  final int longest = math.max(image.width, image.height);
  final double scale = longest <= 0
      ? 1
      : longest >= box
      ? box / longest
      : (box ~/ longest).toDouble();
  return dom.img(
    src: image.dataUri,
    alt: alt,
    attributes: <String, String>{
      'width': '${math.max(1, (image.width * scale).round())}',
      'height': '${math.max(1, (image.height * scale).round())}',
    },
    styles: const dom.Styles(
      raw: <String, String>{'image-rendering': 'pixelated'},
    ),
  );
}

/// An outline button that opens a hidden file input; picked files go into
/// the image library.
class ImageUploadButton extends StatefulWidget {
  const ImageUploadButton({
    required this.images,
    required this.inputId,
    this.label,
    this.onAdded,
    this.upload,
    this.multiple = true,
    super.key,
  });

  final ImageLibrary images;

  /// Must be unique in the document: the change handler reads the files back
  /// out of the DOM by id.
  final String inputId;
  final String? label;

  /// Called with the paths that were stored, so the caller can select one.
  final void Function(List<String> paths)? onAdded;

  /// How picked files enter the library. Null takes the text-image path,
  /// which fits every upload into the 16x16 glyph budget; the MOTD favicon
  /// fields pass `addFaviconsFromFiles`, which keeps 64x64 bytes intact.
  final Future<ImageAddOutcome> Function(List<Object> files)? upload;

  /// False for a field that names exactly one file, such as a server icon.
  final bool multiple;

  @override
  State<ImageUploadButton> createState() => _ImageUploadButtonState();
}

class _ImageUploadButtonState extends State<ImageUploadButton> {
  bool _busy = false;

  void _choose() =>
      (web.document.getElementById(component.inputId) as web.HTMLElement?)
          ?.click();

  /// A refusal reads as an error toast so an upload that produced nothing
  /// cannot be mistaken for one that worked; caveats on stored files warn.
  Future<void> _handleFiles() async {
    final List<Object> files = readInputFiles(component.inputId);
    resetFileInput(component.inputId);
    if (files.isEmpty) return;
    setState(() => _busy = true);
    final Future<ImageAddOutcome> Function(List<Object> files) upload =
        component.upload ?? component.images.addFromFiles;
    final ImageAddOutcome outcome = await upload(files);
    final List<String> paths = outcome.added
        .map((StoredImage image) => image.path)
        .toList();
    if (paths.isNotEmpty) component.onAdded?.call(paths);
    if (mounted) setState(() => _busy = false);
    if (outcome.hasErrors) {
      toastError(outcome.errorMessages.first());
    } else if (outcome.hasWarnings) {
      toastWarning(outcome.warningMessages.first());
    }
  }

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    Button(
      variant: ButtonVariant.outline,
      size: buttonSize(DensityScope.of(context)),
      icon: ArcaneIcon.upload().sized(controlIconSize),
      label: _busy
          ? huiText('Storing...')
          : component.label ?? huiText('Upload images'),
      loading: _busy,
      onPressed: _busy ? null : _choose,
    ),
    // The one raw input: the browser's file chooser has no library control.
    Component.element(
      tag: 'input',
      id: component.inputId,
      classes: 'ui-sr-only',
      attributes: <String, String>{
        'type': 'file',
        'accept': 'image/*',
        'tabindex': '-1',
        'aria-hidden': 'true',
        if (component.multiple) 'multiple': 'multiple',
      },
      events: <String, EventCallback>{'change': (_) => _handleFiles()},
    ),
  ]);
}

/// The stored images as image toggles. With [staged] the grid is a
/// multi-pick: each press turns one path on or off through [onPicked], in
/// the order pressed. Without it, pressing a tile picks that path.
class ImagePickerGrid extends StatelessWidget {
  const ImagePickerGrid({
    required this.images,
    required this.onPicked,
    this.selected,
    this.staged,
    this.label,
    super.key,
  });

  final ImageLibrary images;
  final void Function(String path) onPicked;

  /// The picked path in a single pick.
  final String? selected;

  /// The paths turned on in a multi-pick.
  final List<String>? staged;

  /// Names the set; defaults to "Image library".
  final String? label;

  ToggleGroupItem _tile(StoredImage image) => ToggleGroupItem(
    value: image.path,
    child: huiPixelImage(
      image,
      box: 20,
      alt: huiText("{path} ({width}x{height})", <String, Object?>{
        'path': image.path,
        'width': image.width,
        'height': image.height,
      }),
    ),
  );

  void _multiChanged(Set<String> picked) {
    final Set<String> before = staged!.toSet();
    for (final String path in picked.difference(before)) {
      onPicked(path);
    }
    for (final String path in before.difference(picked)) {
      onPicked(path);
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<StoredImage> stored = images.images;
    if (stored.isEmpty) {
      return EmptyState(
        message: huiText('No images yet'),
        icon: ArcaneIcon.imageOff(),
      );
    }
    final List<ToggleGroupItem> tiles = <ToggleGroupItem>[
      for (final StoredImage image in stored) _tile(image),
    ];
    const ArcaneStyleData wrap = ArcaneStyleData(
      flexWrap: FlexWrap.wrap,
      justifyContent: JustifyContent.flexStart,
    );
    final ToggleGroupSize size = toggleSize(DensityScope.of(context));
    return dom.div(
      attributes: <String, String>{
        'role': 'group',
        'aria-label': label ?? huiText('Image library'),
      },
      <Widget>[
        if (staged == null)
          ArcaneToggleGroup(
            items: tiles,
            value: stored.any((StoredImage image) => image.path == selected)
                ? selected
                : null,
            variant: ToggleGroupVariant.outline,
            size: size,
            styles: wrap,
            // Pressing the picked tile again reports null: keep it.
            onChanged: (String? path) {
              if (path != null && path != selected) onPicked(path);
            },
          )
        else
          ArcaneToggleGroup.multiple(
            items: tiles,
            values: staged!.toSet(),
            variant: ToggleGroupVariant.outline,
            size: size,
            styles: wrap,
            onMultiChanged: _multiChanged,
          ),
      ],
    );
  }
}
