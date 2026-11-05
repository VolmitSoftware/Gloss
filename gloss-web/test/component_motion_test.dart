import 'dart:convert';

import 'package:gloss_editor/logic/canvas_scene.dart';
import 'package:gloss_editor/logic/component_motion.dart';
import 'package:gloss_editor/logic/validation.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:test/test.dart';

HuiMenu _menu(Object? motion, {Map<String, String> vars = const {}}) =>
    HuiMenu.fromJson(
      jsonDecode(
        jsonEncode(<String, Object?>{
          'offset': <double>[0, 0, 0],
          'lockPosition': false,
          'followPlayer': false,
          'closeOnDeath': true,
          'closeOnTeleport': true,
          'vars': vars,
          'components': <Object?>[
            <String, Object?>{
              'id': 'reel',
              'offset': <double>[1, 2, 3],
              'motion': ?motion,
              'data': <String, Object?>{
                'type': 'decoration',
                'icon': <String, Object?>{'type': 'item', 'item': 'diamond'},
              },
            },
          ],
        }),
      ),
    );

CanvasItem _item(HuiMenu menu, {bool trueRender = true, int ticks = 0}) =>
    buildCanvasScene(
      menu: menu,
      uiScale: 1,
      trueRender: trueRender,
      togglePreview: (String _) => false,
      textCache: McTextCache(),
      animationTicks: ticks,
    ).items.single;

void main() {
  test('values add to the offset and the preview follows them', () {
    final CanvasItem moved = _item(
      _menu(<String, Object?>{'x': 2, 'y': 'time.ticks * 0.5'}),
      ticks: 4,
    );

    expect(moved.component.offset.x, 3);
    expect(moved.component.offset.y, 4);
    expect(moved.component.offset.z, 3);
  });

  test('the authoring canvas keeps the authored offset', () {
    final CanvasItem authored = _item(
      _menu(<String, Object?>{'x': 2}),
      trueRender: false,
    );

    expect(authored.component.offset.x, 1);
  });

  test('scale resizes the icon and hitbox without touching the source', () {
    final HuiMenu menu = _menu(<String, Object?>{'scale': 0.5});
    final CanvasItem full = _item(_menu(null));
    final CanvasItem half = _item(menu);

    expect(half.icon?.style?.scaleX, 0.5);
    expect(half.hitbox.w, closeTo(full.hitbox.w / 2, 1e-9));
    final HuiDecorationData data =
        menu.components.single.data as HuiDecorationData;
    expect(data.icon?.style, isNull);
  });

  test('scale stops at the runtime minimum', () {
    expect(
      sampleComponentMotion(<String, Object?>{'scale': 0})?.scale,
      glossMotionMinScale,
    );
  });

  test('session variables and templates resolve like show conditions', () {
    final CanvasItem moved = _item(
      _menu(
        <String, Object?>{'x': '{{ number(session.pos) + 1 }}'},
        vars: <String, String>{'pos': '4'},
      ),
    );

    expect(moved.component.offset.x, 6);
  });

  test('a failing expression leaves the icon where it was authored', () {
    expect(sampleComponentMotion(<String, Object?>{'x': "'text' * 2"}), isNull);
    expect(sampleComponentMotion(<String, Object?>{'ticks': 4}), isNull);
    expect(sampleComponentMotion(null), isNull);
  });

  test('motion round-trips through export unchanged', () {
    final Map<String, Object?> motion = <String, Object?>{
      'x': 'session.pos',
      'scale': 0.5,
      'ticks': 4,
    };

    final Map<String, dynamic> exported = _menu(motion).components.single
        .toJson();

    expect(exported['motion'], motion);
  });

  test('editing the last value removes the block', () {
    final Map<String, dynamic> extras = <String, dynamic>{};

    setComponentMotionValue(extras, 'x', 'session.pos');
    expect(extras['motion'], <String, Object?>{'x': 'session.pos'});
    setComponentMotionValue(extras, 'x', null);

    expect(extras.containsKey('motion'), isFalse);
  });

  test('validation matches what the server refuses at load', () {
    expect(validateComponentMotion(null, path: 'm'), isEmpty);
    expect(
      validateComponentMotion(<String, Object?>{
        'x': 1,
        'y': 'a + 1',
        'ticks': 59,
      }, path: 'm'),
      isEmpty,
    );
    expect(validateComponentMotion(3, path: 'm'), hasLength(1));
    expect(
      validateComponentMotion(<String, Object?>{
        'x': 'a +',
      }, path: 'm').single.path,
      'm.x',
    );
    expect(
      validateComponentMotion(<String, Object?>{
        'x': true,
      }, path: 'm').single.path,
      'm.x',
    );
    expect(
      validateComponentMotion(<String, Object?>{
        'ticks': 60,
      }, path: 'm').single.path,
      'm.ticks',
    );
    expect(
      validateComponentMotion(<String, Object?>{
        'ticks': -1,
      }, path: 'm').single.path,
      'm.ticks',
    );
    expect(
      validateComponentMotion(<String, Object?>{
        'ticks': 1.5,
      }, path: 'm').single.path,
      'm.ticks',
    );
    expect(
      validateComponentMotion(<String, Object?>{
        'ticks': '60',
      }, path: 'm').single.path,
      'm.ticks',
    );
    expect(
      validateComponentMotion(<String, Object?>{
        'ticks': 'session.hidden == 1 ? 0 : 3',
      }, path: 'm'),
      isEmpty,
    );
  });

  test('menu validation reports motion against the component', () {
    final List<HuiIssue> issues = validateHuiMenu(
      _menu(<String, Object?>{'ticks': 60}),
    );

    expect(
      issues.where((HuiIssue issue) => issue.path.endsWith('.motion.ticks')),
      hasLength(1),
    );
  });
}
