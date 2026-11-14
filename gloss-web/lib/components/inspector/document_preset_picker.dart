/// The document's preset from the workspace's preset catalog, last in every
/// inspector and Advanced only (the document inspector frame wraps it).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/document_preset_choice.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';

const String _presetFieldId = 'hui-document-preset';

class DocumentPresetPicker extends StatelessWidget {
  const DocumentPresetPicker({required this.store, super.key});
  final EditorStore store;

  @override
  Widget build(BuildContext context) {
    final DocumentPresetChoice? choice = documentPresetChoice(store);
    if (choice == null) return const Component.fragment(<Widget>[]);
    final String? unreadable = choice.error;
    return AppSection(
      title: huiText('Inheritance'),
      memoryKey: 'document.preset',
      children: <Widget>[
        Field(
          label: huiText('Preset'),
          forId: _presetFieldId,
          tip: huiText(
            'Authored document fields override the selected preset. Clear an override in Code view to inherit it.',
          ),
          error: unreadable != null
              ? huiText(
                  'Preset catalog cannot be read: {message}',
                  <String, Object?>{'message': unreadable},
                )
              : store.codeError,
          child: ArcaneSelect(
            id: _presetFieldId,
            value: choice.selected,
            size: controlSize(DensityScope.of(context)),
            fullWidth: true,
            disabled: unreadable != null,
            options: <ArcaneSelectOption>[
              ArcaneSelectOption(
                value: '',
                label: huiText('Collection defaults only'),
              ),
              for (final String name in choice.names)
                ArcaneSelectOption(value: name, label: name),
            ],
            onChanged: (String value) =>
                store.applyCode(choice.withPreset(value)),
          ),
        ),
      ],
    );
  }
}
