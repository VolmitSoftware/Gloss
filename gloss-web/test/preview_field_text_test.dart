import 'package:gloss_editor/logic/preview_field_text.dart';
import 'package:test/test.dart';

void main() {
  group('parsePreviewNameList', () {
    test('splits on commas and spaces and upper-cases each name', () {
      expect(parsePreviewNameList('chest, barrel  oak_*'), <String>[
        'CHEST',
        'BARREL',
        'OAK_*',
      ]);
    });

    test('drops blanks and repeats, keeping the first spelling in order', () {
      expect(parsePreviewNameList(' ,CHEST,, chest , *_SHULKER_BOX,'), <String>[
        'CHEST',
        '*_SHULKER_BOX',
      ]);
    });

    test('an empty or blank text is an empty list', () {
      expect(parsePreviewNameList(''), isEmpty);
      expect(parsePreviewNameList('  , '), isEmpty);
    });

    test('round-trips through formatPreviewNameList', () {
      const List<String> names = <String>['CHEST', 'OAK_*'];
      expect(formatPreviewNameList(names), 'CHEST, OAK_*');
      expect(parsePreviewNameList(formatPreviewNameList(names)), names);
    });
  });

  group('previewCompleteExprToken', () {
    test('replaces the trailing token with the suggestion', () {
      expect(
        previewCompleteExprToken('vars.a + coo', 'cookTime'),
        'vars.a + cookTime',
      );
      expect(previewCompleteExprToken('vars.ac', 'vars.accent'), 'vars.accent');
    });

    test('appends when the text ends in no token', () {
      expect(previewCompleteExprToken('1 + ', 'cookTime'), '1 + cookTime');
    });
  });

  group('previewExprCompletions', () {
    test('offers whole-text completions for the trailing token', () {
      final List<String> completions = previewExprCompletions(
        'vars.',
        declaredVars: <String>{'accent', 'base'},
        scope: const <String>{},
      );
      expect(completions, containsAll(<String>['vars.accent', 'vars.base']));
      expect(
        completions.every((String text) => text.startsWith('vars.')),
        isTrue,
      );
    });

    test('keeps the text before the token in every completion', () {
      final List<String> completions = previewExprCompletions(
        'row * 2 + ro',
        declaredVars: const <String>{},
        scope: <String>{'row'},
      );
      expect(completions, contains('row * 2 + row'));
    });

    test('offers nothing for a single bare letter', () {
      expect(
        previewExprCompletions(
          'r',
          declaredVars: const <String>{},
          scope: <String>{'row'},
        ),
        isEmpty,
      );
    });
  });
}
