/// The library: every document of the workspace, found by search or kind,
/// in folders and under the six kind group headings. One New button opens
/// the grouped New dialog; each row has one actions menu, also on
/// right-click. Kinds with in-document contents (a menu's components) add a
/// Contents tab beside it. Workspace-wide actions (bundles, erasing local
/// data) live in the top bar's File menu.
///
/// The list is drawn as flat lines (library_list.dart) and only the lines
/// near the pane's view are in the page, so a workspace of thousands of
/// documents costs the same to draw as a small one. Arrow keys, Home and End
/// move between rows and scroll the list; Left and Right close and open
/// folders and groups.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../config/defaults.dart';
import '../../config/kind_groups.dart';
import '../../doctype/doctype.dart';
import '../../l10n/hui_localizations.dart';
import '../../logic/advanced_notice.dart' show hiddenDocumentsText;
import '../../services/clipboard.dart';
import '../../services/editor_sync.dart';
import '../../services/workspace_location.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/density.dart';
import '../../ui/empty_state.dart';
import '../../ui/skeleton.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/pane_header.dart';
import '../../ui/picker.dart';
import '../../ui/sheet.dart';
import '../../ui/tabs.dart';
import '../dialogs/new_document_dialog.dart';
import 'library_dialogs.dart';
import 'library_list.dart';
import 'library_sync.dart';
import '../../ui/toast.dart';

const String _filterGroup = 'library-kind';
const String _renameInputId = 'hui-library-rename';

class EditorRail extends StatefulWidget {
  const EditorRail({
    required this.store,
    required this.contents,
    required this.onNewDocument,
    this.newDocumentDialogOpen = false,
    this.syncBinding,
    this.serverBadge,
    this.listing = false,
    super.key,
  });

  final EditorStore store;

  /// The Contents tab (a menu's components), shown for kinds that have one.
  final Widget contents;

  /// Opens the New dialog with the rail's create callback, which creates in
  /// the rail's target folder under the sync id rules.
  final void Function(void Function(NewDocumentChoice choice) create)
  onNewDocument;
  final bool newDocumentDialogOpen;
  final EditorSyncBinding? syncBinding;

  /// The server state of a document, at the end of its row, when paired.
  final Widget? Function(String documentId)? serverBadge;

  /// The server's first list is on its way: loading rows, not the empty
  /// workspace's call to create something.
  final bool listing;

  @override
  State<EditorRail> createState() => _EditorRailState();
}

class _EditorRailState extends State<EditorRail> {
  bool _contentsTab = false;
  final Set<String> _expanded = <String>{};
  String? _selectedId;
  String? _lastActiveId;
  String _query = '';

  /// The row whose title is edited in place, and the text typed so far.
  String? _renamingId;
  String _renameText = '';

  /// The document the runtime id dialog changes.
  String? _runtimeIdDocId;

  /// The document or folder the Move dialog moves.
  String? _movingId;

  /// The document being dragged and where it would land ('' is the root).
  String? _draggingId;
  String? _dropTarget;

  final EnterKeys _renameKeys = EnterKeys();

  /// Root kind groups the user closed.
  final Set<KindGroup> _closedGroups = <KindGroup>{};

  /// Bumped when a folder or group opens or closes.
  int _layout = 0;

  /// The lines, cached until the workspace, the search, the kind scope, the
  /// open folders or the mode change.
  String? _linesKey;
  List<LibraryLine> _lines = const <LibraryLine>[];
  List<double> _lineRows = const <double>[];
  int _listedTotal = 0;

  /// The view of the list, read on scroll and whenever the scrolling
  /// element changes size (a drawer opening, the window resizing).
  LibraryView _view = initialLibraryView;
  web.HTMLElement? _scroller;
  JSFunction? _onScrollJs;
  web.ResizeObserver? _resizeObserver;
  bool _measureQueued = false;

  /// A line to bring into view, and a row to focus, after the next render.
  String? _revealId;
  String? _focusId;

  /// What the rail shows from the store; a store change that leaves it
  /// unchanged (typing in the inspector) does not redraw the rail.
  String _storeSignature = '';

  EditorStore get _store => component.store;

  Workspace get _workspace => _store.workspace;

  LibrarySyncRules get _sync =>
      LibrarySyncRules(component.syncBinding, _workspace);

  @override
  void initState() {
    super.initState();
    _listen(_store);
  }

  @override
  void didUpdateWidget(EditorRail oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (identical(oldComponent.store, component.store)) return;
    oldComponent.store.removeListener(_onChanged);
    oldComponent.store.workspace.removeListener(_onChanged);
    _listen(_store);
  }

  @override
  void dispose() {
    _store.removeListener(_onStoreChanged);
    _workspace.removeListener(_onChanged);
    _unbindScroller();
    super.dispose();
  }

  void _listen(EditorStore store) {
    store.addListener(_onStoreChanged);
    store.workspace.addListener(_onChanged);
    _lastActiveId = store.workspace.activeId;
    _selectedId = _lastActiveId;
    _storeSignature = _signature();
    _expandActivePath();
  }

  String _signature() =>
      '${_workspace.activeId}|${_store.mode?.kind.name}|'
      '${_store.docType.contentsTabLabel}';

