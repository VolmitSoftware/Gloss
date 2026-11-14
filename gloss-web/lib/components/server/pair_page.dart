/// `#/pair/<code>`: one card that names the server the link is for, when
/// the link stops working, what the editor may do there, this browser's
/// name, Remember this browser, and Pair. The code moves into memory and
/// the address becomes `#/pair`, so the secret stays out of history. A link
/// that cannot work says why in one sentence, gives the next step and, when
/// a new link is needed, the field to paste it.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../services/server/pairing.dart';
import '../../services/server/scope_words.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_hub.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import 'pair_copy.dart';
import 'pair_link_form.dart';
import 'start_parts.dart';
import '../../ui/toast.dart';

class PairPage extends StatefulWidget {
  const PairPage({
    required this.code,
    required this.hub,
    required this.onPaired,
    required this.onCancel,
    required this.userAgent,
    required this.pageOrigin,
    this.now,
    super.key,
  });

  final String code;
  final ServerHub hub;

  /// Called with the server id once the token is stored.
  final void Function(String serverId) onPaired;
  final void Function() onCancel;
  final String userAgent;
  final String pageOrigin;

  /// The clock the expiry is checked against.
  final DateTime Function()? now;

  @override
  State<PairPage> createState() => _PairPageState();
}

class _PairPageState extends State<PairPage> {
  static const String _switchId = 'pair-remember';
  static const String _nameId = 'pair-browser-name';

  String _code = '';
  PairingPayload? _payload;
  PairProblem? _problem;

  /// On by default: the pairing is revocable on the server and ends after
  /// tokenDays without use; a tab-only token makes every new tab ask again.
  bool _remember = true;
  bool _busy = false;

  /// The link's relay did not answer when the card opened.
  bool _relayDown = false;

  /// Runs while the pairing limit's wait lasts; Pair stays off until then.
  Timer? _limit;
  late String _client = browserName(component.userAgent);

  DateTime get _now => (component.now ?? DateTime.now)().toUtc();

  @override
  void initState() {
    super.initState();
    _read(component.code);
  }

  @override
  void dispose() {
    _limit?.cancel();
    super.dispose();
  }

  /// Shows [problem]; one with a wait keeps Pair off and counts the wait
  /// down each second, then clears itself.
  void _refuse(PairProblem problem) {
    _limit?.cancel();
    _limit = null;
    _problem = problem;
    final Duration? wait = problem.wait;
    if (wait == null) return;
    final DateTime ends = _now.add(wait);
    _limit = Timer.periodic(const Duration(seconds: 1), (Timer timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      final Duration left = ends.difference(_now);
      // Only the limit's own line counts down; a newer problem stays.
      final bool counting = _problem?.wait != null;
      setState(() {
        if (left > Duration.zero) {
          if (counting) _problem = pairingLimit(left);
          return;
        }
        timer.cancel();
        _limit = null;
        if (counting) _problem = null;
      });
    });
  }

  /// Reads [code]; a code that cannot work is a problem before anything is
  /// sent.
  void _read(String code) {
    _code = code;
    _payload = null;
    _problem = null;
    if (code.isEmpty) {
      _problem = notALink;
      return;
    }
    try {
      final PairingPayload payload = decodePairingCode(code);
      _payload = payload;
      if (!payload.expiresAt.isAfter(_now)) {
        _problem = expiredLinkAt(payload.expiresAt);
      }
      _checkPaths(code, payload);
    } on PairingCodeError catch (error) {
      _problem = error.message == 'no-path'
          ? pairProblemOf('no-path', pageOrigin: component.pageOrigin)
          : notALink;
    } on FormatException {
      _problem = notALink;
    }
  }

  /// Before Pair is pressed: a link whose relay does not answer pairs over
  /// the direct address, and the card says so; when that address also
  /// refuses this page's origin, the card names the setting instead.
  void _checkPaths(String code, PairingPayload payload) {
    _relayDown = false;
    if (payload.directUrl == null) return;
    unawaited(
      Future.wait<bool>(<Future<bool>>[
        component.hub.relayAnswers(code),
        component.hub.directRefusesOrigin(code),
      ]).then((List<bool> found) {
        // relayAnswers is true for a link without a relay, which only has
        // the direct address.
        final bool relayCarries = payload.relayUrl != null && found[0];
        final bool refused = found[1];
        if (!mounted || _code != code || _problem != null) return;
        if (refused && !relayCarries) {
          setState(
            () => _refuse(
              pairProblemOf(
                'origin-not-allowed',
                pageOrigin: component.pageOrigin,
                directUrl: payload.directUrl,
              ),
            ),
          );
        } else if (!found[0]) {
          setState(() => _relayDown = true);
        }
      }),
    );
  }

