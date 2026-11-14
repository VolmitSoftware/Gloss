import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import 'config/gloss_stylesheet.dart';
import 'config/kind_groups.dart';
import 'ui/confirm_dialog.dart';
import 'ui/editor_mode.dart';
import 'ui/mode.dart';
import 'ui/save_state.dart';
import 'ui/skeleton.dart';

import 'components/canvas/canvas.dart';
import 'components/panel/panel.dart';
import 'components/code_editor/code_editor_view.dart';
import 'components/dialogs/dialogs.dart';
import 'components/dialogs/new_document_dialog.dart';
import 'components/dialogs/workspace_dialogs.dart';
import 'components/animation/animation_view.dart';
import 'components/bubble/bubble_view.dart';
import 'components/damage_indicators/damage_indicator_view.dart';
import 'components/emoji/emoji_view.dart';
import 'components/entity_overlays/entity_overlay_view.dart';
import 'components/connections/connections_view.dart';
import 'components/channel/channel_view.dart';
import 'components/motd/motd_view.dart';
import 'components/scoreboard/scoreboard_view.dart';
import 'components/surface/surface_view.dart';
import 'components/real_drops/real_drops_view.dart';
import 'components/tablist/tablist_view.dart';
import 'components/hologram/hologram_view.dart';
import 'components/lanes/lane_views.dart';
import 'components/leaderboard/leaderboard_view.dart';
import 'components/inspector/inspector.dart';
import 'components/panels/panels.dart';
import 'components/preview/preview_view.dart';
import 'components/preview_card/preview_card.dart';
import 'components/server/pair_dialog.dart';
import 'components/server/pair_page.dart';
import 'components/server/server_sheet.dart';
import 'components/server/start_page.dart';
import 'components/server/start_parts.dart';
import 'components/server/start_rows.dart';
import 'components/shell/shell.dart';
import 'config/defaults.dart';
import 'model/model.dart';
import 'services/catalogs.dart';
import 'services/editor_sync.dart';
import 'services/editor_sync_controller.dart';
import 'services/editor_sync_workspace.dart';
import 'services/file_transfer.dart';
import 'services/image_library.dart';
import 'services/local_data_reset.dart';
import 'services/page_lifecycle.dart';
import 'services/server/browser_hub.dart';
import 'services/server/server_hub.dart';
import 'services/server/server_profiles.dart';
import 'services/tab_lock.dart';
import 'services/workspace_location.dart';
import 'state/editor_scope.dart';
import 'doctype/doctype.dart';
import 'components/inspector/names_inspector.dart';
import 'components/inspector/catalog_document_inspector.dart';
import 'components/inspector/glyph_inspector.dart';
import 'components/inspector/behavior_inspector.dart';
import 'components/inspector/presets_editor.dart';
import 'l10n/hui_locale_loader.dart';
import 'l10n/hui_locale_preferences.dart';
import 'l10n/hui_localizations.dart';
import 'logic/advanced_notice.dart';
import 'logic/preview_sim.dart';
import 'state/editor_store.dart';
import 'state/workspace.dart';
import 'state/workspace_bundle.dart';
import 'state/workspace_repository.dart';
import 'state/workspace_route.dart';
import 'theme/theme_state.dart';
import 'ui/toast.dart';

enum _EditorDialog {
  none,
  import,
  export,
  images,
  settings,
  help,
  newDocument,
  workspaceBundle,
  eraseData,
}

/// What the page shows: the start page, a pairing card, a workspace being
/// opened, or the editor on a workspace.
enum _Screen { start, pair, opening, editor }

/// One open workspace: the browser workspace ([serverId] null) or a paired
/// server's, with its store and, for a server, its sync controller.
final class _Session {
  _Session({
    required this.workspace,
    required this.images,
    required this.store,
    this.serverId,
    this.sync,
  });

  final String? serverId;
  final Workspace workspace;
  final ImageLibrary images;
  final EditorStore store;
  final EditorSyncController? sync;

  /// Another tab of this browser holds this workspace.
  bool waiting = false;

  /// The tab lock's name: the workspace's storage record.
  String get lockName => workspace.key;

  String get key => serverId == null ? 'local' : 'server:$serverId';

  void dispose() {
    sync?.dispose();
    store.dispose();
  }
}

class App extends StatefulWidget {
  const App({
    required this.workspace,
    required this.images,
    required this.localeController,
    super.key,
  });

  /// The browser workspace as the page loaded it.
  final Workspace workspace;
  final ImageLibrary images;
  final HuiLocaleController localeController;

  @override
  State<App> createState() => _AppState();
}

class _AppState extends State<App> {
  void Function(NewDocumentChoice choice)? _createNewDocument;
  NewDocumentStart _newDocumentStart = NewDocumentStart.blank;
  WorkspaceBundleDecodeResult? _bundle;
  String? _bundleFileName;
  bool _importingBundle = false;
  bool _erasing = false;
  Brightness _brightness = Brightness.dark;
  late EditorMode _mode;
  late String _activeLocale;
  bool _localeLoading = false;
  int _localeRequest = 0;

