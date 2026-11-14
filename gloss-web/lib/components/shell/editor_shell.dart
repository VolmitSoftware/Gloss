/// The application frame: the one-row top bar, then the library rail, the
/// centre (`<main id="gloss-main" data-view>`) and the inspector, then the
/// save bar slot and the status bar. The frame never scrolls; each pane
/// scrolls on its own. At 1024 px and below the rail and the inspector are
/// drawers (kit [Sheet]s) opened from the top bar.
///
/// Panels built by other modules arrive as slots, so this file depends on
/// none of them. The centre takes two maps keyed by [DocumentSurface] (the
/// editing surfaces and their in-game renderings) plus the two surfaces it
/// keeps mounted in every view.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/kind_groups.dart';
import '../../doctype/doctype.dart';
import '../../logic/advanced_notice.dart'
    show hiddenDocumentsText, hiddenLanding;
import '../../services/editor_sync.dart' show EditorSyncStatus;
import '../../services/file_transfer.dart';
import '../../services/image_library.dart';
import '../../state/editor_scope.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';
import '../../ui/density.dart';
import '../../ui/editor_mode.dart';
import '../../ui/empty_state.dart';
import '../../ui/mode.dart';
import '../../ui/sheet.dart';
import '../../ui/skeleton.dart';
import 'command_palette.dart';
import 'key_listener.dart';
import 'keyboard_shortcuts.dart';
import 'pane_dom.dart';
import 'pane_layout.dart';
import 'pane_splitter.dart';
import 'shell_actions.dart';
import 'shell_intents.dart';
import 'shortcut_sheet.dart';
import 'status_bar.dart';
import 'shell_status.dart';
import 'store_selector.dart';
import 'top_bar.dart';
import 'tour.dart';
import 'view_rules.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

import '../../ui/toast.dart';

/// The one compact rule: drawers at this width and below.
const String compactQuery = '(max-width: 1024px)';

/// The one phone rule.
const String phoneQuery = '(max-width: 760px)';

class EditorShell extends StatefulWidget {
  const EditorShell({
    required this.rail,
    required this.canvas,
    required this.previewCard,
    required this.surfaces,
    required this.previews,
    required this.inspector,
    required this.codeEditor,
    required this.overlays,
    this.store,
    this.status,
    this.onNewDocument,
    this.onOpenTemplates,
    this.onOpenImport,
    this.onOpenExport,
    this.onOpenImages,
    this.onOpenHelp,
    this.onOpenSettings,
    this.onOpenValidation,
    this.onImportBundle,
    this.onExportBundle,
    this.onEraseData,
    this.onOpenServer,
    this.onPairServer,
    this.serverStatus,
    this.onHome,
    this.readOnly = false,
    this.loading,
    this.saveBar,
    this.onCloseOverlay,
    this.onToggleTheme,
    this.darkMode = true,
    this.tourBlocked = false,
    super.key,
  });

  final Widget rail;

  /// The component canvas. Mounted in every view and hidden by CSS; see
  /// [_CenterArea].
  final Widget canvas;

  /// The container-preview card. Mounted in every view and hidden by CSS,
  /// exactly like [canvas].
  final Widget previewCard;

  /// The editing surface for every other kind. Mounted only while its kind
  /// is open in the edit or split view: each of these owns a playback clock.
  final Map<DocumentSurface, Widget> surfaces;

  /// The in-game rendering behind Preview, per surface.
  final Map<DocumentSurface, Widget> previews;

  final Widget inspector;
  final Widget codeEditor;

  /// Dialogs and sheets owned by other modules, mounted above the frame.
  final List<Widget> overlays;

  /// Falls back to the ambient [EditorScope] when omitted.
  final EditorStore? store;

  /// Canvas-owned pointer and zoom readouts for the status bar.
  final ShellStatus? status;

  final void Function()? onNewDocument;
  final void Function()? onOpenTemplates;
  final void Function()? onOpenImport;
  final void Function()? onOpenExport;
  final void Function()? onOpenImages;
  final void Function()? onOpenHelp;
  final void Function()? onOpenSettings;
  final void Function()? onOpenValidation;
  final void Function()? onImportBundle;
  final void Function()? onExportBundle;
  final void Function()? onEraseData;

