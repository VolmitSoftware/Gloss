import 'package:gloss_editor/logic/canvas_tags.dart';
import 'package:test/test.dart';

void main() {
  test('should put the menu centre tag where it covers no component', () {
    const List<TagBox> components = <TagBox>[
      TagBox(left: 90, top: 70, width: 120, height: 30),
    ];
    const List<TagBox> spots = <TagBox>[
      TagBox(left: 112, top: 78, width: 70, height: 18),
      TagBox(left: 112, top: 102, width: 70, height: 18),
      TagBox(left: 18, top: 78, width: 70, height: 18),
    ];
    expect(clearSpot(spots, components), 1);
    expect(clearSpot(spots, const <TagBox>[]), 0);
    expect(
      clearSpot(spots.sublist(0, 1), components),
      isNull,
      reason: 'no clear spot: the tag is left out, the marker stays',
    );
  });

  test('should draw every tag that clears the ones placed before it', () {
    const List<TagBox> tags = <TagBox>[
      TagBox(left: 0, top: 0, width: 60, height: 18),
      TagBox(left: 70, top: 0, width: 60, height: 18),
      TagBox(left: 0, top: 30, width: 60, height: 18),
    ];
    expect(placeTags(tags), <int>[0, 1, 2]);
  });

  test('should drop a tag that would run into another, keeping the pinned ones', () {
    const List<TagBox> tags = <TagBox>[
      TagBox(left: 0, top: 0, width: 80, height: 18),
      TagBox(left: 40, top: 4, width: 80, height: 18),
      TagBox(left: 60, top: 2, width: 80, height: 18, pinned: true),
    ];
    expect(placeTags(tags), <int>[2], reason: 'the selected tag first; both others overlap it');
    expect(placeTags(tags.sublist(0, 2)), <int>[0]);
  });
}