  final ShellStatus _status = ShellStatus();
  HuiCatalogs? _catalogs;
  WorkspaceMenuImportEnvelope? _menuHandoff;
  late final ServerHub _hub;
  final TabLock _locks = createTabLock();
  void Function() _stopHashListener = () {};
  void Function() _stopPageExitListener = () {};
  bool _applyingHash = false;

  _Screen _screen = _Screen.start;
  String _pairCode = '';
  String _openingName = '';
  int _openTicket = 0;

  /// The browser workspace as last loaded (the start page counts it).
  late Workspace _local;
  late ImageLibrary _localImages;
  bool _localFresh = true;
  _Session? _session;
  bool _pairDialogOpen = false;

  _EditorDialog _dialog = _EditorDialog.none;
  bool _validationOpen = false;

  /// Arrivals that land together (files dropped at once, a listing in
  /// batches) and the notice they share.
  final ArrivalNotices _arrivals = ArrivalNotices(
    window: const Duration(seconds: 1),
  );
  String? _arrivalToast;
  ({String? documentId, Vec3 offset})? _pendingCanvasMedia;

  EditorStore get _store => _session!.store;

  ImageLibrary get _images => _session!.images;

  EditorSyncController? get _sync => _session?.sync;

  @override
  void initState() {
    super.initState();
    _activeLocale = component.localeController.activeLocale;
    stampHuiLocaleDocument(_activeLocale);
    _brightness = loadStoredBrightness();
    stampDocumentTheme(_brightness);
    _mode = loadMode(hasWorkspace: !component.workspace.isEmpty);
    _local = component.workspace;
    _localImages = component.images;
    _hub = createBrowserHub(onForget: _forgetWorkspace);
    _hub.addListener(_onHubChanged);
    listenBrowserWake(_hub.wake);
    _stopPageExitListener = listenForPageExit(() {
      final _Session? session = _session;
      if (session == null || session.waiting) return;
      session.store.flushAutosave(notify: false);
      session.workspace.rescueOnExit();
    });
    _stopHashListener = listenWorkspaceLocationHash(_onHashChange);
    _applyHash(readWorkspaceLocationHash(), initial: true);

    HuiCatalogs.load().then((HuiCatalogs catalogs) {
      if (!mounted) return;
      final HuiCatalogs localized = _withActivePreviewLocale(catalogs);
      setState(() {
        _catalogs = localized;
        _session?.store.setCatalogs(localized);
      });
    });
  }

  Future<void> _changeLocale(String locale) async {
    if (locale == _activeLocale && !_localeLoading) return;
    final int request = ++_localeRequest;
    setState(() => _localeLoading = true);
    final HuiLocaleInstallResult result = await component.localeController
        .activate(locale);
    if (!mounted || request != _localeRequest) return;
    if (!result.applied) {
      setState(() => _localeLoading = false);
      toastError(
        huiText(
          'Could not change language. The current language is still active.',
        ),
      );
      return;
    }
    final String activeLocale = component.localeController.activeLocale;
    persistHuiLocale(activeLocale);
    stampHuiLocaleDocument(activeLocale);
    final HuiCatalogs? current = _catalogs;
    final HuiCatalogs? localized = current == null
        ? null
        : _withActivePreviewLocale(current);
    setState(() {
      _activeLocale = activeLocale;
      _localeLoading = false;
      _catalogs = localized;
    });
    if (localized != null) _session?.store.setCatalogs(localized);
  }

  HuiCatalogs _withActivePreviewLocale(HuiCatalogs catalogs) {
    final Map<String, String> messages =
        huiLocalizations.snapshot.previewMessages;
    if (messages.isEmpty) return catalogs;
    return catalogs.withPreviewLang(PreviewLangCatalog(messages));
  }

  @override
  void dispose() {
    _stopPageExitListener();
    _stopHashListener();
    _closeSession();
    _hub.removeListener(_onHubChanged);
    _hub.dispose();
    super.dispose();
  }

  void _onHubChanged() {
    if (mounted && _screen != _Screen.editor) setState(() {});
  }

  void _toast(String message, {bool error = false}) {
    if (error) {
      toastError(message);
    } else {
      toastSuccess(message);
    }
  }

  // --- screens and workspaces ----------------------------------------------

  /// The hash this page last opened or wrote: its own hashchange event is
  /// not news.
  String _appliedHash = '';

  /// Goes to [hash] and opens what it names at once.
  void _go(String hash) {
    writeWorkspaceLocationHash(hash);
    _applyHash(hash);
  }

  /// The address changed (back, forward, a pasted link).
  void _onHashChange(String hash) {
    if (hash == _appliedHash) return;
    _applyHash(hash);
  }

