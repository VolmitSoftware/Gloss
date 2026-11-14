/// The field a pairing link is pasted into, with Pair. Used on the start
/// page, on the pairing card when a link is spent, and in the Server sheet
/// (the browser workspace's, and a paired one's when the pairing ended).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../services/server/pairing.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import 'pair_copy.dart';

/// Why [text] is no pairing link, or null when it holds one.
String? pairLinkError(String text) {
  if (extractPairingCode(text).startsWith(pairingPrefix)) return null;
  return text.trim().isEmpty
      ? huiText('Paste the link from {command}.', <String, Object?>{
          'command': '/gloss editor pair',
        })
      : notALink.title;
}

/// The pairing link text box under its label.
Widget pairLinkField({
  required String id,
  required String label,
  required String value,
  required void Function(String value) onChanged,
  required void Function() onSubmit,
  String? hint,
  String? error,
}) => Field(
  label: label,
  forId: id,
  hint: hint,
  error: error,
  child: TextInput(
    id: id,
    value: value,
    size: controlSize(Density.regular),
    placeholder: 'https://.../#/pair/GLS1...',
    fullWidth: true,
    attributes: const <String, String>{
      'autocomplete': 'off',
      'spellcheck': 'false',
      'autocapitalize': 'off',
    },
    onChanged: onChanged,
    onSubmitted: (String _) => onSubmit(),
  ),
);

class PairLinkForm extends StatefulWidget {
  const PairLinkForm({
    required this.onCode,
    this.id = 'pair-link',
    this.label,
    this.hint,
    this.secondary = const <RowAction>[],
    this.cancel,
    this.lead = true,
    super.key,
  });

  /// Called with the `GLS1.` code from what was pasted.
  final void Function(String code) onCode;

  /// The text box's id; the label names it.
  final String id;

  /// "Pairing link" unless given (the spent card says "New link").
  final String? label;
  final String? hint;

  /// Buttons before Pair in the form's footer.
  final List<RowAction> secondary;

  /// Cancel, before Pair.
  final RowAction? cancel;

  /// Pair is the main action; off where another action leads the page.
  final bool lead;

  @override
  State<PairLinkForm> createState() => _PairLinkFormState();
}

class _PairLinkFormState extends State<PairLinkForm> {
  String _text = '';
  String? _error;

  void _submit() {
    final String? error = pairLinkError(_text);
    if (error != null) {
      setState(() => _error = error);
      return;
    }
    component.onCode(extractPairingCode(_text));
  }

  RowAction get pair => RowAction(
    huiText('Pair'),
    _submit,
    attributes: <String, String>{'data-testid': '${component.id}-submit'},
  );

  @override
  Widget build(BuildContext context) => dom.div(
    attributes: <String, String>{'data-pair-form': component.id},
    <Widget>[
      pairLinkField(
        id: component.id,
        label: component.label ?? huiText('Pairing link'),
        value: _text,
        hint: component.hint,
        error: _error,
        onChanged: (String value) => setState(() {
          _text = value;
          _error = null;
        }),
        onSubmit: _submit,
      ),
      FormFooter(
        secondary: <RowAction>[
          ...component.secondary,
          if (!component.lead) pair,
        ],
        cancel: component.cancel,
        primary: component.lead ? pair : null,
      ),
    ],
  );
}