  Future<void> _pair() async {
    final PairingPayload? payload = _payload;
    if (payload == null || _busy) return;
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      final String client = _client.trim();
      final String id = await component.hub.pair(
        _code,
        remember: _remember,
        client: client.isEmpty ? null : client,
      );
      // A plain success says nothing the server's loading workspace does
      // not: only a refused storage is worth a toast.
      if (component.hub.storageRefused) {
        final String name = component.hub.server(id)?.name ?? id;
        toastWarning(
          huiText(
            'Paired with {server} for this tab only. The browser refused storage.',
            <String, Object?>{'server': name},
          ),
        );
      }
      component.onPaired(id);
    } on PairingRefused catch (refused) {
      if (mounted) {
        setState(
          () => _refuse(
            pairProblemFor(
              refused,
              pageOrigin: component.pageOrigin,
              directUrl: payload.directUrl,
            ),
          ),
        );
      }
    } on PairingCodeError {
      if (mounted) setState(() => _problem = notALink);
    } on Object {
      if (mounted) {
        setState(
          () => _problem = pairProblemOf(
            'dropped',
            pageOrigin: component.pageOrigin,
            directUrl: payload.directUrl,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _newLink(String code) => setState(() => _read(code));

  @override
  Widget build(BuildContext context) {
    final PairingPayload? payload = _payload;
    final PairProblem? problem = _problem;
    final bool spent = problem != null && problem.newLink;
    final String? expiry = payload == null
        ? null
        : pairExpiryLine(
            payload.expiresAt,
            spent: spent || !payload.expiresAt.isAfter(_now),
          );
    final String title =
        payload?.serverName ??
        payload?.label ??
        (payload == null
            ? huiText('Pair a server')
            : huiText('Minecraft server'));
    return startColumn(label: title, <Widget>[
      brandLine(),
      Card(
        attributes: const <String, String>{'data-pair-card': ''},
        children: <Widget>[
          stack(spacing: 16, <Widget>[
            stack(spacing: 4, <Widget>[
              pageTitle(title),
              // Only while the link still works: once it cannot, the
              // problem below says why, once.
              if (expiry != null)
                dom.div(
                  attributes: const <String, String>{
                    'data-testid': 'pair-expiry',
                  },
                  <Widget>[mutedText(expiry)],
                ),
              if (payload != null && problem == null && _relayDown)
                dom.div(
                  attributes: const <String, String>{
                    'data-testid': 'pair-relay-down',
                  },
                  <Widget>[
                    mutedText(
                      huiText(
                        'The relay does not answer, so this pairing uses the direct address.',
                      ),
                    ),
                  ],
                ),
            ]),
            // In the card's own surface: a bordered alert here would be a
            // card inside the card.
            if (problem != null)
              dom.div(
                key: ValueKey<String>('pair-problem:${problem.title}'),
                attributes: const <String, String>{
                  'data-testid': 'pair-problem',
                  'role': 'alert',
                },
                <Widget>[
                  stack(spacing: 4, <Widget>[
                    Text(
                      problem.title,
                      size: FontSize.sm,
                      weight: FontWeight.w500,
                      color: TextColor.error,
                    ),
                    mutedText(problem.next),
                  ]),
                ],
              ),
            if (spent || payload == null)
              PairLinkForm(
                key: ValueKey<String>('new-link:$_code'),
                id: 'pair-new-link',
                label: huiText('New link'),
                onCode: _newLink,
                cancel: RowAction(huiText('Cancel'), component.onCancel),
              )
            else ...<Widget>[
              _abilities(payload),
              Field(
                label: huiText('Browser name'),
                forId: _nameId,
                tip: huiText(
                  'The name /gloss editor tokens shows for this browser.',
                ),
                child: TextInput(
                  id: _nameId,
                  value: _client,
                  fullWidth: true,
                  onChanged: (String value) => setState(() => _client = value),
                ),
              ),
              SwitchField(
                id: _switchId,
                label: huiText('Remember this browser'),
                tip: huiText(
                  'Turn this off on a shared computer; the pairing then ends when this tab closes.',
                ),
                value: _remember,
                onChanged: (bool value) => setState(() => _remember = value),
              ),
              _details(payload),
              FormFooter(
                cancel: RowAction(huiText('Cancel'), component.onCancel),
                primary: RowAction(
                  huiText('Pair'),
                  _busy || _limit != null ? null : _pair,
                  loading: _busy,
                  attributes: const <String, String>{
                    'data-testid': 'pair-submit',
                  },
                ),
              ),
            ],
          ]),
        ],
      ),
    ]);
  }

  /// What the editor may do, one line each.
  Widget _abilities(PairingPayload payload) {
    final List<String> lines = scopeLines(
      payload.scopes,
      folders: payload.folders,
    );
    return stack(spacing: 8, <Widget>[
      sectionHeading(
        huiText('The editor can'),
        tip: huiText(
          'What this link allows. Run /gloss editor pair with scopes= or folders= for less.',
        ),
      ),
      lineList(
        lines,
        attributes: const <String, String>{'data-testid': 'pair-scopes'},
      ),
    ]);
  }

  /// The fingerprint, the ways in and the link name, folded, in either mode:
  /// they are what a person checks before pressing Pair.
  Widget _details(PairingPayload payload) => AppSection(
    title: huiText('Details'),
    open: false,
    children: <Widget>[
      Field(
        label: huiText('Fingerprint'),
        tip: huiText('Must match what /gloss editor status prints.'),
        child: codeText(
          payload.fingerprint,
          attributes: const <String, String>{'data-testid': 'pair-fingerprint'},
        ),
      ),
      if (payload.directUrl != null)
        Field(
          label: huiText('Direct'),
          tip: huiText('The server\'s own address.'),
          child: codeText(payload.directUrl!),
        ),
      if (payload.relayUrl != null)
        Field(
          label: huiText('Relay'),
          tip: huiText('Used when the direct address does not answer.'),
          child: codeText(payload.relayUrl!),
        ),
      if (payload.label != null)
        Field(
          label: huiText('Link name'),
          tip: huiText('The label= the link was made with.'),
          child: codeText(payload.label!),
        ),
    ],
  );
}
