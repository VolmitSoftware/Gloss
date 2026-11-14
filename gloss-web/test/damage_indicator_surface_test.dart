library;

import 'dart:io';

import 'package:test/test.dart';

void main() {
  test('the editor mounts the damage stage once, in game', () {
    final String app = File('lib/app.dart').readAsStringSync();
    expect(
      RegExp(r'DocumentSurface\.damageIndicators\s*:\s*DamageIndicatorView')
          .allMatches(app),
      hasLength(1),
    );
    expect(app, matches(RegExp(r'DamageIndicatorView\(store: _?store\)')));
  });

  test('the renderer exposes looping trajectory and transport controls', () {
    final String source = File(
      'lib/components/damage_indicators/damage_indicator_view.dart',
    ).readAsStringSync();
    expect(source, contains('resolveDamageIndicatorFrame'));
    expect(source, contains('renderDamageIndicatorText'));
    expect(source, contains('Sample amount'));
    expect(source, contains('Next trajectory'));
    expect(source, contains('Replay'));
    expect(source, contains('GlossGameScreen'));
    expect(source, contains('resolveDamageIndicatorPreviewCycle'));
    expect(source, contains('totalElapsedMs: _elapsedMs()'));
    expect(source, contains('elapsedMs: cycle.elapsedMs'));
    expect(source, contains('seed: cycle.seed'));
    expect(source, contains('_workspaceAnimationsPlaying'));
  });

  test('the indicator type is the kit choice control, not pressed buttons', () {
    final String source = File(
      'lib/components/damage_indicators/damage_indicator_view.dart',
    ).readAsStringSync();
    expect(source, contains('ToggleChoice<DamageIndicatorPreviewKind>('));
    expect(source, contains("huiText('Indicator type')"));
    expect(source, isNot(contains("'aria-pressed'")));
    expect(source, isNot(contains("'title'")));
  });
}
