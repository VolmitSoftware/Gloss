/// Left rail: the ordered component list.
///
/// Order is serialized menu order and breaks exact-distance click ties in-game.
/// Reordering is a document edit, not a view preference, so every move goes
/// through the store and lands in the undo stack.
///
/// The rail is also the non-canvas face of the selection: rows carry set
/// membership, and shift-click toggles it, so a multi-selection can be built
/// and read without touching the artboard.
library;

import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart'
    show Component, EventCallback, ListenableBuilder;

import '../../config/defaults.dart';
import '../../doctype/document_type.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../services/showcase_randomizer.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/empty_state.dart';
import '../../ui/info_tip.dart';
import '../../ui/list_row.dart';
import '../../ui/menu.dart';
import '../../ui/reorder_list.dart';
import '../../ui/skeleton.dart';
import 'preview_elements_rail.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

/// The display types come first in the Add menu; the runtime form types
/// (list, slider, field, tabs) follow a separator.
const Set<String> _displayTypes = <String>{'button', 'decoration', 'toggle'};

String componentTypeLabel(String type) => switch (type) {
  'button' => huiText('Button'),
  'toggle' => huiText('Toggle'),
  'decoration' => huiText('Decoration'),
  'list' => huiText('List'),
  'slider' => huiText('Slider'),
  'field' => huiText('Text field'),
  'tabs' => huiText('Tabs'),
  _ => type,
};

ArcaneGlyph componentTypeIcon(String type, {bool active = false}) =>
    switch (type) {
      'button' => ArcaneIcon.mousePointerClick(),
      'toggle' => active ? ArcaneIcon.toggleRight() : ArcaneIcon.toggleLeft(),
      'list' => ArcaneIcon.listIcon(),
      'slider' => ArcaneIcon.slidersHorizontal(),
      'field' => ArcaneIcon.textCursorInput(),
      'tabs' => ArcaneIcon.panelTop(),
      _ => ArcaneIcon.square(),
    };

/// "Create random menu" (or preview) for the open document: replaces its
/// content in one undo step.
AppMenuEntry railCreateRandomEntry(EditorStore store) {
  final DocumentTypeAdapter type = store.docType;
  final String? documentId = store.workspace.activeId;
  return AppMenuEntry(
    huiText("Create random {noun}", <String, Object?>{'noun': type.noun}),
    documentId == null || !canRandomizeShowcase(type)
        ? null
        : () => randomizeShowcaseDocument(store, documentId),
    description: huiText('Replaces its code'),
    icon: ArcaneIcon.dices(),
  );
}

/// The toast after a rail delete; Undo puts the row back.
void railDeletedToast(EditorStore store, String label) => toastInfo(
  huiText('Deleted {id}.', <String, Object?>{'id': label}),
  action: ToastAction(
    label: huiText('Undo'),
    onPressed: () => store.performUndo(),
  ),
);

/// One rail row: the select target (a ghost button filling the row, named
/// by the item), an issue dot, and the row's action menu. Right-click opens
/// the same entries.
class RailRow extends StatelessWidget {
  const RailRow({
    required this.label,
    required this.icon,
    required this.current,
    required this.onSelect,
    required this.entries,
    required this.menuId,
    this.issue,
    this.onPointerDown,
    super.key,
  });

  final String label;
  final ArcaneGlyph icon;

  /// Selected: `aria-current` on the select target.
  final bool current;
  final void Function() onSelect;
  final List<AppMenuEntry> entries;

  /// The row menu's id; the context menu takes `<menuId>-context`.
  final String menuId;

  /// The worst problem on the item, drawn as a dot.
  final HuiSeverity? issue;
  final void Function(Object? event)? onPointerDown;