  void _onStoreChanged() {
    final String next = _signature();
    if (next == _storeSignature) return;
    _storeSignature = next;
    _onChanged();
  }

  void _onChanged() {
    if (!mounted) return;
    final String? activeId = _workspace.activeId;
    if (activeId != _lastActiveId) {
      _lastActiveId = activeId;
      _selectedId = activeId;
      _renamingId = null;
      _expandActivePath();
      _revealId = activeId;
    }
    setState(() {});
  }

  void _expandActivePath() {
    String? folderId = _workspace.active?.folderId;
    final Set<String> seen = <String>{};
    while (folderId != null && seen.add(folderId)) {
      if (_expanded.add(folderId)) _layout++;
      folderId = _workspace.folderById(folderId)?.parentId;
    }
  }

  @override
  Widget build(BuildContext context) {
    final String? contents = _store.docType.contentsTabLabel;
    final bool library = contents == null || !_contentsTab;
    return dom.div(classes: 'hui-editor-rail', <Widget>[
      if (contents != null)
        AppTabs(
          label: huiText('Library'),
          tabs: <String>[huiText('Library'), contents],
          selected: library ? 0 : 1,
          onSelect: (int index) => setState(() => _contentsTab = index == 1),
        ),
      dom.div(
        classes: 'hui-editor-rail-panel',
        attributes: <String, String>{
          if (contents != null) 'role': 'tabpanel',
          if (contents != null)
            'aria-label': library ? huiText('Library') : contents,
        },
        <Widget>[if (library) _library(context) else component.contents],
      ),
      _runtimeIdDialog(),
      _moveDialog(),
    ]);
  }

  Widget _library(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<LibraryLine> lines = _linesFor(advanced);
    final bool emptyWorkspace =
        _workspace.docs.isEmpty && _workspace.folders.isEmpty;
    context.binding.addPostFrameCallback(_afterBuild);
    final List<AppMenuEntry> more = <AppMenuEntry>[
      AppMenuEntry(
        huiText('New folder'),
        () => _createFolder(_workspace.folderById(_selectedId)?.id),
        icon: ArcaneIcon.folderPlus(),
      ),
    ];
    final RowAction create = RowAction(
      huiText('New'),
      _openNewDocument,
      icon: ArcaneIcon.plus(),
      id: NewDocumentDialog.triggerId,
      expanded: component.newDocumentDialogOpen,
      attributes: <String, String>{
        'aria-haspopup': 'dialog',
        'aria-label': huiText('New document'),
      },
    );
    final List<Widget> find = <Widget>[
      _TextBox(
        id: 'hui-library-search',
        value: _query,
        label: huiText('Search'),
        placeholder: huiText('Search'),
        search: true,
        onChanged: (String value) => setState(() => _query = value),
      ),
      _filterMenu(advanced),
      if (_store.mode != null)
        AppIconButton(
          icon: ArcaneIcon.x(),
          label: huiText('Show all documents'),
          onPressed: () => _store.mode = null,
        ),
    ];
    // In a drawer the sheet titles and pads the library: one toolbar.
    final bool drawer = SheetScope.inside(context);
    // The listed total as data, also in a drawer, which has no header count.
    final Map<String, String> listed = <String, String>{
      'data-listed': '$_listedTotal',
    };
    return dom.div(classes: 'hui-library', attributes: listed, <Widget>[
      // Search, the filter and New stay in view while the list scrolls.
      dom.div(classes: 'hui-library-head', <Widget>[
        if (drawer)
          ActionRow(
            dense: true,
            leading: <Widget>[
              ...find,
              InfoButton(tip: _sync.scopeHint),
            ],
            more: more,
            moreId: 'hui-library-more',
            primary: create,
          )
        else ...<Widget>[
          PaneHeader(
            title: huiText('Library'),
            tip: _sync.scopeHint,
            status: dom.span(classes: 'ui-section-count', <Widget>[
              Text('$_listedTotal'),
            ]),
            menuId: 'hui-library-more',
            actions: more,
            primary: create,
          ),
          ActionRow(dense: true, leading: find),
        ],
      ]),
      if (emptyWorkspace && component.listing)
        const Skeleton(rows: 4)
      else if (emptyWorkspace)
        EmptyState(
          icon: ArcaneIcon.folders(),
          message: huiText('Create a folder or document to begin.'),
        )
      else if (lines.isEmpty)
        EmptyState(message: _emptyMessage(advanced))
      else
        _tree(lines, advanced),
    ]);
  }

  /// The lines for this render, rebuilt only when what they show changed.
  List<LibraryLine> _linesFor(bool advanced) {
    final String key =
        '${_workspace.version}|$_query|${_store.mode?.kind.name}|$_layout|'
        '$advanced';
    if (key == _linesKey) return _lines;
    final bool searching = _query.trim().isNotEmpty;
    final List<WorkspaceDoc> docs = _workspace.docs;
    _lines = libraryLines(
      folders: _workspace.folders,
      docs: docs,
      listed: _listed,
      filtering: searching || _store.mode != null,
      searching: searching,
      expanded: _expanded,
      groupOpen: (KindGroup group) => !_closedGroups.contains(group),
      advanced: advanced,
    );
    _lineRows = <double>[for (final LibraryLine line in _lines) line.rows];
    _listedTotal = docs
        .where(
          (WorkspaceDoc doc) =>
              _listed(doc) && listedInMode(doc, advanced: advanced),
        )
        .length;
    _linesKey = key;
    return _lines;
  }