  void _applyHash(String hash, {bool initial = false}) {
    if (_applyingHash) return;
    _appliedHash = hash;
    final WorkspaceRouteResult parsed = parseWorkspaceRoute(hash);
    final WorkspaceRoute? route = parsed.route;
    switch (route) {
      case WorkspaceStartRoute():
        _show(_Screen.start, notify: !initial);
      case WorkspacePairRoute(:final String code):
        if (code.isNotEmpty) {
          _pairCode = code;
          // The secret stays out of history and screenshots.
          _appliedHash = '#/pair';
          writeWorkspaceLocationHash('#/pair', replace: true);
        }
        _show(_Screen.pair, notify: !initial);
      case WorkspaceLocalRoute():
        unawaited(_openLocal());
      case WorkspaceDocumentRoute(
        :final String workspaceId,
        :final String documentId,
      ):
        if (workspaceId != _local.id) {
          _reportRouteError(
            huiText(
              'That link belongs to a different local workspace. Import its workspace bundle first.',
            ),
          );
          unawaited(_openLocal());
        } else {
          unawaited(_openLocal(documentId: documentId));
        }
      case WorkspaceServerRoute(
        :final String serverId,
        :final String? documentId,
      ):
        unawaited(_openServer(serverId, documentId: documentId));
      case WorkspaceMenuImportRoute(
        :final WorkspaceMenuImportEnvelope envelope,
      ):
        _menuHandoff = envelope;
        unawaited(_openLocal());
      case null:
        _reportRouteError(
          parsed.error ?? huiText('The editor link is not supported.'),
        );
        _show(_Screen.start, notify: !initial);
    }
  }

  void _show(_Screen screen, {bool notify = true}) {
    if (notify) {
      setState(() => _screen = screen);
    } else {
      _screen = screen;
    }
  }

  /// Opens the browser workspace (the one the page loaded the first time,
  /// a fresh read after that), then [documentId] in it.
  Future<void> _openLocal({
    String? documentId,
    bool reopen = false,
    bool keepLock = false,
  }) async {
    final _Session? current = _session;
    if (!reopen && current != null && current.serverId == null) {
      _openDocument(current, documentId);
      _show(_Screen.editor);
      return;
    }
    final int ticket = ++_openTicket;
    _closeSession(keepLock: keepLock);
    Workspace workspace = _local;
    ImageLibrary images = _localImages;
    if (!_localFresh) {
      setState(() {
        _screen = _Screen.opening;
        _openingName = huiText('Browser workspace');
      });
      workspace = await Workspace.open(
        repository: createDefaultWorkspaceRepository(),
      );
      images = await ImageLibrary.open(
        workspaceId: workspace.id,
        repository: workspace.repository,
      );
      if (!mounted || ticket != _openTicket) return;
      _local = workspace;
      _localImages = images;
    }
    _localFresh = false;
    final EditorStore store = EditorStore(workspace: workspace, images: images);
    final HuiCatalogs? catalogs = _catalogs;
    if (catalogs != null) store.setCatalogs(catalogs);
    final _Session session = _Session(
      workspace: workspace,
      images: images,
      store: store,
    );
    _adopt(session, held: keepLock);
    _openDocument(session, documentId);
    setState(() => _screen = _Screen.editor);
    _syncWorkspaceHash(replace: true);
  }

  /// Opens a paired server's workspace and starts live editing in it.
  Future<void> _openServer(
    String serverId, {
    String? documentId,
    bool reopen = false,
    bool keepLock = false,
  }) async {
    final _Session? current = _session;
    if (!reopen && current != null && current.serverId == serverId) {
      _openDocument(current, documentId);
      _show(_Screen.editor);
      return;
    }
    final ServerProfile? profile = _hub.server(serverId);
    if (profile == null) {
      _reportRouteError(
        huiText('This browser is not paired with that server. Pair it again.'),
      );
      _go('#/');
      return;
    }
    final int ticket = ++_openTicket;
    setState(() {
      _screen = _Screen.opening;
      _openingName = profile.name;
    });
    _closeSession(keepLock: keepLock);
    final Workspace workspace = await Workspace.open(
      repository: createDefaultWorkspaceRepository(),
      key: Workspace.serverStorageKey(serverId),
    );
    final ImageLibrary images = await ImageLibrary.open(
      workspaceId: workspace.id,
      repository: workspace.repository,
    );
    if (!mounted || ticket != _openTicket) {
      workspace.dispose();
      return;
    }
    final EditorStore store = EditorStore(
      workspace: workspace,
      images: images,
      seedDocument: false,
    );
    final HuiCatalogs? catalogs = _catalogs;
    if (catalogs != null) store.setCatalogs(catalogs);
    final EditorSyncController sync = EditorSyncController(
      store: store,
      images: images,
      hub: _hub,
      serverId: serverId,
      confirm: askToConfirm,
      toast: _toast,
      onArrived: (List<ArrivedDocument> documents) =>
          _announce(Arrival.server, documents),
      origin: browserOrigin(),
    )..addListener(_onSyncChanged);
    final _Session session = _Session(
      serverId: serverId,
      workspace: workspace,
      images: images,
      store: store,
      sync: sync,
    );
    _adopt(session, held: keepLock);
    _openDocument(session, documentId);
    setState(() => _screen = _Screen.editor);
    _syncWorkspaceHash(replace: true);
  }

  void _openDocument(_Session session, String? documentId) {
    if (documentId == null) return;
    _applyingHash = true;
    final bool opened = session.store.openDocument(documentId);
    _applyingHash = false;
    if (!opened) {
      _reportRouteError(
        huiText('That linked document is not in this workspace.'),
      );
    }
  }

