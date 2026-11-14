/// The centre form of a catalog kind.
///
/// Names, strings, waypoints, glyphs, behaviors and presets have no stage
/// and no inspector pane (`kindHasInspector` in `config/kind_groups.dart`):
/// their editable form fills the centre cell. The form scrolls as a page; the
/// header names the document and carries its revision in Advanced.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show ListenableBuilder;

import '../../l10n/hui_localizations.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field_group.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/pane_header.dart';

/// Rebuilds [builder] whenever [store] changes; the centre cell mounts the
/// view once and leaves listening to it.
class CatalogFormHost extends StatelessWidget {
  const CatalogFormHost({required this.store, required this.builder, super.key});

  final EditorStore store;
  final Widget Function(BuildContext context) builder;

  @override
  Widget build(BuildContext context) =>
      ListenableBuilder(listenable: store, builder: builder);
}

class CatalogForm extends StatelessWidget {
  const CatalogForm({
    required this.kind,
    required this.title,
    required this.children,
    this.revision,
    this.tip,
    this.docKey,
    this.actions = const <AppMenuEntry>[],
    this.kindClass,
    super.key,
  });

  /// What the document is ("Names", "Glyphs").
  final String kind;

  /// The document id.
  final String title;
  final List<Widget> children;
  final int? revision;
  final String? tip;
  final String? docKey;
  final List<AppMenuEntry> actions;

  /// An `is-<kind>` modifier for the form root.
  final String? kindClass;

  @override
  Widget build(BuildContext context) => DenseBox(
    classes: 'hui-catalog-form${kindClass == null ? '' : ' $kindClass'}',
    attributes: const <String, String>{'data-catalog-form': ''},
    children: <Widget>[
      FieldStack(
        children: <Widget>[
          PaneHeader(
            kind: kind,
            title: title,
            tip: tip,
            docKey: docKey,
            menuId: 'hui-catalog-menu',
            actions: actions,
            status: revision == null
                ? null
                : AdvancedOnly(
                    child: StatusBadge(
                      huiText('revision {revision}', <String, Object?>{
                        'revision': revision,
                      }),
                    ),
                  ),
          ),
          ...children,
        ],
      ),
    ],
  );
}
