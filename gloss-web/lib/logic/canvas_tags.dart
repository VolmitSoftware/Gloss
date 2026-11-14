/// Which tags the menu canvas draws and where: at a small zoom the tags of
/// neighbouring components would run together, so a tag that would overlap
/// one already placed is left out, and the menu-centre tag moves off the
/// components. Pure Dart.
library;

/// A tag's chip on screen, in CSS pixels. [pinned] tags (the selection, the
/// hovered component) are always drawn and placed first.
class TagBox {
  const TagBox({
    required this.left,
    required this.top,
    required this.width,
    required this.height,
    this.pinned = false,
  });

  final double left;
  final double top;
  final double width;
  final double height;
  final bool pinned;

  bool overlaps(TagBox other) =>
      left < other.left + other.width &&
      other.left < left + width &&
      top < other.top + other.height &&
      other.top < top + height;
}

/// The indexes of [tags] to draw, pinned ones first, then the rest in order.
List<int> placeTags(List<TagBox> tags) {
  final List<int> placed = <int>[
    for (int index = 0; index < tags.length; index++)
      if (tags[index].pinned) index,
  ];
  for (int index = 0; index < tags.length; index++) {
    final TagBox tag = tags[index];
    if (tag.pinned) continue;
    if (placed.any((int other) => tags[other].overlaps(tag))) continue;
    placed.add(index);
  }
  return placed;
}

/// The first of [spots] that covers none of [occupied], or null when each
/// one does. The menu-centre tag tries the spots around its marker, nearest
/// first, so it never sits on a component's text.
int? clearSpot(List<TagBox> spots, List<TagBox> occupied) {
  for (int index = 0; index < spots.length; index++) {
    final TagBox spot = spots[index];
    if (!occupied.any((TagBox box) => box.overlaps(spot))) return index;
  }
  return null;
}
