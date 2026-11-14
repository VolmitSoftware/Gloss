// The kit's pure parts: the Mode value and the first mode a browser gets,
// density sizes, every save bar state for local autosave and server sync,
// the number field's parsing, the fit zoom of previews, reorder slots, the
// badge tone of an issue and a field tip that leads a field doc.
import 'package:arcane_jaspr/arcane_jaspr.dart'
    show ButtonSize, ComboboxSize, ComponentSize, ToggleGroupSize;
import 'package:gloss_editor/logic/validation.dart' show HuiSeverity;
import 'package:gloss_editor/ui/density.dart';
import 'package:gloss_editor/config/field_docs.dart';
import 'package:gloss_editor/ui/editor_mode.dart';
import 'package:gloss_editor/ui/field_tip.dart';
import 'package:gloss_editor/ui/fit_zoom.dart';
import 'package:gloss_editor/ui/number_value.dart';
import 'package:gloss_editor/ui/reorder_slots.dart';
import 'package:gloss_editor/ui/save_state.dart';
import 'package:gloss_editor/ui/tone.dart';
import 'package:test/test.dart';

void main() {
  group('mode', () {
    test('should read only a stored simple or advanced', () {
      expect(parseMode('simple'), EditorMode.simple);
      expect(parseMode('advanced'), EditorMode.advanced);
      expect(parseMode('Advanced'), isNull);
      expect(parseMode('expert'), isNull);
      expect(parseMode(null), isNull);
      expect(modeStorageKey, 'gloss.mode');
    });

    test('should start a browser with a workspace in Advanced', () {
      expect(initialMode(hasWorkspace: true), EditorMode.advanced);
      expect(initialMode(hasWorkspace: false), EditorMode.simple);
    });

    test('should keep a stored mode whatever the workspace holds', () {
      expect(
        initialMode(stored: 'simple', hasWorkspace: true),
        EditorMode.simple,
      );
      expect(
        initialMode(stored: 'advanced', hasWorkspace: false),
        EditorMode.advanced,
      );
      expect(
        initialMode(stored: 'garbage', hasWorkspace: false),
        EditorMode.simple,
      );
    });

    test('should label the modes', () {
      expect(EditorMode.values.map((EditorMode mode) => mode.label), <String>[
        'Simple',
        'Advanced',
      ]);
    });
  });

  group('density', () {
    test('should map dense to the library 32 px sizes', () {
      expect(buttonSize(Density.dense), ButtonSize.sm);
      expect(iconButtonSize(Density.dense), ButtonSize.iconSm);
      expect(controlSize(Density.dense), ComponentSize.sm);
      expect(toggleSize(Density.dense), ToggleGroupSize.sm);
      expect(comboboxSize(Density.dense), ComboboxSize.sm);
    });

    test('should map regular to the library 36 px sizes', () {
      expect(buttonSize(Density.regular), ButtonSize.md);
      expect(iconButtonSize(Density.regular), ButtonSize.iconMd);
      expect(controlSize(Density.regular), ComponentSize.md);
      expect(toggleSize(Density.regular), ToggleGroupSize.md);
      expect(comboboxSize(Density.regular), ComboboxSize.md);
    });
  });

  group('save bar, local autosave', () {
    test('should say saving, then saved with the time', () {
      expect(
        localSaveState(saving: true),
        const SaveBarState('Saving…', SaveTone.busy),
      );
      expect(
        localSaveState(saving: false),
        const SaveBarState('Autosaved locally', SaveTone.quiet),
      );
      expect(
        localSaveState(saving: false, savedAt: DateTime(2026, 11, 12, 9, 5)),
        const SaveBarState('Autosaved locally 09:05', SaveTone.quiet),
      );
    });

    test('should offer Retry for a failed save and Reload when stuck', () {
      expect(
        localSaveState(saving: true, failure: 'Storage is full.'),
        const SaveBarState(
          'Storage is full.',
          SaveTone.failed,
          primary: SaveAction.retrySave,
        ),
      );
      expect(
        localSaveState(
          saving: false,
          failure: 'Another tab changed this workspace.',
          requiresReload: true,
        ),
        const SaveBarState(
          'Another tab changed this workspace.',
          SaveTone.failed,
          primary: SaveAction.reload,
        ),
      );
    });

    test('should show the bar only for a sync link or a failed save', () {
      expect(showSaveBar(synced: false), isFalse);
      expect(showSaveBar(synced: true), isTrue);
      expect(showSaveBar(synced: false, failure: 'Storage is full.'), isTrue);
    });
  });

  group('number value', () {
    test('should parse finite numbers only', () {
      expect(parseNumber(' 1.5 '), 1.5);
      expect(parseNumber('-0'), 0);
      expect(parseNumber(''), isNull);
      expect(parseNumber('1e400'), isNull);
      expect(parseNumber('abc'), isNull);
    });

    test('should format without float noise or trailing zeros', () {
      expect(formatNumber(1.5000, decimals: 3), '1.5');
      expect(formatNumber(0.1 + 0.2, decimals: 3), '0.3');
      expect(formatNumber(-0.0001, decimals: 3), '0');
      expect(formatNumber(2.6, integer: true), '3');
      expect(formatNumber(double.nan), '0');
    });

    test('should round to the decimals and clamp to the bounds', () {
      expect(normalizeNumber(1.23456, decimals: 2), 1.23);
      expect(normalizeNumber(7.6, integer: true), 8);
      expect(normalizeNumber(12, min: 0, max: 10), 10);
      expect(normalizeNumber(-3, min: 0), 0);
      expect(normalizeNumber(99), 99);
    });

    test('should snap a slider value onto its step', () {
      expect(snapToStep(0.537, step: 0.05, decimals: 3), 0.55);
      expect(snapToStep(3.2, step: 0, decimals: 3), 3.2);
      expect(snapToStep(7, step: 2, decimals: 0), 8);
    });
  });

  group('fit zoom', () {
    test('should keep the natural size when it fits', () {
      expect(fitZoom(naturalWidth: 400, naturalHeight: 200, roomWidth: 800), 1);
      expect(
        fitZoom(
          naturalWidth: 400,
          naturalHeight: 200,
          roomWidth: 400,
          roomHeight: 200,
        ),
        1,
      );
    });

    test('should scale down to the tighter of width and height', () {
      expect(
        fitZoom(naturalWidth: 800, naturalHeight: 200, roomWidth: 400),
        0.5,
      );
      expect(
        fitZoom(
          naturalWidth: 800,
          naturalHeight: 600,
          roomWidth: 800,
          roomHeight: 300,
        ),
        0.5,
      );
    });

    test('should never grow past the natural size or under the floor', () {
      for (final double room in <double>[1, 50, 333, 799, 800, 4000]) {
        final double zoom = fitZoom(
          naturalWidth: 800,
          naturalHeight: 450,
          roomWidth: room,
          roomHeight: room,
        );
        expect(zoom, lessThanOrEqualTo(1), reason: '$room');
        expect(zoom, greaterThanOrEqualTo(minFitZoom), reason: '$room');
      }
    });

    test('should step in whole device pixels for pixel art', () {
      // 2 CSS px per game pixel at 2x: steps of a quarter.
      final double zoom = fitZoom(
        naturalWidth: 400,
        naturalHeight: 200,
        roomWidth: 390,
        dpr: 2,
        pixel: 2,
      );
      expect(zoom, 0.75);
      expect(zoom * 2 * 2, closeTo((zoom * 2 * 2).roundToDouble(), 1e-9));
    });

    test('should treat an empty box as fitting', () {
      expect(fitZoom(naturalWidth: 0, naturalHeight: 0, roomWidth: 10), 1);
    });
  });

  group('reorder slots', () {
    test('should pick the gap under the pointer', () {
      const List<double> mids = <double>[10, 30, 50];
      expect(slotAt(0, mids), 0);
      expect(slotAt(20, mids), 1);
      expect(slotAt(49, mids), 2);
      expect(slotAt(90, mids), 3);
    });

    test('should turn a gap into the index after lifting the row', () {
      expect(dropIndex(from: 0, slot: 3, count: 3), 2);
      expect(dropIndex(from: 2, slot: 0, count: 3), 0);
      expect(dropIndex(from: 1, slot: 1, count: 3), 1);
      expect(dropIndex(from: 1, slot: 2, count: 3), 1);
    });
  });

  group('tone', () {
    test('should map issue severities onto badge tones', () {
      expect(badgeToneOf(HuiSeverity.error), BadgeTone.error);
      expect(badgeToneOf(HuiSeverity.warning), BadgeTone.warning);
      expect(badgeToneOf(HuiSeverity.info), BadgeTone.info);
    });
  });

  group('field tip', () {
    test('should lead with the line and go on with the doc body', () {
      final String body = huiFieldDoc('action.navigation')!.body;
      expect(
        tipWithDoc('Push keeps a Back entry.', 'action.navigation'),
        'Push keeps a Back entry. $body',
      );
    });

    test('should be the line alone without a known doc', () {
      expect(tipWithDoc('Leave empty.', null), 'Leave empty.');
      expect(tipWithDoc('Leave empty.', 'no.such.key'), 'Leave empty.');
    });
  });
}
