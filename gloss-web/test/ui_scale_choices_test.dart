import 'package:gloss_editor/logic/ui_scale_choices.dart';
import 'package:test/test.dart';

void main() {
  group('uiScaleChoices', () {
    test('offers the presets in order when the value is one of them', () {
      expect(uiScaleChoices(1), <double>[0.5, 0.75, 1, 1.25, 1.5, 2]);
    });

    test('adds a custom value in its place so it shows as checked', () {
      expect(uiScaleChoices(1.1), <double>[0.5, 0.75, 1, 1.1, 1.25, 1.5, 2]);
      expect(uiScaleChoices(3.5).last, 3.5);
      expect(uiScaleChoices(0.25).first, 0.25);
    });

    test('labels every choice with two decimals', () {
      expect(uiScaleLabel(0.5), '0.50');
      expect(uiScaleLabel(1), '1.00');
      expect(uiScaleLabel(1.125), '1.13');
    });

    test('reads a picked label back to its value', () {
      for (final double value in uiScaleChoices(1.1)) {
        expect(parseUiScaleLabel(uiScaleLabel(value)), closeTo(value, 0.005));
      }
      expect(parseUiScaleLabel('nonsense'), isNull);
    });
  });
}
