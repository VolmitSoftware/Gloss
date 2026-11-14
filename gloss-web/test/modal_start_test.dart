import 'package:gloss_editor/ui/modal_start.dart';
import 'package:test/test.dart';

void main() {
  test('a coarse pointer starts on the dialog itself, never a text field', () {
    expect(modalStart(coarsePointer: true, keyboard: true), ModalStart.panel);
  });

  test('a dialog opened with the keyboard starts in the first field', () {
    expect(
      modalStart(coarsePointer: false, keyboard: true),
      ModalStart.firstField,
    );
  });

  test('a dialog opened with a mouse click starts on the dialog, no ring', () {
    expect(modalStart(coarsePointer: false, keyboard: false), ModalStart.panel);
  });
}