  /// One row is the dense control height.
  static const double _rowRem = 2;
  static const String _treeId = 'hui-library-tree';

  Widget _tree(List<LibraryLine> lines, bool advanced) {
    final LibraryWindow window = libraryWindow(
      _lineRows,
      viewTop: _view.top,
      viewHeight: _view.height,
    );
    return dom.div(
      id: _treeId,
      classes: 'hui-library-tree${_dropTarget == '' ? ' is-drop' : ''}',
      styles: dom.Styles(
        raw: <String, String>{
          'padding-block':
              '${window.before * _rowRem}rem ${window.after * _rowRem}rem',
        },
      ),
      attributes: <String, String>{
        'role': 'list',
        'aria-label': huiText('Library'),
      },
      events: <String, void Function(web.Event)>{
        ..._dropEvents(null),
        'keydown': _onTreeKey,
      },
      <Widget>[
        for (int index = window.first; index < window.end; index++)
          _line(lines[index], index, advanced),
      ],
    );
  }

  Widget _line(LibraryLine line, int index, bool advanced) {
    final Widget row = switch (line.kind) {
      LibraryLineKind.folder => _folder(line),
      LibraryLineKind.group => _groupHeading(line),
      LibraryLineKind.document => _document(line, advanced),
    };
    final bool drop =
        line.kind == LibraryLineKind.folder && _dropTarget == line.folder!.id;
    return dom.div(
      key: ValueKey<String>('library-line-${line.id}'),
      classes: 'hui-library-line${drop ? ' is-drop' : ''}',
      styles: dom.Styles(
        raw: <String, String>{'height': '${line.rows * _rowRem}rem'},
      ),
      attributes: <String, String>{
        'role': 'listitem',
        'data-line': '$index',
        'aria-posinset': '${index + 1}',
        'aria-setsize': '${_lines.length}',
      },
      events: _dropEvents(line.dropFolderId),
      <Widget>[row],
    );
  }

  Widget _groupHeading(LibraryLine line) =>
      dom.div(classes: 'hui-library-row is-group', <Widget>[
        dom.div(classes: 'hui-library-name', <Widget>[
          _RowButton(
            id: _rowId(line.id),
            label: line.group!.label,
            icon: line.open
                ? ArcaneIcon.chevronDown()
                : ArcaneIcon.chevronRight(),
            attributes: <String, String>{'aria-expanded': '${line.open}'},
            onPressed: () => _toggleGroup(line.group!),
          ),
        ]),
        dom.span(classes: 'ui-section-count', <Widget>[Text('${line.count}')]),
      ]);

  /// "All kinds", then each group's kinds under its heading. The picked
  /// kind is the store's library scope, which also follows the document
  /// that is opened.
  Widget _filterMenu(bool advanced) {
    final DocumentTypeAdapter? mode = _store.mode;
    final String? kind = mode == null ? null : kindInfo(mode.kind).label;
    return AppMenu(
      id: 'hui-library-filter',
      icon: ArcaneIcon.listFilter(),
      label: kind,
      iconLabel: huiText('Filter by kind'),
      attributes: <String, String>{
        if (kind != null)
          'aria-label': huiText('Document kind: {kind}', <String, Object?>{
            'kind': kind,
          }),
      },
      entries: <AppMenuEntry>[
        AppMenuEntry.radio(
          huiText('All kinds'),
          group: _filterGroup,
          value: '',
          checked: mode == null,
          onPicked: (String _) => _store.mode = null,
        ),
        for (final KindGroup group in groupsFor(
          advanced: advanced,
        )) ...<AppMenuEntry>[
          AppMenuEntry.heading(group.label),
          for (final KindInfo info in kindsIn(group, advanced: advanced))
            AppMenuEntry.radio(
              info.label,
              group: _filterGroup,
              value: info.kind.name,
              checked: mode?.kind == info.kind,
              onPicked: (String _) =>
                  _store.mode = DocumentTypeRegistry.of(info.kind),
            ),
        ],
      ],
    );
  }

  Widget _folder(LibraryLine line) {
    final WorkspaceFolder folder = line.folder!;
    final List<AppMenuEntry> entries = _folderEntries(folder);
    return AppContextMenu(
      id: 'hui-library-context-${folder.id}',
      entries: entries,
      child: dom.div(
        classes:
            'hui-library-row is-folder'
            '${_selectedId == folder.id ? ' is-selected' : ''}',
        styles: _depth(line.depth),
        <Widget>[
          dom.div(classes: 'hui-library-name', <Widget>[
            if (_renamingId == folder.id)
              _renameField(folder.id)
            else
              _RowButton(
                id: _rowId(folder.id),
                label: folder.title,
                icon: line.open ? ArcaneIcon.folderOpen() : ArcaneIcon.folder(),
                attributes: <String, String>{'aria-expanded': '${line.open}'},
                onPressed: () => _toggleFolder(folder.id),
              ),
          ]),
          dom.span(classes: 'ui-section-count', <Widget>[
            Text('${line.count}'),
          ]),
          _rowMenu(folder.id, folder.title, entries),
        ],
      ),
    );
  }

