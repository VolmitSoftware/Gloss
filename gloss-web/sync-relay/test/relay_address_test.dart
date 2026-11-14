import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';

void main() {
  test('should count an IPv6 address by its /64', () {
    expect(addressKey('2001:db8:1:2::1'), addressKey('2001:db8:1:2:ffff::9'));
    expect(addressKey('2001:db8:1:2::1'), isNot(addressKey('2001:db8:1:3::1')));
    expect(addressKey('203.0.113.9'), '203.0.113.9');
    expect(addressKey('unknown'), 'unknown');
  });

  test('should take the entry the outermost trusted proxy appended', () {
    expect(forwardedAddress('6.6.6.6, 203.0.113.9', 1), '203.0.113.9');
    expect(
      forwardedAddress('6.6.6.6, 203.0.113.9, 10.0.0.2', 2),
      '203.0.113.9',
    );
    expect(
      forwardedAddress('203.0.113.9', 2),
      isNull,
      reason: 'fewer entries than trusted hops',
    );
    expect(forwardedAddress('6.6.6.6, not-an-address', 1), isNull);
    expect(
      forwardedAddress('6.6.6.6', 0),
      isNull,
      reason: 'no trusted proxy: the header is ignored',
    );
    expect(forwardedAddress(null, 1), isNull);
  });
}
