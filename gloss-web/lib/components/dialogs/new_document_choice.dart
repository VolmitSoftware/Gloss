/// What the New dialog offers and how a pick becomes a document, kept free
/// of the DOM so it runs in VM tests. A document starts blank, from one of
/// its kind's templates, or randomized from a seed.
library;

import 'dart:math' as math;

import '../../config/kind_groups.dart';
import '../../doctype/doctype.dart';
import '../../services/showcase_randomizer.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';

enum NewDocumentStart { blank, template, random }

/// One pick in the New dialog: a blank [type], a [template] of it, or a
/// [seed] to randomize it from.
class NewDocumentChoice {
  const NewDocumentChoice(this.type, {this.template, this.seed});

  final DocumentTypeAdapter type;
  final DocumentTemplate? template;
  final int? seed;
}

/// The kinds of one group the dialog lists for a start option.
class NewDocumentGroup {
  const NewDocumentGroup(this.group, this.types);

  final KindGroup group;
  final List<DocumentTypeAdapter> types;
}

/// Every template [type] ships, in its sections' order.
List<DocumentTemplate> templatesOf(DocumentTypeAdapter type) =>
    <DocumentTemplate>[
      for (final DocumentTemplateSection section in type.templateSections)
        ...section.templates,
    ];

bool _offers(DocumentTypeAdapter type, NewDocumentStart start) =>
    switch (start) {
      NewDocumentStart.blank => true,
      NewDocumentStart.template => templatesOf(type).isNotEmpty,
      NewDocumentStart.random => canRandomizeShowcase(type),
    };

/// The kinds offered for [start], under their groups in display order.
/// Simple ([advanced] false) leaves out the Advanced kinds; a group with
/// nothing to offer is left out.
List<NewDocumentGroup> newDocumentGroups(
  NewDocumentStart start, {
  required bool advanced,
}) {
  final List<NewDocumentGroup> groups = <NewDocumentGroup>[];
  for (final KindGroup group in groupsFor(advanced: advanced)) {
    final List<DocumentTypeAdapter> types = <DocumentTypeAdapter>[
      for (final KindInfo info in kindsIn(group, advanced: advanced))
        if (_offers(DocumentTypeRegistry.of(info.kind), start))
          DocumentTypeRegistry.of(info.kind),
    ];
    if (types.isNotEmpty) groups.add(NewDocumentGroup(group, types));
  }
  return groups;
}

/// Creates [choice] in [folderId] (null is the workspace root) and opens
/// it. [runtimeId] replaces the default id where the kind has one (a synced
/// panel's reserved prefix). A template is created first and then moved; a
/// random pick is created blank and filled from its seed in one undoable
/// edit.
void createNewDocument(
  EditorStore store,
  NewDocumentChoice choice, {
  String? folderId,
  String? runtimeId,
}) {
  final Workspace workspace = store.workspace;
  final DocumentTemplate? template = choice.template;
  if (template == null) {
    choice.type.createNew(store, folderId: folderId, runtimeId: runtimeId);
    final String? created = workspace.activeId;
    final int? seed = choice.seed;
    if (seed != null && created != null) {
      randomizeShowcaseDocument(store, created, random: math.Random(seed));
    }
    return;
  }
  final String? before = workspace.activeId;
  template.create(store);
  final String? created = workspace.activeId;
  if (created == null || created == before) return;
  if (runtimeId != null) store.renameDocumentRuntimeId(created, runtimeId);
  if (folderId != null) workspace.moveDocument(created, folderId);
}