  Widget _document(LibraryLine line, bool advanced) {
    final WorkspaceDoc doc = line.doc!;
    final bool active = _workspace.activeId == doc.id;
    final bool renaming = _renamingId == doc.id;
    final List<AppMenuEntry> entries = _documentEntries(doc, advanced);
    final Widget? badge = component.serverBadge?.call(doc.id);
    final String kindId = 'hui-library-kind-${doc.id}';
    final bool namesKind = line.namesKind;
    return AppContextMenu(
      id: 'hui-library-context-${doc.id}',
      entries: entries,
      child: dom.div(
        classes:
            'hui-library-row is-document'
            '${active ? ' is-active' : ''}'
            '${_selectedId == doc.id ? ' is-selected' : ''}',
        styles: _depth(line.depth),
        attributes: <String, String>{'draggable': '${!renaming}'},
        events: <String, void Function(web.Event)>{
          'dragstart': (web.Event event) => _dragStart(event, doc.id),
          'dragend': (web.Event _) => _dragEnd(),
        },
        <Widget>[
          dom.div(classes: 'hui-library-name', <Widget>[
            if (renaming)
              _renameField(doc.id)
            else
              _RowButton(
                id: _rowId(doc.id),
                label: doc.title,
                icon: DocumentTypeRegistry.of(doc.kind).railIcon(),
                attributes: <String, String>{
                  'data-doc-row': doc.id,
                  'aria-current': active ? 'page' : 'false',
                  if (badge == null && namesKind) 'aria-describedby': kindId,
                },
                onPressed: () => _open(doc.id),
              ),
            if (line.rows > 1)
              dom.span(classes: 'hui-library-id', <Widget>[
                Text(doc.runtimeId!),
              ]),
          ]),
          // A server state takes the kind label's place and keeps its full
          // width. The kind shows only in a folder that mixes kinds; the
          // empty cell still pushes the menu to the end.
          dom.span(
            classes:
                'ui-pane-kind hui-library-kind${badge == null ? '' : ' has-badge'}',
            <Widget>[
              ?(badge ??
                  (namesKind
                      ? dom.span(id: kindId, <Widget>[
                          Text(kindInfo(doc.kind).singular),
                        ])
                      : null)),
            ],
          ),
          if (_duplicateRuntimeId(doc)) _duplicateMark(),
          _rowMenu(doc.id, doc.title, entries),
        ],
      ),
    );
  }

  Widget _rowMenu(String id, String name, List<AppMenuEntry> entries) =>
      AppMenu(
        id: 'hui-library-actions-$id',
        icon: ArcaneIcon.ellipsis(),
        iconLabel: huiText('Actions for {label}', <String, Object?>{
          'label': name,
        }),
        alignEnd: true,
        entries: entries,
      );

  Widget _duplicateMark() => ArcaneTooltip(
    text: huiText('Duplicate runtime id'),
    child: dom.span(
      classes: 'hui-library-warning',
      attributes: <String, String>{
        'role': 'img',
        'aria-label': huiText('Duplicate runtime id'),
      },
      <Widget>[ArcaneIcon.triangleAlert(size: controlIconSize)],
    ),
  );

  List<AppMenuEntry> _documentEntries(WorkspaceDoc doc, bool advanced) {
    final DocumentTypeAdapter type = DocumentTypeRegistry.of(doc.kind);
    final bool canDuplicate = type.fixedRuntimeId == null;
    return <AppMenuEntry>[
      AppMenuEntry(
        huiTextKey('action.open', 'Open'),
        doc.id == _workspace.activeId ? null : () => _open(doc.id),
        icon: ArcaneIcon.externalLink(),
      ),
      AppMenuEntry(
        huiText('Rename'),
        () => _startRename(doc.id, doc.title),
        icon: ArcaneIcon.pencil(),
      ),
      if (advanced && doc.runtimeId != null && type.fixedRuntimeId == null)
        AppMenuEntry(
          huiText('Change runtime id'),
          () => _openRuntimeId(doc),
          icon: ArcaneIcon.idCard(),
        ),
      AppMenuEntry(
        huiText('Duplicate'),
        canDuplicate ? () => _duplicate(doc) : null,
        icon: ArcaneIcon.copy(),
      ),
      AppMenuEntry(
        huiText('Move to folder'),
        () => _openMove(doc.id),
        icon: ArcaneIcon.folderInput(),
      ),
      AppMenuEntry(
        huiText('Copy link'),
        () => _copyDocumentLink(doc),
        icon: ArcaneIcon.link(),
      ),
      const AppMenuEntry.separator(),
      AppMenuEntry(
        huiText('Delete'),
        () => _deleteDocument(doc),
        destructive: true,
        icon: ArcaneIcon.trash2(),
      ),
    ];
  }

