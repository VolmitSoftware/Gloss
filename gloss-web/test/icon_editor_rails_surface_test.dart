import 'dart:io';

import 'package:test/test.dart';

/// The icon editors and the Components and Elements rails are built from the
/// kit (lib/ui) and stock controls only.
void main() {
  const List<String> iconEditors = <String>[
    'lib/components/inspector/icon_editor.dart',
    'lib/components/inspector/image_picker_grid.dart',
    'lib/components/inspector/entity_picker.dart',
    'lib/components/inspector/player_head_picker.dart',
    'lib/components/inspector/item_picker.dart',
    'lib/components/inspector/block_picker.dart',
    'lib/components/inspector/custom_item_picker.dart',
  ];
  const List<String> rails = <String>[
    'lib/components/panels/components_rail.dart',
    'lib/components/panels/preview_elements_rail.dart',
  ];

  String read(String path) => File(path).readAsStringSync();

  test('no old shared components, raw buttons or title tooltips', () {
    const List<String> banned = <String>[
      "'../common/",
      'inspector_widgets.dart',
      'field_help.dart',
      'registry_picker.dart',
      "import 'reorder_list.dart'",
      'dom.button(',
      'dom.select(',
      "'title':",
      'HuiArmedButton',
      'HuiRowTools',
      'HuiIconButton',
      'HuiSkeleton',
      'HuiEmptyState',
      'ArcaneEmptyState',
      "classes: 'hui-",
    ];
    for (final String path in <String>[...iconEditors, ...rails]) {
      final String source = read(path);
      for (final String needle in banned) {
        expect(source, isNot(contains(needle)), reason: '$path: $needle');
      }
    }
  });

  test('catalog pickers are the kit Picker with sprites', () {
    for (final String path in <String>[
      'lib/components/inspector/item_picker.dart',
      'lib/components/inspector/block_picker.dart',
      'lib/components/inspector/custom_item_picker.dart',
      'lib/components/inspector/entity_picker.dart',
    ]) {
      expect(read(path), contains('Picker('), reason: path);
    }
    expect(
      read('lib/components/inspector/item_picker.dart'),
      contains('image: entry.texture'),
    );
  });

  test('the file input is the only raw input and a button opens it', () {
    final String source = read(
      'lib/components/inspector/image_picker_grid.dart',
    );
    expect("tag: 'input'".allMatches(source), hasLength(1));
    expect(source, contains("'type': 'file'"));
    expect(source, contains('variant: ButtonVariant.outline'));
    expect(source, isNot(contains('dom.label(')));
  });

  test('animated frames reorder through the kit list', () {
    final String source = read('lib/components/inspector/icon_editor.dart');
    expect(source, contains('ReorderList('));
    expect(source, isNot(contains('InspectorSection(')));
    expect(source, contains('FieldGroup('));
  });

  test('IconEditor keeps the constructor the component inspector calls', () {
    final String source = read('lib/components/inspector/icon_editor.dart');
    for (final String parameter in <String>[
      'required this.store',
      'required this.images',
      'required this.catalogs',
      'required this.session',
      'required this.componentId',
      'required this.slot',
      'required this.icon',
      'this.catalogsLoading = false',
      'this.issues = const <HuiIssue>[]',
    ]) {
      expect(source, contains(parameter));
    }
  });

  test('rails: add menu, reorder list, one row menu and a context menu', () {
    final String components = read(rails.first);
    final String elements = read(rails.last);
    for (final String source in <String>[components, elements]) {
      expect(source, contains('ActionRow('));
      expect(source, contains("label: huiText('Add')"));
      expect(source, contains('ReorderList('));
      expect(source, contains('RailRow('));
      expect(source, contains('EmptyState('));
      expect(source, contains('railCreateRandomEntry('));
    }
    expect(components, contains('AppContextMenu('));
    expect(components, contains('ListRow('));
    expect(components, contains('selected: current'));
    expect(components, contains("'data-rail-row': ''"));
    // Reorders keep their store calls, one undo step each.
    expect(components, contains('_store.reorder(components[from].id, to)'));
    expect(elements, contains('onReorder: store.reorderPreviewElement'));
    // Deletes act at once and offer Undo.
    expect(components, contains('store.performUndo()'));
    expect(components, contains('_store.toggleInSelection(id)'));
  });
}
