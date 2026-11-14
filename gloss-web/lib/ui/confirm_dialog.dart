/// A question before something Undo cannot bring back (a deleted folder, a
/// disconnected server link, a replaced workspace): an alert dialog on
/// shadcn's dialog, with Cancel and one confirm button (red when it
/// destroys). Things Undo restores just act and toast with Undo instead.
/// Use [ConfirmDialog] where a widget owns the open state, or [askToConfirm]
/// for a `Future<bool>` from a handler; the app root mounts the
/// [ConfirmHost] that shows those, above every sheet.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../l10n/hui_localizations.dart';
import 'action_row.dart';
import 'dialog.dart';

class ConfirmDialog extends StatelessWidget {
  const ConfirmDialog({
    required this.title,
    required this.confirmLabel,
    required this.onConfirm,
    required this.onCancel,
    this.message,
    this.destructive = false,
    this.open = true,
    super.key,
  });

  /// The question ("Delete the folder Lobby?").
  final String title;

  /// What happens, in one sentence ("Its 4 documents go too.").
  final String? message;

  /// The verb on the button ("Delete folder").
  final String confirmLabel;
  final bool destructive;
  final bool open;
  final void Function() onConfirm;
  final void Function() onCancel;

  @override
  Widget build(BuildContext context) => AppDialog(
    open: open,
    alert: true,
    title: title,
    onClose: onCancel,
    footer: DialogFooter(
      cancel: RowAction(huiText('Cancel'), onCancel),
      primary: RowAction(
        confirmLabel,
        onConfirm,
        destructive: destructive,
        attributes: const <String, String>{'data-confirm': ''},
      ),
    ),
    children: <Widget>[
      if (message != null)
        dom.p(classes: 'ui-confirm-message', <Widget>[
          Component.text(message!),
        ]),
    ],
  );
}

class _Request {
  _Request(this.title, this.message, this.confirmLabel, this.destructive);

  final String title;
  final String? message;
  final String confirmLabel;
  final bool destructive;
  final Completer<bool> answer = Completer<bool>();
}

final List<_Request> _waiting = <_Request>[];
final List<void Function()> _hosts = <void Function()>[];

void _notifyHosts() {
  for (final void Function() host in List<void Function()>.of(_hosts)) {
    host();
  }
}

/// Asks the question in a dialog; true when confirmed. Needs a mounted
/// [ConfirmHost] (the app root has one); without one the answer is false.
Future<bool> askToConfirm({
  required String title,
  required String confirmLabel,
  String? message,
  bool destructive = false,
}) => confirmTicket(
  title: title,
  confirmLabel: confirmLabel,
  message: message,
  destructive: destructive,
).answer;

/// A question [askToConfirm] shows, which its asker can take back.
class ConfirmTicket {
  ConfirmTicket._(this._request);

  final _Request _request;

  /// True when confirmed; false when cancelled or taken back.
  Future<bool> get answer => _request.answer.future;

  /// Closes the question unanswered (false), when what asked it is gone.
  void withdraw() {
    if (_request.answer.isCompleted) return;
    _waiting.remove(_request);
    _request.answer.complete(false);
    _notifyHosts();
  }
}

/// [askToConfirm] with a ticket to take the question back. The dialog
/// shows at the shell's [ConfirmHost], above any sheet.
ConfirmTicket confirmTicket({
  required String title,
  required String confirmLabel,
  String? message,
  bool destructive = false,
}) {
  final _Request request = _Request(title, message, confirmLabel, destructive);
  if (_hosts.isEmpty) {
    // Nothing can show it: answer no rather than wait forever.
    request.answer.complete(false);
    return ConfirmTicket._(request);
  }
  _waiting.add(request);
  _notifyHosts();
  return ConfirmTicket._(request);
}

/// Shows the questions [askToConfirm] queues, one at a time.
class ConfirmHost extends StatefulWidget {
  const ConfirmHost({super.key});

  @override
  State<ConfirmHost> createState() => _ConfirmHostState();
}

class _ConfirmHostState extends State<ConfirmHost> {
  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void initState() {
    super.initState();
    _hosts.add(_changed);
  }

  @override
  void dispose() {
    _hosts.remove(_changed);
    super.dispose();
  }

  void _answer(bool yes) {
    if (_waiting.isEmpty) return;
    final _Request request = _waiting.removeAt(0);
    if (!request.answer.isCompleted) request.answer.complete(yes);
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (_waiting.isEmpty) return const Component.fragment(<Widget>[]);
    final _Request request = _waiting.first;
    return ConfirmDialog(
      key: ValueKey<int>(identityHashCode(request)),
      title: request.title,
      message: request.message,
      confirmLabel: request.confirmLabel,
      destructive: request.destructive,
      onConfirm: () => _answer(true),
      onCancel: () => _answer(false),
    );
  }
}
