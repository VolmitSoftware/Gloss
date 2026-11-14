/// The one-row top bar: brand, File menu, the open document's title (renamed
/// in place), the View switch, Undo/Redo, Search, Server, the
/// Simple/Advanced switch and the Help menu. At 1024 px and below the rail
/// and inspector open as drawers from here.
///
/// Each width keeps the same controls whatever the kind ([barLayout]); the
/// rest waits in the "..." menu. When a long title still does not fit, the
/// title ellipsizes first, then, measured after layout and on resize, the
/// mode switch moves into the menu, the View labels drop, and the action row
/// folds its buttons one by one.
///
/// Rebuilds are narrowed to a signature of the values it renders, so canvas
/// drags (which notify the store on every pointer move) do not re-render it.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show EventCallback, Listenable;
import 'package:web/web.dart' as web;

import '../../l10n/hui_localizations.dart';
import '../../services/editor_sync.dart' show EditorSyncStatus;
import '../../state/editor_store.dart';
import '../../state/workspace.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/editor_mode.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/save_state.dart';
import '../../ui/toggle_choice.dart';
import 'doc_title.dart';
import 'shell_intents.dart';
import 'store_selector.dart';
import 'view_rules.dart';
import 'pane_dom.dart' show coarsePointerQuery, mediaMatches;

class TopBar extends StatefulWidget {
  const TopBar({
    required this.intents,
    required this.compact,
    required this.phone,
    required this.hasInspector,
    required this.libraryOpen,
    required this.inspectorOpen,
    required this.onToggleLibrary,
    required this.onToggleInspector,
    required this.onOpenPalette,
    required this.onShowShortcuts,
    required this.onReplayTour,
    this.serverStatus,
    this.onHome,
    this.darkMode = true,
    super.key,
  });

  final ShellIntents intents;

  /// 1024 px and below: the rail and the inspector are drawers.
  final bool compact;

  /// 760 px and below: the phone layout ([BarWidth.phone]).
  final bool phone;

  /// Catalog kinds edit in the centre and have no inspector to open.
  final bool hasInspector;
  final bool libraryOpen;
  final bool inspectorOpen;
  final void Function() onToggleLibrary;
  final void Function() onToggleInspector;
  final void Function() onOpenPalette;
  final void Function() onShowShortcuts;
  final void Function() onReplayTour;

  /// The link status on the Server button; "Server" while not paired.
  final EditorSyncStatus? serverStatus;

  /// The brand leads to the start page.
  final void Function()? onHome;
  final bool darkMode;

  @override
  State<TopBar> createState() => _TopBarState();
}

class _TopBarState extends State<TopBar> {
  Listenable? _sources;

  /// Measured fold steps past the width's layout ([barLayout]); only the
  /// wide layout has steps to take.
  int _fold = 0;
  bool _wide = true;

  /// What the fold was measured for; a new kind, mode or width starts over.
  String _foldKey = '';
  double _width = 0;
  web.ResizeObserver? _observer;

  EditorStore get _store => component.intents.store;

  ShellIntents get _intents => component.intents;