  List<AppMenuEntry> _folderEntries(WorkspaceFolder folder) => <AppMenuEntry>[
    AppMenuEntry(
      huiText('New folder here'),
      () => _createFolder(folder.id),
      icon: ArcaneIcon.folderPlus(),
    ),
    AppMenuEntry(
      huiText('Rename'),
      () => _startRename(folder.id, folder.title),
      icon: ArcaneIcon.pencil(),
    ),
    AppMenuEntry(
      huiText('Move'),
      () => _openMove(folder.id),
      icon: ArcaneIcon.folderInput(),
    ),
    const AppMenuEntry.separator(),
    AppMenuEntry(
      huiText('Delete folder'),
      () => _deleteFolder(folder),
      destructive: true,
      icon: ArcaneIcon.trash2(),
    ),
  ];

  // --- Listing --------------------------------------------------------------

  bool _listed(WorkspaceDoc doc) =>
      _store.inMode(doc) && libraryDocumentMatches(doc, _query);

  /// Why nothing is listed: a search or filter that matched nothing, or a
  /// Simple library whose documents are all of kinds only Advanced lists.
  String _emptyMessage(bool advanced) {
    if (advanced || _store.mode != null || _query.trim().isNotEmpty) {
      return huiText('No matches');
    }
    return hiddenDocumentsText(_workspace.docs.length);
  }

  bool _duplicateRuntimeId(WorkspaceDoc doc) =>
      doc.runtimeId != null &&
      _workspace.runtimeIdConflicts.any(
        (WorkspaceRuntimeIdConflict item) =>
            item.kind == doc.kind && item.runtimeId == doc.runtimeId,
      );

  static dom.Styles _depth(int depth) =>
      dom.Styles(raw: <String, String>{'--hui-tree-depth': '$depth'});

  static String _rowId(String id) => 'hui-library-row-$id';

  // --- The view: which lines are drawn --------------------------------------

  /// One row in CSS pixels at the page's root font size.
  static double get _rowPx {
    final String size = web.window
        .getComputedStyle(web.document.documentElement!)
        .fontSize;
    return (double.tryParse(size.replaceAll('px', '')) ?? 16) * _rowRem;
  }

  /// After each render: follow the element that scrolls the list (the pane,
  /// or the drawer on narrow screens), read its view, then bring a line into
  /// view or focus a row if one is waiting.
  void _afterBuild() {
    if (!mounted) return;
    final web.Element? tree = web.document.getElementById(_treeId);
    if (tree == null) {
      _unbindScroller();
      return;
    }
    final web.HTMLElement? scroller = _scrollParent(tree);
    if (!identical(scroller, _scroller)) {
      _unbindScroller();
      _bindScroller(scroller);
    }
    final String? reveal = _revealId;
    _revealId = null;
    if (reveal != null) _scrollIntoView(reveal);
    _measure();
    final String? focus = _focusId;
    if (focus == null) return;
    if (!_lines.any((LibraryLine line) => line.id == focus)) {
      _focusId = null;
      return;
    }
    final web.Element? row = web.document.getElementById(_rowId(focus));
    if (row != null) {
      _focusId = null;
      (row as web.HTMLElement).focus();
    }
  }

  static web.HTMLElement? _scrollParent(web.Element element) {
    for (
      web.Element? node = element.parentElement;
      node != null;
      node = node.parentElement
    ) {
      final String overflow = web.window.getComputedStyle(node).overflowY;
      if (overflow == 'auto' || overflow == 'scroll') {
        return node as web.HTMLElement;
      }
    }
    return null;
  }

  void _bindScroller(web.HTMLElement? scroller) {
    _scroller = scroller;
    if (scroller == null) return;
    final JSFunction onScroll = ((web.Event _) => _queueMeasure()).toJS;
    scroller.addEventListener(
      'scroll',
      onScroll,
      web.AddEventListenerOptions(passive: true),
    );
    _onScrollJs = onScroll;
    _resizeObserver = web.ResizeObserver(
      ((
        JSArray<web.ResizeObserverEntry> _,
        web.ResizeObserver _,
      ) => _queueMeasure()).toJS,
    )..observe(scroller);
  }

  void _unbindScroller() {
    final JSFunction? onScroll = _onScrollJs;
    if (onScroll != null) _scroller?.removeEventListener('scroll', onScroll);
    _resizeObserver?.disconnect();
    _scroller = null;
    _onScrollJs = null;
    _resizeObserver = null;
  }

  void _queueMeasure() {
    if (_measureQueued) return;
    _measureQueued = true;
    web.window.requestAnimationFrame(
      ((num _) {
        _measureQueued = false;
        if (mounted) _measure();
      }).toJS,
    );
  }

  /// The list's top inside the scrolling element's content, in pixels.
  static double _treeTop(web.HTMLElement scroller, web.Element tree) =>
      tree.getBoundingClientRect().top -
      scroller.getBoundingClientRect().top +
      scroller.scrollTop;

