/// The flow map: every menu in the panel's scope and the navigation routes
/// between them.
///
/// The map is a diagram drawn in a kit [StageFrame]: the board keeps its
/// natural size and is zoomed down to fit the frame ([FitBox] hug), so it
/// never scrolls inside the frame. The list view is the same graph as plain
/// rows, every route spelled out; it reads as a page and grows with its
/// content.
library;

import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Listenable, ListenableBuilder;

import '../../logic/workspace_flow_graph.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';
import '../../state/workspace_panel.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/empty_state.dart';
import '../../ui/fit_box.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/stage_frame.dart';
import '../../ui/tone.dart';
import '../../ui/toggle_choice.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PanelView extends StatefulWidget {
  const PanelView({required this.store, super.key});

  final EditorStore store;

  @override
  State<PanelView> createState() => _PanelViewState();
}

class _PanelViewState extends State<PanelView> {
  Listenable? _sources;

  Listenable get _listenable => _sources ??= Listenable.merge(<Listenable?>[
    component.store,
    component.store.workspace,
  ]);

  @override
  void didUpdateWidget(PanelView oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) _sources = null;
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _listenable,
    builder: (BuildContext context) => _body(),
  );

  Widget _body() {
    final WorkspacePanelDecodeResult? decoded = component.store.activePanel;
    if (decoded == null) {
      return dom.div(classes: 'hui-flow is-empty', <Widget>[
        EmptyState(
          icon: ArcaneIcon.workflow(),
          message: huiText('Open a menu flow map to inspect menu navigation.'),
        ),
      ]);
    }
    final WorkspacePanelData panel = decoded.data;
    final WorkspaceFlowGraph graph = buildWorkspaceFlowGraph(
      component.store.workspace,
      panel,
    );
    final String? notice =
        decoded.warning ??
        (graph.scopeMissing
            ? huiText(
                'This panel\'s saved folder no longer exists. All menus are shown.',
              )
            : null);
    final bool list = panel.view == WorkspacePanelView.list;
    return dom.div(classes: 'hui-flow${list ? ' is-list' : ''}', <Widget>[
      if (notice != null) AppAlert(tone: AlertTone.warning, message: notice),
      if (list) _listPage(panel, graph) else _stage(panel, graph),
    ]);
  }

  String get _title =>
      component.store.workspace.active?.title ?? huiText('Menu flow map');

  Widget _stage(WorkspacePanelData panel, WorkspaceFlowGraph graph) =>
      StageFrame(
        title: _title,
        fit: StageFit.free,
        surface: false,
        toggles: <Widget>[_viewChoice(panel)],
        more: _scopeEntries(panel),
        actions: <RowAction>[
          RowAction(
            huiText('Arrange'),
            () => _arrange(panel, graph),
            icon: ArcaneIcon.wand(),
          ),
        ],
        status: _status(panel, graph),
        statusTip: _statusTip(graph),
        state: graph.documents.isEmpty ? StageState.empty : StageState.ready,
        emptyMessage: huiText('No menu documents are inside this panel scope.'),
        child: dom.div(classes: 'hui-flow-room', <Widget>[
          FitBox(hug: true, child: _board(panel, graph)),
        ]),
      );

  Widget _listPage(WorkspacePanelData panel, WorkspaceFlowGraph graph) =>
      dom.section(
        classes: 'hui-flow-page',
        attributes: <String, String>{'aria-label': _title},
        <Widget>[
          dom.header(classes: 'hui-flow-head', <Widget>[
            ActionRow(
              dense: true,
              label: _title,
              leading: <Widget>[
                dom.h2(classes: 'hui-flow-title', <Widget>[Text(_title)]),
                _viewChoice(panel),
              ],
              more: _scopeEntries(panel),
            ),
          ]),
          if (graph.documents.isEmpty)
            dom.p(classes: 'hui-flow-empty', <Widget>[
              Text(huiText('No menu documents are inside this panel scope.')),
            ])
          else
            DensityScope.dense(
              child: dom.div(<Widget>[
                for (final WorkspaceDoc doc in graph.documents)
                  dom.article(
                    classes: 'hui-flow-item',
                    attributes: <String, String>{'aria-label': doc.title},
                    <Widget>[
                      _nodeHeader(doc, graph),
                      _routeList(graph.outgoing(doc.id)),
                    ],
                  ),
              ]),
            ),
          dom.footer(classes: 'hui-flow-status', <Widget>[
            Text(_status(panel, graph)),
          ]),
        ],
      );

  Widget _viewChoice(WorkspacePanelData panel) =>
      ToggleChoice<WorkspacePanelView>(
        label: huiText('View'),
        value: panel.view,
        onChanged: (WorkspacePanelView view) =>
            component.store.updatePanel(panel.copyWith(view: view)),
        options: <ChoiceOption<WorkspacePanelView>>[
          ChoiceOption<WorkspacePanelView>(
            WorkspacePanelView.canvas,
            huiText('Canvas'),
            icon: ArcaneIcon.share2(),
            iconOnly: true,
          ),
          ChoiceOption<WorkspacePanelView>(
            WorkspacePanelView.list,
            huiText('List'),
            icon: ArcaneIcon.listTree(),
            iconOnly: true,
          ),
        ],
      );

  /// The folder scope as radio entries of the header's `...` menu.
  List<AppMenuEntry> _scopeEntries(WorkspacePanelData panel) {
    final Workspace workspace = component.store.workspace;
    return <AppMenuEntry>[
      AppMenuEntry.heading(huiText('Scope')),
      AppMenuEntry.radio(
        huiText('All workspace folders'),
        group: 'hui-flow-scope',
        value: _allFoldersValue,
        checked: panel.scopeFolderId == null,
        onPicked: (String value) => _setScope(panel, value),
      ),
      for (final WorkspaceFolder folder in _sortedFolders(workspace))
        AppMenuEntry.radio(
          _folderPath(workspace, folder),
          group: 'hui-flow-scope',
          value: folder.id,
          checked: panel.scopeFolderId == folder.id,
          onPicked: (String value) => _setScope(panel, value),
        ),
    ];
  }

  /// One short line: the scope when it is a folder, the menu and route
  /// counts, and what needs attention.
  String _status(WorkspacePanelData panel, WorkspaceFlowGraph graph) {
    final Workspace workspace = component.store.workspace;
    final WorkspaceFolder? folder = panel.scopeFolderId == null
        ? null
        : workspace.folderById(panel.scopeFolderId!);
    final int broken = graph.edges.where(_isBroken).length;
    return <String>[
      if (folder != null) _folderPath(workspace, folder),
      huiPlural(
        'panel.flow.menu_count',
        graph.documents.length,
        oneEnglish: '{count} menu in flow',
        otherEnglish: '{count} menus in flow',
      ),
      '${huiText('Routes')}: ${graph.edges.length}',
      if (broken > 0) '${huiText('Needs attention')}: $broken',
    ].join(' · ');
  }

  String _statusTip(WorkspaceFlowGraph graph) => <String>[
    '${huiText('Back / Home / Close')}: '
        '${graph.edges.where((WorkspaceFlowEdge edge) => _isSink(edge.targetKind)).length}',
    '${huiText('External')}: '
        '${graph.edges.where((WorkspaceFlowEdge edge) => edge.targetKind == WorkspaceFlowTargetKind.external).length}',
    '${huiText('In cycles')}: ${graph.cycleDocumentIds.length}',
    '${huiText('No inbound links')}: ${graph.orphanDocumentIds.length}',
  ].join(' · ');

  Widget _board(WorkspacePanelData panel, WorkspaceFlowGraph graph) {
    final Map<String, WorkspacePanelPoint> automatic =
        arrangeWorkspaceFlowGraph(graph);
    final Map<String, WorkspacePanelPoint> positions =
        <String, WorkspacePanelPoint>{
          for (final WorkspaceDoc doc in graph.documents)
            doc.id: panel.positions[doc.id] ?? automatic[doc.id]!,
        };
    double maxX = 0;
    double maxY = 0;
    for (final WorkspacePanelPoint point in positions.values) {
      maxX = math.max(maxX, point.x);
      maxY = math.max(maxY, point.y);
    }
    final Map<WorkspaceFlowTargetKind, WorkspacePanelPoint> sinks =
        _sinkPositions(graph, maxX + _sinkGap);
    for (final WorkspacePanelPoint point in sinks.values) {
      maxY = math.max(maxY, point.y);
    }
    // The board is as big as what it draws: the zoom that fits it to the
    // frame is then as large as it can be.
    final double width =
        maxX + (sinks.isEmpty ? _nodeWidth : _sinkGap + _sinkWidth) + _margin;
    final double height = maxY + _nodeHeight + _margin;
    return DensityScope.dense(
      child: dom.div(
        classes: 'hui-flow-board',
        styles: dom.Styles(
          raw: <String, String>{
            'width': '${width.round()}px',
            'height': '${height.round()}px',
          },
        ),
        attributes: <String, String>{'role': 'group', 'aria-label': _title},
        <Widget>[
          for (final WorkspaceFlowEdge edge in graph.edges)
            if (edge.targetKind == WorkspaceFlowTargetKind.menu ||
                _isSink(edge.targetKind))
              _edge(
                edge,
                positions[edge.sourceDocumentId]!,
                edge.targetKind == WorkspaceFlowTargetKind.menu
                    ? positions[edge.targetDocumentId]!
                    : sinks[edge.targetKind]!,
              ),
          for (final MapEntry<WorkspaceFlowTargetKind, WorkspacePanelPoint> sink
              in sinks.entries)
            _sink(sink.key, sink.value),
          for (final WorkspaceDoc doc in graph.documents)
            _node(doc, graph, positions[doc.id]!),
        ],
      ),
    );
  }

  Widget _node(
    WorkspaceDoc doc,
    WorkspaceFlowGraph graph,
    WorkspacePanelPoint point,
  ) => dom.article(
    classes: _nodeClasses(doc, graph),
    styles: dom.Styles(
      raw: <String, String>{
        'left': '${point.x.round()}px',
        'top': '${point.y.round()}px',
      },
    ),
    attributes: <String, String>{'aria-label': doc.title},
    <Widget>[
      _nodeHeader(doc, graph),
      _routeList(graph.outgoing(doc.id), compact: true),
    ],
  );

  Widget _nodeHeader(WorkspaceDoc doc, WorkspaceFlowGraph graph) =>
      dom.div(classes: 'hui-flow-node-head', <Widget>[
        dom.div(classes: 'hui-flow-node-name', <Widget>[
          dom.strong(<Widget>[Text(doc.title)]),
          dom.code(<Widget>[Text(doc.runtimeId!)]),
        ]),
        AppIconButton(
          icon: ArcaneIcon.pencil(),
          label: huiText('Edit menu'),
          onPressed: () => component.store.openDocument(doc.id),
        ),
        if (_badges(doc, graph) case final List<Widget> badges
            when badges.isNotEmpty)
          dom.div(classes: 'hui-flow-badges', badges),
      ]);

  List<Widget> _badges(WorkspaceDoc doc, WorkspaceFlowGraph graph) => <Widget>[
    if (graph.cycleDocumentIds.contains(doc.id))
      StatusBadge(huiText('Cycle'), tone: BadgeTone.warning),
    if (graph.orphanDocumentIds.contains(doc.id))
      StatusBadge(huiText('Orphan')),
    if (graph.invalidDocumentIds.contains(doc.id))
      StatusBadge(huiText('Invalid JSON'), tone: BadgeTone.error),
    if (graph.duplicateRuntimeIds.contains(doc.runtimeId))
      StatusBadge(huiText('Duplicate id'), tone: BadgeTone.error),
  ];

  Widget _routeList(List<WorkspaceFlowEdge> edges, {bool compact = false}) {
    if (edges.isEmpty) {
      return dom.p(classes: 'hui-flow-note', <Widget>[
        Text(huiText('No navigation actions')),
      ]);
    }
    final List<WorkspaceFlowEdge> shown = compact && edges.length > 3
        ? edges.take(3).toList(growable: false)
        : edges;
    return dom.ul(classes: 'hui-flow-routes', <Widget>[
      for (final WorkspaceFlowEdge edge in shown)
        dom.li(classes: 'hui-flow-route is-${edge.targetKind.name}', <Widget>[
          dom.code(<Widget>[Text(edge.componentId)]),
          dom.span(<Widget>[Text(_edgeLabel(edge))]),
        ]),
      if (shown.length < edges.length)
        dom.li(classes: 'hui-flow-note', <Widget>[
          Text(
            huiText('+{value} more', <String, Object?>{
              'value': edges.length - shown.length,
            }),
          ),
        ]),
    ]);
  }

  Widget _edge(
    WorkspaceFlowEdge edge,
    WorkspacePanelPoint source,
    WorkspacePanelPoint target,
  ) {
    final double x1 = source.x + _nodeWidth;
    final double y1 = source.y + 48;
    final double x2 = target.x;
    final double y2 = target.y + 32;
    final double dx = x2 - x1;
    final double dy = y2 - y1;
    final double length = math.sqrt(dx * dx + dy * dy);
    final double degrees = math.atan2(dy, dx) * 180 / math.pi;
    return dom.div(
      classes: 'hui-flow-edge is-${edge.targetKind.name}',
      styles: dom.Styles(
        raw: <String, String>{
          'left': '${x1.round()}px',
          'top': '${y1.round()}px',
          'width': '${length.round()}px',
          'transform': 'rotate(${degrees.toStringAsFixed(3)}deg)',
        },
      ),
      const <Widget>[],
    );
  }

  Widget _sink(WorkspaceFlowTargetKind kind, WorkspacePanelPoint point) =>
      dom.div(
        classes: 'hui-flow-sink',
        styles: dom.Styles(
          raw: <String, String>{
            'left': '${point.x.round()}px',
            'top': '${point.y.round()}px',
          },
        ),
        <Widget>[_sinkIcon(kind), Text(_targetLabel(kind))],
      );

  Map<WorkspaceFlowTargetKind, WorkspacePanelPoint> _sinkPositions(
    WorkspaceFlowGraph graph,
    double x,
  ) {
    final Map<WorkspaceFlowTargetKind, WorkspacePanelPoint> out =
        <WorkspaceFlowTargetKind, WorkspacePanelPoint>{};
    int row = 0;
    for (final WorkspaceFlowTargetKind kind in const <WorkspaceFlowTargetKind>[
      WorkspaceFlowTargetKind.back,
      WorkspaceFlowTargetKind.home,
      WorkspaceFlowTargetKind.close,
    ]) {
      if (!graph.edges.any(
        (WorkspaceFlowEdge edge) => edge.targetKind == kind,
      )) {
        continue;
      }
      out[kind] = WorkspacePanelPoint(x, 72 + row * 94);
      row++;
    }
    return out;
  }

  String _nodeClasses(WorkspaceDoc doc, WorkspaceFlowGraph graph) {
    final List<String> classes = <String>['hui-flow-node'];
    if (graph.cycleDocumentIds.contains(doc.id)) classes.add('has-cycle');
    if (graph.invalidDocumentIds.contains(doc.id) ||
        graph.duplicateRuntimeIds.contains(doc.runtimeId)) {
      classes.add('has-danger');
    }
    return classes.join(' ');
  }

  String _edgeLabel(WorkspaceFlowEdge edge) {
    final String branch = edge.branch == 'click' ? '' : '${edge.branch}: ';
    final String trigger = edge.triggers.length == 4
        ? ''
        : '${edge.triggers.join(' / ')}: ';
    final String prefix = '$branch$trigger';
    return switch (edge.targetKind) {
      WorkspaceFlowTargetKind.menu => '$prefix${edge.mode} → ${edge.target}',
      WorkspaceFlowTargetKind.back => '$prefix${huiText('Back sink')}',
      WorkspaceFlowTargetKind.home => '$prefix${huiText('Home sink')}',
      WorkspaceFlowTargetKind.close => '$prefix${huiText('Close sink')}',
      WorkspaceFlowTargetKind.external => huiText(
        '{route} · outside scope',
        <String, Object?>{'route': '$prefix${edge.target}'},
      ),
      WorkspaceFlowTargetKind.dangling => huiText('{route} · dangling', <
        String,
        Object?
      >{
        'route':
            '$prefix${edge.target.isEmpty ? huiText('(missing target)') : edge.target}',
      }),
      WorkspaceFlowTargetKind.ambiguous => huiText(
        '{route} · duplicate runtime id',
        <String, Object?>{'route': '$prefix${edge.target}'},
      ),
      WorkspaceFlowTargetKind.invalid => huiText(
        '{route} · unknown mode',
        <String, Object?>{'route': '$prefix${edge.mode}'},
      ),
    };
  }

  String _targetLabel(WorkspaceFlowTargetKind kind) => switch (kind) {
    WorkspaceFlowTargetKind.back => huiText('Back'),
    WorkspaceFlowTargetKind.home => huiText('Home'),
    WorkspaceFlowTargetKind.close => huiText('Close'),
    _ => kind.name,
  };

  Widget _sinkIcon(WorkspaceFlowTargetKind kind) => switch (kind) {
    WorkspaceFlowTargetKind.back => ArcaneIcon.undo(size: IconSize.sm),
    WorkspaceFlowTargetKind.home => ArcaneIcon.house(size: IconSize.sm),
    WorkspaceFlowTargetKind.close => ArcaneIcon.x(size: IconSize.sm),
    _ => ArcaneIcon.circle(size: IconSize.sm),
  };

  void _setScope(WorkspacePanelData panel, String raw) {
    component.store.updatePanel(
      panel.copyWith(
        scopeFolderId: raw == _allFoldersValue ? null : raw,
        clearScope: raw == _allFoldersValue,
        positions: const <String, WorkspacePanelPoint>{},
      ),
    );
  }

  void _arrange(WorkspacePanelData panel, WorkspaceFlowGraph graph) {
    component.store.updatePanel(
      panel.copyWith(positions: arrangeWorkspaceFlowGraph(graph)),
    );
  }
}

