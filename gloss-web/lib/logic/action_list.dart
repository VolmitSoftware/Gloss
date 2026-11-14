/// The action list's pure parts: the one line a closed row shows, and the
/// open row's place while rows move or go.
library;

import '../model/model.dart';

String _textOf(Object? value) => value is String ? value : '';

/// What a closed row shows after its type: the command, sound, target and
/// so on. Empty when the type says it all.
String actionSummary(HuiAction action) => switch (action) {
  HuiCommandAction(:final String command) => command,
  HuiSoundAction(:final String sound) => sound,
  HuiMessageAction(:final String message) => message.split('\n').first,
  HuiTeleportAction(:final String world) => world,
  HuiConnectAction(:final String server) => server,
  final HuiNavigateAction navigation =>
    navigation.requiresTarget ? navigation.target : navigation.mode,
  final HuiRuntimeAction runtime => switch (runtime.type) {
    'dialog' => _textOf(runtime.extras['title']),
    'call' => _textOf(runtime.extras['action']),
    _ => '',
  },
};

/// The open row's index after the row at [from] moved to [to]
/// (`removeAt(from)` then `insert(to, row)`).
int? openRowAfterMove(int? open, {required int from, required int to}) {
  if (open == null) return null;
  if (open == from) return to;
  int index = open;
  if (from < index) index--;
  if (to <= index) index++;
  return index;
}

/// The open row's index after the row at [removed] went.
int? openRowAfterRemove(int? open, {required int removed}) {
  if (open == null || open == removed) return null;
  return open > removed ? open - 1 : open;
}
