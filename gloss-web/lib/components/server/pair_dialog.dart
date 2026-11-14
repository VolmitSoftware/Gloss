/// Pair a server: the command to run and the field its link goes into. The
/// editor opens it from the Server button while no server is paired, from
/// the File menu and from the command palette; the start page has the same
/// field of its own.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/pairing.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import 'pair_link_form.dart';
import 'start_parts.dart';

class PairDialog extends StatefulWidget {
  const PairDialog({
    required this.open,
    required this.onClose,
    required this.onCode,
    super.key,
  });

  final bool open;
  final void Function() onClose;

  /// Called with the `GLS1.` code; the pairing card takes it from there.
  final void Function(String code) onCode;

  static const String dialogId = 'gloss-pair-dialog';

  @override
  State<PairDialog> createState() => _PairDialogState();
}

class _PairDialogState extends State<PairDialog> {
  static const String _fieldId = 'pair-dialog-link';

  String _text = '';
  String? _error;

  void _submit() {
    final String? error = pairLinkError(_text);
    if (error != null) {
      setState(() => _error = error);
      return;
    }
    final String code = extractPairingCode(_text);
    setState(() {
      _text = '';
      _error = null;
    });
    component.onCode(code);
  }

  @override
  Widget build(BuildContext context) => AppDialog(
    id: PairDialog.dialogId,
    open: component.open,
    title: huiText('Pair a server'),
    onClose: component.onClose,
    footer: DialogFooter(
      cancel: RowAction(huiText('Cancel'), component.onClose),
      primary: RowAction(
        huiText('Pair'),
        _submit,
        attributes: const <String, String>{'data-testid': 'pair-dialog-submit'},
      ),
    ),
    children: <Widget>[
      stack(spacing: 4, <Widget>[
        mutedText(huiText('Run this on the server, then paste its link:')),
        const dom.code(
          attributes: <String, String>{'data-testid': 'pair-command'},
          <Widget>[Text(pairCommand)],
        ),
      ]),
      pairLinkField(
        id: _fieldId,
        label: huiText('Pairing link'),
        value: _text,
        error: _error,
        onChanged: (String value) => setState(() {
          _text = value;
          _error = null;
        }),
        onSubmit: _submit,
      ),
    ],
  );
}