  /// Cached: a fresh merged listenable on every build would re-subscribe the
  /// selector each time.
  Listenable get _listenable =>
      _sources ??= Listenable.merge(<Listenable?>[_store, _store.workspace]);

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant TopBar oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.intents.store, component.intents.store)) {
      _sources = null;
    }
  }

  String _signature() {
    final WorkspaceDoc? active = _store.workspace.active;
    return <Object?>[
      active?.id,
      active?.title,
      active?.runtimeId,
      _store.view.name,
      _store.docKind.name,
      _store.hasActiveDocument,
      _store.canTransferDocument,
      _store.canUndo,
      _store.canRedo,
      _store.undoLabel,
      _store.redoLabel,
    ].join('|');
  }

  @override
  Widget build(BuildContext context) => StoreSelector<String>(
    listenable: _listenable,
    selector: _signature,
    builder: (BuildContext context, String signature) => _bar(context),
  );

  Widget _bar(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final ModeScope? scope = ModeScope.maybeOf(context);
    final BarWidth width = barWidth(
      compact: component.compact,
      phone: component.phone,
    );
    final String key =
        '${width.name}|${_store.docKind.name}|$advanced|${component.hasInspector}';
    if (key != _foldKey) {
      _foldKey = key;
      _fold = 0;
    }
    _wide = width == BarWidth.wide;
    final BarLayout layout = barLayout(width, fold: _fold);
    final bool showMode = layout.mode && scope != null;
    web.window.requestAnimationFrame(
      ((JSNumber _) {
        _observe();
        _measure();
      }).toJS,
    );
    final List<RowAction> history = <RowAction>[
      RowAction(
        _store.undoLabel == null
            ? huiText('Undo')
            : huiText('Undo {action}', <String, Object?>{
                'action': _store.undoLabel,
              }),
        _store.canUndo ? _intents.undo : null,
        icon: ArcaneIcon.undo(),
        iconOnly: true,
        attributes: _keys('mod+Z'),
      ),
      RowAction(
        _store.redoLabel == null
            ? huiText('Redo')
            : huiText('Redo {action}', <String, Object?>{
                'action': _store.redoLabel,
              }),
        _store.canRedo ? _intents.redo : null,
        icon: ArcaneIcon.redo(),
        iconOnly: true,
        attributes: _keys('mod+Shift+Z'),
      ),
    ];
    final RowAction search = RowAction(
      huiText('Search'),
      component.onOpenPalette,
      id: 'hui-bar-search',
      icon: ArcaneIcon.search(),
      iconOnly: !layout.actionLabels,
      attributes: _keys('mod+K'),
    );
    final RowAction server = _serverButton(iconOnly: !layout.actionLabels);
    final List<RowAction> waiting = <RowAction>[
      if (!layout.history) ...history,
      if (!layout.search) search,
      if (!layout.server) server,
    ];
    final List<AppMenuEntry> views =
        component.phone && _store.hasActiveDocument
        ? _viewEntries(advanced)
        : const <AppMenuEntry>[];
    return DenseBox(
      classes: 'hui-bar',
      attributes: <String, String>{'role': 'banner', 'id': 'hui-bar'},
      children: <Widget>[
        if (component.compact)
          AppIconButton(
            id: 'hui-open-library',
            icon: ArcaneIcon.panelLeft(),
            label: component.libraryOpen
                ? huiText('Close library')
                : huiText('Open library'),
            pressed: component.libraryOpen,
            onPressed: component.onToggleLibrary,
          ),
        _brand(),
        AppMenu(
          id: 'hui-file-menu',
          label: component.phone ? null : huiText('File'),
          icon: component.phone ? ArcaneIcon.fileText() : null,
          iconLabel: huiText('File'),
          entries: _fileEntries(),
        ),
        _title(advanced),
        // A phone gives the title the room: the views go in `...`.
        if (_store.hasActiveDocument && !component.phone)
          _viewSwitch(advanced, layout),
        dom.div(id: 'hui-bar-actions', classes: 'hui-bar-actions', <Widget>[
          ActionRow(
            dense: true,
            label: huiText('Editor'),
            moreId: 'hui-bar-fold',
            secondary: <RowAction>[
              if (layout.history) ...history,
              if (layout.search) search,
              if (layout.server) server,
              if (component.compact &&
                  component.hasInspector &&
                  _store.hasActiveDocument)
                RowAction(
                  component.inspectorOpen
                      ? huiText('Close inspector')
                      : huiText('Open inspector'),
                  component.onToggleInspector,
                  id: 'hui-open-inspector',
                  icon: ArcaneIcon.panelRight(),
                  iconOnly: true,
                  pressed: component.inspectorOpen,
                ),
            ],
          ),
        ]),
        // What the bar folds, and the mode once it no longer fits, go in
        // `...` where the mode switch stands on a wide bar: last before Help.
        if (!showMode &&
            (waiting.isNotEmpty || scope != null || views.isNotEmpty))
          AppMenu(
            id: 'hui-bar-more',
            icon: ArcaneIcon.ellipsis(),
            iconLabel: huiText('More actions'),
            alignEnd: true,
            entries: <AppMenuEntry>[
              ...views,
              if (views.isNotEmpty &&
                  (waiting.isNotEmpty || scope != null))
                const AppMenuEntry.separator(),
              for (final RowAction action in waiting)
                AppMenuEntry(
                  action.label,
                  action.onPressed,
                  icon: action.icon?.sized(controlIconSize),
                ),
              if (waiting.isNotEmpty && scope != null)
                const AppMenuEntry.separator(),
              if (scope != null) ..._modeEntries(scope),
            ],
          ),
        if (showMode)
          dom.div(classes: 'hui-bar-switch', <Widget>[
            ToggleChoice<EditorMode>(
              id: 'hui-mode-switch',
              label: huiText('Mode'),
              value: scope.mode,
              onChanged: scope.onChanged,
              options: <ChoiceOption<EditorMode>>[
                for (final EditorMode mode in EditorMode.values)
                  ChoiceOption<EditorMode>(mode, mode.label),
              ],
            ),
          ]),
        AppMenu(
          id: 'hui-help-menu',
          icon: ArcaneIcon.circleQuestionMark(),
          iconLabel: huiText('Help'),
          alignEnd: true,
          entries: _helpEntries(),
        ),
      ],
    );
  }

  void _observe() {
    if (!mounted || _observer != null) return;
    final web.Element? bar = web.document.getElementById('hui-bar');
    if (bar == null) return;
    _observer = web.ResizeObserver(
      ((
        JSArray<web.ResizeObserverEntry> _,
        web.ResizeObserver _,
      ) => _resized()).toJS,
    );
    _observer!.observe(bar);
  }

  /// A wider bar starts the measurement over from the width's layout.
  void _resized() {
    final web.Element? bar = web.document.getElementById('hui-bar');
    if (bar == null || !mounted) return;
    final double width = bar.getBoundingClientRect().width;
    final bool grew = width > _width + 1;
    _width = width;
    if (grew && _fold > 0) {
      setState(() => _fold = 0);
      return;
    }
    _measure();
  }

  /// One more fold step while the bar does not fit: the action row's
  /// buttons stick out past its start (it is right-aligned), or the last
  /// control past the bar's end.
  void _measure() {
    if (!mounted || !_wide || _fold >= barFoldSteps) return;
    final web.Element? bar = web.document.getElementById('hui-bar');
    final web.Element? actions = web.document.getElementById('hui-bar-actions');
    // The action row's end group: its last child.
    final web.Element? end = actions?.firstElementChild?.lastElementChild;
    final web.Element? last = bar?.lastElementChild;
    if (bar == null || actions == null || end == null || last == null) return;
    final bool over =
        end.getBoundingClientRect().left <
            actions.getBoundingClientRect().left - 1 ||
        last.getBoundingClientRect().right >
            bar.getBoundingClientRect().right + 1;
    if (over) setState(() => _fold++);
  }

  List<AppMenuEntry> _modeEntries(ModeScope scope) => <AppMenuEntry>[
    AppMenuEntry.heading(huiText('Mode')),
    for (final EditorMode mode in EditorMode.values)
      AppMenuEntry.radio(
        mode.label,
        group: 'gloss-mode',
        value: mode.name,
        checked: scope.mode == mode,
        onPicked: (String _) => scope.onChanged(mode),
      ),
  ];

  List<AppMenuEntry> _fileEntries() {
    final bool transfer = _store.canTransferDocument;
    final String noun = _store.docType.noun;
    return <AppMenuEntry>[
      AppMenuEntry(
        huiText('New document'),
        _intents.newDocument,
        icon: ArcaneIcon.filePlus(),
      ),
      AppMenuEntry(
        huiText('Templates'),
        _intents.openTemplates,
        icon: ArcaneIcon.layoutTemplate(),
      ),
      const AppMenuEntry.separator(),
      AppMenuEntry(
        huiText('Import JSON'),
        transfer ? () => _intents.importMenu() : null,
        icon: ArcaneIcon.upload(),
      ),
      AppMenuEntry(
        huiText('Export {document} JSON', <String, Object?>{'document': noun}),
        transfer ? _intents.exportMenu : null,
        icon: ArcaneIcon.download(),
      ),
      AppMenuEntry(
        huiText('Copy {document} JSON', <String, Object?>{'document': noun}),
        transfer ? () => _intents.copyJson() : null,
        icon: ArcaneIcon.copy(),
      ),
      const AppMenuEntry.separator(),
      AppMenuEntry(
        huiText('Images'),
        _intents.openImages,
        icon: ArcaneIcon.images(),
      ),
      const AppMenuEntry.separator(),
      AppMenuEntry(
        huiText('Pair a server'),
        _intents.onPairServer == null ? null : _intents.pairServer,
        icon: ArcaneIcon.link(),
      ),
      const AppMenuEntry.separator(),
      AppMenuEntry.heading(huiText('Workspace')),
      AppMenuEntry(
        huiText('Import workspace bundle'),
        _intents.importBundle,
        icon: ArcaneIcon.folderInput(),
      ),
      AppMenuEntry(
        huiText('Export workspace bundle'),
        _intents.exportBundle,
        icon: ArcaneIcon.folderOutput(),
      ),
      AppMenuEntry(
        huiText('Erase all local data'),
        _intents.eraseData,
        icon: ArcaneIcon.trash2(),
        destructive: true,
      ),
    ];
  }

  List<AppMenuEntry> _helpEntries() => <AppMenuEntry>[
    AppMenuEntry(
      huiText('Help'),
      _intents.openHelp,
      icon: ArcaneIcon.bookOpen(),
    ),
    // A touch screen has no keys to list.
    if (!mediaMatches(coarsePointerQuery))
      AppMenuEntry(
        huiText('Keyboard shortcuts'),
        component.onShowShortcuts,
        icon: ArcaneIcon.keyboard(),
      ),
    AppMenuEntry(
      huiText('Guided tour'),
      component.onReplayTour,
      icon: ArcaneIcon.compass(),
    ),
    AppMenuEntry(
      huiText('Settings'),
      _intents.openSettings,
      icon: ArcaneIcon.settings(),
    ),
    const AppMenuEntry.separator(),
    AppMenuEntry.checkbox(
      huiText('Dark theme'),
      checked: component.darkMode,
      onChanged: (bool _) => _intents.toggleTheme(),
      icon: ArcaneIcon.moon(),
    ),
  ];

  Widget _brand() {
    const List<Widget> mark = <Widget>[
      dom.img(
        src: 'assets/brand/logo.png',
        alt: '',
        classes: 'hui-brand-mark',
        attributes: <String, String>{'aria-hidden': 'true'},
      ),
      dom.span(classes: 'hui-brand-word', <Widget>[Text('Gloss')]),
    ];
    final void Function()? home = component.onHome;
    if (home == null) return const dom.div(classes: 'hui-brand', mark);
    return dom.a(
      href: '#/',
      classes: 'hui-brand',
      events: <String, EventCallback>{
        'click': (web.Event event) {
          event.preventDefault();
          home();
        },
      },
      attributes: <String, String>{
        'aria-label': huiText('Start page'),
        'id': 'gloss-home',
      },
      styles: const dom.Styles(
        raw: <String, String>{'color': 'inherit', 'text-decoration': 'none'},
      ),
      mark,
    );
  }

  /// The open document's title, renamed in place; its runtime id beside it
  /// in Advanced.
  Widget _title(bool advanced) {
    final WorkspaceDoc? active = _store.workspace.active;
    if (active == null) {
      return dom.div(classes: 'hui-doc', <Widget>[
        dom.span(classes: 'hui-doc-title is-empty', <Widget>[
          Text(huiText('No document')),
        ]),
      ]);
    }
    final String? runtimeId = active.runtimeId;
    return dom.div(
      classes: 'hui-doc',
      attributes: <String, String>{
        'role': 'group',
        'aria-label': huiText('Document title'),
      },
      <Widget>[
        DocTitle(
          key: ValueKey<String>(active.id),
          title: active.title,
          onRename: (String title) =>
              _store.workspace.renameDocumentTitle(active.id, title),
        ),
        if (advanced && runtimeId != null && runtimeId != active.title)
          dom.span(classes: 'hui-doc-id', <Widget>[Text(runtimeId)]),
      ],
    );
  }

  /// One View switch for every kind: Edit, then Preview, Code and Split
  /// where the kind offers them. A kind with one view shows none.
  Widget _viewSwitch(bool advanced, BarLayout layout) {
    final ViewChoices choices = viewChoices(
      _store.docType,
      advanced: advanced,
      compact: component.compact,
    );
    if (!choices.any) return const dom.span(<Widget>[]);
    final EditorView shown = shownView(
      _store.view,
      _store.docKind,
      advanced: advanced,
      compact: component.compact,
    );
    return dom.div(id: 'hui-view-controls', classes: 'hui-bar-switch', <Widget>[
      ToggleChoice<EditorView>(
        id: 'hui-view-switch',
        label: huiText('View'),
        value: shown,
        onChanged: _intents.setView,
        options: <ChoiceOption<EditorView>>[
          for (final EditorView view in choices.options)
            ChoiceOption<EditorView>(
              view,
              _viewLabel(view),
              icon: _viewIcon(view),
              iconOnly: !layout.viewLabels,
            ),
        ],
      ),
    ]);
  }

  /// The View switch as menu choices, for a phone's `...` menu.
  List<AppMenuEntry> _viewEntries(bool advanced) {
    final ViewChoices choices = viewChoices(
      _store.docType,
      advanced: advanced,
      compact: component.compact,
    );
    if (!choices.any) return const <AppMenuEntry>[];
    final EditorView shown = shownView(
      _store.view,
      _store.docKind,
      advanced: advanced,
      compact: component.compact,
    );
    return <AppMenuEntry>[
      AppMenuEntry.heading(huiText('View')),
      for (final EditorView view in choices.options)
        AppMenuEntry.radio(
          _viewLabel(view),
          group: 'gloss-view',
          value: view.name,
          checked: shown == view,
          onPicked: (String _) => _intents.setView(view),
        ),
    ];
  }

  String _viewLabel(EditorView view) => switch (view) {
    EditorView.visual => huiText('Edit'),
    EditorView.preview => huiText('Preview'),
    EditorView.code => huiText('Code'),
    EditorView.split => huiText('Split view'),
  };

  ArcaneGlyph _viewIcon(EditorView view) => switch (view) {
    EditorView.visual => ArcaneIcon.pencil(),
    EditorView.preview => ArcaneIcon.eye(),
    EditorView.code => ArcaneIcon.code(),
    EditorView.split => ArcaneIcon.columns2(),
  };

  /// Server, or the link status with its tone as the icon: arrows while
  /// busy, a check once applied, an alert when the link needs attention. It
  /// stays clickable in every state (the sheet explains each one).
  RowAction _serverButton({required bool iconOnly}) {
    final EditorSyncStatus? status = component.serverStatus;
    final SaveTone? tone = status == null ? null : editorSyncStatusTone(status);
    return RowAction(
      status == null ? huiText('Server') : editorSyncStatusLabel(status),
      _intents.openServer,
      id: 'gloss-server-button',
      iconOnly: iconOnly,
      icon: switch (tone) {
        SaveTone.busy => ArcaneIcon.refreshCw(),
        SaveTone.done => ArcaneIcon.cloudCheck(),
        SaveTone.attention || SaveTone.failed => ArcaneIcon.circleAlert(),
        _ => ArcaneIcon.server(),
      },
      attributes: <String, String>{
        'data-sync-action': 'open',
        if (status != null) 'data-sync-status': status.name,
        if (tone != null) 'data-sync-tone': tone.name,
      },
    );
  }

  /// `mod+Shift+Z` advertises as both platform chords, which is what screen
  /// readers expect from `aria-keyshortcuts`.
  Map<String, String> _keys(String spec) {
    final List<String> parts = spec.split('+');
    final String tail = parts
        .skip(1)
        .map((String token) => token.length == 1 ? token.toUpperCase() : token)
        .join('+');
    return <String, String>{'aria-keyshortcuts': 'Meta+$tail Control+$tail'};
  }
}
