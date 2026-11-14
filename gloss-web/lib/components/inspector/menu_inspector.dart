/// Menu root properties, shown whenever nothing is selected.
///
/// Every field here is one of the keys `MenuDefinitionData` actually reads.
/// There is no background, scale, rotation, title, permission or version at
/// the menu root.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../logic/gloss_show.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/vec3_field.dart';
import 'extras_editor.dart';
import 'gloss_visibility_editor.dart';
import 'menu_variants_editor.dart';
import 'particle_layers_editor.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class MenuInspector extends StatelessWidget {
  const MenuInspector({required this.store, super.key});

  final EditorStore store;

  HuiMenu get _menu => store.menu;

  /// Root-level issues only: anything carrying a component id belongs to that
  /// component's inspector instead.
  List<HuiIssue> _issuesFor(String path) => store.issues
      .where(
        (HuiIssue issue) =>
            issue.componentId == null && issue.path.startsWith(path),
      )
      .toList();

  @override
  Widget build(BuildContext context) => DenseBox(
    classes: 'hui-inspector-body is-menu',
    children: <Widget>[
      // Inside the dense box, so the stock controls read its size.
      Builder(
        builder: (BuildContext context) => Component.fragment(<Widget>[
          PaneHeader(
            kind: huiText('Menu'),
            title: store.menuId,
            docKey: 'menu.id',
            tip: huiText(
              'Nothing is selected, so these are the settings for the whole '
              'menu. Pick a component to edit it.',
            ),
          ),
          AdvancedOnly(
            child: GlossVisibilityEditor(
              raw: _menu.extras['show'],
              sectionKey: 'menu.visibility',
              onChanged: (Object? value) => store.mutate(
                'visibility',
                (HuiMenu edited) => setGlossShow(edited.extras, value),
              ),
            ),
          ),
          _placement(context),
          ParticleLayersEditor(
            layers: _menu.particleLayers,
            sectionKey: 'menu.particleLayers',
            mutate:
                (String label, void Function(List<GlossParticleLayer>) edit) =>
                    store.mutate(
                      label,
                      (HuiMenu menu) => edit(menu.particleLayers),
                    ),
          ),
          MenuVariantsEditor(store: store),
          _lifetime(),
          _install(),
          AdvancedOnly(child: _extras()),
        ]),
      ),
    ],
  );

  Widget _placement(BuildContext context) {
    final Vec3 offset = _menu.offset;
    final bool atDefault =
        offset.x == 0 &&
        offset.y == huiDefaultMenuHeight &&
        offset.z == huiDefaultMenuDistance;
    return FieldGroup(
      title: huiText('Placement'),
      tip: <String>[
        huiText(
          'The offset is measured from the player\'s feet, so y 1.7 is '
          'about eye level and z is how far in front of them the menu '
          'floats.',
        ),
        huiText(
          'Lock position rewrites movement back and zeroes velocity every '
          'tick. The player can still look; follow player turns the menu '
          'with that yaw.',
        ),
      ].join(' '),
      children: <Widget>[
        Field(
          label: huiText('Offset'),
          forId: 'menu-offset',
          required: true,
          docKey: 'menu.offset',
          // Eye level, arm's length forward: what a new document and every
          // template open at.
          defaultValue: '0, $huiDefaultMenuHeight, $huiDefaultMenuDistance',
          onReset: atDefault
              ? null
              : () => store.mutate(
                  'menu offset',
                  (HuiMenu menu) => menu.offset = Vec3(
                    0,
                    huiDefaultMenuHeight,
                    huiDefaultMenuDistance,
                  ),
                ),
          problems: _issuesFor('offset'),
          child: Vec3Field(
            id: 'menu-offset',
            label: huiText('Offset'),
            axisHints: offsetAxisHints(),
            value: offset,
            onChanged: (Vec3 value) => store.mutate(
              'menu offset',
              (HuiMenu menu) => menu.offset = value,
            ),
          ),
        ),
        SwitchField(
          id: 'menu-lock-position',
          label: huiText('Lock position'),
          value: _menu.lockPosition,
          docKey: 'menu.lockPosition',
          onChanged: (bool value) => store.mutate(
            'menu lockPosition',
            (HuiMenu menu) => menu.lockPosition = value,
          ),
        ),
        SwitchField(
          id: 'menu-follow-player',
          label: huiText('Follow player'),
          value: _menu.followPlayer,
          warning: _menu.lockPosition && _menu.followPlayer
              ? huiText(
                  'Position stays locked, but look changes can still turn it.',
                )
              : null,
          docKey: 'menu.followPlayer',
          onChanged: (bool value) => store.mutate(
            'menu followPlayer',
            (HuiMenu menu) => menu.followPlayer = value,
          ),
        ),
        _maxDistance(context),
      ],
    );
  }

  Widget _maxDistance(BuildContext context) {
    final double? value = _menu.maxDistance;
    final bool unlimited = value == null;
    // Unlimited is the checkbox alone: a disabled box holding the ceiling
    // would read as a real distance.
    return Field(
      label: huiText('Max distance'),
      forId: unlimited ? null : 'menu-max-distance',
      docKey: 'menu.maxDistance',
      problems: _issuesFor('maxDistance'),
      trailing: ArcaneCheckbox(
        checked: unlimited,
        size: controlSize(DensityScope.of(context)),
        label: huiText('Unlimited'),
        onChanged: (bool checked) => store.mutate(
          'menu maxDistance',
          (HuiMenu menu) => menu.maxDistance = checked ? null : 8,
        ),
      ),
      child: unlimited
          ? const dom.span(<Widget>[])
          : NumberField(
              id: 'menu-max-distance',
              value: value,
              min: 0,
              max: huiMaxDistanceCeiling,
              step: 1,
              decimals: 2,
              suffix: huiText('blocks'),
              onChanged: (double next) => store.mutate(
                'menu maxDistance',
                (HuiMenu menu) => menu.maxDistance = next,
              ),
            ),
    );
  }

  Widget _lifetime() => AppSection(
    title: huiText('Auto close'),
    memoryKey: 'menu.autoClose',
    tip: huiText(
      'A player can only have one menu open at a time; opening '
      'another replaces this one.',
    ),
    children: <Widget>[
      SwitchField(
        id: 'menu-close-on-death',
        label: huiText('Close on death'),
        value: _menu.closeOnDeath,
        docKey: 'menu.closeOnDeath',
        onChanged: (bool value) => store.mutate(
          'menu closeOnDeath',
          (HuiMenu menu) => menu.closeOnDeath = value,
        ),
      ),
      SwitchField(
        id: 'menu-close-on-teleport',
        label: huiText('Close on teleport'),
        value: _menu.closeOnTeleport,
        docKey: 'menu.closeOnTeleport',
        onChanged: (bool value) => store.mutate(
          'menu closeOnTeleport',
          (HuiMenu menu) => menu.closeOnTeleport = value,
        ),
      ),
    ],
  );

  Widget _code(String value) =>
      dom.code(classes: 'hui-install-code', <Widget>[Text(value)]);

  /// Reference facts, not controls: where the file goes and how to test it.
  Widget _install() => AppSection(
    title: huiText('Install on the server'),
    memoryKey: 'menu.install',
    tip: huiText(
      'Saving over an existing file reloads it within about a second '
      'and closes every session that had it open. New files load just '
      'as quickly, and a deleted file unregisters after 3 to 4 '
      'seconds.',
    ),
    children: <Widget>[
      Field(
        label: huiText('Menu file'),
        tip: huiText(
          'The menu id is the file base name, so renaming the file '
          'renames the menu. Slash-separated ids map to matching subfolders '
          'under menus/, such as shop/tools.json for shop/tools.',
        ),
        child: _code('plugins/Gloss/menus/${store.menuId}.json'),
      ),
      Field(label: huiText('Images'), child: _code('plugins/Gloss/images/')),
      Field(
        label: huiText('Test command'),
        child: _code('/gloss menu open ${store.menuId}'),
      ),
      Field(
        label: huiText('Permissions'),
        tip: huiText(
          'gloss.open.<id> is not declared in plugin.yml, so it has to '
          'be granted explicitly in your permissions plugin. Command '
          'aliases: gl, glo, gg.',
        ),
        child: _code('gloss.menus.open + gloss.open.${store.menuId}'),
      ),
    ],
  );

  /// Unknown keys at the menu root. `name` is the one that turns up in real
  /// files: the plugin ignores it and takes the id from the filename, so seeing
  /// it here is the only way to learn it does nothing.
  Widget _extras() => AppSection(
    title: huiText('Extra keys'),
    memoryKey: 'menu.extras',
    open: false,
    children: <Widget>[
      ExtrasEditor(
        title: huiText('Menu'),
        extras: _menu.extras,
        knownKeys: const <String>{'show'},
        onChanged: (String label, Map<String, dynamic> next) =>
            store.mutate(label, (HuiMenu menu) => menu.extras = next),
      ),
    ],
  );
}