  /// Makes [session] the open one. Unless this tab already holds its tab
  /// lock ([held], a reopen after taking it over), it asks for it: live
  /// editing starts once this tab holds it; while another tab does, the
  /// workspace shows read only with Take over.
  void _adopt(_Session session, {bool held = false}) {
    _session = session;
    _dialog = _EditorDialog.none;
    _validationOpen = false;
    _pairDialogOpen = false;
    session.store.addListener(_syncWorkspaceHashListener);
    session.store.addListener(_keepSimpleLanding);
    session.store.onImported =
        (List<ArrivedDocument> documents, String? message) =>
            _announce(Arrival.imported, documents, message);
    if (held) {
      session.sync?.start();
      return;
    }
    final String name = session.lockName;
    _locks.hold(
      name,
      TabLockEvents(
        onHeld: () => _lockHeld(name),
        onWaiting: () => _lockWaiting(name),
        onLost: () => _lockLost(name),
      ),
    );
  }

  _Session? _sessionLocked(String name) {
    final _Session? session = _session;
    return session != null && session.lockName == name && mounted
        ? session
        : null;
  }

  Future<void> _lockHeld(String name) async {
    final _Session? session = _sessionLocked(name);
    if (session == null) return;
    if (!session.waiting) {
      session.sync?.start();
      return;
    }
    // The other tab let go: give its last write a moment to land, read what
    // it wrote, then edit here, keeping the lock.
    await Future<void>.delayed(const Duration(milliseconds: 150));
    if (identical(_sessionLocked(name), session)) {
      await _reopen(session, keepLock: true);
    }
  }

  void _lockWaiting(String name) {
    final _Session? session = _sessionLocked(name);
    if (session == null) return;
    session.waiting = true;
    session.workspace.pauseWrites();
    session.sync?.owned = false;
    setState(() {});
  }

  Future<void> _lockLost(String name) async {
    final _Session? session = _sessionLocked(name);
    if (session == null) return;
    session.store.flushAutosave(notify: false);
    session.workspace.pauseWrites();
    session.waiting = true;
    session.sync?.owned = false;
    if (mounted) setState(() {});
  }

  Future<void> _reopen(_Session session, {bool keepLock = false}) {
    final String? active = session.store.workspace.activeId;
    final String? serverId = session.serverId;
    return serverId == null
        ? _openLocal(documentId: active, reopen: true, keepLock: keepLock)
        : _openServer(
            serverId,
            documentId: active,
            reopen: true,
            keepLock: keepLock,
          );
  }

  void _takeOver() {
    final _Session? session = _session;
    if (session == null || !session.waiting) return;
    _locks.takeOver(session.lockName);
  }

  void _closeSession({bool keepLock = false}) {
    final _Session? session = _session;
    if (session == null) return;
    _session = null;
    session.store.removeListener(_syncWorkspaceHashListener);
    session.store.removeListener(_keepSimpleLanding);
    session.store.onImported = null;
    session.sync?.removeListener(_onSyncChanged);
    if (!session.waiting) session.store.flushAutosave(notify: false);
    if (!keepLock) _locks.release(session.lockName);
    session.dispose();
    final String? serverId = session.serverId;
    if (serverId != null) {
      unawaited(_hub.release(serverId));
      session.workspace.dispose();
    }
  }

  /// Forget server removes the server's workspace from this browser.
  Future<void> _forgetWorkspace(String serverId) async {
    final _Session? session = _session;
    if (session?.serverId == serverId) {
      _closeSession();
      if (mounted) _go('#/');
    }
    const EditorSyncBindingStore().clear(serverId);
    final Workspace workspace = await Workspace.open(
      repository: createDefaultWorkspaceRepository(),
      key: Workspace.serverStorageKey(serverId),
    );
    final ImageLibrary images = await ImageLibrary.open(
      workspaceId: workspace.id,
      repository: workspace.repository,
    );
    images.removeAll();
    await images.writesSettled;
    await workspace.replacePortableState(<String, dynamic>{
      'schemaVersion': Workspace.schemaVersion,
      'workspaceId': workspace.id,
      'folders': const <Object?>[],
      'documents': const <Object?>[],
      'activeDocumentId': null,
    });
    workspace.dispose();
  }

  Future<void> _forgetServer() async {
    final String? serverId = _session?.serverId;
    final ServerProfile? server = serverId == null
        ? null
        : _hub.server(serverId);
    if (server == null) return;
    final bool yes = await askToConfirm(
      title: huiText('Forget {server}?', <String, Object?>{
        'server': server.name,
      }),
      message: _hub.forgetPrompt(server.id),
      confirmLabel: huiText('Forget server'),
      destructive: true,
    );
    if (!yes) return;
    final String? stillValid = await _hub.forget(server.id);
    if (stillValid == null) {
      toastSuccess(
        huiText('Forgot {server}.', <String, Object?>{'server': server.name}),
      );
    } else {
      toastWarning(
        huiText(
          'Forgot {server}. Its pairing works until it expires; {command} ends it now.',
          <String, Object?>{
            'server': server.name,
            'command': '/gloss editor revoke $stillValid',
          },
        ),
      );
    }
  }

  /// A pasted or opened pairing code goes to the pairing card.
  void _takePairCode(String code) {
    _closeServerSheet();
    _closePairDialog();
    _go('#/pair/$code');
  }

