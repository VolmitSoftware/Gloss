/// Toasts, all through here: one at a time, in the top bar's strip where
/// they cover no inspector, footer, save bar or preview, and gone in a few
/// seconds. On the library's Sonner with Gloss's lifetimes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

/// How long a toast stays, in milliseconds: a note, a warning, a failure,
/// and any toast with a button (Undo) the user needs time to reach.
const int toastNoteMs = 2500;
const int toastWarningMs = 4000;
const int toastErrorMs = 5000;
const int toastActionMs = 6000;

String toastSuccess(String message, {ToastAction? action}) =>
    ArcaneSonner.success(
      message,
      duration: action == null ? toastNoteMs : toastActionMs,
      action: action,
    );

String toastInfo(String message, {ToastAction? action}) => ArcaneSonner.info(
  message,
  duration: action == null ? toastNoteMs : toastActionMs,
  action: action,
);

String toastWarning(String message, {ToastAction? action}) =>
    ArcaneSonner.warning(
      message,
      duration: action == null ? toastWarningMs : toastActionMs,
      action: action,
    );

String toastError(String message, {ToastAction? action}) =>
    ArcaneSonner.error(
      message,
      duration: action == null ? toastErrorMs : toastActionMs,
      action: action,
    );

/// The one toaster: the newest toast only, centred on the 3rem top bar
/// (`--shadcn-toast-padding` in 00-kit.css keeps a one-line toast inside
/// it); clicks pass through its body to the bar below.
class AppToaster extends StatelessWidget {
  const AppToaster({super.key});

  @override
  Widget build(BuildContext context) => const ArcaneSonner(
    position: ToastPosition.topCenter,
    maxVisible: 1,
    offset: 16,
    edgeOffset: 5,
    passThrough: true,
  );
}
