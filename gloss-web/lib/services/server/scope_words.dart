/// A pairing's scopes in plain words (sync-relay/README.md, editor API
/// section 6.1), in the order shown everywhere. Pure Dart.
library;

import '../../l10n/hui_localizations.dart';

/// Every scope, in the contract's order.
const List<String> scopeOrder = <String>[
  'read',
  'history',
  'publish:screens',
  'publish:hud',
  'publish:chat',
  'publish:world',
  'publish:players',
  'publish:shared',
  'publish:images',
  'publish:commands',
];

/// The six library groups plus images: holding all of them reads as one line.
const List<String> publishEverything = <String>[
  'publish:screens',
  'publish:hud',
  'publish:chat',
  'publish:world',
  'publish:players',
  'publish:shared',
  'publish:images',
];

/// A scope in words; an unknown one as it is.
String scopeWord(String scope) => switch (scope) {
  'read' => huiText('See documents and images'),
  'history' => huiText('See stored versions'),
  'publish:screens' => huiText('Publish menus and screens'),
  'publish:hud' => huiText('Publish HUD and server list'),
  'publish:chat' => huiText('Publish chat'),
  'publish:world' => huiText('Publish world displays'),
  'publish:players' => huiText('Publish player labels'),
  'publish:shared' => huiText('Publish shared resources'),
  'publish:images' => huiText('Upload and delete images'),
  'publish:commands' => huiText('Publish documents that run console commands'),
  _ => scope,
};

/// What a pairing allows, one line each, in [scopeOrder] (unknown scopes
/// last). Every publish group and images together read as one line; a
/// folder limit adds "Only folders a, b".
List<String> scopeLines(Iterable<String> scopes, {List<String>? folders}) {
  final Set<String> given = scopes.toSet();
  final bool everything = publishEverything.every(given.contains);
  return <String>[
    for (final String scope in scopeOrder)
      if (given.contains(scope) &&
          !(everything && publishEverything.contains(scope)))
        scopeWord(scope)
      else if (everything && scope == publishEverything.first)
        given.contains('publish:commands')
            ? huiText('Publish every kind and images')
            : huiText(
                'Publish every kind and images, except documents that run console commands',
              ),
    for (final String scope in given)
      if (!scopeOrder.contains(scope)) scope,
    if (folders != null && folders.isNotEmpty)
      huiText('Only folders {folders}', <String, Object?>{
        'folders': folders.join(', '),
      }),
  ];
}
