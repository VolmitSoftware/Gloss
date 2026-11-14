import 'package:gloss_sync_relay/src/frame_guard.dart';
import 'package:test/test.dart';

List<int> _frame(int first, int length, {bool payload = true}) {
  final List<int> header = <int>[first];
  if (length < 126) {
    header.add(0x80 | length);
  } else if (length < 65536) {
    header.addAll(<int>[0x80 | 126, length >> 8, length & 0xff]);
  } else {
    header.add(0x80 | 127);
    for (int shift = 56; shift >= 0; shift -= 8) {
      header.add((length >> shift) & 0xff);
    }
  }
  header.addAll(const <int>[1, 2, 3, 4]);
  return <int>[...header, if (payload) ...List<int>.filled(length, 0x61)];
}

void main() {
  test('should pass messages up to the limit, split anywhere', () {
    final List<int> bytes = <int>[
      ..._frame(0x81, 1000),
      ..._frame(0x01, 500),
      ..._frame(0x89, 4),
      ..._frame(0x80, 524),
      ..._frame(0x81, 1024),
    ];
    final FrameSizeGuard guard = FrameSizeGuard(1024);
    for (int index = 0; index < bytes.length; index += 7) {
      expect(
        guard.accept(bytes.sublist(index, (index + 7).clamp(0, bytes.length))),
        isTrue,
      );
    }
  });

  test('should refuse a header over the limit before its payload', () {
    final FrameSizeGuard guard = FrameSizeGuard(1024);
    expect(guard.accept(_frame(0x81, 1025, payload: false)), isFalse);
    expect(
      FrameSizeGuard(1024).accept(_frame(0x81, 1 << 40, payload: false)),
      isFalse,
    );
    expect(guard.accept(const <int>[0x81, 0x80]), isFalse);
  });

  test('should refuse continuation frames that add up past the limit', () {
    final FrameSizeGuard guard = FrameSizeGuard(1024);
    expect(guard.accept(_frame(0x01, 600)), isTrue);
    expect(guard.accept(_frame(0x89, 10)), isTrue);
    expect(guard.accept(_frame(0x00, 425, payload: false)), isFalse);
  });
}
