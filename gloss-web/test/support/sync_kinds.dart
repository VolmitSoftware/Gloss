/// The document kinds `EditorSyncDocumentKind` declares, read from the Java
/// source so editor tests can pin themselves to the plugin's own table.
library;

/// One row of the protocol's kind table.
final class ProtocolKind {
  const ProtocolKind({
    required this.wireName,
    required this.storageName,
    required this.layout,
    required this.versioned,
    required this.singletonId,
  });

  final String wireName;
  final String storageName;
  final String layout;
  final bool versioned;
  final String singletonId;

  String get row =>
      '| `$wireName` | `$storageName` | $layout | ${versioned ? 'yes' : 'no'} '
      '| ${singletonId.isEmpty ? '-' : '`$singletonId`'} |';
}

List<ProtocolKind> protocolKinds(String java) {
  final RegExp constant = RegExp(
    r'\n  [A-Z_]+\(\s*"([a-z0-9-]+)"\s*,\s*"([^"]+)"\s*,\s*'
    r'Layout\.([A-Z_]+)\s*,\s*(true|false)\s*,\s*([^,]+?)\s*,',
  );
  final List<ProtocolKind> kinds = <ProtocolKind>[];
  for (final RegExpMatch match in constant.allMatches(java)) {
    final String rawSingleton = match.group(5)!.trim();
    final String singletonId;
    if (rawSingleton == 'null') {
      singletonId = '';
    } else if (rawSingleton.startsWith('"')) {
      singletonId = rawSingleton.substring(1, rawSingleton.length - 1);
    } else if (rawSingleton.endsWith('DEFAULT_ID')) {
      singletonId = 'default';
    } else {
      singletonId = rawSingleton;
    }
    kinds.add(
      ProtocolKind(
        wireName: match.group(1)!,
        storageName: match.group(2)!,
        layout: match.group(3)!,
        versioned: match.group(4) == 'true',
        singletonId: singletonId,
      ),
    );
  }
  kinds.sort(
    (ProtocolKind left, ProtocolKind right) =>
        left.wireName.compareTo(right.wireName),
  );
  return kinds;
}
