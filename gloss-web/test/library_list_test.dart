import 'package:gloss_editor/components/panels/library_list.dart';
import 'package:gloss_editor/config/kind_groups.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

WorkspaceDoc _doc(
  String title, {
  String? runtimeId,
  WorkspaceDocKind kind = WorkspaceDocKind.menu,
  String id = '',
  String? folderId,
}) => WorkspaceDoc(
  id: id.isEmpty ? title : id,
  title: title,
  runtimeId: runtimeId,
  json: '{}',
  updatedAt: 0,
  folderId: folderId,
  kind: kind,
);

WorkspaceFolder _folder(String id, {String? parentId}) =>
    WorkspaceFolder(id: id, title: id, updatedAt: 0, parentId: parentId);

/// Each line as `<depth><kind letter>:<name>` for compact expectations.
List<String> _shape(List<LibraryLine> lines) => <String>[
  for (final LibraryLine line in lines)
    '${line.depth}${switch (line.kind) {
      LibraryLineKind.folder => 'f',
      LibraryLineKind.group => 'g',
      LibraryLineKind.document => 'd',
    }}:${line.folder?.title ?? line.group?.name ?? line.doc!.title}',
];

void main() {
  group('libraryDocumentMatches', () {
    final WorkspaceDoc lobby = _doc(
      'Lobby selector',
      runtimeId: 'hub-main',
      kind: WorkspaceDocKind.scoreboard,
    );

    test('an empty or blank query matches every document', () {
      expect(libraryDocumentMatches(lobby, ''), isTrue);
      expect(libraryDocumentMatches(lobby, '   '), isTrue);
    });

    test('matches the title ignoring case and outer spaces', () {
      expect(libraryDocumentMatches(lobby, 'LOBBY'), isTrue);
      expect(libraryDocumentMatches(lobby, '  selector '), isTrue);
    });

    test('matches the runtime id', () {
      expect(libraryDocumentMatches(lobby, 'hub-m'), isTrue);
    });

    test('matches the kind label, plural or singular', () {
      expect(libraryDocumentMatches(lobby, 'scoreboards'), isTrue);
      expect(libraryDocumentMatches(lobby, 'Scoreboard'), isTrue);
    });

    test('does not match unrelated text', () {
      expect(libraryDocumentMatches(lobby, 'hologram'), isFalse);
      expect(
        libraryDocumentMatches(_doc('Map', kind: WorkspaceDocKind.panel), 'x'),
        isFalse,
      );
    });
  });

  test('documents sort by kind group, then kind, then title', () {
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[
      _doc('beta', kind: WorkspaceDocKind.hologram),
      _doc('Zeta', kind: WorkspaceDocKind.menu),
      _doc('alpha', kind: WorkspaceDocKind.hologram),
      _doc('Gamma', kind: WorkspaceDocKind.animation),
      _doc('Board', kind: WorkspaceDocKind.scoreboard),
      _doc('Card', kind: WorkspaceDocKind.containerPreview),
      _doc('apple', kind: WorkspaceDocKind.menu),
    ]..sort(compareLibraryDocuments);
    expect(
      <String>[for (final WorkspaceDoc doc in docs) doc.title],
      <String>['apple', 'Zeta', 'Card', 'Board', 'alpha', 'beta', 'Gamma'],
    );
  });

  test('documents group under their kind group headings in group order', () {
    final List<LibraryGroup> groups = groupLibraryDocuments(<WorkspaceDoc>[
      _doc('Names', kind: WorkspaceDocKind.names),
      _doc('Tag', kind: WorkspaceDocKind.nametag),
      _doc('Zed', kind: WorkspaceDocKind.menu),
      _doc('Anchor', kind: WorkspaceDocKind.inventory),
    ]);
    expect(
      <KindGroup>[for (final LibraryGroup group in groups) group.group],
      <KindGroup>[KindGroup.screens, KindGroup.players, KindGroup.shared],
    );
    expect(
      <String>[
        for (final WorkspaceDoc doc in groups.first.documents) doc.title,
      ],
      <String>['Zed', 'Anchor'],
    );
    expect(groupLibraryDocuments(const <WorkspaceDoc>[]), isEmpty);
  });

  group('showsRuntimeId', () {
    test('hidden when it reads like the title in Simple', () {
      expect(
        showsRuntimeId(_doc('Lobby', runtimeId: 'lobby'), advanced: false),
        isFalse,
      );
    });

    test('shown when it differs from the title', () {
      expect(
        showsRuntimeId(_doc('Main lobby', runtimeId: 'lobby'), advanced: false),
        isTrue,
      );
    });

    test('hidden in Simple when it is the title written as an id', () {
      expect(
        showsRuntimeId(_doc('New menu', runtimeId: 'New-menu'), advanced: false),
        isFalse,
      );
      expect(
        showsRuntimeId(
          _doc('New preview document 2', runtimeId: 'new_preview_document_2'),
          advanced: false,
        ),
        isFalse,
      );
    });

    test('shown in Advanced unless it is the title exactly', () {
      expect(
        showsRuntimeId(_doc('Lobby', runtimeId: 'lobby'), advanced: true),
        isTrue,
      );
      expect(
        showsRuntimeId(_doc('motd', runtimeId: 'motd'), advanced: true),
        isFalse,
        reason: 'a second line saying the name again',
      );
    });

    test('never shown for a document without one', () {
      expect(showsRuntimeId(_doc('Map'), advanced: true), isFalse);
    });
  });

  test('Simple leaves flow maps and shared resources out of the library', () {
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[
      _doc('Lobby'),
      _doc('Routes', kind: WorkspaceDocKind.panel),
      _doc('Spin', kind: WorkspaceDocKind.animation),
      _doc('Icons', kind: WorkspaceDocKind.glyph, folderId: 'Shops'),
      _doc('Market', folderId: 'Shops'),
    ];
    List<String> shape({required bool advanced}) => _shape(
      libraryLines(
        folders: <WorkspaceFolder>[_folder('Shops')],
        docs: docs,
        listed: (WorkspaceDoc _) => true,
        filtering: false,
        searching: false,
        expanded: const <String>{'Shops'},
        groupOpen: (KindGroup _) => true,
        advanced: advanced,
      ),
    );
    expect(shape(advanced: false), <String>[
      '0f:Shops',
      '1d:Market',
      '0g:screens',
      '0d:Lobby',
    ]);
    expect(shape(advanced: true), containsAll(<String>['1d:Icons', '0d:Routes', '0d:Spin']));
  });

  test('Simple hides a folder that holds only kinds it leaves out', () {
    final List<WorkspaceFolder> folders = <WorkspaceFolder>[
      _folder('Animations'),
      _folder('Empty'),
      _folder('Mixed'),
    ];
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[
      _doc('Spin', kind: WorkspaceDocKind.animation, folderId: 'Animations'),
      _doc('Glow', kind: WorkspaceDocKind.animation, folderId: 'Mixed'),
      _doc('Shop', folderId: 'Mixed'),
    ];
    List<String> shape({required bool advanced}) => _shape(
      libraryLines(
        folders: folders,
        docs: docs,
        listed: (WorkspaceDoc _) => true,
        filtering: false,
        searching: false,
        expanded: const <String>{},
        groupOpen: (KindGroup _) => true,
        advanced: advanced,
      ),
    );
    expect(shape(advanced: false), <String>['0f:Empty', '0f:Mixed']);
    expect(shape(advanced: true), <String>['0f:Animations', '0f:Empty', '0f:Mixed']);
  });

  test('a document names its kind only in a folder that mixes kinds', () {
    final List<LibraryLine> lines = libraryLines(
      folders: <WorkspaceFolder>[_folder('Holograms'), _folder('Mixed')],
      docs: <WorkspaceDoc>[
        _doc('Sign', kind: WorkspaceDocKind.hologram, folderId: 'Holograms'),
        _doc('Post', kind: WorkspaceDocKind.hologram, folderId: 'Holograms'),
        _doc('Shop', folderId: 'Mixed'),
        _doc('Board', kind: WorkspaceDocKind.scoreboard, folderId: 'Mixed'),
        _doc('Lobby'),
      ],
      listed: (WorkspaceDoc _) => true,
      filtering: false,
      searching: false,
      expanded: const <String>{'Holograms', 'Mixed'},
      groupOpen: (KindGroup _) => true,
      advanced: true,
    );
    expect(<String, bool>{
      for (final LibraryLine line in lines)
        if (line.kind == LibraryLineKind.document) line.doc!.title: line.namesKind,
    }, <String, bool>{
      'Sign': false,
      'Post': false,
      'Shop': true,
      'Board': true,
      'Lobby': false,
    });
  });

  group('libraryLines', () {
    final List<WorkspaceFolder> folders = <WorkspaceFolder>[
      _folder('Shops'),
      _folder('Arcade'),
      _folder('Deep', parentId: 'Shops'),
    ];
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[
      _doc('Market', folderId: 'Shops'),
      _doc('Vault', folderId: 'Deep'),
      _doc('Lobby'),
      _doc('Sidebar', kind: WorkspaceDocKind.scoreboard),
    ];

    List<LibraryLine> lines({
      Set<String> expanded = const <String>{},
      String query = '',
      Set<KindGroup> closed = const <KindGroup>{},
    }) => libraryLines(
      folders: folders,
      docs: docs,
      listed: (WorkspaceDoc doc) => libraryDocumentMatches(doc, query),
      filtering: query.trim().isNotEmpty,
      searching: query.trim().isNotEmpty,
      expanded: expanded,
      groupOpen: (KindGroup group) => !closed.contains(group),
      advanced: false,
    );

    test(
      'folders by title first, then root documents under group headings',
      () {
        expect(_shape(lines()), <String>[
          '0f:Arcade',
          '0f:Shops',
          '0g:screens',
          '0d:Lobby',
          '0g:hud',
          '0d:Sidebar',
        ]);
      },
    );

    test('an expanded folder lists its folders, then its documents, one level deeper', () {
      expect(_shape(lines(expanded: <String>{'Shops', 'Deep'})), <String>[
        '0f:Arcade',
        '0f:Shops',
        '1f:Deep',
        '2d:Vault',
        '1d:Market',
        '0g:screens',
        '0d:Lobby',
        '0g:hud',
        '0d:Sidebar',
      ]);
    });

    test('a closed group keeps its heading and hides its documents', () {
      expect(_shape(lines(closed: <KindGroup>{KindGroup.screens})), <String>[
        '0f:Arcade',
        '0f:Shops',
        '0g:screens',
        '0g:hud',
        '0d:Sidebar',
      ]);
    });

    test('a search opens every folder and drops the ones without a match', () {
      expect(_shape(lines(query: 'vault')), <String>[
        '0f:Shops',
        '1f:Deep',
        '2d:Vault',
      ]);
    });

    test('a drop on a line lands in its folder, a root line on the root', () {
      final List<LibraryLine> all = lines(expanded: <String>{'Shops'});
      String? target(String name) => all
          .firstWhere(
            (LibraryLine line) =>
                (line.folder?.title ?? line.group?.name ?? line.doc!.title) ==
                name,
          )
          .dropFolderId;
      expect(target('Shops'), 'Shops');
      expect(target('Deep'), 'Deep');
      expect(target('Market'), 'Shops');
      expect(target('Lobby'), isNull);
      expect(target('screens'), isNull);
    });

    test('counts and heights', () {
      final List<LibraryLine> all = libraryLines(
        folders: folders,
        docs: <WorkspaceDoc>[
          ...docs,
          _doc('Vendor', runtimeId: 'shop/vendor'),
        ],
        listed: (WorkspaceDoc _) => true,
        filtering: false,
        searching: false,
        expanded: const <String>{},
        groupOpen: (KindGroup _) => true,
        advanced: false,
      );
      final LibraryLine shops = all.firstWhere(
        (LibraryLine line) => line.folder?.id == 'Shops',
      );
      expect(shops.count, 2);
      final LibraryLine screens = all.firstWhere(
        (LibraryLine line) => line.group == KindGroup.screens,
      );
      expect(screens.count, 2);
      expect(screens.rows, 1);
      expect(
        all.firstWhere((LibraryLine line) => line.doc?.title == 'Vendor').rows,
        1.5,
      );
    });
  });

  group('libraryWindow', () {
    final List<double> rows = <double>[for (int i = 0; i < 1000; i++) 1];

    test('renders the rows in view plus the overscan on each side', () {
      final LibraryWindow window = libraryWindow(
        rows,
        viewTop: 100,
        viewHeight: 20,
        overscan: 5,
      );
      expect(window.first, 95);
      expect(window.end, 125);
      expect(window.before, 95);
      expect(window.after, 875);
    });

    test('clamps at both ends', () {
      expect(
        libraryWindow(rows, viewTop: -40, viewHeight: 10, overscan: 5).first,
        0,
      );
      final LibraryWindow last = libraryWindow(
        rows,
        viewTop: 995,
        viewHeight: 30,
        overscan: 5,
      );
      expect(last.end, 1000);
      expect(last.after, 0);
    });

    test('uneven rows place by their offsets', () {
      final LibraryWindow window = libraryWindow(
        <double>[1, 1.5, 1, 1.5, 1],
        viewTop: 2.6,
        viewHeight: 1,
        overscan: 0,
      );
      expect(window.first, 2);
      expect(window.end, 4);
      expect(window.before, 2.5);
      expect(window.after, 1);
    });
  });

  group('libraryView', () {
    const LibraryView previous = LibraryView(4, 12);

    test('reads the view in rows from the list top', () {
      final LibraryView view = libraryView(
        scrollTop: 164,
        clientHeight: 762,
        listTop: 100,
        rowPx: 32,
        previous: previous,
      );
      expect(view.top, 2);
      expect(view.height, closeTo(23.81, 0.01));
    });

    test('a scroller with no height keeps the previous view', () {
      final LibraryView view = libraryView(
        scrollTop: 0,
        clientHeight: 0,
        listTop: 0,
        rowPx: 32,
        previous: previous,
      );
      expect(view.top, previous.top);
      expect(view.height, previous.height);
    });

    test('the first view fills a full height drawer', () {
      final LibraryWindow window = libraryWindow(
        <double>[for (int i = 0; i < 500; i++) 1],
        viewTop: initialLibraryView.top,
        viewHeight: initialLibraryView.height,
      );
      expect(window.end * 32, greaterThanOrEqualTo(1024));
    });
  });
}
