/// The one disclosure: a titled group of fields that opens and closes, on
/// shadcn's disclosure (native `details`) in its borderless variant: a
/// heading over fields on the pane it sits on, never a box of its own. The disclosure is controlled: the open state is owned here, so a
/// rebuild (an edit, a sync event) keeps it as the reader left it; with a
/// [AppSection.memoryKey] it is also kept across documents for this page
/// visit. Opens made outside it (find in page, `details.open = true`) come
/// back through `onOpenChange`. Header actions sit on the title line,
/// outside the summary, so they never toggle it.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import 'density.dart';
import 'info_tip.dart';

/// Open states kept by [AppSection.memoryKey] for this page visit.
final Map<String, bool> _remembered = <String, bool>{};

/// Whether the section kept under [key] is open, else [fallback].
bool rememberedOpen(String key, {required bool fallback}) =>
    _remembered[key] ?? fallback;

int _nextSectionId = 0;

class AppSection extends StatefulWidget {
  const AppSection({
    required this.children,
    this.title,
    this.summary,
    this.open = true,
    this.count,
    this.tip,
    this.docKey,
    this.description,
    this.actions = const <Widget>[],
    this.memoryKey,
    this.classes,
    this.attributes = const <String, String>{},
    this.onKey,
    super.key,
  }) : assert(
         title != null || summary != null,
         'A section needs a title or a summary.',
       );

  /// The heading text; [summary] replaces it with widgets.
  final String? title;
  final List<Widget>? summary;
  final List<Widget> children;

  /// Whether it starts open. A later change of this value is followed; the
  /// reader's own open and close is kept otherwise.
  final bool open;

  /// A number after the title (items inside).
  final int? count;

  /// Tip text on the title, or a field-doc key for it.
  final String? tip;
  final String? docKey;

  /// One short muted line at the top of the body. Prefer [tip].
  final String? description;

  /// Dense buttons on the title line (an Add icon button, an [AppMenu]).
  final List<Widget> actions;

  /// Keeps the open state under this key across rebuilds that recreate the
  /// section (another document of the same kind).
  final String? memoryKey;

  final String? classes;

  /// Extra attributes on the wrapper, such as a `data-testid`.
  final Map<String, String> attributes;

  /// A key pressed on this section's own summary; [show] opens or closes it.
  final void Function(web.KeyboardEvent event, void Function(bool open) show)?
  onKey;

  @override
  State<AppSection> createState() => _AppSectionState();
}

class _AppSectionState extends State<AppSection> {
  final String _id = 'ui-section-${_nextSectionId++}';
  late bool _open = component.memoryKey == null
      ? component.open
      : rememberedOpen(component.memoryKey!, fallback: component.open);

  @override
  void didUpdateWidget(AppSection oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.open != component.open) _open = component.open;
    if (oldComponent.memoryKey != component.memoryKey &&
        component.memoryKey != null) {
      _open = rememberedOpen(component.memoryKey!, fallback: component.open);
    }
  }

  void _set(bool open) {
    final String? key = component.memoryKey;
    if (key != null) _remembered[key] = open;
    if (mounted && open != _open) setState(() => _open = open);
  }

  /// A key pressed on this section's own summary, not a nested section's.
  void _key(web.Event event) {
    final web.EventTarget? target = event.target;
    if (!event.isA<web.KeyboardEvent>() ||
        target == null ||
        !target.isA<web.Element>()) {
      return;
    }
    final web.Element element = target as web.Element;
    if (element.tagName.toLowerCase() != 'summary' ||
        element.closest('.ui-section')?.id != _id) {
      return;
    }
    component.onKey!(event as web.KeyboardEvent, _set);
  }

  @override
  Widget build(BuildContext context) {
    final AppSection section = component;
    final Widget heading = section.summary != null
        ? dom.span(classes: 'ui-section-summary', section.summary!)
        : dom.span(classes: 'ui-section-title', <Widget>[
            TipLabel(
              label: Component.text(section.title!),
              tip: section.tip,
              docKey: section.docKey,
            ),
          ]);
    final bool hasActions = section.actions.isNotEmpty;
    return dom.div(
      id: _id,
      classes:
          'ui-section${hasActions ? ' has-actions' : ''}'
          '${section.classes == null ? '' : ' ${section.classes}'}',
      attributes: section.attributes,
      events: section.onKey == null
          ? null
          : <String, void Function(web.Event)>{'keydown': _key},
      <Widget>[
        dom.div(classes: 'ui-section-box', <Widget>[
          ArcaneDisclosure(
            open: _open,
            onOpenChange: _set,
            variant: DisclosureVariant.minimal,
            showTreeLines: false,
            summary: dom.span(classes: 'ui-section-head', <Widget>[
              heading,
              if (section.count != null)
                dom.span(classes: 'ui-section-count', <Widget>[
                  Component.text('${section.count}'),
                ]),
            ]),
            child: dom.div(classes: 'ui-section-body', <Widget>[
              if (section.description != null)
                dom.p(classes: 'ui-section-description', <Widget>[
                  Component.text(section.description!),
                ]),
              ...section.children,
            ]),
          ),
        ]),
        if (hasActions)
          DensityScope.dense(
            child: dom.div(classes: 'ui-section-actions', section.actions),
          ),
      ],
    );
  }
}
