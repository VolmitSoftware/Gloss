/// The preview's readout: one muted line above the stage.
///
/// Reads [PreviewLiveState], which the stage writes once per 50 ms simulation
/// tick and which notifies only when a value actually changed, so a parked
/// pointer over a static menu rebuilds nothing. The line never wraps (it cuts
/// with an ellipsis), so a value changing length never resizes the stage
/// under the pointer. On a phone it drops the document settings (follow
/// player, also in the inspector) so the live values fit. Only the session
/// state is a live region: the distance changes twenty times a second.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/mode.dart';
import 'preview_pose.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PreviewHud extends StatelessWidget {
  const PreviewHud({required this.store, required this.pose, super.key});

  final EditorStore store;
  final PreviewPose pose;

  @override
  Widget build(BuildContext context) {
    final PreviewLiveState live = pose.live;
    final HuiMenu menu = store.menu;
    final String? hovered = live.hoveredId;
    final int behind = live.hoveredIds > 1 ? live.hoveredIds - 1 : 0;
    final double? max = menu.maxDistance;
    final String distance = live.distanceToCenter.toStringAsFixed(2);
    return dom.div(
      classes: 'hui-preview-readout',
      attributes: <String, String>{
        'role': 'group',
        'aria-label': huiText('Preview readout'),
      },
      <Widget>[
        _item(
          huiText('menu'),
          live.isOpen
              ? huiTextKey('state.open', 'Open')
              : huiText('Closed: {reason}', <String, Object?>{
                  'reason': live.closeReason?.wireName ?? huiText('CLOSED'),
                }),
          tone: live.isOpen ? null : 'is-closed',
          // The one value here slow enough to announce.
          status: true,
        ),
        _item(
          huiText('hovered'),
          hovered ?? huiText('nothing'),
          tone: hovered == null ? 'is-muted' : null,
          note: behind == 0
              ? null
              : AdvancedOnly(
                  child: dom.span(classes: 'hui-preview-readout-note', <Widget>[
                    Component.text(
                      huiText('+{count} behind', <String, Object?>{
                        'count': behind,
                      }),
                    ),
                  ]),
                ),
        ),
        if (hovered != null)
          AdvancedOnly(
            child: _item(
              huiText('push'),
              huiText('{value} blocks', <String, Object?>{
                'value': _blocks(live.hoverPushBlocks),
              }),
            ),
          ),
        _item(
          huiText('distance'),
          max == null
              ? huiText('{value} blocks', <String, Object?>{'value': distance})
              : huiText('{distance} / {maximum} blocks', <String, Object?>{
                  'distance': distance,
                  'maximum': _blocks(max),
                }),
        ),
        if (live.movementLocked)
          _item(huiText('movement'), huiText('locked'), tone: 'is-warning'),
        if (menu.followPlayer)
          _item(huiText('follow'), huiText('on'), tone: 'is-setting'),
      ],
    );
  }

  static Widget _item(
    String label,
    String value, {
    String? tone,
    Widget? note,
    bool status = false,
  }) => dom.span(
    classes: 'hui-preview-readout-item${tone == null ? '' : ' $tone'}',
    attributes: status ? const <String, String>{'role': 'status'} : null,
    <Widget>[
      Component.text(label),
      dom.span(classes: 'hui-preview-readout-value', <Widget>[
        Component.text(value),
      ]),
      ?note,
    ],
  );

  /// Whole values read as whole numbers; `1.00` in a readout is noise.
  static String _blocks(double value) {
    if (!value.isFinite) return '$value';
    if (value == value.roundToDouble()) return value.toStringAsFixed(0);
    return value
        .toStringAsFixed(3)
        .replaceFirst(RegExp(r'0+$'), '')
        .replaceFirst(RegExp(r'\.$'), '');
  }
}