  /// The Server button: the paired server's sheet, or Pair a server while
  /// this workspace has none.
  void _openServerSheet() {
    final EditorSyncController? sync = _sync;
    if (sync != null) {
      sync.openServer();
    } else {
      _openPairDialog();
    }
  }

  void _closeServerSheet() => _sync?.closeServer();

  void _openPairDialog() => setState(() => _pairDialogOpen = true);

  void _closePairDialog() {
    if (_pairDialogOpen) setState(() => _pairDialogOpen = false);
  }

  void _runSaveAction(SaveAction action) {
    final _Session? session = _session;
    if (session == null) return;
    final EditorSyncController? sync = session.sync;
    switch (action) {
      case SaveAction.publish:
        unawaited(sync?.publish());
      case SaveAction.review:
      case SaveAction.newLink:
        sync?.openServer(review: action == SaveAction.review);
      case SaveAction.reconnect:
        sync?.reconnect();
      case SaveAction.takeOver:
        _takeOver();
      case SaveAction.retrySave:
        unawaited(session.store.retryAutosave());
      case SaveAction.reload:
        unawaited(_reopen(session));
    }
  }

  void _setMode(EditorMode mode) {
    if (mode == _mode) return;
    saveMode(mode);
    setState(() => _mode = mode);
    _keepSimpleLanding();
  }

  /// Simple never shows a flow map or a shared resource: it moves off one to
  /// the newest document it lists, or closes it when it lists none. Values
  /// stay as they are; Advanced lists the document again.
  void _keepSimpleLanding() {
    final EditorStore? store = _session?.store;
    if (store == null || _mode == EditorMode.advanced) return;
    final WorkspaceDoc? active = store.workspace.active;
    if (active == null || !kindIsAdvanced(active.kind)) return;
    scheduleMicrotask(() {
      if (!identical(_session?.store, store) || _mode == EditorMode.advanced) {
        return;
      }
      final Workspace workspace = store.workspace;
      final String? target = simpleLanding(workspace.docs, workspace.activeId);
      if (target == workspace.activeId) return;
      if (target == null) {
        store.closeDocument();
      } else {
        store.openDocument(target);
      }
    });
  }

  /// Says what arrived. In Simple, documents only Advanced lists get one
  /// notice with the count and Switch to Advanced; otherwise [message], when
  /// there is one, shows as it always did. True when the notice showed.
  bool _announce(
    Arrival arrival,
    List<ArrivedDocument> documents, [
    String? message,
  ]) {
    final AdvancedNotice? notice = _mode == EditorMode.simple
        ? _arrivals.add(arrival, documents)
        : null;
    if (notice == null) {
      if (message != null) toastInfo(message);
      return false;
    }
    final String? shown = _arrivalToast;
    if (shown != null) ArcaneSonner.dismiss(shown);
    _arrivalToast = toastInfo(
      notice.message,
      action: ToastAction(
        label: huiText('Switch to Advanced'),
        onPressed: () => _showInAdvanced(notice.landing),
      ),
    );
    return true;
  }

  /// Switch to Advanced from a notice: every kind is listed again and
  /// [documentId] opens.
  void _showInAdvanced(String documentId) {
    final String? shown = _arrivalToast;
    if (shown != null) ArcaneSonner.dismiss(shown);
    _arrivalToast = null;
    _setMode(EditorMode.advanced);
    _session?.store.openDocument(documentId);
  }

  /// The editor under the Simple/Advanced switch.
  Widget _withMode(Widget editor) =>
      ModeScope(mode: _mode, onChanged: _setMode, child: editor);

  void _toggleBrightness() {
    setState(() {
      _brightness = _brightness == Brightness.dark
          ? Brightness.light
          : Brightness.dark;
      persistBrightness(_brightness);
      stampDocumentTheme(_brightness);
    });
  }

  void _openDialog(_EditorDialog dialog) => setState(() {
    _pendingCanvasMedia = null;
    _validationOpen = false;
    _dialog = dialog;
  });

  /// The rail passes its own create callback (its target folder); the top
  /// bar and the palette open it with none, which creates beside the open
  /// document. Focus moves in and back through AppDialog.
  void _openNewDocumentDialog([
    void Function(NewDocumentChoice choice)? create,
    NewDocumentStart start = NewDocumentStart.blank,
  ]) {
    _createNewDocument = create;
    _newDocumentStart = start;
    _openDialog(_EditorDialog.newDocument);
  }

  void _createDocument(NewDocumentChoice choice) {
    final void Function(NewDocumentChoice choice)? create = _createNewDocument;
    if (create != null) {
      create(choice);
    } else {
      createNewDocument(
        _store,
        choice,
        folderId: _store.workspace.active?.folderId,
      );
    }
    _closeDialog();
  }

  void _closeDialog() => setState(() {
    _createNewDocument = null;
    _pendingCanvasMedia = null;
    _dialog = _EditorDialog.none;
  });

  /// The start page's ways into the browser workspace.
  Future<void> _startLocal(void Function() then) async {
    _go('#/workspace');
    await _openLocal();
    if (mounted && _session?.serverId == null) then();
  }

  // --- File > Workspace --------------------------------------------------