  /// The top bar's Server button (`#gloss-server-button`).
  final void Function()? onOpenServer;

  /// Pair a server, from the File menu and the command palette.
  final void Function()? onPairServer;

  /// The server status the Server button shows; null in the browser
  /// workspace.
  final EditorSyncStatus? serverStatus;

  /// The top bar's brand: back to the start page.
  final void Function()? onHome;

  /// Another tab holds this workspace: the panes and the centre take no
  /// input (the save bar offers Take over).
  final bool readOnly;

  /// The open document's name while its body is on its way from the
  /// server: the centre says so and the panes take no input.
  final String? loading;

  /// The bottom slot above the status bar (the save bar).
  final Widget? saveBar;

  /// Escape handler for whichever dialog or sheet the owner has open.
  final void Function()? onCloseOverlay;
  final void Function()? onToggleTheme;
  final bool darkMode;

  /// A pairing or sync dialog is up: the tour waits until it is gone.
  final bool tourBlocked;

  @override
  State<EditorShell> createState() => _EditorShellState();
}

class _EditorShellState extends State<EditorShell> {
  EditorStore? _store;
  ShellIntents? _intents;
  void Function()? _uninstallDrop;
  void Function()? _unwatchCompact;
  void Function()? _unwatchPhone;
  bool _apple = false;
  bool _compact = false;
  bool _phone = false;
  bool _paletteOpen = false;
  int _paletteOpens = 0;
  bool _dropActive = false;
  bool _tourWanted = false;
  bool _tourChecked = false;
  bool _shortcutsOpen = false;
  bool _libraryOpen = false;
  bool _inspectorOpen = false;
  String? _lastActiveId;
  EditorView? _lastView;
  PaneLayout _panes = PaneLayout.defaults;

  bool get _tourOpen => _tourWanted && !component.tourBlocked;

  @override
  void initState() {
    super.initState();
    _apple = isApplePlatform();
    _compact = mediaMatches(compactQuery);
    _phone = mediaMatches(phoneQuery);
    _unwatchCompact = watchMedia(compactQuery, (bool matches) {
      if (!mounted || matches == _compact) return;
      setState(() {
        _compact = matches;
        _libraryOpen = false;
        _inspectorOpen = false;
      });
    });
    _unwatchPhone = watchMedia(phoneQuery, (bool matches) {
      if (mounted && matches != _phone) setState(() => _phone = matches);
    });
    // Restored before the first paint: the custom properties live on the
    // document root, so a stored layout never flashes through the defaults.
    _panes = PaneLayout.load();
    _paintPanes();
  }

  void _paintPanes() {
    writePaneVariable(PaneLayout.variableOf(PaneSide.rail), _panes.railWidth);
    writePaneVariable(
      PaneLayout.variableOf(PaneSide.inspector),
      _panes.inspectorWidth,
    );
  }

  /// One rebuild per settled gesture, never one per pointer move.
  void _commitPanes(PaneLayout next) {
    if (next == _panes) return;
    setState(() => _panes = next);
    _paintPanes();
    _panes.persist();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final EditorStore resolved = component.store ?? EditorScope.of(context);
    _maybeStartTour(resolved);
    if (identical(resolved, _store)) {
      _intents = _buildIntents(resolved);
      return;
    }
    _detach();
    _store = resolved;
    _lastActiveId = resolved.workspace.activeId;
    _lastView = resolved.view;
    resolved.workspace.addListener(_onWorkspace);
    resolved.addListener(_onView);
    _intents = _buildIntents(resolved);
    resolved.onError = toastError;
    resolved.onInfo = toastInfo;
    _uninstallDrop = installDropHandler(
      onJson: resolved.importJsonAsNewDocument,
      onImages: _acceptImages,
      onDragActive: (bool active) {
        if (!mounted || active == _dropActive) return;
        setState(() => _dropActive = active);
      },
    );
  }

  @override
  void didUpdateWidget(covariant EditorShell oldComponent) {
    super.didUpdateWidget(oldComponent);
    final EditorStore? store = _store;
    if (store != null) _intents = _buildIntents(store);
  }

