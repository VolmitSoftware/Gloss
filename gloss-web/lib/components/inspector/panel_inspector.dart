/// The flow map inspector: the route counts of the map, then the world
/// panel it is linked to (Advanced to create one). The flow layout itself
/// stays in this browser workspace; only the linked world panel is
/// published.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ListenableBuilder;

import '../../logic/workspace_flow_graph.dart';
import '../../model/runtime_panel_definition.dart';
import '../../model/vec3.dart';
import '../../services/editor_sync.dart';
import '../../services/file_transfer.dart';
import '../../doctype/doctype.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';
import '../../state/workspace_panel.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';
import 'preview_expr_field.dart';
import 'panel_authoring.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PanelInspector extends StatelessWidget {
  const PanelInspector({required this.store, super.key});

  final EditorStore store;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: store.workspace,
    builder: (BuildContext context) {
      final WorkspacePanelDecodeResult? decoded = store.activePanel;
      if (decoded == null) return const dom.div(<Widget>[]);
      final WorkspacePanelData panel = decoded.data;
      final WorkspaceFlowGraph graph = buildWorkspaceFlowGraph(
        store.workspace,
        panel,
      );
      final int broken = graph.edges
          .where(
            (WorkspaceFlowEdge edge) =>
                edge.targetKind == WorkspaceFlowTargetKind.dangling ||
                edge.targetKind == WorkspaceFlowTargetKind.ambiguous ||
                edge.targetKind == WorkspaceFlowTargetKind.invalid,
          )
          .length;
      final int external = graph.edges
          .where(
            (WorkspaceFlowEdge edge) =>
                edge.targetKind == WorkspaceFlowTargetKind.external,
          )
          .length;
      final Map<String, dynamic>? board = panel.runtimeBoard;
      final String externalNote = huiText(
        'External means the target exists elsewhere in this workspace '
        'but outside the selected folder scope.',
      );
      final String orphanNote = huiText(
        'Orphan means no menu in this map links inward. It can still '
        'be a valid entry menu opened by command or API.',
      );
      return DenseBox(
        classes: 'hui-inspector-body is-panel',
        children: <Widget>[
          PaneHeader(
            kind: huiText('Flow map'),
            title: store.workspace.active?.title ?? huiText('Menu flow map'),
            tip: board == null
                ? huiText(
                    'The flow layout stays in this browser workspace. Menu JSON '
                    'exports are unchanged.',
                  )
                : huiText(
                    'The flow layout stays local. The linked world-panel JSON '
                    'below is included only when you explicitly publish.',
                  ),
            status: board == null
                ? null
                : AdvancedOnly(
                    child: StatusBadge(
                      huiText('revision {revision}', <String, Object?>{
                        'revision': board['revision'],
                      }),
                    ),
                  ),
            menuId: 'hui-panel-menu',
            actions: <AppMenuEntry>[
              if (board != null)
                AppMenuEntry(
                  huiText('Export world panel'),
                  () => downloadText(
                    '${panel.runtimeBoardId}.json',
                    const JsonEncoder.withIndent('  ').convert(board),
                  ),
                  icon: ArcaneIcon.download(),
                ),
            ],
          ),
          AppSection(
            title: huiText('Routes'),
            memoryKey: 'panel.routes',
            tip: '$externalNote $orphanNote',
            children: <Widget>[
              dom.dl(classes: 'hui-flow-stats', <Widget>[
                _stat(huiText('Menus in scope'), '${graph.documents.length}'),
                _stat(huiText('Native routes'), '${graph.edges.length}'),
                _stat(
                  huiText('Dangling or ambiguous'),
                  '$broken',
                  danger: broken > 0,
                ),
                _stat(huiText('External targets'), '$external'),
                _stat(
                  huiText('Menus in cycles'),
                  '${graph.cycleDocumentIds.length}',
                ),
                _stat(
                  huiText('Menus without inbound links'),
                  '${graph.orphanDocumentIds.length}',
                ),
                _stat(
                  huiText('Unreadable menus'),
                  '${graph.invalidDocumentIds.length}',
                ),
              ]),
            ],
          ),
          if (board == null)
            AdvancedOnly(
              child: PanelAuthoring(
                store: store,
                key: ValueKey<String?>(store.workspace.activeId),
              ),
            )
          else
            _RuntimePanelEditor(store: store, panel: panel),
        ],
      );
    },
  );
}