  Future<void> _pickWorkspaceBundle() async {
    final (String, String)? picked = await pickJsonFile();
    if (!mounted || picked == null) return;
    final WorkspaceBundleDecodeResult decoded = decodeWorkspaceBundle(
      picked.$2,
      _store.workspace,
    );
    setState(() {
      _bundleFileName = picked.$1;
      _bundle = decoded;
    });
    _openDialog(_EditorDialog.workspaceBundle);
  }

  Future<void> _confirmWorkspaceBundle() async {
    final WorkspaceBundle? bundle = _bundle?.bundle;
    if (bundle == null) return;
    setState(() => _importingBundle = true);
    final bool imported = await _store.importBundle(bundle);
    if (!mounted) return;
    setState(() => _importingBundle = false);
    if (imported) {
      _closeDialog();
      final bool hidden = _announce(Arrival.imported, <ArrivedDocument>[
        for (final WorkspaceDoc doc in _store.workspace.docs)
          (id: doc.id, kind: doc.kind),
      ]);
      if (!hidden) toastSuccess(huiText('Workspace bundle imported.'));
    } else {
      toastError(huiText('The workspace bundle could not be saved.'));
    }
  }

  void _exportWorkspaceBundle() {
    downloadText(
      'gloss-workspace.json',
      encodeWorkspaceBundle(_store.workspace, _images),
      mime: 'application/json',
    );
    toastSuccess(huiText('Saved gloss-workspace.json.'));
  }

  Future<void> _eraseLocalData() async {
    setState(() => _erasing = true);
    final LocalDataResetResult result = await resetAllLocalEditorData(
      _store,
      isDarkMode: _brightness == Brightness.dark,
    );
    if (!mounted) return;
    setState(() => _erasing = false);
    if (result.success) {
      _closeDialog();
      toastWarning(result.message);
    } else {
      toastError(result.message);
    }
  }

  void _openCanvasMedia(Vec3 offset) => setState(() {
    _pendingCanvasMedia = (
      documentId: _store.workspace.activeId,
      offset: offset.copy(),
    );
    _validationOpen = false;
    _dialog = _EditorDialog.images;
  });

  void _closeImageDialog() => setState(() {
    _pendingCanvasMedia = null;
    _dialog = _EditorDialog.none;
  });

  void _useCanvasImage(StoredImage image) {
    final ({String? documentId, Vec3 offset})? pending = _pendingCanvasMedia;
    if (pending == null || pending.documentId != _store.workspace.activeId) {
      return;
    }
    _store.addComponentData(
      HuiDecorationData(HuiTextImageIcon(image.path)),
      offset: pending.offset,
      id: 'image',
    );
  }

  void _useCanvasAnimation(List<StoredImage> frames) {
    final ({String? documentId, Vec3 offset})? pending = _pendingCanvasMedia;
    if (pending == null ||
        pending.documentId != _store.workspace.activeId ||
        frames.isEmpty) {
      return;
    }
    _store.addComponentData(
      HuiDecorationData(
        HuiAnimatedImageIcon(
          frames.map((StoredImage image) => image.path).toList(),
          huiDefaultAnimationSpeed,
        ),
      ),
      offset: pending.offset,
      id: 'animation',
    );
  }

  void _closeOverlay() {
    _closeServerSheet();
    setState(() {
      _createNewDocument = null;
      _pendingCanvasMedia = null;
      _dialog = _EditorDialog.none;
      _validationOpen = false;
    });
  }

  void _syncWorkspaceHashListener() => _syncWorkspaceHash();

  /// Keeps the address on the open document of the open workspace.
  void _syncWorkspaceHash({bool replace = false}) {
    final _Session? session = _session;
    if (_applyingHash ||
        _menuHandoff != null ||
        session == null ||
        _screen != _Screen.editor) {
      return;
    }
    final String? documentId = session.store.workspace.activeId;
    final String? serverId = session.serverId;
    final String hash = serverId != null
        ? serverWorkspaceHash(serverId, documentId)
        : (documentId == null
              ? '#/workspace'
              : workspaceDocumentHash(session.workspace.id, documentId));
    final String current = readWorkspaceLocationHash();
    if (current == hash) return;
    _appliedHash = hash;
    writeWorkspaceLocationHash(
      hash,
      replace:
          replace ||
          current.startsWith('#/import/menu/') ||
          current == '#/workspace' ||
          current.startsWith('#/server/') && !current.contains('/document/'),
    );
  }

  void _reportRouteError(String message) {
    Timer.run(() {
      if (mounted) toastError(message);
    });
  }

  void _cancelMenuHandoff() {
    setState(() => _menuHandoff = null);
    _syncWorkspaceHash(replace: true);
  }

  void _confirmMenuHandoff() {
    final WorkspaceMenuImportEnvelope? envelope = _menuHandoff;
    if (envelope == null) return;
    final bool created = _store.newMenuDocumentFromJson(
      name: envelope.runtimeId.split('/').last,
      runtimeId: envelope.runtimeId,
      json: envelope.json,
    );
    if (!created) return;
    setState(() => _menuHandoff = null);
    toastSuccess(
      huiText('Added {document} to the workspace.', <String, Object?>{
        'document': envelope.runtimeId,
      }),
    );
  }

