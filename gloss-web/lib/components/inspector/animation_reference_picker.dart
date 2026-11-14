/// Cross-kind reference picker: the workspace's animation documents, offered
/// as `|animation.<id>|` insertions.
///
/// The list is the same resolver the surfaces render through
/// ([EditorStore.workspaceAnimations]), so a document listed here is by
/// definition one whose reference will play rather than warn.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gloss_text.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/menu.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';

class AnimationReferencePicker extends StatelessWidget {
  const AnimationReferencePicker({
    required this.store,
    required this.onPicked,
    this.label,
    super.key,
  });

  final EditorStore store;

  /// Receives the raw reference token, e.g. `|animation.rainbow|`.
  final void Function(String reference) onPicked;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final GlossAnimationResolver animations = store.workspaceAnimations;
    final List<String> ids = animations.ids;
    // It sits on a section's title line beside Add, so its note is a line in
    // the menu rather than a second (i) there.
    return AppMenu(
      icon: ArcaneIcon.film(),
      iconLabel: label ?? huiText('Animations'),
      alignEnd: true,
      entries: <AppMenuEntry>[
        AppMenuEntry.heading(huiText('Animations')),
        AppMenuEntry(
          huiText(
            'A reference plays the animation\'s current frame wherever the '
            'line renders. Deleting or renaming the animation later leaves '
            'the reference showing literally.',
          ),
          null,
        ),
        if (ids.isEmpty)
          AppMenuEntry(
            huiText(
              'This workspace has no animation documents yet. Create one '
              'from the library rail; its |animation.<id>| reference will '
              'appear here.',
            ),
            null,
          )
        else
          for (final String id in ids)
            AppMenuEntry(
              huiText("|animation.{id}|", <String, Object?>{'id': id}),
              () => onPicked('|animation.$id|'),
              description: _summary(animations.byId(id)),
            ),
      ],
    );
  }

  String _summary(GlossAnimationDoc? doc) => doc == null
      ? huiText('unreadable document')
      : huiPlural(
          'animation.reference.frame_summary',
          doc.frames.length,
          oneEnglish: '{count} frame · {interval} ms · {mode}',
          otherEnglish: '{count} frames · {interval} ms · {mode}',
          arguments: <String, Object?>{
            'interval': doc.effectiveFrameIntervalMs,
            'mode': doc.normalizedMode ?? doc.mode,
          },
        );
}