  /// Reads the view; redraws only when it reaches lines that are not drawn.
  void _measure() {
    final web.HTMLElement? scroller = _scroller;
    final web.Element? tree = web.document.getElementById(_treeId);
    if (scroller == null || tree == null) return;
    final LibraryView view = libraryView(
      scrollTop: scroller.scrollTop,
      clientHeight: scroller.clientHeight.toDouble(),
      listTop: _treeTop(scroller, tree),
      rowPx: _rowPx,
      previous: _view,
    );
    final LibraryWindow drawn = libraryWindow(
      _lineRows,
      viewTop: _view.top,
      viewHeight: _view.height,
    );
    final LibraryWindow seen = libraryWindow(
      _lineRows,
      viewTop: view.top,
      viewHeight: view.height,
      overscan: 2,
    );
    _view = view;
    if (seen.first < drawn.first || seen.end > drawn.end) setState(() {});
  }

  /// Scrolls the list just enough to show the line [id].
  void _scrollIntoView(String id) {
    final int index = _lines.indexWhere((LibraryLine line) => line.id == id);
    final web.HTMLElement? scroller = _scroller;
    final web.Element? tree = web.document.getElementById(_treeId);
    if (index < 0 || scroller == null || tree == null) return;
    double offset = 0;
    for (int i = 0; i < index; i++) {
      offset += _lineRows[i];
    }
    final double px = _rowPx;
    final double lineTop = _treeTop(scroller, tree) + offset * px;
    final double lineBottom = lineTop + _lineRows[index] * px;
    if (lineTop < scroller.scrollTop) {
      scroller.scrollTop = lineTop;
    } else if (lineBottom > scroller.scrollTop + scroller.clientHeight) {
      scroller.scrollTop = lineBottom - scroller.clientHeight;
    }
  }

  /// Up and Down, Home and End move between rows; Right opens a folder or
  /// group, Left closes it or moves to the one that holds the row.
  void _onTreeKey(web.Event event) {
    final web.KeyboardEvent key = event as web.KeyboardEvent;
    if (key.altKey || key.ctrlKey || key.metaKey || key.shiftKey) return;
    final web.Element? target = key.target as web.Element?;
    if (target == null || !target.id.startsWith('hui-library-row-')) return;
    final int? index = int.tryParse(
      target.closest('[data-line]')?.getAttribute('data-line') ?? '',
    );
    if (index == null || index >= _lines.length) return;
    final LibraryLine line = _lines[index];
    final bool opens = line.kind != LibraryLineKind.document;
    final int? next = switch (key.key) {
      'ArrowDown' => index + 1,
      'ArrowUp' => index - 1,
      'Home' => 0,
      'End' => _lines.length - 1,
      'ArrowRight' when opens && !line.open => index,
      'ArrowLeft' when opens && line.open => index,
      'ArrowLeft' => _holderIndex(index),
      _ => -2,
    };
    if (next == -2) return;
    key.preventDefault();
    if (next == index && opens) _toggleLine(line);
    if (next == null || next < 0 || next >= _lines.length) return;
    _focusLine(_lines[next].id);
  }

  /// The folder or group line that holds line [index], if any.
  int? _holderIndex(int index) {
    final LibraryLine line = _lines[index];
    for (int i = index - 1; i >= 0; i--) {
      final LibraryLine above = _lines[i];
      if (above.depth < line.depth) return i;
      if (line.depth == 0 &&
          line.kind == LibraryLineKind.document &&
          above.kind == LibraryLineKind.group) {
        return i;
      }
    }
    return null;
  }

  void _toggleLine(LibraryLine line) {
    if (line.kind == LibraryLineKind.folder) _toggleFolder(line.folder!.id);
    if (line.kind == LibraryLineKind.group) _toggleGroup(line.group!);
  }

  void _focusLine(String id) => setState(() {
    _focusId = id;
    _revealId = id;
  });

  // --- Selection and creation ----------------------------------------------

  void _open(String documentId) {
    setState(() => _selectedId = documentId);
    _store.openDocument(documentId);
  }

  void _toggleFolder(String id) => setState(() {
    _selectedId = id;
    if (!_expanded.remove(id)) _expanded.add(id);
    _layout++;
  });

  void _toggleGroup(KindGroup group) => setState(() {
    if (!_closedGroups.remove(group)) _closedGroups.add(group);
    _layout++;
  });

  /// Where New creates: the selected folder, else the selected document's
  /// folder, else the open document's.
  String? get _targetFolderId {
    final WorkspaceFolder? folder = _workspace.folderById(_selectedId);
    if (folder != null) return folder.id;
    return _workspace.byId(_selectedId)?.folderId ??
        _workspace.active?.folderId;
  }

  void _openNewDocument() => component.onNewDocument(_createChoice);

  void _createChoice(NewDocumentChoice choice) {
    final String? folderId = _targetFolderId;
    if (folderId != null && _expanded.add(folderId)) _layout++;
    createNewDocument(_store, choice, folderId: folderId);
  }

  void _createFolder(String? parentId) {
    final WorkspaceFolder folder = _workspace.createFolder(
      title: huiText('New folder'),
      parentId: parentId,
    );
    if (parentId != null && _expanded.add(parentId)) _layout++;
    _selectedId = folder.id;
    _revealId = folder.id;
    _startRename(folder.id, folder.title);
  }

