import 'package:gloss_editor/ui/number_value.dart';
import 'package:test/test.dart';

/// The values a field commits while [text] is typed one key at a time.
List<double> _typedCommits(String text, {double? min, double? max}) => <double>[
  for (int end = 1; end <= text.length; end++)
    ?typedNumber(text.substring(0, end), decimals: 2, min: min, max: max),
];

void main() {
  test('typing below the minimum commits nothing until it is in range', () {
    expect(_typedCommits('0.85', min: 0.25, max: 4), <double>[0.8, 0.85]);
  });

  test('typing above the maximum commits nothing', () {
    expect(_typedCommits('12', min: 0.25, max: 4), <double>[1]);
  });

  test('empty and unfinished text commits nothing', () {
    expect(typedNumber(''), isNull);
    expect(typedNumber('-'), isNull);
    expect(typedNumber('1e400'), isNull);
  });

  test('typed values keep the field precision', () {
    expect(typedNumber('1.234', decimals: 2), 1.23);
    expect(typedNumber('2.6', integer: true), 3);
    expect(typedNumber('-0', min: -1), 0);
  });

  test('the bounds still apply when a value is committed', () {
    expect(normalizeNumber(0, decimals: 2, min: 0.25, max: 4), 0.25);
    expect(normalizeNumber(12, decimals: 2, min: 0.25, max: 4), 4);
  });
}