/// One label and value row of a `dl.hui-flow-stats`.
Widget _stat(String label, String value, {bool danger = false}) =>
    dom.div(classes: danger ? 'is-danger' : null, <Widget>[
      dom.dt(<Widget>[Text(label)]),
      dom.dd(<Widget>[Text(value)]),
    ]);

class _RuntimePanelEditor extends StatefulWidget {
  const _RuntimePanelEditor({required this.store, required this.panel});

  final EditorStore store;
  final WorkspacePanelData panel;

  @override
  State<_RuntimePanelEditor> createState() => _RuntimePanelEditorState();
}

class _RuntimePanelEditorState extends State<_RuntimePanelEditor> {
  late String _text;
  late String _source;
  RuntimePanelDefinition? _draft;
  int _generation = 0;
  bool _typedDirty = false;
  String Function()? _error;

  static final RegExp _uuidPattern = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );
  static final RegExp _permissionPattern = RegExp(r'^[a-z0-9][a-z0-9._-]*$');

  @override
  void initState() {
    super.initState();
    _adopt();
  }

  @override
  void didUpdateWidget(_RuntimePanelEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    final String source = _encode(component.panel.runtimeBoard!);
    if (source != _source && _text == _source && !_typedDirty) {
      _adopt();
    }
  }

  void _adopt() {
    _adoptPanel(component.panel.runtimeBoard!);
  }

  void _adoptPanel(Map<String, dynamic> panel) {
    _source = _encode(panel);
    _text = _source;
    _generation++;
    _typedDirty = false;
    try {
      _draft = RuntimePanelDefinition.fromJson(panel);
      _error = null;
    } on FormatException catch (error) {
      _draft = null;
      final String message = error.message.toString();
      _error = () => huiText(
        'Typed controls are unavailable: {message}',
        <String, Object?>{'message': huiText(message)},
      );
    }
  }

  void _changeDraft(RuntimePanelDefinition next) {
    setState(() {
      _draft = next;
      _typedDirty = true;
      _error = null;
    });
  }

  void _discardTypedChanges() {
    setState(_adopt);
  }

  void _applyTyped() {
    final RuntimePanelDefinition? draft = _draft;
    if (draft == null) return;
    final String? problem = _typedProblem(draft);
    if (problem != null) {
      setState(() => _error = null);
      return;
    }
    _applyPanel(draft.toJson());
  }

  void _applyJson() {
    final Object? decoded;
    try {
      decoded = jsonDecode(_text);
    } catch (_) {
      setState(() => _error = () => huiText('World-panel JSON is not valid.'));
      return;
    }
    if (decoded is! Map) {
      setState(
        () => _error = () => huiText('World-panel JSON must be an object.'),
      );
      return;
    }
    final Map<String, dynamic> panel = <String, dynamic>{};
    for (final MapEntry<Object?, Object?> entry in decoded.entries) {
      if (entry.key is! String) {
        setState(
          () => _error = () => huiText('World-panel keys must be strings.'),
        );
        return;
      }
      panel[entry.key! as String] = entry.value;
    }
    _applyPanel(panel);
  }

  void _applyPanel(Map<String, dynamic> panel) {
    final Map<String, dynamic> original = component.panel.runtimeBoard!;
    for (final String key in <String>[
      'schemaVersion',
      'id',
      'uuid',
      'revision',
    ]) {
      if (panel[key] != original[key]) {
        setState(() {
          _error = () => huiText(
            '{key} is owned by the server and cannot change.',
            <String, Object?>{'key': key},
          );
        });
        return;
      }
    }
    final Object? rootMenuId = panel['rootMenuId'];
    if (rootMenuId is! String ||
        !component.store.workspace.docs.any(
          (WorkspaceDoc doc) =>
              doc.kind == DocumentTypes.menu.kind &&
              doc.runtimeId == rootMenuId,
        )) {
      setState(() {
        _error = () =>
            huiText('rootMenuId must name a menu in this workspace.');
      });
      return;
    }
    final String? panelProblem = editorSyncPanelDefinitionProblem(
      panel,
      component.store.workspace.docs
          .where((WorkspaceDoc doc) => doc.kind == DocumentTypes.menu.kind)
          .map((WorkspaceDoc doc) => doc.runtimeId)
          .whereType<String>(),
    );
    if (panelProblem != null) {
      final Map<String, dynamic> capturedPanel = Map<String, dynamic>.of(panel);
      final List<String> capturedMenuIds = component.store.workspace.docs
          .where((WorkspaceDoc doc) => doc.kind == DocumentTypes.menu.kind)
          .map((WorkspaceDoc doc) => doc.runtimeId)
          .whereType<String>()
          .toList(growable: false);
      setState(() {
        _error = () =>
            editorSyncPanelDefinitionProblem(capturedPanel, capturedMenuIds)!;
      });
      return;
    }
    try {
      RuntimePanelDefinition.fromJson(panel);
    } on FormatException catch (error) {
      final String message = error.message.toString();
      setState(() => _error = () => huiText(message));
      return;
    }
    if (!component.store.updatePanel(
      component.panel.copyWith(runtimeBoard: panel),
    )) {
      setState(() {
        _error = () => huiText('The linked world panel could not be saved.');
      });
      return;
    }
    setState(() {
      _adoptPanel(panel);
    });
  }

  @override
  Widget build(BuildContext context) {
    final RuntimePanelDefinition? draft = _draft;
    final String? problem = draft == null ? null : _typedProblem(draft);
    return Component.fragment(<Widget>[
      if (_error != null)
        AppAlert(tone: AlertTone.error, message: _error!(), live: true),
      if (draft != null) ...<Widget>[
        _identity(draft),
        _placement(draft),
        _follow(draft),
        _visibility(draft),
        AdvancedOnly(
          child: PreviewExprField(
            label: huiText('Show condition'),
            raw: draft.show,
            kind: PreviewExprKind.boolean,
            showCondition: true,
            onChanged: (Object? value) =>
                _changeDraft(draft.copyWith(show: value ?? true)),
          ),
        ),
        if (problem != null)
          AppAlert(tone: AlertTone.warning, message: problem),
        FormFooter(
          dense: true,
          cancel: RowAction(
            huiText('Discard changes'),
            _typedDirty ? _discardTypedChanges : null,
          ),
          primary: RowAction(
            huiText('Apply panel settings'),
            _typedDirty && problem == null ? _applyTyped : null,
            icon: ArcaneIcon.check(),
          ),
        ),
      ],
      AdvancedOnly(child: _advancedJson()),
    ]);
  }

  ComponentSize get _size => controlSize(Density.dense);

  Widget _identity(RuntimePanelDefinition draft) {
    final List<WorkspaceDoc> menus =
        component.store.workspace.docs
            .where(
              (WorkspaceDoc doc) =>
                  doc.kind == DocumentTypes.menu.kind && doc.runtimeId != null,
            )
            .toList()
          ..sort(
            (WorkspaceDoc a, WorkspaceDoc b) =>
                a.runtimeId!.compareTo(b.runtimeId!),
          );
    return AppSection(
      title: huiText('Linked world panel'),
      memoryKey: 'panel.content',
      tip: huiText(
        'These controls edit the panel players see in the world. Apply stores '
        'the changes in this workspace; Publish sends them to the server.',
      ),
      children: <Widget>[
        Field(
          label: huiText('Root menu'),
          required: true,
          docKey: 'panel.rootMenuId',
          child: ArcaneSelect(
            value: draft.rootMenuId,
            size: _size,
            fullWidth: true,
            options: <ArcaneSelectOption>[
              for (final WorkspaceDoc menu in menus)
                ArcaneSelectOption(
                  label: menu.runtimeId!,
                  value: menu.runtimeId!,
                ),
            ],
            onChanged: (String value) =>
                _changeDraft(draft.copyWith(rootMenuId: value)),
          ),
        ),
        AdvancedOnly(
          child: Field(
            label: huiText('Server-owned identity'),
            docKey: 'panel.identity',
            child: dom.dl(classes: 'hui-flow-stats', <Widget>[
              _stat(huiText('Panel id'), draft.id),
              _stat(huiText('Panel UUID'), draft.uuid),
              _stat(huiText('Revision'), '${draft.revision}'),
            ]),
          ),
        ),
      ],
    );
  }

  Widget _placement(RuntimePanelDefinition draft) {
    final RuntimePanelTransform transform = draft.transform;
    final bool following = draft.follow.mode == RuntimePanelFollowMode.player;
    return AppSection(
      title: huiText('Placement'),
      memoryKey: 'panel.placement',
      // The same three numbers mean two different things, and the runtime
      // never says which: see the position help.
      tip: following
          ? huiText(
              'Blocks in the followed player\'s local frame; rotation is in degrees.',
            )
          : huiText(
              'Coordinates are world-space blocks; rotation is in degrees.',
            ),
      children: <Widget>[
        AdvancedOnly(
          child: Component.fragment(<Widget>[
            _textField(
              'hui-panel-world',
              huiText('World key'),
              transform.worldKey,
              (String value) => _changeDraft(
                draft.copyWith(transform: transform.copyWith(worldKey: value)),
              ),
              docKey: 'panel.transform.world',
            ),
            _textField(
              'hui-panel-world-uuid',
              huiText('World UUID'),
              transform.worldUuid,
              (String value) => _changeDraft(
                draft.copyWith(transform: transform.copyWith(worldUuid: value)),
              ),
              tip: huiText(
                'The world key and UUID must identify the same loaded server world.',
              ),
            ),
          ]),
        ),
        Field(
          label: following
              ? huiText('Offset from the player')
              : huiText('Position'),
          required: true,
          docKey: 'panel.transform.position',
          child: Vec3Field(
            id: 'hui-panel-position',
            label: following
                ? huiText('Offset from the player')
                : huiText('Position'),
            value: Vec3(transform.x, transform.y, transform.z),
            axisHints: <String>[
              huiText('x: east or west in the panel world'),
              huiText('y: height in the panel world'),
              huiText('z: north or south in the panel world'),
            ],
            onChanged: (Vec3 value) => _changeDraft(
              draft.copyWith(
                transform: transform.copyWith(
                  x: value.x,
                  y: value.y,
                  z: value.z,
                ),
              ),
            ),
          ),
        ),
        Field(
          label: huiText('Rotation'),
          required: true,
          docKey: 'panel.transform.rotation',
          child: Vec3Field(
            id: 'hui-panel-rotation',
            label: huiText('Rotation'),
            value: Vec3(transform.yaw, transform.pitch, transform.roll),
            labels: <String>[
              huiText('Yaw'),
              huiTextKey('field.pitch.orientation', 'Pitch'),
              huiText('Roll'),
            ],
            axisHints: <String>[
              huiText('yaw: rotation around the vertical axis'),
              huiText('pitch: tilt around the side axis'),
              huiText('roll: rotation around the forward axis'),
            ],
            step: 1,
            decimals: 2,
            onChanged: (Vec3 value) => _changeDraft(
              draft.copyWith(
                transform: transform.copyWith(
                  yaw: value.x,
                  pitch: value.y,
                  roll: value.z,
                ),
              ),
            ),
          ),
        ),
        Field(
          label: huiText('Scale'),
          forId: 'hui-panel-scale',
          required: true,
          docKey: 'panel.transform.scale',
          defaultValue: '1',
          onReset: transform.scale == 1
              ? null
              : () => _changeDraft(
                  draft.copyWith(transform: transform.copyWith(scale: 1)),
                ),
          child: NumberField(
            id: 'hui-panel-scale',
            value: transform.scale,
            min: 0.05,
            max: 16,
            step: 0.05,
            decimals: 3,
            onChanged: (double value) => _changeDraft(
              draft.copyWith(transform: transform.copyWith(scale: value)),
            ),
          ),
        ),
      ],
    );
  }

  Widget _follow(RuntimePanelDefinition draft) {
    final RuntimePanelFollow follow = draft.follow;
    final String? targetProblem = follow.mode == RuntimePanelFollowMode.player
        ? _uuidProblem(follow.targetPlayerUuid)
        : null;
    return AppSection(
      title: huiText('Follow'),
      memoryKey: 'panel.follow',
      tip: huiText(
        'Attach the panel to one player or leave it fixed in the world.',
      ),
      children: <Widget>[
        Field(
          label: huiText('Follow mode'),
          docKey: 'panel.follow.mode',
          defaultValue: huiText('Fixed in world'),
          onReset: follow.mode == RuntimePanelFollowMode.none
              ? null
              : () => _setFollowMode(draft, 'none'),
          child: ToggleChoice<String>(
            label: huiText('Follow mode'),
            value: follow.mode.name,
            onChanged: (String value) => _setFollowMode(draft, value),
            options: <ChoiceOption<String>>[
              ChoiceOption<String>('none', huiText('Fixed in world')),
              ChoiceOption<String>('player', huiText('Follow a player')),
            ],
          ),
        ),
        if (follow.mode == RuntimePanelFollowMode.player) ...<Widget>[
          _textField(
            'hui-panel-follow-player',
            huiText('Player UUID'),
            follow.targetPlayerUuid ?? '',
            (String value) => _changeDraft(
              draft.copyWith(
                follow: RuntimePanelFollow(
                  mode: RuntimePanelFollowMode.player,
                  targetPlayerUuid: _optionalText(value),
                  rotation: follow.rotation,
                ),
              ),
            ),
            required: true,
            error: targetProblem,
            docKey: 'panel.follow.targetPlayerUuid',
            placeholder: '00000000-0000-4000-8000-000000000000',
          ),
          Field(
            label: huiText('Rotation behavior'),
            docKey: 'panel.follow.rotation',
            child: ArcaneSelect(
              value: follow.rotation.name,
              size: _size,
              fullWidth: true,
              options: <ArcaneSelectOption>[
                ArcaneSelectOption(
                  label: huiText('Keep fixed orientation'),
                  value: 'fixed',
                ),
                ArcaneSelectOption(label: huiText('Follow yaw'), value: 'yaw'),
                ArcaneSelectOption(
                  label: huiText('Follow yaw and pitch'),
                  value: 'full',
                ),
              ],
              onChanged: (String value) => _setFollowRotation(draft, value),
            ),
          ),
        ],
      ],
    );
  }

  Widget _visibility(RuntimePanelDefinition draft) {
    final RuntimePanelVisibility visibility = draft.visibility;
    final String? viewPermissionProblem =
        visibility.mode == RuntimePanelVisibilityMode.permission
        ? _permissionProblem(visibility.viewPermission, required: true)
        : null;
    final String? interactPermissionProblem = _permissionProblem(
      visibility.interactPermission,
    );
    return AppSection(
      title: huiText('Audience and reach'),
      memoryKey: 'panel.visibility',
      tip: huiText('Choose who can see the panel and how close they must be.'),
      children: <Widget>[
        Field(
          label: huiText('Audience'),
          docKey: 'panel.visibility.mode',
          child: ArcaneSelect(
            value: visibility.mode.name,
            size: _size,
            fullWidth: true,
            options: <ArcaneSelectOption>[
              ArcaneSelectOption(label: huiText('Public'), value: 'public'),
              ArcaneSelectOption(
                label: huiText('Permission required'),
                value: 'permission',
              ),
              ArcaneSelectOption(label: huiText('Hidden'), value: 'hidden'),
            ],
            onChanged: (String value) => _setVisibilityMode(draft, value),
          ),
        ),
        if (visibility.mode == RuntimePanelVisibilityMode.permission)
          _textField(
            'hui-panel-view-permission',
            huiText('View permission'),
            visibility.viewPermission ?? '',
            (String raw) => _changeDraft(
              draft.copyWith(
                visibility: RuntimePanelVisibility(
                  mode: visibility.mode,
                  viewPermission: _optionalText(raw),
                  interactPermission: visibility.interactPermission,
                  viewRange: visibility.viewRange,
                  interactionRange: visibility.interactionRange,
                ),
              ),
            ),
            required: true,
            error: viewPermissionProblem,
            docKey: 'panel.visibility.viewPermission',
            placeholder: huiText('myserver.gloss.panel'),
          ),
        if (visibility.mode != RuntimePanelVisibilityMode.hidden)
          _textField(
            'hui-panel-interact-permission',
            huiText('Interaction permission'),
            visibility.interactPermission ?? '',
            (String raw) => _changeDraft(
              draft.copyWith(
                visibility: RuntimePanelVisibility(
                  mode: visibility.mode,
                  viewPermission: visibility.viewPermission,
                  interactPermission: _optionalText(raw),
                  viewRange: visibility.viewRange,
                  interactionRange: visibility.interactionRange,
                ),
              ),
            ),
            error: interactPermissionProblem,
            docKey: 'panel.visibility.interactPermission',
            placeholder: huiText('myserver.gloss.panel'),
          ),
        Field(
          label: huiText('View range'),
          forId: 'hui-panel-view-range',
          docKey: 'panel.visibility.viewRange',
          child: NumberField(
            id: 'hui-panel-view-range',
            value: visibility.viewRange,
            min: 0.05,
            max: 256,
            step: 1,
            decimals: 2,
            suffix: huiText('blocks'),
            onChanged: (double value) => _setViewRange(draft, value),
          ),
        ),
        Field(
          label: huiText('Interaction range'),
          forId: 'hui-panel-interaction-range',
          docKey: 'panel.visibility.interactionRange',
          child: NumberField(
            id: 'hui-panel-interaction-range',
            value: visibility.interactionRange,
            min: 0.05,
            max: visibility.viewRange < 32 ? visibility.viewRange : 32,
            step: 0.5,
            decimals: 2,
            suffix: huiText('blocks'),
            onChanged: (double value) => _changeDraft(
              draft.copyWith(
                visibility: RuntimePanelVisibility(
                  mode: visibility.mode,
                  viewPermission: visibility.viewPermission,
                  interactPermission: visibility.interactPermission,
                  viewRange: visibility.viewRange,
                  interactionRange: value,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  /// The world panel as JSON, for exact contract edits (Advanced only).
  Widget _advancedJson() => AppSection(
    title: huiText('Advanced: world-panel JSON'),
    memoryKey: 'panel.json',
    open: false,
    tip: huiText(
      'Use this only for exact contract edits. Identity, schema and revision '
      'are server-owned, and applying JSON replaces any unapplied controls.',
    ),
    children: <Widget>[
      Field(
        label: huiText('Advanced world-panel JSON'),
        forId: 'hui-panel-json',
        child: dom.div(
          key: ValueKey<int>(_generation),
          classes: 'hui-flow-json',
          <Widget>[
            TextArea(
              id: 'hui-panel-json',
              value: _text,
              rows: 18,
              styles: const ArcaneStyleData(fontFamily: FontFamily.mono),
              attributes: const <String, String>{
                ...technicalInputAttributes,
                'wrap': 'off',
              },
              onChanged: (String value) => _text = value,
            ),
          ],
        ),
      ),
      FormFooter(
        dense: true,
        secondary: <RowAction>[
          RowAction(
            huiText('Apply advanced JSON'),
            _applyJson,
            icon: ArcaneIcon.check(),
          ),
        ],
      ),
    ],
  );

  /// A technical text field: ids, UUIDs, permissions.
  Widget _textField(
    String id,
    String label,
    String value,
    void Function(String value) onChanged, {
    bool required = false,
    String? error,
    String? docKey,
    String? tip,
    String? placeholder,
  }) => Field(
    label: label,
    forId: id,
    required: required,
    error: error,
    docKey: docKey,
    tip: tip,
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      placeholder: placeholder,
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
      onChanged: onChanged,
    ),
  );

  void _setFollowMode(RuntimePanelDefinition draft, String raw) {
    final RuntimePanelFollowMode mode = RuntimePanelFollowMode.values
        .firstWhere(
          (RuntimePanelFollowMode candidate) => candidate.name == raw,
        );
    _changeDraft(
      draft.copyWith(
        follow: mode == RuntimePanelFollowMode.none
            ? const RuntimePanelFollow(
                mode: RuntimePanelFollowMode.none,
                targetPlayerUuid: null,
                rotation: RuntimePanelFollowRotation.fixed,
              )
            : RuntimePanelFollow(
                mode: RuntimePanelFollowMode.player,
                targetPlayerUuid: draft.follow.targetPlayerUuid,
                rotation:
                    draft.follow.rotation == RuntimePanelFollowRotation.fixed
                    ? RuntimePanelFollowRotation.yaw
                    : draft.follow.rotation,
              ),
      ),
    );
  }

  void _setFollowRotation(RuntimePanelDefinition draft, String raw) {
    final RuntimePanelFollowRotation rotation = RuntimePanelFollowRotation
        .values
        .firstWhere(
          (RuntimePanelFollowRotation candidate) => candidate.name == raw,
        );
    _changeDraft(
      draft.copyWith(
        follow: RuntimePanelFollow(
          mode: draft.follow.mode,
          targetPlayerUuid: draft.follow.targetPlayerUuid,
          rotation: rotation,
        ),
      ),
    );
  }

  void _setVisibilityMode(RuntimePanelDefinition draft, String raw) {
    final RuntimePanelVisibilityMode mode = RuntimePanelVisibilityMode.values
        .firstWhere(
          (RuntimePanelVisibilityMode candidate) => candidate.name == raw,
        );
    final RuntimePanelVisibility current = draft.visibility;
    _changeDraft(
      draft.copyWith(
        visibility: RuntimePanelVisibility(
          mode: mode,
          viewPermission: mode == RuntimePanelVisibilityMode.permission
              ? current.viewPermission
              : null,
          interactPermission: mode == RuntimePanelVisibilityMode.hidden
              ? null
              : current.interactPermission,
          viewRange: current.viewRange,
          interactionRange: current.interactionRange,
        ),
      ),
    );
  }

  void _setViewRange(RuntimePanelDefinition draft, double value) {
    final RuntimePanelVisibility current = draft.visibility;
    final double interactionRange = current.interactionRange > value
        ? value
        : current.interactionRange;
    _changeDraft(
      draft.copyWith(
        visibility: RuntimePanelVisibility(
          mode: current.mode,
          viewPermission: current.viewPermission,
          interactPermission: current.interactPermission,
          viewRange: value,
          interactionRange: interactionRange,
        ),
      ),
    );
  }

  String? _typedProblem(RuntimePanelDefinition draft) {
    if (draft.transform.scale < 0.05 || draft.transform.scale > 16) {
      return huiText('Scale must be between 0.05 and 16.');
    }
    if (draft.follow.mode == RuntimePanelFollowMode.player) {
      final String? problem = _uuidProblem(draft.follow.targetPlayerUuid);
      if (problem != null) return problem;
    }
    if (draft.visibility.mode == RuntimePanelVisibilityMode.permission) {
      final String? problem = _permissionProblem(
        draft.visibility.viewPermission,
        required: true,
      );
      if (problem != null) return problem;
    }
    final String? interactionProblem = _permissionProblem(
      draft.visibility.interactPermission,
    );
    if (interactionProblem != null) return interactionProblem;
    if (draft.visibility.viewRange <= 0 || draft.visibility.viewRange > 256) {
      return huiText(
        'View range must be greater than 0 and no more than 256 blocks.',
      );
    }
    if (draft.visibility.interactionRange <= 0 ||
        draft.visibility.interactionRange > 32 ||
        draft.visibility.interactionRange > draft.visibility.viewRange) {
      return huiText(
        'Interaction range must fit inside the view range and be no more than '
        '32 blocks.',
      );
    }
    return null;
  }

  String? _uuidProblem(String? value) {
    if (value == null || value.isEmpty) {
      return huiText('Enter the player UUID.');
    }
    if (!_uuidPattern.hasMatch(value)) {
      return huiText('Enter a valid player UUID.');
    }
    return null;
  }

  String? _permissionProblem(String? value, {bool required = false}) {
    if (value == null || value.isEmpty) {
      return required ? huiText('Enter a view permission.') : null;
    }
    if (!_permissionPattern.hasMatch(value)) {
      return huiText(
        'Use lowercase letters, digits, dots, underscores, and hyphens.',
      );
    }
    return null;
  }

  String? _optionalText(String value) {
    final String trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  static String _encode(Map<String, dynamic> value) =>
      const JsonEncoder.withIndent('  ').convert(value);
}