  void _onSyncChanged() {
    if (mounted) setState(() {});
  }

  LocalSummary get _localSummary => LocalSummary(
    documents: _local.docs.length,
    hasWork: !editorSyncIsSampleWorkspace(_local, _localImages),
  );

  @override
  Widget build(BuildContext context) => ArcaneApp(
    stylesheet: const GlossStylesheet(),
    brightness: _brightness,
    // Adds arcane_jaspr's interaction runtime inline: dropdown and context
    // menus, popovers, tooltips, sliders and the command palette open
    // through it. The page has no CSP, so it need not be a separate file.
    includeFallbackScripts: true,
    home: _withMode(switch (_screen) {
      _Screen.start => _page(
        StartPage(
          key: const ValueKey<String>('start'),
          hub: _hub,
          local: _localSummary,
          onOpenServer: (String id) => _go(serverWorkspaceHash(id)),
          onOpenLocal: () => _go('#/workspace'),
          onNewDocument: () => unawaited(_startLocal(_openNewDocumentDialog)),
          onTemplates: () => unawaited(
            _startLocal(
              () => _openNewDocumentDialog(null, NewDocumentStart.template),
            ),
          ),
          onImport: () =>
              unawaited(_startLocal(() => _openDialog(_EditorDialog.import))),
          onPairCode: _takePairCode,
        ),
      ),
      _Screen.pair => _page(
        PairPage(
          key: ValueKey<String>('pair:$_pairCode'),
          code: _pairCode,
          hub: _hub,
          userAgent: browserUserAgent(),
          pageOrigin: browserOrigin(),
          onPaired: (String id) {
            _pairCode = '';
            _go(serverWorkspaceHash(id));
          },
          onCancel: () {
            _pairCode = '';
            _go('#/');
          },
        ),
      ),
      _Screen.opening => _page(
        startColumn(<Widget>[
          pageTitle(_openingName),
          Skeleton(
            rows: 6,
            label: huiText('Opening {name}', <String, Object?>{
              'name': _openingName,
            }),
          ),
        ]),
      ),
      _Screen.editor => _editor(_session!),
    }),
  );

  /// A page outside the editor, with its toasts and confirm dialogs.
  Widget _page(Widget page) =>
      dom.div(<Widget>[page, const AppToaster(), const ConfirmHost()]);

