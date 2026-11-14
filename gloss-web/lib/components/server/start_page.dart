/// The start page. It leads with Continue when this browser keeps a paired
/// server or a browser workspace with work in it: each paired server with
/// its state (Online, Checking or Not reachable) and counts, Open and a
/// "..." menu with Forget server, then the browser workspace. Below: the
/// Pairing link field with Pair, then the ways into the browser workspace
/// (Import, Templates, New document), the same on every visit.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../services/server/server_hub.dart';
import '../../services/server/server_profiles.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/menu.dart';
import '../../ui/tone.dart';
import 'connection_words.dart';
import 'pair_link_form.dart';
import 'start_parts.dart';
import 'start_rows.dart';
import '../../ui/toast.dart';

class StartPage extends StatefulWidget {
  const StartPage({
    required this.hub,
    required this.local,
    required this.onOpenServer,
    required this.onOpenLocal,
    required this.onNewDocument,
    required this.onTemplates,
    required this.onImport,
    required this.onPairCode,
    this.now,
    super.key,
  });

  final ServerHub hub;

  /// The browser workspace: its document count and whether it holds work.
  final LocalSummary local;
  final void Function(String serverId) onOpenServer;
  final void Function() onOpenLocal;
  final void Function() onNewDocument;
  final void Function() onTemplates;
  final void Function() onImport;

  /// A pasted pairing code: the pairing card takes it.
  final void Function(String code) onPairCode;
  final DateTime Function()? now;

  @override
  State<StartPage> createState() => _StartPageState();
}

class _StartPageState extends State<StartPage> {
  /// Asks again after servers found not reachable while the page is open.
  Timer? _recheck;

  @override
  void initState() {
    super.initState();
    component.hub.addListener(_changed);
    // One hello per saved server: Online, Checking or Not reachable.
    component.hub.probeServers();
    _recheck = Timer.periodic(
      const Duration(seconds: 10),
      (Timer _) => component.hub.probeUnreachable(),
    );
  }

  @override
  void dispose() {
    _recheck?.cancel();
    component.hub.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  Future<void> _forget(ServerProfile server) async {
    final bool yes = await askToConfirm(
      title: huiText('Forget {server}?', <String, Object?>{
        'server': server.name,
      }),
      message: component.hub.forgetPrompt(server.id),
      confirmLabel: huiText('Forget server'),
      destructive: true,
    );
    if (!yes) return;
    final String? stillValid = await component.hub.forget(server.id);
    if (stillValid == null) {
      toastSuccess(
        huiText('Forgot {server}.', <String, Object?>{'server': server.name}),
      );
    } else {
      toastWarning(
        huiText(
          'Forgot {server}. Its pairing works until it expires; {command} ends it now.',
          <String, Object?>{
            'server': server.name,
            'command': '/gloss editor revoke $stillValid',
          },
        ),
      );
    }
  }

  static String _slug(String key) =>
      key.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');

  /// [lead] is the first row: its Open is the page's main action.
  Widget _row(ContinueRow row, {required bool lead}) {
    final ServerProfile? server = row.server;
    final List<Widget> meta = <Widget>[];
    if (server != null) {
      final ServerHub hub = component.hub;
      final (String label, BadgeTone tone) = serverState(
        hub.status(server.id),
        paired: hub.hasToken(server.id),
      );
      meta.add(
        StatusBadge(
          label,
          tone: tone,
          dot: true,
          attributes: const <String, String>{'data-testid': 'server-state'},
        ),
      );
    }
    meta.add(mutedText(row.line));
    final RowAction open = RowAction(
      huiText('Open'),
      server != null
          ? () => component.onOpenServer(server.id)
          : component.onOpenLocal,
      attributes: <String, String>{
        'aria-label': huiText('Open {name}', <String, Object?>{
          'name': row.name,
        }),
        'data-testid': 'continue-open',
      },
    );
    return dom.div(
      attributes: <String, String>{
        'role': 'listitem',
        'data-continue': row.isServer ? 'server' : 'local',
        if (server != null) 'data-server': server.id,
      },
      styles: const dom.Styles(raw: <String, String>{'padding': '0.75rem 0'}),
      <Widget>[
        Row(
          spacing: 16,
          children: <Widget>[
            Expanded(
              child: stack(spacing: 4, <Widget>[
                Text.truncated(row.name, weight: FontWeight.w500),
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: meta,
                ),
                if (row.warning != null) mutedText(row.warning!, small: true),
              ]),
            ),
            ActionRow(
              dense: true,
              label: row.name,
              moreId: 'continue-more-${_slug(row.key)}',
              more: <AppMenuEntry>[
                if (server != null)
                  AppMenuEntry(
                    huiText('Forget server'),
                    () => unawaited(_forget(server)),
                    destructive: true,
                  ),
              ],
              secondary: <RowAction>[if (!lead) open],
              primary: lead ? open : null,
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final List<ContinueRow> rows = continueRows(
      servers: component.hub.servers,
      local: component.local,
      tokenOf: component.hub.token,
      now: (component.now ?? DateTime.now)(),
    );
    final RowAction newDocument = RowAction(
      huiText('New document'),
      component.onNewDocument,
    );
    return startColumn(<Widget>[
      Row(
        spacing: 12,
        children: <Widget>[
          const dom.img(
            src: 'assets/brand/logo.png',
            alt: '',
            width: 40,
            height: 40,
          ),
          stack(spacing: 2, <Widget>[
            pageTitle(huiText('Gloss web editor')),
            mutedText(
              huiText('Edit menus, holograms, HUD and chat for your server.'),
            ),
          ]),
        ],
      ),
      if (rows.isNotEmpty)
        dom.section(
          attributes: const <String, String>{
            'aria-labelledby': 'start-continue',
          },
          <Widget>[
            stack(spacing: 8, <Widget>[
              sectionHeading(huiText('Continue'), id: 'start-continue'),
              Card(
                variant: CardVariant.outlined,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: dom.div(
                  attributes: <String, String>{
                    'role': 'list',
                    'aria-label': huiText('Continue'),
                  },
                  <Widget>[
                    for (
                      int index = 0;
                      index < rows.length;
                      index++
                    ) ...<Widget>[
                      if (index > 0) const ArcaneSeparator(),
                      _row(rows[index], lead: index == 0),
                    ],
                  ],
                ),
              ),
            ]),
          ],
        ),
      dom.section(
        attributes: const <String, String>{'aria-labelledby': 'start-pair'},
        <Widget>[
          stack(spacing: 8, <Widget>[
            sectionHeading(
              huiText('Pair a server'),
              id: 'start-pair',
              tip: huiText(
                'Run /gloss editor pair on the server and open its link, or paste it here.',
              ),
            ),
            PairLinkForm(
              id: 'start-pair-link',
              onCode: component.onPairCode,
              lead: rows.isEmpty,
            ),
          ]),
        ],
      ),
      dom.section(
        attributes: const <String, String>{'aria-labelledby': 'start-browser'},
        <Widget>[
          stack(spacing: 8, <Widget>[
            sectionHeading(
              huiText('Start in this browser'),
              id: 'start-browser',
              tip: huiText(
                'Documents kept in this browser only. Export them as files to use them on a server.',
              ),
            ),
            // With Continue rows, Open leads the page and these follow it.
            FormFooter(
              secondary: <RowAction>[
                RowAction(huiText('Import'), component.onImport),
                RowAction(huiText('Templates'), component.onTemplates),
                if (rows.isNotEmpty) newDocument,
              ],
              primary: rows.isEmpty ? newDocument : null,
            ),
          ]),
        ],
      ),
    ]);
  }
}