  @override
  Widget build(BuildContext context) {
    final HuiSeverity? severity = issue;
    final bool error = severity == HuiSeverity.error;
    return AppContextMenu(
      id: '$menuId-context',
      entries: entries,
      child: ListRow(
        label: label,
        icon: icon,
        selected: current,
        onSelect: onSelect,
        attributes: const <String, String>{'data-rail-row': ''},
        rowAttributes: const <String, String>{'data-rail-item': ''},
        events: <String, EventCallback>{'pointerdown': ?onPointerDown},
        trailing: <Widget>[
          if (severity == HuiSeverity.error || severity == HuiSeverity.warning)
            dom.span(
              attributes: <String, String>{
                'data-rail-issue': error ? 'error' : 'warning',
                'role': 'img',
                'aria-label': error
                    ? huiText('Has errors')
                    : huiText('Has warnings'),
              },
              const <Widget>[],
            ),
          AppMenu(
            id: menuId,
            icon: ArcaneIcon.ellipsis(),
            iconLabel: huiText("Actions for {label}", <String, Object?>{
              'label': label,
            }),
            alignEnd: true,
            entries: entries,
          ),
        ],
      ),
    );
  }
}

/// The worst severity in [issues], or null when there is none worth a dot.
HuiSeverity? railIssue(List<HuiIssue> issues) {
  if (issues.any((HuiIssue issue) => issue.severity == HuiSeverity.error)) {
    return HuiSeverity.error;
  }
  if (issues.any((HuiIssue issue) => issue.severity == HuiSeverity.warning)) {
    return HuiSeverity.warning;
  }
  return null;
}

class ComponentsRail extends StatefulWidget {
  const ComponentsRail({required this.store, super.key});

  final EditorStore store;

  @override
  State<ComponentsRail> createState() => _ComponentsRailState();
}

class _ComponentsRailState extends State<ComponentsRail> {
  /// The catalogs the store held when this rail mounted.
  ///
  /// `HuiCatalogs.loaded` is false both before the fetch and after it fails, so
  /// it cannot tell "still loading" from "gave up" on its own. The instance
  /// can: `EditorStore.setCatalogs` swaps in a new one exactly once, whichever
  /// way the fetch went.
  late final HuiCatalogs _bootCatalogs;

  /// Whether the pointer pressing a row held Shift, Ctrl or Cmd. Read at
  /// pointerdown: the row button's press callback carries no event.
  bool _extend = false;

  EditorStore get _store => component.store;

  @override
  void initState() {
    super.initState();
    _bootCatalogs = _store.catalogs;
  }

  bool get _catalogsPending =>
      identical(_store.catalogs, _bootCatalogs) && !_store.catalogs.loaded;

  /// Shift is the documented gesture; Ctrl and Cmd come along because every
  /// list in every other editor accepts them.
  void _notePointer(Object? event) {
    bool held(String key) {
      final JSAny? value = (event as JSObject?)?.getProperty<JSAny?>(key.toJS);
      return value != null &&
          value.isA<JSBoolean>() &&
          (value as JSBoolean).toDart;
    }

    _extend = held('shiftKey') || held('ctrlKey') || held('metaKey');
  }

  /// A plain press replaces the selection; a modified one toggles membership.
  ///
  /// The canvas keeps the group when a member is clicked plainly (the click
  /// starts a group drag). A rail row has no such gesture, so here the list
  /// convention wins and a plain press collapses to the row pressed. Keyboard
  /// activation has no pointerdown, so Enter always selects.
  void _select(String id) {
    final bool extend = _extend;
    _extend = false;
    if (extend) {
      _store.toggleInSelection(id);
    } else {
      _store.select(id);
    }
  }

  void _delete(String id, String label) {
    _store.deleteComponent(id);
    railDeletedToast(_store, label);
  }