  @override
  void dispose() {
    _detach();
    _unwatchCompact?.call();
    _unwatchPhone?.call();
    super.dispose();
  }

  void _detach() {
    _uninstallDrop?.call();
    _uninstallDrop = null;
    final EditorStore? store = _store;
    if (store != null) {
      store.workspace.removeListener(_onWorkspace);
      store.removeListener(_onView);
      store.onError = null;
      store.onInfo = null;
    }
  }

  /// Opening a document from the library drawer closes the drawer.
  void _onWorkspace() {
    final String? active = _store?.workspace.activeId;
    if (active == _lastActiveId) return;
    _lastActiveId = active;
    if (mounted && _libraryOpen) setState(() => _libraryOpen = false);
    _inspectorToTop();
  }

  /// Another view can show another inspector: it starts at its header too.
  void _onView() {
    final EditorView? view = _store?.view;
    if (view == _lastView) return;
    _lastView = view;
    _inspectorToTop();
  }

  /// Another document starts at its header, not where the last one was.
  void _inspectorToTop() {
    if (!mounted) return;
    context.binding.addPostFrameCallback(() => scrollPaneToTop('inspector'));
  }

  ShellIntents _buildIntents(EditorStore store) => ShellIntents(
    store: store,
    openPalette: _openPalette,
    onNewDocument: component.onNewDocument,
    onOpenImport: component.onOpenImport,
    onOpenExport: component.onOpenExport,
    onOpenImages: component.onOpenImages,
    onOpenTemplates: component.onOpenTemplates,
    onOpenHelp: component.onOpenHelp,
    onOpenSettings: component.onOpenSettings,
    onOpenValidation: component.onOpenValidation,
    onToggleTheme: component.onToggleTheme,
    onImportBundle: component.onImportBundle,
    onExportBundle: component.onExportBundle,
    onEraseData: component.onEraseData,
    onOpenServer: component.onOpenServer,
    onPairServer: component.onPairServer,
  );

  void _openPalette() {
    if (_paletteOpen) return;
    setState(() {
      _paletteOpen = true;
      _paletteOpens++;
    });
  }

  /// The arcane runtime claims mod+K and clicks `[data-command-trigger]`,
  /// and the shell's own key table also binds it; both only open, so the
  /// one keydown reaching both never toggles the palette shut again.
  void _triggerPalette() {
    if (_tourOpen || _shortcutsOpen || huiArcaneOverlayOpen()) return;
    _openPalette();
  }

  void _closePalette() {
    if (!_paletteOpen) return;
    setState(() => _paletteOpen = false);
  }

  /// Escape while an arcane surface is open: the shell's own first.
  void _escapeOverlay() {
    if (_paletteOpen) return _closePalette();
    if (_shortcutsOpen) return setState(() => _shortcutsOpen = false);
    if (_libraryOpen || _inspectorOpen) return _closeDrawers();
    component.onCloseOverlay?.call();
  }

  // --- tour and shortcuts ---------------------------------------------------

  /// Runs once per shell, before the first build, so an unseen tour is up in
  /// the first frame. The flag is browser state under `gloss.tour.v1`.
  void _maybeStartTour(EditorStore store) {
    if (_tourChecked) return;
    _tourChecked = true;
    final String? seen = store.workspace.read(huiTourSeenKey);
    if (seen == null || seen.isEmpty) _tourWanted = true;
  }

  void _replayTour() => setState(() {
    _tourWanted = true;
    _libraryOpen = false;
    _inspectorOpen = false;
  });

  void _endTour({required bool remember}) {
    if (remember) _store?.workspace.write(huiTourSeenKey, 'seen');
    setState(() => _tourWanted = false);
  }

  void _toggleShortcuts() => setState(() => _shortcutsOpen = !_shortcutsOpen);

  void _toggleLibrary() => setState(() {
    _libraryOpen = !_libraryOpen;
    if (_libraryOpen) _inspectorOpen = false;
  });

  void _toggleInspector() {
    setState(() {
      _inspectorOpen = !_inspectorOpen;
      if (_inspectorOpen) _libraryOpen = false;
    });
    if (_inspectorOpen) _inspectorToTop();
  }

