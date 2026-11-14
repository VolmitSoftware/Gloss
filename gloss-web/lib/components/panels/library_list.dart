/// The rules behind the library list, kept free of the DOM: what the search
/// box finds, the order rows sit in, how root documents fall under the kind
/// group headings and when a row shows its runtime id.
library;

import '../../config/kind_groups.dart';
import '../../state/workspace.dart';

/// Root documents of one kind group, sorted.
class LibraryGroup {
  const LibraryGroup(this.group, this.documents);

  final KindGroup group;
  final List<WorkspaceDoc> documents;
}

/// Whether [doc] matches [query] by title, runtime id or kind label,
/// ignoring case and outer spaces. A blank query matches every document.
bool libraryDocumentMatches(WorkspaceDoc doc, String query) {
  final String needle = query.trim().toLowerCase();
  if (needle.isEmpty) return true;
  final KindInfo info = kindInfo(doc.kind);
  return <String?>[
    doc.title,
    doc.runtimeId,
    info.label,
    info.singular,
  ].any((String? text) => text != null && text.toLowerCase().contains(needle));
}

/// Kind group first, then the kind's place in [kindInfos], then the title.
int compareLibraryDocuments(WorkspaceDoc a, WorkspaceDoc b) {
  final int group = groupOf(a.kind).index.compareTo(groupOf(b.kind).index);
  if (group != 0) return group;
  final int kind = _kindOrder(a.kind).compareTo(_kindOrder(b.kind));
  if (kind != 0) return kind;
  final int title = a.title.toLowerCase().compareTo(b.title.toLowerCase());
  return title != 0 ? title : a.id.compareTo(b.id);
}

int _kindOrder(WorkspaceDocKind kind) =>
    kindInfos.indexWhere((KindInfo info) => info.kind == kind);

/// [documents] under their kind groups, in group order; groups with none
/// are left out.
List<LibraryGroup> groupLibraryDocuments(Iterable<WorkspaceDoc> documents) {
  final List<WorkspaceDoc> sorted = List<WorkspaceDoc>.of(documents)
    ..sort(compareLibraryDocuments);
  return <LibraryGroup>[
    for (final KindGroup group in KindGroup.values)
      if (sorted.any((WorkspaceDoc doc) => groupOf(doc.kind) == group))
        LibraryGroup(group, <WorkspaceDoc>[
          for (final WorkspaceDoc doc in sorted)
            if (groupOf(doc.kind) == group) doc,
        ]),
  ];
}

/// Whether a row shows the runtime id under its title: never when it is the
/// title exactly; in Advanced whenever it is written differently; otherwise
/// only when it reads differently ("New menu" and "new-menu" read the same).
bool showsRuntimeId(WorkspaceDoc doc, {required bool advanced}) {
  final String? id = doc.runtimeId;
  if (id == null || id.trim().isEmpty || id == doc.title) return false;
  return advanced || _words(id) != _words(doc.title);
}

String _words(String text) => text
    .toLowerCase()
    .split(RegExp(r'[^a-z0-9]+'))
    .where((String word) => word.isNotEmpty)
    .join(' ');

enum LibraryLineKind { folder, group, document }

/// One line of the library as drawn: a folder, a kind group heading or a
/// document, flattened so the rail can draw only the lines in view.
class LibraryLine {
  const LibraryLine._(
    this.kind,
    this.depth, {
    this.folder,
    this.group,
    this.doc,
    this.open = false,
    this.count = 0,
    this.dropFolderId,
    this.rows = 1,
    this.namesKind = false,
  });

  final LibraryLineKind kind;
  final int depth;
  final WorkspaceFolder? folder;
  final KindGroup? group;
  final WorkspaceDoc? doc;

  /// A folder or group showing its lines.
  final bool open;

  /// What a folder holds (listed folders and documents) or a group's
  /// documents.
  final int count;

  /// Where a document dropped on this line goes; null is the root.
  final String? dropFolderId;

