import 'dart:io';

/// The key an address's connections are counted under: an IPv4 address
/// itself, an IPv6 address by its `/64`, since one host can usually choose any
/// address inside its `/64`. Anything else counts as itself.
String addressKey(String address) {
  final InternetAddress? parsed = InternetAddress.tryParse(address);
  if (parsed == null || parsed.type != InternetAddressType.IPv6) {
    return address;
  }
  final List<int> bytes = parsed.rawAddress;
  final List<String> groups = <String>[
    for (int index = 0; index < 8; index += 2)
      ((bytes[index] << 8) | bytes[index + 1]).toRadixString(16),
  ];
  return '${groups.join(':')}::/64';
}

/// The client's address from an `X-Forwarded-For` header behind
/// [trustedProxyHops] proxies that each append the address they saw: the
/// entry the outermost trusted proxy appended. Null when no proxy is trusted,
/// the header has fewer entries than trusted hops, or that entry is not an IP
/// address; the caller then uses the socket peer.
String? forwardedAddress(String? header, int trustedProxyHops) {
  if (header == null || trustedProxyHops <= 0) return null;
  final List<String> entries = header.split(',');
  if (entries.length < trustedProxyHops) return null;
  return InternetAddress.tryParse(
    entries[entries.length - trustedProxyHops].trim(),
  )?.address;
}
