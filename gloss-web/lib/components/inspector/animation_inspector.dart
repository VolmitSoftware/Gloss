/// Inspector body for a Gloss animation document: mode, interval (with the
/// plugin's silent clamp surfaced), and the reorderable frame list with
/// per-frame pipeline previews.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/badge.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/toggle_choice.dart';
import '../gloss/gloss_text_line.dart';
import 'gloss_visibility_editor.dart';
import 'line_list_section.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class AnimationInspector extends StatelessWidget {
  const AnimationInspector({required this.store, super.key});

  final EditorStore store;

  GlossAnimationDoc? get _doc => store.animationDoc;

  List<HuiIssue> _issuesFor(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) =>
      DensityScope.dense(child: Builder(builder: _build));

  Widget _build(BuildContext context) {
    final GlossAnimationDoc? doc = _doc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-animation',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Animation'),
          title: store.menuId,
          tip: huiText(
            "Other documents play this through |animation.{menuId}|.",
            <String, Object?>{'menuId': store.menuId},
          ),
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        GlossVisibilityEditor(
          raw: doc.extras['show'],
          sectionKey: 'animation.visibility',
          issues: store.issues
              .where((HuiIssue issue) => issue.path == r'$.show')
              .toList(),
          onChanged: (Object? value) => store.mutateAnimation(
            'visibility',
            (GlossAnimationDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        _playback(doc),
        _frames(doc),
      ],
    );
  }

  Widget _playback(GlossAnimationDoc doc) => AppSection(
    title: huiText('Playback'),
    memoryKey: 'animation.playback',
    children: <Widget>[
      Field(
        label: huiText('Mode'),
        required: true,
        docKey: 'animation.mode',
        problems: _issuesFor(r'$.mode'),
        child: ToggleChoice<String>(
          label: huiText('Mode'),
          value: doc.normalizedMode ?? doc.mode,
          options: <ChoiceOption<String>>[
            ChoiceOption<String>('ascend', huiText('Ascend')),
            ChoiceOption<String>('descend', huiText('Descend')),
            ChoiceOption<String>(
              'ascend_descend',
              huiText('Ping-pong'),
              tip: huiText('ascend_descend: up the list, then back down'),
            ),
            ChoiceOption<String>('random', huiText('Random')),
          ],
          onChanged: (String value) => store.mutateAnimation(
            'animation mode',
            (GlossAnimationDoc edited) => edited.mode = value,
          ),
        ),
      ),
      Field(
        label: huiText('Frame interval'),
        forId: 'animation-interval',
        required: true,
        docKey: 'animation.frameIntervalMs',
        problems: _issuesFor(r'$.frameIntervalMs'),
        child: DurationField(
          id: 'animation-interval',
          value: doc.frameIntervalMs.toDouble(),
          unit: HuiDurationUnit.milliseconds,
          step: 50,
          perLabel: huiText('per frame'),
          onChanged: (double value) => store.mutateAnimation(
            'animation interval',
            (GlossAnimationDoc edited) =>
                edited.frameIntervalMs = value.round(),
          ),
        ),
      ),
    ],
  );

  Widget _frames(GlossAnimationDoc doc) => LineListSection(
    title: huiText('Frames'),
    docKey: 'animation.frames',
    sectionKey: 'animation.frames',
    addLabel: huiText('Add frame'),
    itemCount: doc.frames.length,
    issues: _issuesFor(r'$.frames'),
    emptyBody: huiText(
      'Gloss rejects an animation with no frames, so nothing that '
      'references this document renders either. One frame is legal and '
      'simply holds.',
    ),
    onAdd: () => store.mutateAnimation(
      'add frame',
      (GlossAnimationDoc edited) => edited.frames.add(''),
    ),
    onReorder: (int from, int to) =>
        store.mutateAnimation('reorder frame', (GlossAnimationDoc edited) {
          final String moved = edited.frames.removeAt(from);
          edited.frames.insert(to, moved);
        }),
    itemBuilder: (int index) => _frameRow(doc, index),
  );

  Widget _frameRow(GlossAnimationDoc doc, int index) {
    final String frame = doc.frames[index];
    return LineRow(
      value: frame,
      placeholder: huiText('&cFrame text'),
      label: huiText('Frame {number}', <String, Object?>{'number': index + 1}),
      removeLabel: huiText('Delete frame {number}', <String, Object?>{
        'number': index + 1,
      }),
      preview: GlossTextLine(
        render: renderGlossLine(frame, emoji: store.workspaceEmoji),
      ),
      onChanged: (String value) =>
          store.mutateAnimation('edit frame', (GlossAnimationDoc edited) {
            if (index < edited.frames.length) edited.frames[index] = value;
          }),
      onRemove: () =>
          store.mutateAnimation('delete frame', (GlossAnimationDoc edited) {
            if (index < edited.frames.length) edited.frames.removeAt(index);
          }),
    );
  }
}