  Widget _editor(_Session session) {
    final EditorStore store = session.store;
    final ImageLibrary images = session.images;
    final EditorSyncController? sync = session.sync;
    final String? loading = sync?.activeLoading;
    return EditorScope(
      store: store,
      child: EditorShell(
        key: ValueKey<String>(session.key),
        store: store,
        status: _status,
        darkMode: _brightness == Brightness.dark,
        onToggleTheme: _toggleBrightness,
        onNewDocument: _openNewDocumentDialog,
        onOpenTemplates: () =>
            _openNewDocumentDialog(null, NewDocumentStart.template),
        onOpenImport: () => _openDialog(_EditorDialog.import),
        onOpenExport: () => _openDialog(_EditorDialog.export),
        onOpenImages: () => _openDialog(_EditorDialog.images),
        onOpenSettings: () => _openDialog(_EditorDialog.settings),
        onOpenHelp: () => _openDialog(_EditorDialog.help),
        onOpenValidation: () =>
            setState(() => _validationOpen = !_validationOpen),
        onImportBundle: _pickWorkspaceBundle,
        onExportBundle: _exportWorkspaceBundle,
        onEraseData: () => _openDialog(_EditorDialog.eraseData),
        onCloseOverlay: _closeOverlay,
        onOpenServer: _openServerSheet,
        onPairServer: _openPairDialog,
        serverStatus: sync?.status,
        onHome: () => _go('#/'),
        readOnly: session.waiting,
        loading: loading,
        tourBlocked: sync != null || session.waiting || _menuHandoff != null,
        saveBar:
            showEditorSyncBar(
              store: store,
              server: sync != null,
              waiting: session.waiting,
            )
            ? EditorSyncBar(
                controller: sync,
                store: store,
                waiting: session.waiting,
                onAction: _runSaveAction,
              )
            : null,
        rail: EditorRail(
          store: store,
          onNewDocument: _openNewDocumentDialog,
          newDocumentDialogOpen: _dialog == _EditorDialog.newDocument,
          contents: ComponentsRail(store: store),
          syncBinding: sync?.binding,
          serverBadge: sync == null ? null : syncDocBadgeFor(sync),
          listing: sync?.firstListing ?? false,
        ),
        canvas: CanvasViewport(
          store: store,
          images: images,
          catalogs: _catalogs,
          status: _status,
          onAddMedia: _openCanvasMedia,
          onAddPlayerHead: _openCanvasMedia,
        ),
        previewCard: PreviewCardViewport(
          store: store,
          catalogs: _catalogs,
          status: _status,
        ),
        surfaces: <DocumentSurface, Widget>{
          DocumentSurface.presets: PresetsEditor(store: store),
          DocumentSurface.strings: CatalogDocumentView(store: store),
          DocumentSurface.waypoint: CatalogDocumentView(store: store),
          DocumentSurface.leaderboard: LeaderboardView(store: store),
          DocumentSurface.glyph: GlyphView(store: store),
          DocumentSurface.behavior: BehaviorView(store: store),
          DocumentSurface.names: NamesView(store: store),
          DocumentSurface.panel: PanelView(store: store),
          DocumentSurface.hologram: HologramView(store: store),
          DocumentSurface.animation: AnimationView(store: store),
          DocumentSurface.scoreboard: ScoreboardView(store: store),
          DocumentSurface.hud: SurfaceView(store: store),
          DocumentSurface.motd: MotdView(store: store),
          DocumentSurface.channel: ChannelView(store: store),
          DocumentSurface.connections: ConnectionsView(store: store),
          DocumentSurface.emoji: EmojiView(store: store),
          DocumentSurface.bubble: BubbleView(store: store),
          DocumentSurface.damageIndicators: DamageIndicatorView(store: store),
          DocumentSurface.entityOverlays: EntityOverlayView(store: store),
          DocumentSurface.tablist: TablistView(store: store),
          DocumentSurface.realDrops: RealDropsView(store: store),
          DocumentSurface.inventory: InventoryView(store: store),
          DocumentSurface.nameplate: PlayerIdentityView(store: store),
          DocumentSurface.nametag: PlayerIdentityView(store: store),
          DocumentSurface.marker: MarkerView(store: store),
        },
        // Preview differs from the stage only for the menu (its 3D stage)
        // and the container preview (the card on the game screen); every
        // other kind shows its stage once (view_rules.dart).
        previews: <DocumentSurface, Widget>{
          DocumentSurface.canvas: PreviewView(
            store: store,
            images: images,
            catalogs: _catalogs,
          ),
          DocumentSurface.previewCard: PreviewCardViewport(
            store: store,
            catalogs: _catalogs,
            gameContext: true,
          ),
        },
        inspector: InspectorPane(
          store: store,
          images: images,
          catalogs: _catalogs,
        ),
        codeEditor: CodeEditorView(store: store),
        overlays: <Widget>[
          NewDocumentDialog(
            open: _dialog == _EditorDialog.newDocument,
            start: _newDocumentStart,
            onClose: _closeDialog,
            onCreate: _createDocument,
          ),
          WorkspaceBundleDialog(
            open: _dialog == _EditorDialog.workspaceBundle,
            fileName: _bundleFileName,
            documentCount: _bundle?.workspaceCheck?.documentCount ?? 0,
            folderCount: _bundle?.workspaceCheck?.folderCount ?? 0,
            imageCount: _bundle?.bundle?.images.length ?? 0,
            warning: _bundle?.workspaceCheck?.warning,
            error: _bundle == null || _bundle!.bundle != null
                ? null
                : (_bundle!.error ?? huiText('The bundle was unreadable.')),
            importing: _importingBundle,
            onConfirm: _confirmWorkspaceBundle,
            onClose: _closeDialog,
          ),
          EraseDataDialog(
            open: _dialog == _EditorDialog.eraseData,
            documentCount: store.workspace.docs.length,
            folderCount: store.workspace.folders.length,
            erasing: _erasing,
            onExportBackup: _exportWorkspaceBundle,
            onConfirm: _eraseLocalData,
            onClose: _closeDialog,
          ),
          ImportDialog(
            store: store,
            isOpen: _dialog == _EditorDialog.import,
            onClose: _closeDialog,
          ),
          ExportDialog(
            store: store,
            images: images,
            isOpen: _dialog == _EditorDialog.export,
            onClose: _closeDialog,
          ),
          ImageManagerDialog(
            store: store,
            images: images,
            isOpen: _dialog == _EditorDialog.images,
            onClose: _closeImageDialog,
            onUseImage: _pendingCanvasMedia == null ? null : _useCanvasImage,
            onUseAnimation: _pendingCanvasMedia == null
                ? null
                : _useCanvasAnimation,
            serverWeight: sync?.serverImageWeight,
          ),
          SettingsDialog(
            store: store,
            isOpen: _dialog == _EditorDialog.settings,
            onClose: _closeDialog,
            activeLocale: _activeLocale,
            localeLoading: _localeLoading,
            onLocaleChanged: _changeLocale,
          ),
          HelpDialog(
            store: store,
            isOpen: _dialog == _EditorDialog.help,
            onClose: _closeDialog,
          ),
          MenuHandoffDialog(
            envelope: _menuHandoff,
            onConfirm: _confirmMenuHandoff,
            onClose: _cancelMenuHandoff,
          ),
          if (sync != null)
            ServerSheet(
              open: sync.serverSheetOpen,
              controller: sync,
              onClose: _closeServerSheet,
              onPairCode: _takePairCode,
              onForget: () => unawaited(_forgetServer()),
            ),
          PairDialog(
            open: _pairDialogOpen,
            onClose: _closePairDialog,
            onCode: _takePairCode,
          ),
          ValidationPanel(
            store: store,
            isOpen: _validationOpen,
            onClose: () => setState(() => _validationOpen = false),
          ),
          // Last, so a question asked from an open sheet shows above it.
          const ConfirmHost(),
        ],
      ),
    );
  }
}
