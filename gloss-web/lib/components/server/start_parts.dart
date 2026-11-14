/// Plain parts the start page, the pairing card and the Server sheet share,
/// on the library's own type and layout: the page column, a title, a
/// section heading, a muted line and a list of lines without bullets.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/html.dart' show ArcaneHeading, HeadingLevel;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../ui/info_tip.dart';

/// The id of the page's one `h1`, which names the page's `main`.
const String mainHeadingId = 'gloss-page-title';

/// The pages outside a workspace (start, pairing card): one centred column,
/// 16 px from the edges on a phone.
Widget startColumn(List<Widget> children, {String? label}) => dom.main_(
  id: 'gloss-start',
  attributes: const <String, String>{'aria-labelledby': mainHeadingId},
  styles: const dom.Styles(
    raw: <String, String>{
      'box-sizing': 'border-box',
      'width': 'min(44rem, 100%)',
      'margin': '0 auto',
      'padding': 'clamp(1rem, 4vw, 2.5rem) 1rem 3rem',
      'display': 'grid',
      'grid-template-columns': 'minmax(0, 1fr)',
      'gap': '1.5rem',
      'min-height': '100dvh',
      'align-content': 'start',
    },
  ),
  children,
);

/// [children] one under another, each as wide as the stack, [spacing] px
/// apart.
Widget stack(List<Widget> children, {double spacing = 8, Key? key}) => Column(
  key: key,
  crossAxisAlignment: CrossAxisAlignment.stretch,
  mainAxisSize: MainAxisSize.min,
  spacing: spacing,
  children: children,
);

/// A count, a hint or the line under a heading; [small] for a detail.
Widget mutedText(String text, {bool small = false}) =>
    Text(text, size: small ? FontSize.xs : FontSize.sm, color: TextColor.muted);

/// The editor's logo and name over a page that is not the start page, so a
/// link opened cold says where it landed.
Widget brandLine() => Row(
  spacing: 8,
  children: <Widget>[
    const dom.img(src: 'assets/brand/logo.png', alt: '', width: 24, height: 24),
    Text(huiText('Gloss web editor'), size: FontSize.sm, weight: FontWeight.w500),
  ],
);

/// The page's one heading.
Widget pageTitle(String text) => ArcaneHeading(
  level: HeadingLevel.h1,
  id: mainHeadingId,
  styles: const ArcaneStyleData(
    fontSize: FontSize.xl2,
    fontWeight: FontWeight.w600,
  ),
  child: Component.text(text),
);

/// A section's heading, with its (i) tip when given.
Widget sectionHeading(String text, {String? id, String? tip}) => ArcaneHeading(
  level: HeadingLevel.h2,
  id: id,
  styles: const ArcaneStyleData(
    fontSize: FontSize.base,
    fontWeight: FontWeight.w600,
  ),
  child: tip == null
      ? Component.text(text)
      : TipLabel(label: Component.text(text), tip: tip),
);

/// A fingerprint, address or token id as code, broken anywhere so a long
/// one wraps inside a narrow card or sheet instead of running past it.
Widget codeText(
  String text, {
  Map<String, String> attributes = const <String, String>{},
}) => dom.code(attributes: attributes, <Widget>[
  Text(text, cssStyle: const ArcaneStyleData(wordBreak: WordBreak.breakAll)),
]);

/// Lines of text as a list without bullets.
Widget lineList(
  List<String> lines, {
  Map<String, String> attributes = const <String, String>{},
}) => dom.div(
  attributes: <String, String>{'role': 'list', ...attributes},
  <Widget>[
    stack(spacing: 4, <Widget>[
      for (final String line in lines)
        dom.div(
          attributes: const <String, String>{'role': 'listitem'},
          <Widget>[Text(line, size: FontSize.sm)],
        ),
    ]),
  ],
);
