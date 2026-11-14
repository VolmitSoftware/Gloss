import 'package:arcane_jaspr/arcane_jaspr.dart'
    show ArcaneGlyph, ArcaneIcon, IconSize;

import '../l10n/hui_localizations.dart';
import '../logic/validation.dart';
import '../model/model.dart';
import '../state/workspace.dart';
import '../state/editor_store.dart';
import 'document_type.dart';
import 'gloss_document_type.dart';

/// Leaderboards: a ranked list the plugin computes. No stage to draw, so the
/// form edits in the centre.
final class LeaderboardDocumentType extends GlossDocumentTypeAdapter {
  const LeaderboardDocumentType();
  @override
  WorkspaceDocKind get kind => WorkspaceDocKind.leaderboard;
  @override
  String get noun => huiText('Leaderboard');
  @override
  String get pluralLabel => huiText('Leaderboards');
  @override
  int get tabOrder => 198;
  @override
  DocumentSurface get surface => DocumentSurface.leaderboard;
  @override
  String get surfaceLabel => huiText('Leaderboard');
  @override
  bool get hasRuntimePreview => false;
  @override
  String get syncWireKind => 'leaderboard';
  @override
  String get defaultDocumentName => 'new-leaderboard';
  @override
  ArcaneGlyph railIcon() => ArcaneIcon.trophy(size: IconSize.sm);
  @override
  GlossDoc decodeDoc(String json) => decodeGlossLeaderboardDoc(json);
  @override
  String encodeDoc(GlossDoc doc) =>
      encodeGlossLeaderboardDoc(doc as GlossLeaderboardDoc);
  @override
  GlossDoc newBlank() => GlossLeaderboardDoc();
  @override
  bool looksLike(Object? decoded) => looksLikeLeaderboardDoc(decoded);
  @override
  String get codeShapeError =>
      'A leaderboard document needs schemaVersion and a source block.';
  @override
  List<HuiIssue> validate(DocumentStateView state) {
    final GlossDoc? doc = state.glossDoc;
    if (doc is! GlossLeaderboardDoc) return const <HuiIssue>[];
    return <HuiIssue>[
      ?glossRevisionIssue(doc.revision),
      if (!glossLeaderboardSourceTypes.contains(doc.sourceType.toLowerCase()))
        const HuiIssue(
          severity: HuiSeverity.error,
          path: r'$.source.type',
          message: 'The source type must be statistic, papi or metric.',
        ),
      if (doc.sourceValue.trim().isEmpty)
        const HuiIssue(
          severity: HuiSeverity.error,
          path: r'$.source',
          message: 'Name what the leaderboard counts.',
        ),
    ];
  }

  @override
  String get templatesTabLabel => huiText('Leaderboards');
  @override
  List<DocumentTemplateSection> get templateSections =>
      <DocumentTemplateSection>[
        DocumentTemplateSection(
          templates: <DocumentTemplate>[
            DocumentTemplate(
              id: 'leaderboard-default',
              name: huiText('Leaderboard'),
              description: huiText('Plugin default'),
              highlights: const <String>[],
              create: (EditorStore store) => store.newGlossDocument(this),
            ),
          ],
        ),
      ];
}