  /// A document in a folder that lists more than one kind, so its row names
  /// the kind; under a kind group or in a one-kind folder the heading says it.
  final bool namesKind;

  /// The line's height in rows: a document with its runtime id under the
  /// title takes one and a half.
  final double rows;

  /// The id the line's row button and keyboard focus use.
  String get id => switch (kind) {
    LibraryLineKind.folder => folder!.id,
    LibraryLineKind.group => 'group-${group!.name}',
    LibraryLineKind.document => doc!.id,
  };
}

/// The library's lines in order: root folders by title (each open folder
/// followed by its folders, then its documents, one level deeper), then the
/// root documents under their kind group headings.
///
/// [filtering] (a search or a kind scope) hides folders that hold no listed
/// document at any depth; [searching] also opens every folder.
List<LibraryLine> libraryLines({
  required List<WorkspaceFolder> folders,
  required List<WorkspaceDoc> docs,
  required bool Function(WorkspaceDoc doc) listed,
  required bool filtering,
  required bool searching,
  required Set<String> expanded,
  required bool Function(KindGroup group) groupOpen,
  required bool advanced,
}) {
  final Map<String?, List<WorkspaceFolder>> childFolders =
      <String?, List<WorkspaceFolder>>{};
  for (final WorkspaceFolder folder in folders) {
    childFolders
        .putIfAbsent(folder.parentId, () => <WorkspaceFolder>[])
        .add(folder);
  }
  for (final List<WorkspaceFolder> list in childFolders.values) {
    list.sort(
      (WorkspaceFolder a, WorkspaceFolder b) =>
          a.title.toLowerCase().compareTo(b.title.toLowerCase()),
    );
  }
  final Map<String?, List<WorkspaceDoc>> listedIn =
      <String?, List<WorkspaceDoc>>{};
  final Set<String?> modeHidden = <String?>{};
  for (final WorkspaceDoc doc in docs) {
    if (!listedInMode(doc, advanced: advanced)) {
      modeHidden.add(doc.folderId);
    } else if (listed(doc)) {
      listedIn.putIfAbsent(doc.folderId, () => <WorkspaceDoc>[]).add(doc);
    }
  }
  for (final List<WorkspaceDoc> list in listedIn.values) {
    list.sort(compareLibraryDocuments);
  }

  final Map<String, bool> holds = <String, bool>{};
  bool holdsListed(String id, Set<String> seen) {
    final bool? known = holds[id];
    if (known != null) return known;
    if (!seen.add(id)) return false;
    final bool result =
        (listedIn[id]?.isNotEmpty ?? false) ||
        (childFolders[id] ?? const <WorkspaceFolder>[]).any(
          (WorkspaceFolder child) => holdsListed(child.id, seen),
        );
    return holds[id] = result;
  }

  final Map<String, bool> hides = <String, bool>{};
  bool holdsModeHidden(String id, Set<String> seen) {
    final bool? known = hides[id];
    if (known != null) return known;
    if (!seen.add(id)) return false;
    final bool result =
        modeHidden.contains(id) ||
        (childFolders[id] ?? const <WorkspaceFolder>[]).any(
          (WorkspaceFolder child) => holdsModeHidden(child.id, seen),
        );
    return hides[id] = result;
  }

  // An empty folder still shows; one whose documents this mode all leaves
  // out does not.
  bool shown(WorkspaceFolder folder) =>
      holdsListed(folder.id, <String>{}) ||
      (!filtering && !holdsModeHidden(folder.id, <String>{}));

  double rowsOf(WorkspaceDoc doc) =>
      showsRuntimeId(doc, advanced: advanced) ? 1.5 : 1;

  final List<LibraryLine> lines = <LibraryLine>[];
  final Set<String> drawn = <String>{};
  void addFolder(WorkspaceFolder folder, int depth) {
    if (!drawn.add(folder.id)) return;
    final List<WorkspaceFolder> kids = <WorkspaceFolder>[
      for (final WorkspaceFolder child
          in childFolders[folder.id] ?? const <WorkspaceFolder>[])
        if (shown(child)) child,
    ];
    final List<WorkspaceDoc> own =
        listedIn[folder.id] ?? const <WorkspaceDoc>[];
    final bool mixed = own.any(
      (WorkspaceDoc doc) => doc.kind != own.first.kind,
    );
    final bool open = searching || expanded.contains(folder.id);
    lines.add(
      LibraryLine._(
        LibraryLineKind.folder,
        depth,
        folder: folder,
        open: open,
        count: kids.length + own.length,
        dropFolderId: folder.id,
      ),
    );
    if (!open) return;
    for (final WorkspaceFolder child in kids) {
      addFolder(child, depth + 1);
    }
    for (final WorkspaceDoc doc in own) {
      lines.add(
        LibraryLine._(
          LibraryLineKind.document,
          depth + 1,
          doc: doc,
          dropFolderId: folder.id,
          rows: rowsOf(doc),
          namesKind: mixed,
        ),
      );
    }
  }

  for (final WorkspaceFolder folder
      in childFolders[null] ?? const <WorkspaceFolder>[]) {
    if (shown(folder)) addFolder(folder, 0);
  }
  for (final LibraryGroup group in groupLibraryDocuments(
    listedIn[null] ?? const <WorkspaceDoc>[],
  )) {
    final bool open = groupOpen(group.group);
    lines.add(
      LibraryLine._(
        LibraryLineKind.group,
        0,
        group: group.group,
        open: open,
        count: group.documents.length,
      ),
    );
    if (!open) continue;
    for (final WorkspaceDoc doc in group.documents) {
      lines.add(
        LibraryLine._(LibraryLineKind.document, 0, doc: doc, rows: rowsOf(doc)),
      );
    }
  }
  return lines;
}