  void _duplicate(WorkspaceDoc doc) {
    final WorkspaceDoc? copy = _store.duplicateDocument(doc.id);
    if (copy == null) return;
    toastSuccess(
      huiText('Created {title}.', <String, Object?>{'title': copy.title}),
    );
  }

  Future<void> _deleteDocument(WorkspaceDoc doc) async {
    final bool confirmed = await askToConfirm(
      title: huiText('Delete {name}?', <String, Object?>{'name': doc.title}),
      message: LibrarySyncRules(
        component.syncBinding,
        _workspace,
      ).deleteMessage(doc),
      confirmLabel: huiText('Delete document'),
      destructive: true,
    );
    if (confirmed && mounted) _store.deleteDocument(doc.id);
  }

  Future<void> _deleteFolder(WorkspaceFolder folder) async {
    final bool confirmed = await askToConfirm(
      title: huiText('Delete {name}?', <String, Object?>{'name': folder.title}),
      message: huiText('Its contents move up one level.'),
      confirmLabel: huiText('Delete folder'),
      destructive: true,
    );
    if (confirmed && mounted) _workspace.deleteFolder(folder.id);
  }

  Future<void> _copyDocumentLink(WorkspaceDoc doc) async {
    final String hash = workspaceDocumentHash(_workspace.id, doc.id);
    if (await copyText(workspaceUrlForHash(hash))) {
      toastSuccess(huiText('Document link copied.'));
    } else {
      toastError(huiText('The browser refused clipboard access.'));
    }
  }

  // --- Rename in place ------------------------------------------------------

  Widget _renameField(String id) => dom.span(
    classes: 'hui-library-rename',
    events: _renameKeys.events(
      onEnter: () => _commitRename(id, _renameText),
      onEscape: () => _endRename(id),
    ),
    <Widget>[
      _TextBox(
        id: _renameInputId,
        value: _renameText,
        label: huiText('Title'),
        onChanged: (String value) => _renameText = value,
        onBlur: () => _commitRename(id, _renameText),
      ),
    ],
  );

  void _startRename(String id, String title) {
    setState(() {
      _renamingId = id;
      _renameText = title;
    });
    _afterRender(() {
      final web.HTMLInputElement? input =
          web.document.getElementById(_renameInputId) as web.HTMLInputElement?;
      if (input == null || identical(web.document.activeElement, input)) {
        return;
      }
      input.focus();
      input.select();
    });
  }

  /// Enter and blur keep the text; a blur after Escape or Enter finds the
  /// row no longer renaming and does nothing.
  void _commitRename(String id, String value) {
    if (_renamingId != id) return;
    final WorkspaceFolder? folder = _workspace.folderById(id);
    if (folder != null) {
      _workspace.renameFolder(id, value);
    } else {
      final WorkspaceDoc? doc = _workspace.byId(id);
      final String? runtimeId = doc == null
          ? null
          : _sync.renamedRuntimeId(doc, value);
      if (runtimeId == null || _renameRuntimeId(doc!, runtimeId)) {
        _workspace.renameDocumentTitle(id, value);
      }
    }
    _endRename(id);
  }

  void _endRename(String id) {
    if (_renamingId != id) return;
    setState(() => _renamingId = null);
    _afterRender(() {
      (web.document.getElementById(_rowId(id)) as web.HTMLElement?)?.focus();
    });
  }

  /// Runs [action] once the frame is drawn and again shortly after, since a
  /// menu that just closed hands focus back to its button in between.
  void _afterRender(void Function() action) {
    context.binding.addPostFrameCallback(() {
      if (mounted) action();
    });
    web.window.setTimeout(
      (() {
        if (mounted) action();
      }).toJS,
      30.toJS,
    );
  }

  // --- Runtime id and Move dialogs ------------------------------------------

  void _openRuntimeId(WorkspaceDoc doc) =>
      setState(() => _runtimeIdDocId = doc.id);

  void _closeRuntimeId() => setState(() => _runtimeIdDocId = null);

  Widget _runtimeIdDialog() {
    final WorkspaceDoc? doc = _workspace.byId(_runtimeIdDocId);
    return RuntimeIdDialog(
      open: doc != null,
      value: doc?.runtimeId ?? '',
      onSave: (String runtimeId) =>
          doc == null || _renameRuntimeId(doc, runtimeId),
      onClose: _closeRuntimeId,
    );
  }

  void _openMove(String id) => setState(() => _movingId = id);

  void _closeMove() => setState(() => _movingId = null);

  void _applyMove(String? target) {
    final WorkspaceFolder? folder = _workspace.folderById(_movingId);
    final WorkspaceDoc? doc = _workspace.byId(_movingId);
    if (folder != null) {
      _workspace.moveFolder(folder.id, target);
    } else if (doc != null) {
      _moveDocument(doc, target);
    }
    if (target != null && _expanded.add(target)) _layout++;
    _closeMove();
  }

