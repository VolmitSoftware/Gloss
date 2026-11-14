/// Help: one row per topic with its few lines behind the (i), the key for
/// the shortcuts list, and the documentation links. The details live in the
/// documentation and in the (i) tips beside each field.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/html.dart' show ArcaneLink;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/links.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/info_tip.dart';
import '../shell/pane_dom.dart' show coarsePointerQuery, mediaMatches;
import '../shell/key_listener.dart' show isApplePlatform;
import '../shell/shell_keys.dart' show shortcutKeys;

import 'package:gloss_editor/l10n/hui_localizations.dart';

class HelpDialog extends StatelessWidget {
  const HelpDialog({
    required this.store,
    required this.isOpen,
    required this.onClose,
    super.key,
  });

  final EditorStore store;
  final bool isOpen;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-help-dialog',
    open: isOpen,
    onClose: onClose,
    title: huiText('Gloss editor help'),
    footer: DialogFooter(cancel: RowAction(huiText('Close'), onClose)),
    children: isOpen ? _topics() : const <Widget>[],
  );

  /// One row per topic, its few lines behind the (i); then the shortcuts
  /// key on a keyboard device, then the documentation links.
  List<Widget> _topics() => <Widget>[
    dom.div(
      attributes: const <String, String>{'role': 'list'},
      <Widget>[
        Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          spacing: 12,
          children: <Widget>[
            _topic(huiText('Quick start'), <String>[
              huiText(
                'Make a document with New in the library, or File > New document.',
              ),
              huiText(
                'Drag on the canvas or type exact values in the inspector.',
              ),
              huiText(
                'Simple mode hides expert kinds and fields. Advanced shows all.',
              ),
            ]),
            _topic(huiText('Text formatting'), <String>[
              '&a &l &r: ${huiText('Legacy colour and style codes')}.',
              '<red> <b>: ${huiText('MiniMessage colours and styles')}.',
              '%player_name%: ${huiText('Placeholders, filled in on the server')}.',
            ]),
            _topic(huiText('Server'), <String>[
              huiText(
                'Run /gloss editor pair on the server and open the link it gives.',
              ),
              huiText(
                'Autosave stays in this browser. Publish sends your changes to the server.',
              ),
              huiText(
                'No link? Export the file and copy it into {folder}.',
                <String, Object?>{'folder': huiPluginFolder},
              ),
            ]),
            if (!mediaMatches(coarsePointerQuery))
              _row(
                Component.text(huiText('Keyboard shortcuts')),
                ArcaneKbd.combo(
                  shortcutKeys('?', apple: isApplePlatform()),
                  size: ComponentSize.sm,
                ),
              ),
          ],
        ),
      ],
    ),
    // Foreground links: the accent stays on the primary action.
    dom.p(<Widget>[
      for (int index = 0; index < huiLinks.length; index++) ...<Widget>[
        if (index > 0) const Component.text(' · '),
        ArcaneLink.external(
          href: huiLinks[index].url,
          styles: const ArcaneStyleData(
            textColor: TextColor.primary,
            textDecoration: TextDecoration.underline,
          ),
          child: Component.text(huiText(huiLinks[index].label)),
        ),
      ],
    ]),
  ];

  /// A topic's title with its sentences behind the (i).
  Widget _topic(String title, List<String> lines) =>
      _row(TipLabel(label: Component.text(title), tip: lines.join(' ')), null);

  Widget _row(Widget label, Widget? trailing) => dom.div(
    attributes: const <String, String>{'role': 'listitem'},
    <Widget>[
      Row(
        spacing: 8,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: <Widget>[label, ?trailing],
      ),
    ],
  );
}
