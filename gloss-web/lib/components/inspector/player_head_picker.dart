/// `playerHead` icon editor: whose head, and how often the name is re-read.
///
/// The editor cannot resolve a skin (that is a Mojang lookup the server makes
/// and caches, `PlayerHeadService.java:102-122`), so the canvas draws the
/// generic head for every name; the type tip in the icon editor says so.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/number_field.dart';
import '../../ui/technical_input.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PlayerHeadIconEditor extends StatelessWidget {
  const PlayerHeadIconEditor({
    required this.icon,
    required this.onChanged,
    this.issues = const <HuiIssue>[],
    this.idPrefix = 'hui-icon-head',
    super.key,
  });

  final HuiPlayerHeadIcon icon;

  /// Called with a mutation label and the edited copy.
  final void Function(String label, HuiPlayerHeadIcon icon) onChanged;
  final List<HuiIssue> issues;

  /// Unique per icon slot: a toggle shows two of these at once.
  final String idPrefix;

  List<HuiIssue> _issuesFor(String suffix) =>
      issues.where((HuiIssue issue) => issue.path.endsWith(suffix)).toList();

  HuiPlayerHeadIcon _with({String? player, int? refreshTicks}) =>
      HuiPlayerHeadIcon(
        player ?? icon.player,
        icon.style?.copy(),
        refreshTicks ?? icon.refreshTicks,
      )..extras = huiDeepCopyMap(icon.extras);

  int get _refreshTicks =>
      icon.refreshTicks ?? huiRuntimeDefaultPlayerHeadRefreshTicks;

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    Field(
      label: huiText('Player'),
      required: true,
      forId: '$idPrefix-player',
      docKey: 'icon.playerHead.player',
      problems: _issuesFor('.player'),
      child: TextInput(
        id: '$idPrefix-player',
        value: icon.player,
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        placeholder: huiDefaultPlayerHeadSource,
        onChanged: (String value) =>
            onChanged('player head name', _with(player: value)),
        attributes: technicalInputAttributes,
        styles: technicalInputStyles,
      ),
    ),
    Field(
      label: huiText('Refresh'),
      forId: '$idPrefix-refresh',
      tip: huiText(
        'The first render of a name is always the blank unowned head: the '
        'lookup is asynchronous and never blocks the server. A name that '
        'does not exist, or head resolution switched off in config.toml, '
        'draws the configured fallback block - a skeleton skull unless '
        'the operator changed it.',
      ),
      problems: _issuesFor('.refreshTicks'),
      defaultValue: huiPlural(
        'duration.tick_count',
        huiRuntimeDefaultPlayerHeadRefreshTicks,
        oneEnglish: '{count} tick',
        otherEnglish: '{count} ticks',
      ),
      onReset: _refreshTicks == huiRuntimeDefaultPlayerHeadRefreshTicks
          ? null
          : () => onChanged(
              'player head refresh',
              _with(refreshTicks: huiRuntimeDefaultPlayerHeadRefreshTicks),
            ),
      child: NumberField(
        id: '$idPrefix-refresh',
        value: _refreshTicks.toDouble(),
        min: 0,
        max: 1200,
        step: 1,
        integer: true,
        suffix: huiText('ticks'),
        onChanged: (double value) => onChanged(
          'player head refresh',
          _with(refreshTicks: value.round()),
        ),
      ),
    ),
  ]);
}
