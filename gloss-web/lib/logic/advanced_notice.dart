/// What Simple says when documents arrive that only Advanced lists: an
/// import, a dropped file, a workspace bundle, or the paired server's
/// listing and changes. One sentence with the count, beside one Switch to
/// Advanced action that opens [AdvancedNotice.landing]. Pure Dart, so the
/// counting and the wording are unit tested.
library;

import '../config/kind_groups.dart';
import '../l10n/hui_localizations.dart';
import '../state/workspace.dart';

/// One document an import or the server wrote, with the kind it holds now
/// (an import can change the kind of the document it replaced).
typedef ArrivedDocument = ({String id, WorkspaceDocKind kind});

/// Where documents came from.
enum Arrival {
  /// Import JSON (a file or pasted text), a dropped file, a workspace bundle.
  imported,

  /// The paired server: its listing after pairing, or a change made there.
  server,
}

/// The sentence and the document Switch to Advanced opens.
final class AdvancedNotice {
  const AdvancedNotice(this.message, this.landing);

  final String message;

  /// The first arrived document only Advanced lists.
  final String landing;
}

/// The notice for [documents], or null when Simple lists all of them.
AdvancedNotice? advancedNotice(
  Arrival arrival,
  List<ArrivedDocument> documents,
) {
  final List<ArrivedDocument> hidden = <ArrivedDocument>[
    for (final ArrivedDocument document in documents)
      if (kindIsAdvanced(document.kind)) document,
  ];
  if (hidden.isEmpty) return null;
  final int count = hidden.length;
  final String message = switch (arrival) {
    Arrival.imported when count == documents.length => huiPlural(
      'mode.imported_in_advanced',
      count,
      oneEnglish: '{count} imported document is in Advanced.',
      otherEnglish: '{count} imported documents are in Advanced.',
    ),
    Arrival.imported => huiPlural(
      'mode.imported_some_in_advanced',
      count,
      oneEnglish: '{count} of {total} imported documents is in Advanced.',
      otherEnglish: '{count} of {total} imported documents are in Advanced.',
      arguments: <String, Object?>{'total': documents.length},
    ),
    Arrival.server => huiPlural(
      'mode.new_in_advanced',
      count,
      oneEnglish: '{count} new document is in Advanced.',
      otherEnglish: '{count} new documents are in Advanced.',
    ),
  };
  return AdvancedNotice(message, hidden.first.id);
}

/// What an empty Simple library and centre say when the workspace holds
/// only documents Advanced lists.
String hiddenDocumentsText(int count) => huiPlural(
  'mode.in_advanced',
  count,
  oneEnglish: '{count} document is in Advanced.',
  otherEnglish: '{count} documents are in Advanced.',
);

/// The newest document of [docs] only Advanced lists, or null when Simple
/// lists them all: where Switch to Advanced lands from an empty Simple view.
String? hiddenLanding(List<WorkspaceDoc> docs) {
  WorkspaceDoc? newest;
  for (final WorkspaceDoc doc in docs) {
    if (listedInMode(doc, advanced: false)) continue;
    if (newest == null || doc.updatedAt > newest.updatedAt) newest = doc;
  }
  return newest?.id;
}

/// Counts arrivals of one source together when each comes within [window]
/// of the last, so files dropped at once, or a listing written in batches,
/// read as one import or one listing; a later import starts its own count.
/// A document written twice counts once.
final class ArrivalNotices {
  ArrivalNotices({required this.window, DateTime Function()? clock})
    : _clock = clock ?? DateTime.now;

  /// The longest pause between arrivals of one batch.
  final Duration window;
  final DateTime Function() _clock;

  Arrival? _source;
  DateTime? _at;
  final Map<String, ArrivedDocument> _documents = <String, ArrivedDocument>{};

  /// Adds [documents] and returns the notice for everything counted so
  /// far, or null when Simple lists all of it.
  AdvancedNotice? add(Arrival arrival, List<ArrivedDocument> documents) {
    final DateTime now = _clock();
    final DateTime? at = _at;
    if (arrival != _source || at == null || now.difference(at) > window) {
      _documents.clear();
      _source = arrival;
    }
    _at = now;
    for (final ArrivedDocument document in documents) {
      _documents[document.id] = document;
    }
    return advancedNotice(arrival, _documents.values.toList());
  }
}
