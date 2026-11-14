/// The simulation controls of a container-preview document, as one section
/// of the inspector: the simulated target, playback, sample values and sample
/// items the card animates through.
///
/// Everything here reads `store.previewSim` ([PreviewSimController]), the one
/// simulation the pixel-card viewport paints from, so the two never disagree
/// about what is on screen. Every control writes through the controller,
/// never through `EditorStore.mutatePreview`: a pinned sample value is
/// simulation state, not document state, so it repaints the card without an
/// undo step, without dirtying the workspace and without the revision bump
/// the viewport memoizes its category and vars against.
///
/// Mounted for every view except [EditorView.code] (see
/// `doctype/document_inspector.dart`): the code view has no card to animate.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/preview_card_edit.dart' show previewAutoSimCategory;
import '../../logic/preview_sim.dart';
import '../../logic/preview_sim_controls.dart';
import '../../model/preview_doc.dart';
import '../../services/catalogs.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/picker.dart';
import '../../ui/section.dart';
import '../../ui/slider_field.dart';
import '../../ui/switch_field.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PreviewSimPanel extends StatelessWidget {
  const PreviewSimPanel({required this.store, this.catalogs, super.key});

  final EditorStore store;

  /// Boot snapshot; `store.catalogs` wins once it holds anything, the same
  /// freshness rule the rest of the inspector uses.
  final HuiCatalogs? catalogs;

  @override
  Widget build(BuildContext context) => DenseBox(
    classes: 'hui-inspector-body is-preview-sim',
    children: <Widget>[Builder(builder: _section)],
  );

  Widget _section(BuildContext context) {
    final PreviewSimController controller = store.previewSim;
    final PreviewSim sim = controller.sim;
    final ComponentSize size = controlSize(DensityScope.of(context));
    return AppSection(
      title: huiText('Simulation'),
      memoryKey: 'preview.simulation',
      tip: huiText(
        'The editor has no server, so this is a canned target the card '
        'previews against. Pin any value below to hold it still; '
        'anything not pinned follows the simulation.',
      ),
      children: <Widget>[
        _target(controller, size),
        _playback(controller),
        SwitchField(
          label: huiText('Force ticking on a static card'),
          value: controller.forcePlay,
          onChanged: controller.setForcePlay,
          tip: huiText(
            'Runs the clock even if nothing in this document reads a '
            'value it moves. Normally the surface skips ticking a card '
            'that would draw the same picture every frame.',
          ),
        ),
        if (previewSimCategorySupportsSurge(controller.category))
          ..._surge(controller, sim),
        ?_sampleValues(controller, sim, size),
        if (previewSimCategorySupportsSlots(controller.category) &&
            sim.inventorySize > 0)
          _sampleItems(
            controller,
            sim,
            huiFreshestCatalogs(store.catalogs, catalogs),
          ),
      ],
    );
  }

  Widget _target(PreviewSimController controller, ComponentSize size) {
    final HuiPreviewDoc? doc = store.previewDoc;
    final String auto = doc == null ? 'statics' : previewAutoSimCategory(doc);
    return Field(
      label: huiText('Simulated target'),
      forId: 'hui-preview-sim-target',
      tip: huiText(
        'Which canned state (a chest, a running furnace, ...) the '
        'card previews against.',
      ),
      trailing: controller.categoryOverride == null
          ? null
          : AppIconButton(
              icon: ArcaneIcon.refreshCcw(),
              label: huiText(
                "Follow the document's own match ({previewSimCategoryLabel})",
                <String, Object?>{
                  'previewSimCategoryLabel': previewSimCategoryLabel(auto),
                },
              ),
              tipAtEnd: true,
              onPressed: () => controller.setCategory(null),
            ),
      child: ArcaneSelect(
        id: 'hui-preview-sim-target',
        value: controller.category,
        size: size,
        fullWidth: true,
        options: <ArcaneSelectOption>[
          for (final String category in previewSimCategories)
            ArcaneSelectOption(
              label: previewSimCategoryLabel(category),
              value: category,
            ),
        ],
        onChanged: (String value) => controller.setCategory(value),
      ),
    );
  }

  Widget _playback(PreviewSimController controller) {
    final bool playing = store.animationsPlaying;
    return Field(
      label: huiText('Playback'),
      tip: huiText(
        'Drives the same clock as the canvas Play button: one game '
        'tick every 50 ms.',
      ),
      child: ActionRow(
        dense: true,
        label: huiText('Playback'),
        secondary: <RowAction>[
          RowAction(
            huiText('Play'),
            () => store.animationsPlaying = !playing,
            icon: ArcaneIcon.play(),
            pressed: playing,
          ),
          RowAction(
            huiText('Step'),
            controller.step,
            icon: ArcaneIcon.stepForward(),
          ),
          RowAction(
            huiText('Reset'),
            controller.reset,
            icon: ArcaneIcon.rotateCcw(),
          ),
        ],
      ),
    );
  }

  List<Widget> _surge(PreviewSimController controller, PreviewSim sim) =>
      <Widget>[
        SwitchField(
          label: huiText('Surge active'),
          value: sim.surgeActive,
          onChanged: (bool value) => controller.setSurge(value),
          tip: huiText(
            'Simulates the plugin\'s own catch-up window after a '
            'refresh gap: progress jumps by more than the elapsed ticks, '
            'which is what `surge.active`/`surge.gain` report.',
          ),
        ),
        if (sim.surgeActive)
          Field(
            label: huiText('Gain (seconds)'),
            forId: 'hui-preview-sim-surge-gain',
            child: SliderField(
              id: 'hui-preview-sim-surge-gain',
              label: huiText('Surge gain'),
              value: sim.surgeGain,
              min: 0,
              max: 30,
              step: 0.1,
              decimals: 1,
              onChanged: (double value) =>
                  controller.setSurge(true, gain: value),
            ),
          ),
      ];

  Widget? _sampleValues(
    PreviewSimController controller,
    PreviewSim sim,
    ComponentSize size,
  ) {
    final List<PreviewSimVarControl> controls = previewSimControlsFor(
      controller.category,
    );
    if (controls.isEmpty) return null;
    final bool anyPinned = controls.any(
      (PreviewSimVarControl control) => sim.overrides.containsKey(control.name),
    );
    return FieldGroup(
      title: huiText('Sample values'),
      actions: <Widget>[
        if (anyPinned)
          AppIconButton(
            icon: ArcaneIcon.pinOff(),
            label: huiText('Unpin all'),
            tipAtEnd: true,
            onPressed: () {
              for (final String name in sim.overrides.keys.toList()) {
                controller.clearOverride(name);
              }
            },
          ),
      ],
      children: <Widget>[
        for (final PreviewSimVarControl control in controls)
          _sampleValue(controller, sim, control, size),
      ],
    );
  }

  Widget _sampleValue(
    PreviewSimController controller,
    PreviewSim sim,
    PreviewSimVarControl control,
    ComponentSize size,
  ) {
    final String label = previewSimVarLabel(control.name);
    final String id = 'hui-preview-sim-${control.name.replaceAll('.', '-')}';
    final Object current =
        sim.overrides[control.name] ??
        sim.variable(control.name) ??
        _fallback(control.kind);
    final Widget? unpin = !sim.overrides.containsKey(control.name)
        ? null
        : AppIconButton(
            icon: ArcaneIcon.pinOff(),
            label: huiText("Unpin {previewSimVarLabel}", <String, Object?>{
              'previewSimVarLabel': label,
            }),
            tipAtEnd: true,
            onPressed: () => controller.clearOverride(control.name),
          );
    switch (control.kind) {
      case PreviewSimControlKind.number:
        return Field(
          label: label,
          forId: id,
          trailing: unpin,
          child: SliderField(
            id: id,
            label: label,
            value: (current as num).toDouble(),
            min: control.min,
            max: control.max,
            step: control.step,
            decimals: control.decimals,
            onChanged: (double value) => controller.setOverride(control, value),
          ),
        );
      case PreviewSimControlKind.boolean:
        return SwitchField(
          id: id,
          label: label,
          value: current == true,
          onChanged: (bool value) => controller.setOverride(control, value),
        );
      case PreviewSimControlKind.string:
        return Field(
          label: label,
          forId: id,
          trailing: unpin,
          child: TextInput(
            id: id,
            value: current.toString(),
            size: size,
            fullWidth: true,
            onChanged: (String value) => controller.setOverride(control, value),
            attributes: const <String, String>{
              'autocomplete': 'off',
              'spellcheck': 'false',
            },
          ),
        );
    }
  }

  static Object _fallback(PreviewSimControlKind kind) => switch (kind) {
    PreviewSimControlKind.number => 0.0,
    PreviewSimControlKind.boolean => false,
    PreviewSimControlKind.string => '',
  };

  Widget _sampleItems(
    PreviewSimController controller,
    PreviewSim sim,
    HuiCatalogs cats,
  ) {
    final int size = sim.inventorySize;
    return MoreFields(
      memoryKey: 'preview.simulation.items',
      label: huiText('Sample items ({slots})', <String, Object?>{
        'slots': huiPlural(
          'preview.sample_slot_count',
          size,
          oneEnglish: '{count} slot',
          otherEnglish: '{count} slots',
        ),
      }),
      children: <Widget>[
        for (int slot = 0; slot < size; slot++)
          _sampleItem(controller, sim, cats, slot),
      ],
    );
  }

  Widget _sampleItem(
    PreviewSimController controller,
    PreviewSim sim,
    HuiCatalogs cats,
    int slot,
  ) {
    final SimSlotItem? item = previewSimSlotAt(sim.slotItems, slot);
    final String material = item?.material ?? '';
    final int count = item?.count ?? 0;
    final String id = 'hui-preview-sim-slot-$slot';
    return Field(
      label: huiText("Slot {slot}", <String, Object?>{'slot': slot}),
      forId: id,
      child: dom.div(classes: 'hui-sim-slot', <Widget>[
        Picker(
          id: id,
          // The sim stores Bukkit enum names (`IRON_ORE`); the catalog and
          // every other picker use lowercase keys (`iron_ore`).
          value: material.toLowerCase(),
          lowercase: true,
          placeholder: huiText('air'),
          searchPlaceholder: huiPlural(
            'preview.search_materials',
            cats.materials.length,
            oneEnglish: 'Search {count} material',
            otherEnglish: 'Search {count} materials',
          ),
          search: (String query) => <PickerOption>[
            for (final MaterialEntry entry in cats.searchMaterials(
              query,
              limit: 60,
            ))
              PickerOption(entry.key, image: entry.texture),
          ],
          onChanged: (String value) => controller.setSlot(
            slot,
            material: value.toUpperCase(),
            count: count > 0 ? count : 1,
          ),
        ),
        NumberField(
          id: '$id-count',
          label: huiText('Count'),
          value: count.toDouble(),
          min: 0,
          max: 6400,
          step: 1,
          integer: true,
          steppers: false,
          onChanged: (double value) => controller.setSlot(
            slot,
            material: material,
            count: value.round(),
          ),
        ),
      ]),
    );
  }
}