const String _allFoldersValue = '__all__';

/// Node and sink sizes in board pixels; the CSS draws them at these sizes.
const double _nodeWidth = 228;
const double _nodeHeight = 196;
const double _sinkWidth = 122;

/// From the last node column to the sink column.
const double _sinkGap = 340;
const double _margin = 40;

bool _isSink(WorkspaceFlowTargetKind kind) =>
    kind == WorkspaceFlowTargetKind.back ||
    kind == WorkspaceFlowTargetKind.home ||
    kind == WorkspaceFlowTargetKind.close;

bool _isBroken(WorkspaceFlowEdge edge) =>
    edge.targetKind == WorkspaceFlowTargetKind.dangling ||
    edge.targetKind == WorkspaceFlowTargetKind.ambiguous ||
    edge.targetKind == WorkspaceFlowTargetKind.invalid;

List<WorkspaceFolder> _sortedFolders(Workspace workspace) {
  final List<WorkspaceFolder> folders = List<WorkspaceFolder>.of(
    workspace.folders,
  );
  folders.sort(
    (WorkspaceFolder a, WorkspaceFolder b) =>
        _folderPath(workspace, a).compareTo(_folderPath(workspace, b)),
  );
  return folders;
}

String _folderPath(Workspace workspace, WorkspaceFolder folder) {
  final List<String> parts = <String>[folder.title];
  final Set<String> seen = <String>{folder.id};
  String? parentId = folder.parentId;
  while (parentId != null && seen.add(parentId)) {
    final WorkspaceFolder? parent = workspace.folderById(parentId);
    if (parent == null) break;
    parts.insert(0, parent.title);
    parentId = parent.parentId;
  }
  return parts.join(' / ');
}
