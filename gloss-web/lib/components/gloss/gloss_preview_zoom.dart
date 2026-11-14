/// A fixed-size game window scaled down to the width it is given, through
/// the kit's [FitBox] (CSS zoom, so the layout follows the scaled size).
/// Only the editor-only stages that the shell no longer mounts still use it;
/// it goes once they do not.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../ui/fit_box.dart';

enum GlossPreviewAlignment { center, end, top, bottom }

class GlossPreviewZoom extends StatelessWidget {
  const GlossPreviewZoom({
    required this.child,
    required this.label,
    this.alignment = GlossPreviewAlignment.center,
    super.key,
  });

  final Widget child;
  final String label;
  final GlossPreviewAlignment alignment;

  @override
  Widget build(BuildContext context) => FitBox(
    align: alignment == GlossPreviewAlignment.end
        ? FitAlign.end
        : FitAlign.center,
    child: child,
  );
}
