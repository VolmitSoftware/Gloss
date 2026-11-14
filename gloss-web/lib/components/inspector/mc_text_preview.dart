/// Live in-game text preview.
///
/// Rendered as styled DOM spans rather than on a canvas so it stays selectable,
/// scales with the browser and needs no paint loop. The parse comes from
/// `logic/mc_text.dart`, which mirrors the plugin's `TextUtils.parse` exactly,
/// including the two legacy codes that turn into literal text in game, behind
/// `glossRenderMenuText`, the emoji and bracket-hex prelude `TextMenuIcon`
/// runs before it.
///
/// It returns the drawn text, the metrics line and the parser warnings as
/// siblings, so the field it sits in spaces them; the drawn text is the only
/// surface and is marked as game-drawn.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gloss_text.dart'
    show GlossEmojiResolver, GlossNoEmoji, glossRenderMenuText;
import '../../logic/mc_text.dart';
import '../gloss/gloss_mc_span.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class McTextPreview extends StatelessWidget {
  const McTextPreview({
    required this.raw,
    this.emoji = const GlossNoEmoji(),
    super.key,
  });

  final String raw;

  /// Emoji available to the menu text stage: the shipped catalog with the
  /// workspace's emoji documents layered over it.
  final GlossEmojiResolver emoji;

  @override
  Widget build(BuildContext context) {
    final McTextResult result = parseMcText(
      glossRenderMenuText(raw, emoji: emoji),
    );
    final String lineMetric = huiPlural(
      'text_preview.line_count',
      result.lineCount,
      oneEnglish: '{count} line',
      otherEnglish: '{count} lines',
    );
    final String characterMetric = huiPlural(
      'text_preview.character_count',
      result.maxLineLength,
      oneEnglish: '{count} character',
      otherEnglish: '{count} characters',
    );
    return Component.fragment(<Widget>[
      dom.div(
        classes: 'hui-mc-preview-surface',
        attributes: const <String, String>{'data-game-drawn': ''},
        <Widget>[
          if (result.lines.isEmpty)
            dom.div(classes: 'hui-mc-line is-empty', <Widget>[
              Text(huiText('(empty)')),
            ])
          else
            for (final List<McSpan> line in result.lines) _line(line),
        ],
      ),
      ArcaneFieldDescription(
        huiText('{lines} · longest {characters}', <String, Object?>{
          'lines': lineMetric,
          'characters': characterMetric,
        }),
      ),
      for (final String warning in result.warnings)
        ArcaneFieldDescription(
          warning,
          classes: const <String>['ui-field-problem', 'is-warning'],
        ),
    ]);
  }

  Widget _line(List<McSpan> spans) => dom.div(classes: 'hui-mc-line', <Widget>[
    if (spans.isEmpty)
      const dom.span(classes: 'hui-mc-span is-blank', <Widget>[Text(' ')])
    else
      for (final McSpan span in spans) GlossMcSpan(span: span),
  ]);
}
