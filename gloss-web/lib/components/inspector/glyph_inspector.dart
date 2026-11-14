/// The glyph catalog form: the font's identity, its space provider, bitmap
/// glyphs, overlays and waypoint styles. A catalog kind, so it edits in the
/// centre (see `catalog_form.dart`); removing a glyph acts at once and Undo
/// brings it back.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../logic/validation.dart' show HuiIssue;
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import 'catalog_form.dart';

class GlyphView extends StatelessWidget {
  const GlyphView({required this.store, super.key});
  final EditorStore store;

  /// The form is always dense (`CatalogForm` is a `DenseBox`).
  ComponentSize get _size => controlSize(Density.dense);

  void _edit(String label, void Function(GlossGlyphDoc) edit) =>
      store.mutateGloss(label, (GlossDoc doc) {
        if (doc is GlossGlyphDoc) edit(doc);
      });

  List<HuiIssue> _issues(String path) => <HuiIssue>[
    for (final HuiIssue issue in store.issues)
      if (issue.path == path) issue,
  ];

  /// Labels go through [huiText] as passed; the source scan does not collect
  /// them, as before this form moved to the centre.
  Widget _text(
    String label,
    String id,
    String value,
    String path,
    void Function(GlossGlyphDoc, String) edit,
  ) => Field(
    label: huiText(label),
    forId: id,
    problems: _issues(path),
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      attributes: technicalInputAttributes,
      onChanged: (String value) =>
          _edit(label, (GlossGlyphDoc doc) => edit(doc, value)),
    ),
  );

  Widget _number(
    String label,
    String id,
    num value,
    String path,
    void Function(GlossGlyphDoc, double) edit, {
    bool integer = true,
  }) => Field(
    label: huiText(label),
    forId: id,
    problems: _issues(path),
    child: NumberField(
      id: id,
      value: value.toDouble(),
      integer: integer,
      step: integer ? 1 : 0.5,
      decimals: integer ? 0 : 3,
      onChanged: (double value) =>
          _edit(label, (GlossGlyphDoc doc) => edit(doc, value)),
    ),
  );

  Widget _iconAction(
    ArcaneGlyph icon,
    String label,
    void Function(GlossGlyphDoc) edit,
  ) => AppIconButton(
    icon: icon,
    label: huiText(label),
    onPressed: () => _edit(label, edit),
  );

  @override
  Widget build(BuildContext context) =>
      CatalogFormHost(store: store, builder: _form);

  Widget _form(BuildContext context) {
    final GlossDoc? active = store.glossDoc;
    if (active is! GlossGlyphDoc) return const dom.div(<Widget>[]);
    return CatalogForm(
      kind: huiText('Glyphs'),
      title: store.menuId,
      revision: active.revision,
      kindClass: 'is-glyph',
      tip: huiText(
        'Bitmap paths are relative to images/. Build and offer the resource pack from the server to use these assets.',
      ),
      children: <Widget>[
        AppSection(
          title: huiText('Font'),
          memoryKey: 'glyph.font',
          children: <Widget>[
            _text(
              'Namespace',
              'glyph-namespace',
              active.namespace,
              r'$.namespace',
              (GlossGlyphDoc doc, String value) => doc.namespace = value,
            ),
            _text(
              'Font',
              'glyph-font',
              active.font,
              r'$.font',
              (GlossGlyphDoc doc, String value) => doc.font = value,
            ),
          ],
        ),
        AppSection(
          title: huiText('Space provider'),
          memoryKey: 'glyph.space',
          children: <Widget>[
            SwitchField(
              id: 'glyph-space-enabled',
              label: huiText('Enabled'),
              value: active.space.enabled,
              onChanged: (bool value) => _edit(
                'space enabled',
                (GlossGlyphDoc doc) => doc.space.enabled = value,
              ),
            ),
            _number(
              'Minimum',
              'glyph-space-min',
              active.space.range.firstOrNull ?? -256,
              r'$.space.range',
              (GlossGlyphDoc doc, double value) => doc.space.range = <int>[
                value.toInt(),
                doc.space.range.lastOrNull ?? 256,
              ],
            ),
            _number(
              'Maximum',
              'glyph-space-max',
              active.space.range.lastOrNull ?? 256,
              r'$.space.range',
              (GlossGlyphDoc doc, double value) => doc.space.range = <int>[
                doc.space.range.firstOrNull ?? -256,
                value.toInt(),
              ],
            ),
          ],
        ),
        AppSection(
          title: huiText('Glyphs'),
          memoryKey: 'glyph.glyphs',
          count: active.glyphs.length,
          actions: <Widget>[
            _iconAction(
              ArcaneIcon.plus(),
              'Add glyph',
              (GlossGlyphDoc doc) => doc.glyphs.add(
                GlossGlyph(id: _nextId('glyph', _bitmapIds(doc))),
              ),
            ),
          ],
          description: active.glyphs.isEmpty
              ? huiText(
                  'No bitmap glyphs. Add a glyph and choose its PNG path.',
                )
              : null,
          children: <Widget>[
            for (int index = 0; index < active.glyphs.length; index++)
              _glyph(active.glyphs[index], index),
          ],
        ),
        AppSection(
          title: huiText('Overlays'),
          memoryKey: 'glyph.overlays',
          count: active.overlays.length,
          actions: <Widget>[
            _iconAction(
              ArcaneIcon.plus(),
              'Add overlay',
              (GlossGlyphDoc doc) => doc.overlays.add(
                GlossGlyphOverlay(id: _nextId('overlay', _bitmapIds(doc))),
              ),
            ),
          ],
          description: active.overlays.isEmpty
              ? huiText('No overlay bitmaps.')
              : null,
          children: <Widget>[
            for (int index = 0; index < active.overlays.length; index++)
              _overlay(active.overlays[index], index),
          ],
        ),
        AppSection(
          title: huiText('Waypoint styles'),
          memoryKey: 'glyph.styles',
          count: active.waypointStyles.length,
          actions: <Widget>[
            _iconAction(
              ArcaneIcon.plus(),
              'Add waypoint style',
              (GlossGlyphDoc doc) => doc.waypointStyles.add(
                GlossWaypointStyleAsset(
                  id: _nextId('style', <String>{
                    for (final GlossWaypointStyleAsset style
                        in doc.waypointStyles)
                      style.id,
                  }),
                  sprites: <GlossWaypointSprite>[GlossWaypointSprite()],
                ),
              ),
            ),
          ],
          description: active.waypointStyles.isEmpty
              ? huiText('No custom waypoint styles.')
              : null,
          children: <Widget>[
            for (int index = 0; index < active.waypointStyles.length; index++)
              _style(active.waypointStyles[index], index),
          ],
        ),
      ],
    );
  }

  Widget _glyph(GlossGlyph glyph, int index) {
    final String path = '\$.glyphs[$index]';
    final String id = 'glyph-$index';
    return AppSection(
      title: glyph.id.isEmpty ? '${index + 1}' : glyph.id,
      memoryKey: 'glyph.glyph.$index',
      actions: <Widget>[
        _iconAction(
          ArcaneIcon.trash(),
          'Remove glyph',
          (GlossGlyphDoc doc) => doc.glyphs.removeAt(index),
        ),
      ],
      children: <Widget>[
        _text(
          'Glyph ID',
          '$id-id',
          glyph.id,
          '$path.id',
          (GlossGlyphDoc doc, String value) => doc.glyphs[index].id = value,
        ),
        _text(
          'Glyph image',
          '$id-image',
          glyph.image,
          '$path.image',
          (GlossGlyphDoc doc, String value) => doc.glyphs[index].image = value,
        ),
        _number(
          'Height',
          '$id-height',
          glyph.height,
          '$path.height',
          (GlossGlyphDoc doc, double value) =>
              doc.glyphs[index].height = value.toInt(),
        ),
        _number(
          'Ascent',
          '$id-ascent',
          glyph.ascent,
          '$path.ascent',
          (GlossGlyphDoc doc, double value) =>
              doc.glyphs[index].ascent = value.toInt(),
        ),
        _number(
          'Frames',
          '$id-frames',
          glyph.frames,
          '$path.frames',
          (GlossGlyphDoc doc, double value) =>
              doc.glyphs[index].frames = value.toInt(),
        ),
        _text(
          'Emoji',
          '$id-emoji',
          glyph.emoji,
          '$path.emoji',
          (GlossGlyphDoc doc, String value) => doc.glyphs[index].emoji = value,
        ),
        _text(
          'Fallback text',
          '$id-fallback',
          glyph.fallback,
          '$path.fallback',
          (GlossGlyphDoc doc, String value) =>
              doc.glyphs[index].fallback = value,
        ),
        SwitchField(
          id: '$id-width-override',
          label: huiText('Override width'),
          value: glyph.width != null,
          problems: glyph.width == null
              ? _issues('$path.width')
              : const <HuiIssue>[],
          onChanged: (bool value) => _edit(
            'glyph width',
            (GlossGlyphDoc doc) => doc.glyphs[index].width = value ? 8 : null,
          ),
        ),
        if (glyph.width != null)
          _number(
            'Width',
            '$id-width',
            glyph.width!,
            '$path.width',
            (GlossGlyphDoc doc, double value) =>
                doc.glyphs[index].width = value.toInt(),
          ),
      ],
    );
  }

  Widget _overlay(GlossGlyphOverlay overlay, int index) {
    final String path = '\$.overlays[$index]';
    final String id = 'overlay-$index';
    return AppSection(
      title: overlay.id.isEmpty ? '${index + 1}' : overlay.id,
      memoryKey: 'glyph.overlay.$index',
      actions: <Widget>[
        _iconAction(
          ArcaneIcon.trash(),
          'Remove overlay',
          (GlossGlyphDoc doc) => doc.overlays.removeAt(index),
        ),
      ],
      children: <Widget>[
        _text(
          'Overlay ID',
          '$id-id',
          overlay.id,
          '$path.id',
          (GlossGlyphDoc doc, String value) => doc.overlays[index].id = value,
        ),
        _text(
          'Overlay image',
          '$id-image',
          overlay.image,
          '$path.image',
          (GlossGlyphDoc doc, String value) =>
              doc.overlays[index].image = value,
        ),
        _number(
          'Height',
          '$id-height',
          overlay.height,
          '$path.height',
          (GlossGlyphDoc doc, double value) =>
              doc.overlays[index].height = value.toInt(),
        ),
        _number(
          'Ascent',
          '$id-ascent',
          overlay.ascent,
          '$path.ascent',
          (GlossGlyphDoc doc, double value) =>
              doc.overlays[index].ascent = value.toInt(),
        ),
        Field(
          label: huiText('Anchor'),
          forId: '$id-anchor',
          problems: _issues('$path.anchor'),
          child: ArcaneSelect(
            id: '$id-anchor',
            value: overlay.anchor,
            size: _size,
            fullWidth: true,
            options: const <ArcaneSelectOption>[
              ArcaneSelectOption(value: 'top', label: 'top'),
              ArcaneSelectOption(value: 'center', label: 'center'),
              ArcaneSelectOption(value: 'bottom', label: 'bottom'),
            ],
            onChanged: (String value) => _edit(
              'overlay anchor',
              (GlossGlyphDoc doc) => doc.overlays[index].anchor = value,
            ),
          ),
        ),
      ],
    );
  }

  Widget _style(GlossWaypointStyleAsset style, int index) {
    final String path = '\$.waypointStyles[$index]';
    final String id = 'waypoint-style-$index';
    return AppSection(
      title: style.id.isEmpty ? '${index + 1}' : style.id,
      memoryKey: 'glyph.style.$index',
      actions: <Widget>[
        _iconAction(
          ArcaneIcon.plus(),
          'Add sprite',
          (GlossGlyphDoc doc) => doc.waypointStyles[index].sprites.add(
            GlossWaypointSprite(
              id: _nextId('sprite', <String>{
                for (final GlossWaypointSprite sprite
                    in doc.waypointStyles[index].sprites)
                  sprite.id,
              }),
            ),
          ),
        ),
        _iconAction(
          ArcaneIcon.trash(),
          'Remove waypoint style',
          (GlossGlyphDoc doc) => doc.waypointStyles.removeAt(index),
        ),
      ],
      children: <Widget>[
        _text(
          'Style ID',
          '$id-id',
          style.id,
          '$path.id',
          (GlossGlyphDoc doc, String value) =>
              doc.waypointStyles[index].id = value,
        ),
        _number(
          'Near distance',
          '$id-near',
          style.nearDistance,
          '$path.nearDistance',
          (GlossGlyphDoc doc, double value) =>
              doc.waypointStyles[index].nearDistance = value,
          integer: false,
        ),
        _number(
          'Far distance',
          '$id-far',
          style.farDistance,
          '$path.farDistance',
          (GlossGlyphDoc doc, double value) =>
              doc.waypointStyles[index].farDistance = value,
          integer: false,
        ),
        for (final HuiIssue issue in _issues('$path.sprites'))
          ArcaneFieldError(issueLine(issue)),
        for (int sprite = 0; sprite < style.sprites.length; sprite++)
          _sprite(style.sprites[sprite], index, sprite),
      ],
    );
  }

  Widget _sprite(GlossWaypointSprite sprite, int style, int index) {
    final String path = '\$.waypointStyles[$style].sprites[$index]';
    final String id = 'waypoint-style-$style-sprite-$index';
    return AppSection(
      title: sprite.id.isEmpty ? '${index + 1}' : sprite.id,
      memoryKey: 'glyph.style.$style.sprite.$index',
      actions: <Widget>[
        _iconAction(
          ArcaneIcon.trash(),
          'Remove sprite',
          (GlossGlyphDoc doc) =>
              doc.waypointStyles[style].sprites.removeAt(index),
        ),
      ],
      children: <Widget>[
        _text(
          'Sprite ID',
          '$id-id',
          sprite.id,
          '$path.id',
          (GlossGlyphDoc doc, String value) =>
              doc.waypointStyles[style].sprites[index].id = value,
        ),
        _text(
          'Sprite image',
          '$id-image',
          sprite.image,
          '$path.image',
          (GlossGlyphDoc doc, String value) =>
              doc.waypointStyles[style].sprites[index].image = value,
        ),
      ],
    );
  }

  Set<String> _bitmapIds(GlossGlyphDoc doc) => <String>{
    for (final GlossGlyph glyph in doc.glyphs) glyph.id,
    for (final GlossGlyphOverlay overlay in doc.overlays) overlay.id,
  };

  String _nextId(String prefix, Set<String> ids) {
    int number = 1;
    while (ids.contains('$prefix-$number')) {
      number++;
    }
    return '$prefix-$number';
  }
}
