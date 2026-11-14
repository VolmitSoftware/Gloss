/// Left rail for a container-preview document: the ordered element list.
///
/// Paint order is document order (later elements draw in front at equal
/// `z`), so reordering is a document edit like it is for a menu component.
/// Delete lives here, not on the pixel-card surface; it acts at once and
/// Undo brings the element back.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component, ListenableBuilder;

import '../../config/defaults.dart';
import '../../model/preview_doc.dart';
import '../../services/showcase_randomizer.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/empty_state.dart';
import '../../ui/menu.dart';
import '../../ui/reorder_list.dart';
import 'components_rail.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PreviewElementsRail extends StatelessWidget {
  const PreviewElementsRail({required this.store, super.key});

  final EditorStore store;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: store,
    builder: (BuildContext _) => _body(),
  );

  Widget _body() {
    final HuiPreviewDoc? doc = store.previewDoc;
    if (doc == null) return const Component.fragment(<Widget>[]);
    final List<HuiPreviewElement> elements = doc.elements;
    return DenseBox(
      attributes: const <String, String>{'data-rail': 'elements'},
      children: <Widget>[
        ActionRow(
          dense: true,
          label: huiText('Elements'),
          moreId: 'hui-elements-more',
          leading: <Widget>[
            AppMenu(
              id: 'hui-elements-add',
              label: huiText('Add'),
              icon: ArcaneIcon.plus(),
              outline: true,
              entries: <AppMenuEntry>[
                for (final String type in previewElementTypes)
                  AppMenuEntry(
                    _typeLabel(type),
                    () => store.addPreviewElement(type),
                    icon: _typeIcon(type),
                    description: huiText(
                      previewElementTypeDescriptions[type] ?? '',
                    ),
                  ),
              ],
            ),
          ],
          more: <AppMenuEntry>[railCreateRandomEntry(store)],
        ),
        if (elements.isEmpty)
          EmptyState(
            message: huiText('No elements yet'),
            icon: ArcaneIcon.layers(),
          )
        else
          ReorderList(
            label: huiText('Elements'),
            itemCount: elements.length,
            tools: false,
            onReorder: store.reorderPreviewElement,
            itemBuilder: (int index) =>
                _row(index, elements[index], elements.length),
          ),
      ],
    );
  }

  Widget _row(int index, HuiPreviewElement element, int total) {
    final String label = '${index + 1}. ${_typeLabel(element.type)}';
    return RailRow(
      label: label,
      icon: _typeIcon(element.type),
      current: store.previewSelectedIndex == index,
      issue: railIssue(store.issuesForPreviewElement(index)),
      menuId: 'hui-elements-row-$index',
      onSelect: () => store.selectPreviewElement(index),
      entries: <AppMenuEntry>[
        AppMenuEntry(
          huiText('Duplicate'),
          () => store.duplicatePreviewElement(index),
          icon: ArcaneIcon.copy(),
        ),
        AppMenuEntry(
          huiText("Randomize {toLowerCase}", <String, Object?>{
            'toLowerCase': _typeLabel(element.type).toLowerCase(),
          }),
          () => randomizePreviewElement(store, index),
          icon: ArcaneIcon.dices(),
        ),
        const AppMenuEntry.separator(),
        AppMenuEntry(
          huiText('Move up'),
          index == 0
              ? null
              : () => store.reorderPreviewElement(index, index - 1),
          icon: ArcaneIcon.arrowUp(),
        ),
        AppMenuEntry(
          huiText('Move down'),
          index >= total - 1
              ? null
              : () => store.reorderPreviewElement(index, index + 1),
          icon: ArcaneIcon.arrowDown(),
        ),
        AppMenuEntry(
          huiText('Move to top'),
          index == 0 ? null : () => store.reorderPreviewElement(index, 0),
          icon: ArcaneIcon.arrowUpToLine(),
        ),
        AppMenuEntry(
          huiText('Move to bottom'),
          index >= total - 1
              ? null
              : () => store.reorderPreviewElement(index, total - 1),
          icon: ArcaneIcon.arrowDownToLine(),
        ),
        const AppMenuEntry.separator(),
        AppMenuEntry(
          huiText('Delete'),
          () {
            store.deletePreviewElement(index);
            railDeletedToast(store, label);
          },
          icon: ArcaneIcon.trash2(),
          destructive: true,
        ),
      ],
    );
  }

  static ArcaneGlyph _typeIcon(String type) => switch (type) {
    'panel' => ArcaneIcon.square(),
    'cell' => ArcaneIcon.grid2x2(),
    'slot' => ArcaneIcon.package(),
    'label' => ArcaneIcon.typeIcon(),
    _ => ArcaneIcon.circleAlert(),
  };

  static String _typeLabel(String type) => switch (type) {
    'panel' => huiText('Panel'),
    'cell' => huiText('Cell'),
    'slot' => huiText('Slot'),
    'label' => huiText('Label'),
    _ => type.isEmpty ? huiText('(no type)') : type,
  };
}