/// The list's view: from [top] for [height], in rows from the top of the
/// list.
class LibraryView {
  const LibraryView(this.top, this.height);

  final double top;
  final double height;
}

/// The view before the first measure: tall enough to fill a full height
/// drawer, so it never opens on a short list.
const LibraryView initialLibraryView = LibraryView(0, 40);

/// The view of the element that scrolls the list: its [scrollTop] and
/// [clientHeight], the list's top inside its content ([listTop]), and one
/// row in pixels. A scroller with no height (a closed drawer) shows nothing
/// to measure, so the [previous] view stays.
LibraryView libraryView({
  required double scrollTop,
  required double clientHeight,
  required double listTop,
  required double rowPx,
  required LibraryView previous,
}) {
  if (clientHeight <= 0 || rowPx <= 0) return previous;
  return LibraryView((scrollTop - listTop) / rowPx, clientHeight / rowPx);
}

/// The lines to draw: [first] to [end] (exclusive), with [before] and
/// [after] rows of space standing in for the ones left out.
class LibraryWindow {
  const LibraryWindow(this.first, this.end, this.before, this.after);

  final int first;
  final int end;
  final double before;
  final double after;
}

/// The lines of [rows] (heights in rows) that meet the view from [viewTop]
/// for [viewHeight] rows, plus [overscan] lines on each side.
LibraryWindow libraryWindow(
  List<double> rows, {
  required double viewTop,
  required double viewHeight,
  int overscan = 8,
}) {
  final double viewEnd = viewTop + viewHeight;
  int first = rows.length;
  int end = rows.length;
  double offset = 0;
  for (int index = 0; index < rows.length; index++) {
    final double next = offset + rows[index];
    if (first == rows.length && next > viewTop) first = index;
    if (offset >= viewEnd) {
      end = index;
      break;
    }
    offset = next;
  }
  if (first == rows.length) first = rows.isEmpty ? 0 : rows.length - 1;
  first = (first - overscan).clamp(0, rows.length);
  end = (end + overscan).clamp(first, rows.length);
  double before = 0;
  for (int index = 0; index < first; index++) {
    before += rows[index];
  }
  double after = 0;
  for (int index = end; index < rows.length; index++) {
    after += rows[index];
  }
  return LibraryWindow(first, end, before, after);
}
