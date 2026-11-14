/// Loading rows: grey bars where content will be, read as "Loading" by
/// screen readers. On shadcn's skeleton.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../l10n/hui_localizations.dart';

class Skeleton extends StatelessWidget {
  const Skeleton({this.rows = 3, this.label, this.height, super.key});

  /// Bars to draw; with [height], one block of that height instead.
  final int rows;

  /// What is loading, for screen readers; defaults to "Loading".
  final String? label;

  /// A CSS height for one block (a canvas placeholder).
  final String? height;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'ui-skeleton',
    attributes: <String, String>{
      'role': 'status',
      'aria-label': label ?? huiText('Loading'),
    },
    <Widget>[
      if (height != null)
        ArcaneSkeleton(width: '100%', height: height)
      else
        for (int row = 0; row < rows; row++)
          ArcaneSkeleton.line(
            width: row == rows - 1 && rows > 1 ? '60%' : '100%',
          ),
    ],
  );
}
