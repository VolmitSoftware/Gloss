import 'dart:io';

import 'package:gloss_editor/components/shell/shell_keys.dart';
import 'package:test/test.dart';

void main() {
  group('keys while an Arcane surface is open', () {
    test('Escape over a dialog or sheet closes the owner state', () {
      expect(
        huiOverlayKey(const ShellKey(key: 'Escape', overlayOpen: true)),
        HuiOverlayKey.closeOverlay,
      );
    });

    test('an open menu answers its own keys, Escape included', () {
      expect(
        huiOverlayKey(
          const ShellKey(key: 'Escape', overlayOpen: true, menuOpen: true),
        ),
        HuiOverlayKey.ignore,
      );
    });

    test('other keys and modified Escape are left alone', () {
      expect(
        huiOverlayKey(const ShellKey(key: 'k', overlayOpen: true)),
        HuiOverlayKey.ignore,
      );
      expect(
        huiOverlayKey(
          const ShellKey(key: 'Escape', overlayOpen: true, ctrl: true),
        ),
        HuiOverlayKey.ignore,
      );
    });
  });

  group('mobile pane Escape', () {
    test('closes an open pane with an unmodified Escape press', () {
      expect(
        huiClosesMobilePane(
          const ShellKey(key: 'Escape'),
          mobilePaneOpen: true,
        ),
        isTrue,
      );
    });

    test('does not claim Escape without a pane or with modifiers', () {
      expect(
        huiClosesMobilePane(
          const ShellKey(key: 'Escape'),
          mobilePaneOpen: false,
        ),
        isFalse,
      );
      expect(
        huiClosesMobilePane(
          const ShellKey(key: 'Escape', ctrl: true),
          mobilePaneOpen: true,
        ),
        isFalse,
      );
    });

    test('both canvas stages defer Escape to the open pane', () {
      for (final String path in <String>[
        'lib/components/canvas/canvas_interactions.dart',
        'lib/components/preview_card/preview_card_interactions.dart',
      ]) {
        expect(
          File(path).readAsStringSync(),
          contains("if (key.key == 'Escape' && huiMobilePaneOpen()) return;"),
          reason: path,
        );
      }
    });
  });
}