  void _closeDrawers() {
    if (!_libraryOpen && !_inspectorOpen) return;
    setState(() {
      _libraryOpen = false;
      _inspectorOpen = false;
    });
  }

  Future<void> _acceptImages(List<Object> files) async {
    final ImageLibrary? library = _store?.images;
    if (library == null) {
      toastError(huiText('The image library is not available in this build.'));
      return;
    }
    final ImageAddOutcome outcome = await library.addFromFiles(files);
    for (final String error in outcome.errors) {
      toastError(error);
    }
    for (final String warning in outcome.warnings) {
      toastWarning(warning);
    }
    if (outcome.added.isNotEmpty) {
      toastSuccess(
        huiPlural(
          'images.added_count',
          outcome.added.length,
          oneEnglish: 'Added {count} image.',
          otherEnglish: 'Added {count} images.',
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final EditorStore? store = _store;
    final ShellIntents? intents = _intents;
    if (store == null || intents == null) {
      return const dom.div(classes: 'hui-shell', <Widget>[]);
    }
    return KeyboardShortcuts(
      intents: intents,
      paletteOpen: _paletteOpen,
      tourOpen: _tourOpen,
      shortcutsOpen: _shortcutsOpen,
      mobilePaneOpen: _libraryOpen || _inspectorOpen,
      onTogglePalette: _openPalette,
      onToggleShortcuts: _toggleShortcuts,
      onSkipTour: () => _endTour(remember: false),
      onCloseOverlay: _escapeOverlay,
      onCloseMobilePane: _closeDrawers,
      child: StoreSelector<WorkspaceDocKind>(
        listenable: store,
        selector: () => store.docKind,
        builder: (BuildContext context, WorkspaceDocKind kind) =>
            _frame(store, intents, kindHasInspector(kind)),
      ),
    );
  }

  Widget _frame(EditorStore store, ShellIntents intents, bool hasInspector) {
    final bool compact = _compact;
    return dom.div(
      id: 'hui-shell',
      classes:
          'hui-shell${compact ? ' is-compact' : ''}'
          '${hasInspector ? '' : ' no-inspector'}',
      <Widget>[
        TopBar(
          intents: intents,
          compact: compact,
          phone: _phone,
          hasInspector: hasInspector,
          libraryOpen: _libraryOpen,
          inspectorOpen: _inspectorOpen,
          onToggleLibrary: _toggleLibrary,
          onToggleInspector: _toggleInspector,
          onOpenPalette: _openPalette,
          onShowShortcuts: _toggleShortcuts,
          onReplayTour: _replayTour,
          serverStatus: component.serverStatus,
          onHome: component.onHome,
          darkMode: component.darkMode,
        ),
        // Menus opened in the panes stay below the top bar.
        dom.div(
          classes: 'hui-shell-body',
          attributes: <String, String>{
            'data-arcane-anchor-top': '.hui-shell-body',
            if (component.readOnly || component.loading != null) 'inert': '',
            if (component.loading != null) 'aria-busy': 'true',
          },
          <Widget>[
            if (!compact) ...<Widget>[
              _pane('library', huiText('Library'), component.rail),
              PaneSplitter(
                side: PaneSide.rail,
                layout: _panes,
                onCommit: _commitPanes,
              ),
            ],
            _CenterArea(
              store: store,
              intents: intents,
              compact: compact,
              loading: component.loading,
              canvas: component.canvas,
              previewCard: component.previewCard,
              surfaces: component.surfaces,
              previews: component.previews,
              codeEditor: component.codeEditor,
            ),
            if (!compact && hasInspector) ...<Widget>[
              PaneSplitter(
                side: PaneSide.inspector,
                layout: _panes,
                onCommit: _commitPanes,
              ),
              _pane('inspector', huiText('Inspector'), component.inspector),
            ],
          ],
        ),
        ?component.saveBar,
        StatusBar(
          store: store,
          status: component.status,
          onOpenValidation: component.onOpenValidation,
        ),
        // The runtime's mod+K handler clicks this; never focusable or shown.
        dom.span(
          attributes: const <String, String>{
            'hidden': '',
            'aria-hidden': 'true',
            'data-command-trigger': '',
          },
          events: dom.events<Null>(onClick: _triggerPalette),
          const <Widget>[],
        ),
        if (compact) ...<Widget>[
          Sheet(
            id: 'hui-library-drawer',
            open: _libraryOpen,
            title: huiText('Library'),
            side: SheetSide.start,
            dense: true,
            onClose: _closeDrawers,
            child: _paneBody('library', component.rail),
          ),
          if (hasInspector)
            Sheet(
              id: 'hui-inspector-drawer',
              open: _inspectorOpen,
              title: huiText('Inspector'),
              side: SheetSide.end,
              dense: true,
              onClose: _closeDrawers,
              child: _paneBody('inspector', component.inspector),
            ),
        ],
        ...component.overlays,
        if (_paletteOpen)
          ShellCommandPalette(
            key: ValueKey<int>(_paletteOpens),
            actions: <ShellAction>[
              ...buildShellActions(
                intents,
                onShowShortcuts: _toggleShortcuts,
                onReplayTour: _replayTour,
                advanced: ModeScope.advanced(context),
                compact: compact,
              ),
            ],
            onClose: _closePalette,
            apple: _apple,
          ),
        ShortcutSheet(
          open: _shortcutsOpen,
          onClose: () => setState(() => _shortcutsOpen = false),
          apple: _apple,
        ),
        // Above the other overlays: a spotlight over a region a dialog
        // covers would teach the wrong thing.
        if (_tourOpen)
          HuiTour(
            compact: compact,
            onFinish: () => _endTour(remember: true),
            onSkip: () => _endTour(remember: false),
            onNever: () => _endTour(remember: true),
          ),
        if (_dropActive) const _DropOverlay(),
        // Over the top bar, clear of the inspector, footers, the save bar
        // and previews at every width.
        const AppToaster(),
      ],
    );
  }

  Widget _pane(String name, String label, Widget child) => dom.aside(
    classes: 'hui-pane hui-$name',
    attributes: <String, String>{'data-pane': name, 'aria-label': label},
    <Widget>[
      DenseBox(children: <Widget>[child]),
    ],
  );

  Widget _paneBody(String name, Widget child) => dom.div(
    classes: 'hui-$name',
    attributes: <String, String>{'data-pane': name},
    <Widget>[child],
  );
}

/// The centre: the view the bar shows for the open document. Rebuilds only
/// when the view or kind changes, not on every store notification.
///
/// The canvas cell is child 0 in every arm and the card cell child 1, and
/// both are only ever hidden by CSS, never unmounted: Jaspr replaces a child
/// whose type changed, which would reset zoom and pan and drop every decoded
/// bitmap. The code and preview cells ARE unmounted when inactive: a live
/// code editor re-serializes the document on every change and a live 3D
/// preview keeps a frame loop running.
class _CenterArea extends StatelessWidget {
  const _CenterArea({
    required this.store,
    required this.intents,
    required this.compact,
    required this.canvas,
    required this.previewCard,
    required this.surfaces,
    required this.previews,
    required this.codeEditor,
    this.loading,
  });

  final EditorStore store;
  final ShellIntents intents;
  final bool compact;

  /// The open document's name while its body is on its way.
  final String? loading;
  final Widget canvas;
  final Widget previewCard;
  final Map<DocumentSurface, Widget> surfaces;
  final Map<DocumentSurface, Widget> previews;
  final Widget codeEditor;

  @override
  Widget build(BuildContext context) {
    final String? loading = this.loading;
    if (loading != null) {
      return _main(
        const <String, String>{'data-view': 'loading'},
        <Widget>[
          Skeleton(
            rows: 6,
            label: huiText('Loading {name}', <String, Object?>{
              'name': loading,
            }),
          ),
        ],
      );
    }
    final bool advanced = ModeScope.advanced(context);
    return StoreSelector<
      ({bool active, EditorView view, DocumentSurface surface, int hidden})
    >(
      listenable: store,
      selector: () => (
        active: store.hasActiveDocument,
        view: shownView(
          store.view,
          store.docKind,
          advanced: advanced,
          compact: compact,
        ),
        surface: store.docType.surface,
        hidden: advanced || store.hasActiveDocument
            ? 0
            : _onlyHidden(store.workspace.docs),
      ),
      builder:
          (
            BuildContext context,
            ({
              bool active,
              EditorView view,
              DocumentSurface surface,
              int hidden,
            })
            state,
          ) => state.active
          ? _active(state.view, state.surface)
          : state.hidden > 0
          ? _main(
              <String, String>{
                'data-view': 'empty',
                'data-advanced-documents': '${state.hidden}',
              },
              <Widget>[
                EmptyState(
                  message: hiddenDocumentsText(state.hidden),
                  icon: ArcaneIcon.eyeOff(),
                  action: Button(
                    label: huiText('Switch to Advanced'),
                    variant: ButtonVariant.primary,
                    attributes: const <String, String>{
                      'data-action-slot': 'primary',
                    },
                    onPressed: () => _showInAdvanced(context),
                  ),
                ),
              ],
            )
          : _main(
              const <String, String>{
                'data-view': 'empty',
                'data-empty-workspace': '',
              },
              <Widget>[
                EmptyState(
                  message: huiText('Workspace is empty'),
                  icon: ArcaneIcon.filePlus(),
                  action: Button(
                    label: huiText('New document'),
                    variant: ButtonVariant.primary,
                    attributes: const <String, String>{
                      'data-action-slot': 'primary',
                    },
                    onPressed: intents.newDocument,
                  ),
                ),
              ],
            ),
    );
  }

  /// How many documents a Simple workspace holds when Simple lists none of
  /// them; 0 when it lists one or holds none.
  static int _onlyHidden(List<WorkspaceDoc> docs) =>
      docs.any((WorkspaceDoc doc) => listedInMode(doc, advanced: false))
      ? 0
      : docs.length;

  /// Advanced lists every kind; the newest hidden document opens.
  void _showInAdvanced(BuildContext context) {
    ModeScope.maybeOf(context)?.onChanged(EditorMode.advanced);
    final String? landing = hiddenLanding(store.workspace.docs);
    if (landing != null) store.openDocument(landing);
  }

  /// `<main id="gloss-main" data-view>`, the centre the checks read.
  static Widget _main(Map<String, String> attributes, List<Widget> children) =>
      Component.element(
        tag: 'main',
        id: 'gloss-main',
        attributes: attributes,
        children: children,
      );

  Widget _active(EditorView view, DocumentSurface surface) => _main(
    <String, String>{'data-view': view.name, 'data-surface': surface.name},
    <Widget>[
      dom.div(classes: 'hui-split-cell is-canvas', <Widget>[canvas]),
      dom.div(classes: 'hui-split-cell is-preview-card', <Widget>[previewCard]),
      if (_mountsSurfaceCell(view, surface))
        dom.div(
          key: ValueKey<String>('surface-${surface.name}'),
          classes: 'hui-split-cell is-surface',
          <Widget>[surfaces[surface] ?? _missing(surface)],
        ),
      if (view == EditorView.preview)
        dom.div(
          key: ValueKey<String>('preview-${surface.name}'),
          classes: 'hui-split-cell is-preview',
          <Widget>[previews[surface] ?? _missing(surface)],
        ),
      if (view == EditorView.code || view == EditorView.split)
        dom.div(
          key: const ValueKey<String>('code'),
          classes: 'hui-split-cell is-code',
          <Widget>[codeEditor],
        ),
    ],
  );

  /// The two always-mounted surfaces never get a second cell; every other
  /// surface is mounted for exactly as long as it is on screen.
  static bool _mountsSurfaceCell(EditorView view, DocumentSurface surface) =>
      (view == EditorView.visual || view == EditorView.split) &&
      surface != DocumentSurface.canvas &&
      surface != DocumentSurface.previewCard;

  /// A slot the owner did not supply: a wiring mistake, named.
  static Widget _missing(DocumentSurface surface) => EmptyState(
    message: huiText('That surface is not in this build'),
    icon: ArcaneIcon.triangleAlert(),
  );
}

class _DropOverlay extends StatelessWidget {
  const _DropOverlay();

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'hui-dropzone',
    attributes: const <String, String>{'aria-hidden': 'true'},
    <Widget>[
      EmptyState(
        icon: ArcaneIcon.upload(),
        message: huiText('Drop a menu .json or PNG images'),
      ),
    ],
  );
}