  // A container-preview document delegates whole to [PreviewElementsRail]:
  // elements have no id, so this state class's id-keyed bookkeeping does not
  // apply to them.
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _store,
    builder: (BuildContext inner) =>
        _store.isPreviewDoc ? PreviewElementsRail(store: _store) : _body(),
  );

  Widget _body() {
    final List<HuiComponent> components = _store.components;
    return DenseBox(
      attributes: const <String, String>{'data-rail': 'components'},
      children: <Widget>[
        _head(components.length),
        if (components.isEmpty)
          // Nothing authored yet AND nothing fetched yet reads as loading;
          // the empty state would be a claim the boot sequence corrects a
          // moment later.
          _catalogsPending
              ? const Skeleton(rows: 3)
              : EmptyState(
                  message: huiText('No components yet'),
                  icon: ArcaneIcon.layers(),
                )
        else
          ReorderList(
            label: huiText('Components'),
            itemCount: components.length,
            tools: false,
            onReorder: (int from, int to) =>
                _store.reorder(components[from].id, to),
            itemBuilder: (int index) =>
                _row(index, components[index], components.length),
          ),
      ],
    );
  }

  /// Add, the selection count while a group is selected, the ordering tip,
  /// and the rail's `...` menu, on one dense line.
  Widget _head(int count) {
    final int selected = _store.selectionIds.length;
    final bool group = selected > 1;
    return ActionRow(
      dense: true,
      label: huiText('Components'),
      moreId: 'hui-rail-more',
      leading: <Widget>[
        AppMenu(
          id: 'hui-rail-add',
          label: huiText('Add'),
          icon: ArcaneIcon.plus(),
          outline: true,
          entries: <AppMenuEntry>[
            for (final String type in huiEditorComponentTypes)
              if (_displayTypes.contains(type)) _addEntry(type),
            const AppMenuEntry.separator(),
            for (final String type in huiEditorComponentTypes)
              if (!_displayTypes.contains(type)) _addEntry(type),
          ],
        ),
        if (group)
          dom.span(<Widget>[
            Component.text(
              huiText('{selected} of {count} selected', <String, Object?>{
                'selected': selected,
                'count': count,
              }),
            ),
          ]),
        InfoButton(
          tip: group
              ? huiText(
                  'Commands act on all {count}. Shift-click a row to add or remove it; Escape clears the selection.',
                  <String, Object?>{'count': selected},
                )
              : huiText(
                  'The nearest hitbox fires; list order breaks an exact-distance tie. Drag a row or use its menu to change it.',
                ),
        ),
      ],
      more: <AppMenuEntry>[railCreateRandomEntry(_store)],
    );
  }

  AppMenuEntry _addEntry(String type) => AppMenuEntry(
    componentTypeLabel(type),
    () => _store.addComponent(type),
    icon: componentTypeIcon(type),
    description: huiText(huiComponentTypeDescriptions[type] ?? ''),
  );

  Widget _row(int index, HuiComponent data, int total) {
    final String id = data.id;
    final String label = id.isEmpty ? huiText('(no id)') : id;
    final String type = data.data.type;
    final bool selected = _store.isSelected(id);
    return RailRow(
      label: label,
      icon: componentTypeIcon(type, active: selected),
      current: selected,
      issue: railIssue(_store.issuesFor(id)),
      menuId: 'hui-rail-row-$index',
      onSelect: () => _select(id),
      onPointerDown: _notePointer,
      entries: <AppMenuEntry>[
        AppMenuEntry(
          huiText('Duplicate'),
          () => _store.duplicateComponent(id),
          icon: ArcaneIcon.copy(),
        ),
        AppMenuEntry(
          huiText("Randomize {toLowerCase}", <String, Object?>{
            'toLowerCase': componentTypeLabel(type).toLowerCase(),
          }),
          () => randomizeMenuComponent(_store, id),
          icon: ArcaneIcon.dices(),
        ),
        const AppMenuEntry.separator(),
        AppMenuEntry(
          huiText('Move up'),
          index == 0 ? null : () => _store.reorder(id, index - 1),
          icon: ArcaneIcon.arrowUp(),
        ),
        AppMenuEntry(
          huiText('Move down'),
          index >= total - 1 ? null : () => _store.reorder(id, index + 1),
          icon: ArcaneIcon.arrowDown(),
        ),
        AppMenuEntry(
          huiText('Move to top'),
          index == 0 ? null : () => _store.reorder(id, 0),
          icon: ArcaneIcon.arrowUpToLine(),
        ),
        AppMenuEntry(
          huiText('Move to bottom'),
          index >= total - 1 ? null : () => _store.reorder(id, total - 1),
          icon: ArcaneIcon.arrowDownToLine(),
        ),
        const AppMenuEntry.separator(),
        AppMenuEntry(
          huiText('Delete'),
          () => _delete(id, label),
          icon: ArcaneIcon.trash2(),
          destructive: true,
        ),
      ],
    );
  }
}
