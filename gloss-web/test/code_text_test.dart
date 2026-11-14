/// The text the code pane shows for a document's exported JSON: a file kept
/// byte for byte as it was imported on one line reads like every other kind,
/// indented, and anything already laid out stays exactly as it is.
library;

import 'package:gloss_editor/components/code_editor/code_text.dart';
import 'package:gloss_editor/model/json_codec.dart';
import 'package:test/test.dart';

void main() {
  test('should indent one-line JSON with the export writer', () {
    const String minified = '{"offset":[0,1.7,2.5],"components":[]}';
    final String shown = huiCodeViewText(minified);
    expect(shown, contains('\n'));
    expect(
      shown,
      huiWriteJson(<String, Object?>{
        'offset': <Object?>[0, 1.7, 2.5],
        'components': <Object?>[],
      }),
    );
  });

  test('should keep text that already has line breaks', () {
    const String laidOut = '{\n    "a": 1\n}';
    expect(huiCodeViewText(laidOut), laidOut);
  });

  test('should keep one-line text that is not JSON', () {
    const String broken = '{"a": ';
    expect(huiCodeViewText(broken), broken);
  });
}
