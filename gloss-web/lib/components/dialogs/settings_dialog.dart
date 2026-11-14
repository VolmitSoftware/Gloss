/// Editor settings: language, canvas defaults and, in Advanced, the custom
/// item catalog and what this browser stores.
///
/// Nothing here is part of a document. The server's real `uiScale` lives in
/// `plugins/Gloss/settings.json` and is global, so the one here is a preview.
/// Theme lives in the Help menu and erasing local data in the File menu.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ListenableBuilder;

import '../../services/catalogs.dart';
import '../../services/file_transfer.dart';
import '../../services/storage_service.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/slider_field.dart';
import '../../ui/switch_field.dart';
import '../../logic/image_paths.dart' show huiFormatBytes;

import 'package:gloss_editor/l10n/hui_localizations.dart';

class SettingsDialog extends StatelessWidget {
  const SettingsDialog({
    required this.store,
    required this.isOpen,
    required this.onClose,
    required this.activeLocale,
    required this.localeLoading,
    required this.onLocaleChanged,
    super.key,
  });

  final EditorStore store;
  final bool isOpen;
  final void Function() onClose;

  /// The catalog code in use, one of [huiSupportedLocales].
  final String activeLocale;

  /// True while a catalog downloads; the picker waits for it.
  final bool localeLoading;
  final void Function(String locale) onLocaleChanged;

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-settings-dialog',
    open: isOpen,
    onClose: onClose,
    title: huiText('Settings'),
    footer: DialogFooter(cancel: RowAction(huiText('Close'), onClose)),
    children: <Widget>[
      ListenableBuilder(
        listenable: store,
        builder: (BuildContext inner) =>
            FieldStack(children: isOpen ? _body() : const <Widget>[]),
      ),
    ],
  );

  /// The catalog is only ever an aid: autocomplete, an approximate canvas
  /// sprite and an informational hint. A failed import changes nothing.
  Future<void> _importCustomItems() async {
    final (String, String)? picked = await pickJsonFile();
    if (picked == null) return;
    final HuiCustomItemCatalog? parsed = HuiCustomItemCatalog.parse(picked.$2);
    if (parsed == null) {
      toast.error(
        huiText(
          "{value} is not a Gloss custom item catalog. Run /gloss item export and pick the file it names.",
          <String, Object?>{'value': picked.$1},
        ),
      );
      return;
    }
    store.setCatalogs(store.catalogs.withCustomItems(parsed));
    if (!HuiCustomItemCatalog.store(picked.$2)) {
      toast.warning(
        huiPlural(
          'custom_items.storage_failure',
          parsed.items.length,
          oneEnglish: 'Loaded {count} item, but this browser refused to save it, so it will be gone after a reload.',
          otherEnglish: 'Loaded {count} items, but this browser refused to save them, so they will be gone after a reload.',
        ),
      );
      return;
    }
    final String itemCount = huiPlural(
      'custom_items.item_count',
      parsed.items.length,
      oneEnglish: '{count} custom item',
      otherEnglish: '{count} custom items',
    );
    final String providerCount = huiPlural(
      'custom_items.provider_count',
      parsed.providers.length,
      oneEnglish: '{count} provider',
      otherEnglish: '{count} providers',
    );
    toast.success(
      huiText('Loaded {items} from {providers}.', <String, Object?>{
        'items': itemCount,
        'providers': providerCount,
      }),
    );
  }

  /// Undo does not reach the stored catalog, so this asks first.
  Future<void> _forgetCustomItems() async {
    final bool confirmed = await askToConfirm(
      title: huiText('Forget the custom item catalog?'),
      message: huiText('Import the file again to get it back.'),
      confirmLabel: huiText('Forget catalog'),
      destructive: true,
    );
    if (!confirmed) return;
    HuiCustomItemCatalog.forgetStored();
    store.setCatalogs(
      store.catalogs.withCustomItems(HuiCustomItemCatalog.empty()),
    );
    toast.warning(huiText('Custom item catalog cleared.'));
  }

  List<Widget> _body() => <Widget>[
    _language(),
    FieldGroup(
      title: huiText('Canvas defaults'),
      tip: huiText('Preview only. None of this is written to the menu file.'),
      children: _canvasDefaults(),
    ),
    AdvancedOnly(child: _customItems()),
    AdvancedOnly(child: _localData()),
  ];

  List<Widget> _canvasDefaults() => <Widget>[
    Field(
      label: huiText('Grid size'),
      forId: 'hui-settings-grid-size',
      tip: huiText('Blocks per snap step. 0.05 matches the arrow-key nudge.'),
      child: NumberField(
        id: 'hui-settings-grid-size',
        value: store.gridSize,
        min: 0.01,
        max: 1,
        step: 0.05,
        onChanged: (double value) => store.gridSize = value,
        suffix: huiText('blocks'),
      ),
    ),
    Field(
      label: huiText('Backdrop'),
      forId: 'hui-settings-backdrop',
      child: ArcaneSelect(
        id: 'hui-settings-backdrop',
        value: store.backdrop.name,
        fullWidth: true,
        options: <ArcaneSelectOption>[
          ArcaneSelectOption(label: huiText('Screenshot'), value: 'image'),
          ArcaneSelectOption(label: huiText('Dark'), value: 'dark'),
          ArcaneSelectOption(label: huiText('Light'), value: 'light'),
          ArcaneSelectOption(label: huiText('None'), value: 'none'),
        ],
        onChanged: (String value) {
          for (final HuiBackdropMode mode in HuiBackdropMode.values) {
            if (mode.name == value) store.backdrop = mode;
          }
        },
      ),
    ),
    AdvancedOnly(
      child: SwitchField(
        label: huiText('True render offsets'),
        tip: huiText(
          'Applies the in-game vertical bias: text sits lower than its '
          'anchor and items lower still. Turn it off to author against '
          'clean anchors.',
        ),
        value: store.trueRender,
        onChanged: (bool value) => store.trueRender = value,
      ),
    ),
    AdvancedOnly(child: _uiScale()),
  ];

  Widget _language() => Field(
    label: huiText('Language'),
    forId: 'hui-settings-language',
    child: ArcaneSelect(
      id: 'hui-settings-language',
      value: activeLocale,
      fullWidth: true,
      disabled: localeLoading,
      options: <ArcaneSelectOption>[
        for (final HuiLocale locale in huiSupportedLocales)
          ArcaneSelectOption(label: locale.nativeName, value: locale.code),
      ],
      onChanged: (String code) {
        if (code != activeLocale) onLocaleChanged(code);
      },
    ),
  );

  /// Bounds, step and rounding match the canvas toolbar's uiScale control:
  /// both edit one store field, so nudging one and then the other must not
  /// land on a different number.
  Widget _uiScale() => Field(
    label: huiText('Server uiScale (preview)'),
    forId: 'hui-settings-ui-scale',
    tip: huiText(
      'The real value lives in plugins/Gloss/settings.json and is '
      'global for the server. It multiplies every offset and every '
      'icon size.',
    ),
    defaultValue: '1',
    onReset: store.previewUiScale == 1 ? null : () => store.previewUiScale = 1,
    child: SliderField(
      id: 'hui-settings-ui-scale',
      label: huiText('Server uiScale (preview)'),
      value: store.previewUiScale,
      min: 0.25,
      max: 4,
      step: 0.05,
      decimals: 2,
      numberMin: 0.25,
      numberMax: 4,
      onChanged: (double next) =>
          store.previewUiScale = (next * 100).round() / 100,
    ),
  );

  Widget _customItems() {
    final HuiCustomItemCatalog catalog = store.catalogs.customItems;
    final String status = catalog.isEmpty
        ? huiText('no catalog loaded')
        : <String>[
            huiPlural(
              'settings.custom_items.count',
              catalog.items.length,
              oneEnglish: '{count} item',
              otherEnglish: '{count} items',
            ),
            ...catalog.providers,
          ].join(' · ');
    return FieldGroup(
      title: huiText('Custom item catalog'),
      tip: huiText(
        'Optional. Only powers autocomplete and the canvas preview '
        'for customItem icons; ids always work without it.',
      ),
      children: <Widget>[
        Field(
          label: huiText('Catalog'),
          tip: huiText(
            'Run /gloss item export on your server, then import '
            'plugins/Gloss/custom-items.json here. The server is still '
            'the only thing that can confirm an id.',
          ),
          child: ActionRow(
            leading: <Widget>[Component.text(status)],
            secondary: <RowAction>[
              if (catalog.isNotEmpty)
                RowAction(
                  huiText('Forget catalog'),
                  _forgetCustomItems,
                  destructive: true,
                ),
              RowAction(huiText('Import catalog'), _importCustomItems),
            ],
          ),
        ),
      ],
    );
  }

  Widget _localData() {
    final int used = StorageService.estimateUsageBytes();
    final int workspaceBytes = store.workspace.estimateBytes();
    return FieldGroup(
      title: huiText('Local data'),
      tip: huiText(
        'Documents use transactional IndexedDB storage. Images and '
        'preferences remain in this browser too. Nothing is uploaded.',
      ),
      children: <Widget>[
        dom.p(classes: 'ui-section-description', <Widget>[
          Component.text(
            <String>[
              huiText('{size} workspace', <String, Object?>{
                'size': huiFormatBytes(workspaceBytes),
              }),
              huiText('{size} localStorage', <String, Object?>{
                'size': huiFormatBytes(used),
              }),
              huiPlural(
                'settings.documents.count',
                store.workspace.docs.length,
                oneEnglish: '{count} document',
                otherEnglish: '{count} documents',
              ),
              huiPlural(
                'settings.images.count',
                store.images?.images.length ?? 0,
                oneEnglish: '{count} image',
                otherEnglish: '{count} images',
              ),
              if (!StorageService.isWritable)
                huiText('image and preference storage is read-only'),
            ].join(' · '),
          ),
        ]),
      ],
    );
  }
}
