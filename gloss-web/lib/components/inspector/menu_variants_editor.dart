/// Conditional variants of a menu: each swaps in its own components (and
/// optionally particle layers) while its condition holds. Ids, priorities
/// and the raw components JSON are Advanced; hiding them never rewrites the
/// variant.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import 'particle_layers_editor.dart';
import '../../ui/toast.dart';

class MenuVariantsEditor extends StatefulWidget {
  const MenuVariantsEditor({required this.store, super.key});
  final EditorStore store;
  @override
  State<MenuVariantsEditor> createState() => _MenuVariantsEditorState();
}

class _MenuVariantsEditorState extends State<MenuVariantsEditor> {
  /// Components JSON being typed, per variant, until it parses.
  final Map<int, String> drafts = <int, String>{};
  final Map<int, String> errors = <int, String>{};
  EditorStore get store => component.store;

  void edit(int index, String label, void Function(HuiMenuVariant) change) =>
      store.mutate(label, (HuiMenu menu) => change(menu.variants[index]));

  void components(int index, String source) {
    setState(() {
      drafts[index] = source;
      errors.remove(index);
    });
    try {
      final Object? value = jsonDecode(source);
      if (value is! List) {
        throw const FormatException('Enter a component array.');
      }
      final List<HuiComponent> parsed = <HuiComponent>[
        for (final Object? item in value) HuiComponent.fromJson(item),
      ];
      setState(() => drafts.remove(index));
      edit(
        index,
        'variant components',
        (HuiMenuVariant variant) => variant.components = parsed,
      );
    } catch (error) {
      setState(() => errors[index] = error.toString());
    }
  }

  void _add() => store.mutate('add menu variant', (HuiMenu menu) {
    int suffix = menu.variants.length + 1;
    while (menu.variants.any(
      (HuiMenuVariant variant) => variant.id == 'variant-$suffix',
    )) {
      suffix++;
    }
    menu.variants.add(
      HuiMenuVariant(
        id: 'variant-$suffix',
        components: <HuiComponent>[
          for (final HuiComponent item in menu.components) item.copy(),
        ],
      ),
    );
  });

  void _copyBase(int index) {
    setState(() {
      drafts.remove(index);
      errors.remove(index);
    });
    edit(
      index,
      'copy base components',
      (HuiMenuVariant variant) => variant.components = <HuiComponent>[
        for (final HuiComponent item in store.menu.components) item.copy(),
      ],
    );
  }

  /// Acts at once: Undo brings the variant back, and the toast offers it.
  void _remove(int index, String name) {
    setState(() {
      drafts.clear();
      errors.clear();
    });
    store.mutate(
      'remove menu variant',
      (HuiMenu menu) => menu.variants.removeAt(index),
    );
    toastInfo(
      huiText('Deleted {id}.', <String, Object?>{'id': name}),
      action: ToastAction(
        label: huiText('Undo'),
        onPressed: () {
          store.performUndo();
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final List<HuiMenuVariant> variants = store.menu.variants;
    return AppSection(
      title: huiText('Conditional variants'),
      memoryKey: 'menu.variants',
      count: variants.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add variant'),
          onPressed: _add,
        ),
      ],
      children: <Widget>[
        for (int index = 0; index < variants.length; index++)
          variant(context, index),
      ],
    );
  }

  Widget variant(BuildContext context, int index) {
    final HuiMenuVariant value = store.menu.variants[index];
    final ComponentSize size = controlSize(DensityScope.of(context));
    final String name = value.id.isEmpty ? '${index + 1}' : value.id;
    final String stem = 'menu-variant-$index';
    return dom.div(classes: 'hui-variant', <Widget>[
      dom.div(classes: 'hui-item-head', <Widget>[
        dom.h4(classes: 'hui-item-title', <Widget>[Text(name)]),
        dom.span(classes: 'hui-item-actions', <Widget>[
          AppMenu(
            icon: ArcaneIcon.ellipsis(),
            iconLabel: huiText('Actions for {label}', <String, Object?>{
              'label': name,
            }),
            alignEnd: true,
            entries: <AppMenuEntry>[
              AppMenuEntry(
                huiText('Copy base components'),
                () => _copyBase(index),
                icon: ArcaneIcon.copy(),
              ),
              const AppMenuEntry.separator(),
              AppMenuEntry(
                huiText('Remove variant'),
                () => _remove(index, name),
                destructive: true,
                icon: ArcaneIcon.trash2(),
              ),
            ],
          ),
        ]),
      ]),
      AdvancedOnly(
        child: Field(
          label: huiText('Variant id'),
          forId: '$stem-id',
          child: TextInput(
            id: '$stem-id',
            value: value.id,
            size: size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            styles: technicalInputStyles,
            onChanged: (String text) => edit(
              index,
              'variant id',
              (HuiMenuVariant variant) => variant.id = text,
            ),
          ),
        ),
      ),
      AdvancedOnly(
        child: Field(
          label: huiText('Priority'),
          forId: '$stem-priority',
          child: NumberField(
            id: '$stem-priority',
            value: value.priority.toDouble(),
            step: 1,
            decimals: 0,
            integer: true,
            onChanged: (double next) => edit(
              index,
              'variant priority',
              (HuiMenuVariant variant) => variant.priority = next.round(),
            ),
          ),
        ),
      ),
      Field(
        label: huiText('Condition'),
        forId: '$stem-when',
        child: TextInput(
          id: '$stem-when',
          value: '${value.when ?? ''}',
          size: size,
          fullWidth: true,
          attributes: technicalInputAttributes,
          styles: technicalInputStyles,
          onChanged: (String text) => edit(
            index,
            'variant condition',
            (HuiMenuVariant variant) => variant.when = text,
          ),
        ),
      ),
      AdvancedOnly(
        child: Field(
          label: huiText('Components (JSON)'),
          forId: '$stem-components',
          tip: huiText(
            'Replacement components use the same format as the base menu.',
          ),
          error: errors[index],
          child: TextArea(
            id: '$stem-components',
            value:
                drafts[index] ??
                const JsonEncoder.withIndent('  ').convert(
                  value.components
                      .map((HuiComponent item) => item.toJson())
                      .toList(),
                ),
            onChanged: (String text) => components(index, text),
          ),
        ),
      ),
      SwitchField(
        id: '$stem-particles',
        label: huiText('Override particle layers'),
        value: value.particleLayers != null,
        onChanged: (bool enabled) => edit(
          index,
          'variant particles',
          (HuiMenuVariant variant) =>
              variant.particleLayers = enabled ? <GlossParticleLayer>[] : null,
        ),
      ),
      if (value.particleLayers != null)
        ParticleLayersEditor(
          layers: value.particleLayers!,
          sectionKey: 'menu.variant.$index.particles',
          mutate:
              (String label, void Function(List<GlossParticleLayer>) change) =>
                  edit(
                    index,
                    label,
                    (HuiMenuVariant variant) => change(variant.particleLayers!),
                  ),
        ),
    ]);
  }
}
