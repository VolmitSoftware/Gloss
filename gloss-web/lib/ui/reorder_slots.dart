/// Where a dragged row lands. Pure Dart.
library;

/// The gap (`0 .. midpoints.length`) under [clientY], given each row's
/// vertical centre.
int slotAt(double clientY, List<double> midpoints) {
  int slot = 0;
  while (slot < midpoints.length && clientY > midpoints[slot]) {
    slot++;
  }
  return slot;
}

/// The index row [from] ends at when dropped into gap [slot] of a list of
/// [count] rows: the arguments to `removeAt(from)` then `insert(to, row)`.
int dropIndex({required int from, required int slot, required int count}) =>
    (slot > from ? slot - 1 : slot).clamp(0, count - 1);