  /// A folder never moves into itself or below itself.
  Widget _moveDialog() {
    final WorkspaceFolder? folder = _workspace.folderById(_movingId);
    final WorkspaceDoc? doc = _workspace.byId(_movingId);
    final List<PickerOption> destinations = <PickerOption>[
      for (final WorkspaceFolder candidate in _workspace.folders)
        if (folder == null || !_isInside(candidate.id, folder.id))
          PickerOption(candidate.id, label: _folderPath(candidate)),
    ]..sort((PickerOption a, PickerOption b) => a.label!.compareTo(b.label!));
    return LibraryMoveDialog(
      open: folder != null || doc != null,
      value: folder?.parentId ?? doc?.folderId,
      destinations: destinations,
      onMove: _applyMove,
      onClose: _closeMove,
    );
  }

  bool _isInside(String candidateId, String ancestorId) {
    String? cursor = candidateId;
    final Set<String> seen = <String>{};
    while (cursor != null && seen.add(cursor)) {
      if (cursor == ancestorId) return true;
      cursor = _workspace.folderById(cursor)?.parentId;
    }
    return false;
  }

  String _folderPath(WorkspaceFolder folder) {
    final List<String> parts = <String>[folder.title];
    String? parentId = folder.parentId;
    final Set<String> seen = <String>{folder.id};
    while (parentId != null && seen.add(parentId)) {
      final WorkspaceFolder? parent = _workspace.folderById(parentId);
      if (parent == null) break;
      parts.insert(0, parent.title);
      parentId = parent.parentId;
    }
    return parts.join(' / ');
  }

  // --- Drag a document onto a folder or the root ---------------------------

  void _dragStart(web.Event event, String documentId) {
    _draggingId = documentId;
    final web.DataTransfer? transfer = (event as web.DragEvent).dataTransfer;
    if (transfer == null) return;
    transfer.effectAllowed = 'move';
    transfer.setData('text/plain', documentId);
  }

  void _dragEnd() {
    _draggingId = null;
    if (_dropTarget != null) setState(() => _dropTarget = null);
  }

  /// Drop handlers for [folderId]'s row and contents, or the root (null).
  /// The innermost folder takes the drop.
  Map<String, void Function(web.Event)> _dropEvents(String? folderId) {
    final String target = folderId ?? '';
    return <String, void Function(web.Event)>{
      'dragover': (web.Event event) {
        if (_draggingId == null) return;
        event.preventDefault();
        event.stopPropagation();
        (event as web.DragEvent).dataTransfer?.dropEffect = 'move';
        if (_dropTarget != target) setState(() => _dropTarget = target);
      },
      'drop': (web.Event event) {
        final WorkspaceDoc? doc = _workspace.byId(_draggingId);
        if (_draggingId == null) return;
        event.preventDefault();
        event.stopPropagation();
        setState(() {
          _draggingId = null;
          _dropTarget = null;
          if (folderId != null && _expanded.add(folderId)) _layout++;
        });
        if (doc != null && doc.folderId != folderId) {
          _moveDocument(doc, folderId);
        }
      },
    };
  }

  // --- Server ids -----------------------------------------------------------

  /// Renames the document's runtime id; a refused id keeps the dialog open
  /// (the store says why).
  bool _renameRuntimeId(WorkspaceDoc doc, String value) {
    final String canonical = sanitizeMenuId(value);
    return canonical == doc.runtimeId ||
        _store.renameDocumentRuntimeId(doc.id, canonical);
  }

  void _moveDocument(WorkspaceDoc doc, String? folderId) =>
      _workspace.moveDocument(doc.id, folderId);
}

/// A row's main control: its icon and name, at the pane's density.
class _RowButton extends StatelessWidget {
  const _RowButton({
    required this.id,
    required this.label,
    required this.icon,
    required this.onPressed,
    this.attributes = const <String, String>{},
  });

  /// The library lays a button out as a centred flex row in inline styles,
  /// where a bare label cannot take an ellipsis: a row is a block that reads
  /// from the start and ends a long name with one.
  static const ArcaneStyleData _rowStyles = ArcaneStyleData(
    display: Display.block,
    textAlign: TextAlign.start,
    overflow: Overflow.hidden,
    textOverflow: TextOverflow.ellipsis,
    maxWidthCustom: '100%',
    minWidth: '0',
  );

  final String id;
  final String label;
  final ArcaneGlyph icon;
  final Map<String, String> attributes;
  final void Function() onPressed;

  @override
  Widget build(BuildContext context) => Button(
    id: id,
    variant: ButtonVariant.ghost,
    label: label,
    icon: icon.sized(controlIconSize),
    size: buttonSize(DensityScope.of(context)),
    styles: _rowStyles,
    attributes: attributes,
    onPressed: onPressed,
  );
}

/// A one-line text box at the density of the part it sits in.
class _TextBox extends StatelessWidget {
  const _TextBox({
    required this.id,
    required this.value,
    required this.label,
    required this.onChanged,
    this.onBlur,
    this.placeholder,
    this.search = false,
  });

  final String id;
  final String value;

  /// The accessible name.
  final String label;
  final String? placeholder;
  final bool search;
  final void Function(String value) onChanged;
  final void Function()? onBlur;

  @override
  Widget build(BuildContext context) => TextInput(
    id: id,
    value: value,
    placeholder: placeholder,
    type: search ? TextInputType.search : TextInputType.text,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    onChanged: onChanged,
    onBlur: onBlur,
    attributes: <String, String>{'aria-label': label, 'autocomplete': 'off'},
  );
}
