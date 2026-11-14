/// The one tab row, on shadcn's tab bar. The wrapper adds what the library
/// leaves out: the tablist's name, Left and Right (Home and End) moving and
/// selecting, and tabs that cannot be picked. The row scrolls sideways on a
/// narrow screen instead of wrapping.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

int _nextTabsId = 0;

class AppTabs extends StatefulWidget {
  const AppTabs({
    required this.label,
    required this.tabs,
    required this.selected,
    required this.onSelect,
    this.disabled = const <int>{},
    this.id,
    super.key,
  });

  /// The tablist's accessible name.
  final String label;
  final List<String> tabs;
  final int selected;
  final void Function(int index) onSelect;

  /// Tabs shown but not pickable.
  final Set<int> disabled;
  final String? id;

  @override
  State<AppTabs> createState() => _AppTabsState();
}

class _AppTabsState extends State<AppTabs> {
  late final String _id = component.id ?? 'ui-tabs-${_nextTabsId++}';

  /// The library's bar keeps showing the tab it last drew selected, so a
  /// selection that did not come from a click on it (code, the arrow keys,
  /// a tab enabled meanwhile) draws a new bar.
  int _bar = 0;
  int? _clicked;

  @override
  void didUpdateWidget(AppTabs oldComponent) {
    super.didUpdateWidget(oldComponent);
    final bool moved =
        component.selected != oldComponent.selected &&
        component.selected != _clicked;
    final bool enabled =
        component.disabled.length != oldComponent.disabled.length ||
        !component.disabled.containsAll(oldComponent.disabled);
    if (moved || enabled) _bar++;
    _clicked = null;
  }

  List<String> get tabs => component.tabs;
  Set<int> get disabled => component.disabled;
  int get selected => component.selected;

  /// Names the library's tablist, marks tabs that cannot be picked and keeps
  /// one Tab stop (the selected tab). Set after each render.
  void _decorate() {
    final web.Element? row = web.document.getElementById(_id);
    final web.Element? list = row?.querySelector('[role="tablist"]');
    if (list == null) return;
    list.setAttribute('aria-label', component.label);
    final web.NodeList found = list.querySelectorAll('[role="tab"]');
    for (int index = 0; index < found.length; index++) {
      final web.Element tab = found.item(index)! as web.Element;
      tab.setAttribute('tabindex', index == selected ? '0' : '-1');
      if (disabled.contains(index)) {
        tab.setAttribute('aria-disabled', 'true');
      } else {
        tab.removeAttribute('aria-disabled');
      }
    }
  }

  void _pick(int index) {
    if (index < 0 || index >= tabs.length || disabled.contains(index)) return;
    component.onSelect(index);
  }

  void _keys(web.Event raw) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final web.EventTarget? target = event.target;
    if (target == null ||
        !target.isA<web.Element>() ||
        (target as web.Element).getAttribute('role') != 'tab') {
      return;
    }
    final List<int> open = <int>[
      for (int index = 0; index < tabs.length; index++)
        if (!disabled.contains(index)) index,
    ];
    if (open.isEmpty) return;
    final int at = open.indexOf(selected);
    final int? next = switch (event.key) {
      'ArrowRight' => open[(at + 1) % open.length],
      'ArrowLeft' => open[(at - 1 + open.length) % open.length],
      'Home' => open.first,
      'End' => open.last,
      _ => null,
    };
    if (next == null) return;
    event.preventDefault();
    _pick(next);
    final web.Element? row = target.closest('.ui-tabs');
    web.window.setTimeout(
      (() {
        final web.NodeList? found = row?.querySelectorAll('[role="tab"]');
        if (found != null && next < found.length) {
          (found.item(next)! as web.HTMLElement).focus();
        }
      }).toJS,
      0.toJS,
    );
  }

  @override
  Widget build(BuildContext context) {
    web.window.requestAnimationFrame(((JSNumber _) => _decorate()).toJS);
    return dom.div(
      id: _id,
      classes: 'ui-tabs',
      events: <String, void Function(web.Event)>{'keydown': _keys},
      <Widget>[
        ArcaneTabBar(
          key: ValueKey<int>(_bar),
          tabs: <ArcaneTabBarItem>[
            for (final String tab in tabs) ArcaneTabBarItem(label: tab),
          ],
          selectedIndex: selected,
          onChanged: (int index) {
            _clicked = index;
            _pick(index);
          },
        ),
      ],
    );
  }
}
