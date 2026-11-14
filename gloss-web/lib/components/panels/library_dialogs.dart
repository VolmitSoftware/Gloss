/// The library's two small dialogs: a document's runtime id (Advanced) and
/// the folder a document or folder moves to. Each holds what is typed or
/// picked until its primary button or Enter.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../l10n/hui_localizations.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/picker.dart';
import '../../ui/technical_input.dart';

/// Enter in a field acts on its keyup. Acting on keydown closes the field
/// or dialog while the key is still down, focus returns to the button that
/// opened it, and the same key press then presses that button (a row menu
/// opens again, a folder toggles).
class EnterKeys {
  /// Whether Enter went down in the field (not while composing text).
  bool _down = false;

  Map<String, void Function(web.Event)> events({
    required void Function() onEnter,
    void Function()? onEscape,
  }) => <String, void Function(web.Event)>{
    'keydown': (web.Event raw) {
      final web.KeyboardEvent event = raw as web.KeyboardEvent;
      _down = event.key == 'Enter' && !event.isComposing;
      if (event.key != 'Escape' || onEscape == null) return;
      event.preventDefault();
      event.stopPropagation();
      onEscape();
    },
    'keyup': (web.Event raw) {
      if ((raw as web.KeyboardEvent).key != 'Enter' || !_down) return;
      _down = false;
      onEnter();
    },
  };
}

class RuntimeIdDialog extends StatefulWidget {
  const RuntimeIdDialog({
    required this.open,
    required this.value,
    required this.onSave,
    required this.onClose,
    super.key,
  });

  final bool open;

  /// The id it opens with.
  final String value;

  /// Applies the typed id; true closes the dialog, false keeps it open for
  /// another try (the caller says why).
  final bool Function(String runtimeId) onSave;
  final void Function() onClose;

  @override
  State<RuntimeIdDialog> createState() => _RuntimeIdDialogState();
}

class _RuntimeIdDialogState extends State<RuntimeIdDialog> {
  static const String _inputId = 'hui-library-runtime-id';

  late String _text = component.value;
  final EnterKeys _keys = EnterKeys();

  @override
  void didUpdateWidget(RuntimeIdDialog oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.open && !oldComponent.open) _text = component.value;
  }

  void _save() {
    if (component.onSave(_text)) component.onClose();
  }

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-library-runtime-id-dialog',
    open: component.open,
    title: huiText('Change runtime id'),
    onClose: component.onClose,
    footer: DialogFooter(
      cancel: RowAction(huiText('Cancel'), component.onClose),
      primary: RowAction(huiText('Save'), _save),
    ),
    children: <Widget>[
      if (component.open)
        Field(
          label: huiText('Runtime id'),
          forId: _inputId,
          child: dom.span(events: _keys.events(onEnter: _save), <Widget>[
            _RuntimeIdInput(
              id: _inputId,
              value: _text,
              onChanged: (String value) => _text = value,
            ),
          ]),
        ),
    ],
  );
}

/// The runtime id box: an ltr island whatever the page direction, at the
/// dialog's density.
class _RuntimeIdInput extends StatelessWidget {
  const _RuntimeIdInput({
    required this.id,
    required this.value,
    required this.onChanged,
  });

  final String id;
  final String value;
  final void Function(String value) onChanged;

  @override
  Widget build(BuildContext context) => TextInput(
    id: id,
    value: value,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    onChanged: onChanged,
    styles: technicalInputStyles,
    attributes: technicalInputAttributes,
  );
}

class LibraryMoveDialog extends StatefulWidget {
  const LibraryMoveDialog({
    required this.open,
    required this.value,
    required this.destinations,
    required this.onMove,
    required this.onClose,
    super.key,
  });

  final bool open;

  /// The folder it opens on; null is the workspace root.
  final String? value;

  /// The folders it may go to: id and path, sorted.
  final List<PickerOption> destinations;

  /// The picked folder; null is the workspace root.
  final void Function(String? folderId) onMove;
  final void Function() onClose;

  @override
  State<LibraryMoveDialog> createState() => _LibraryMoveDialogState();
}

class _LibraryMoveDialogState extends State<LibraryMoveDialog> {
  static const String _root = '__root__';
  static const String _pickerId = 'hui-library-move-target';

  late String _target = component.value ?? _root;

  @override
  void didUpdateWidget(LibraryMoveDialog oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.open && !oldComponent.open) {
      _target = component.value ?? _root;
    }
  }

  List<PickerOption> _search(String query) {
    final String needle = query.trim().toLowerCase();
    return <PickerOption>[
      for (final PickerOption option in <PickerOption>[
        PickerOption(_root, label: huiText('Workspace root')),
        ...component.destinations,
      ])
        if ((option.label ?? option.value).toLowerCase().contains(needle))
          option,
    ];
  }

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-library-move-dialog',
    open: component.open,
    title: huiText('Move to folder'),
    onClose: component.onClose,
    footer: DialogFooter(
      cancel: RowAction(huiText('Cancel'), component.onClose),
      primary: RowAction(
        huiText('Move'),
        () => component.onMove(_target == _root ? null : _target),
      ),
    ),
    children: <Widget>[
      if (component.open)
        Field(
          label: huiText('Folder'),
          forId: _pickerId,
          child: Picker(
            id: _pickerId,
            value: _target,
            allowCustom: false,
            searchPlaceholder: huiText('Search'),
            onChanged: (String value) => setState(() => _target = value),
            search: _search,
          ),
        ),
    ],
  );
}
