/// Whether the browser may stop a page at [pageOrigin] from reaching [url]
/// before any answer: an https page calling plain http on this computer or
/// the local network, which mixed-content rules or an unanswered Local
/// Network Access prompt can refuse or hold.
bool browserMayBlock(String? pageOrigin, String url) {
  if (pageOrigin == null || Uri.tryParse(pageOrigin)?.scheme != 'https') {
    return false;
  }
  final Uri? target = Uri.tryParse(url);
  return target != null &&
      target.scheme == 'http' &&
      isLocalNetworkHost(target.host);
}

/// Whether [host] is this computer, a private or link-local IPv4 address, or
/// a `.local` name.
bool isLocalNetworkHost(String host) {
  final String name = host.toLowerCase();
  if (name == 'localhost' || name == '::1' || name.endsWith('.local')) {
    return true;
  }
  final List<String> parts = name.split('.');
  if (parts.length != 4) return false;
  final List<int?> octets = <int?>[
    for (final String part in parts) int.tryParse(part),
  ];
  if (octets.any((int? octet) => octet == null || octet < 0 || octet > 255)) {
    return false;
  }
  final int first = octets[0]!;
  final int second = octets[1]!;
  return first == 127 ||
      first == 10 ||
      (first == 172 && second >= 16 && second <= 31) ||
      (first == 192 && second == 168) ||
      (first == 169 && second == 254);
}
