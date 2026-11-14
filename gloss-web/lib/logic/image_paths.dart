/// Image paths a menu references, and byte sizes for quota and export
/// summaries. Pure: the export, settings and image manager dialogs share them.
library;

import '../model/model.dart';

/// Every image path the menu references, in document order.
///
/// The export dialog uses it to decide whether an images zip is needed, and the
/// image manager uses it to mark which stored images are actually in use.
Set<String> huiUsedImagePaths(HuiMenu menu) {
  final Set<String> paths = <String>{};
  void collect(HuiIcon? icon) {
    switch (icon) {
      case null:
        return;
      case final HuiTextImageIcon image:
        if (image.path.isNotEmpty) paths.add(image.path);
      case final HuiAnimatedImageIcon animated:
        for (final String frame in animated.source) {
          if (frame.isNotEmpty) paths.add(frame);
        }
      case HuiTextIcon():
      case HuiItemIcon():
      case HuiBlockIcon():
      case HuiCustomItemIcon():
      case HuiEntityIcon():
      case HuiPlayerHeadIcon():
        return;
    }
  }

  for (final HuiComponent entry in menu.components) {
    switch (entry.data) {
      case HuiRuntimeComponentData():
        break;

      case final HuiButtonData data:
        collect(data.icon);
      case final HuiDecorationData data:
        collect(data.icon);
      case final HuiToggleData data:
        collect(data.trueIcon);
        collect(data.falseIcon);
    }
  }
  return paths;
}

/// Rewrites every reference to [from] as [to]. Returns true when something
/// changed, so callers can skip an empty undo step.
///
/// Renaming a stored image would otherwise silently orphan the icons that point
/// at it: the plugin resolves the path at open time and draws the missing-icon
/// placeholder when the file is gone.
bool huiRepointImagePaths(HuiMenu menu, String from, String to) {
  bool changed = false;
  void repoint(HuiIcon? icon) {
    switch (icon) {
      case null:
        return;
      case final HuiTextImageIcon image:
        if (image.path == from) {
          image.path = to;
          changed = true;
        }
      case final HuiAnimatedImageIcon animated:
        for (int i = 0; i < animated.source.length; i++) {
          if (animated.source[i] == from) {
            animated.source[i] = to;
            changed = true;
          }
        }
      case HuiTextIcon():
      case HuiItemIcon():
      case HuiBlockIcon():
      case HuiCustomItemIcon():
      case HuiEntityIcon():
      case HuiPlayerHeadIcon():
        return;
    }
  }

  for (final HuiComponent entry in menu.components) {
    switch (entry.data) {
      case HuiRuntimeComponentData():
        break;

      case final HuiButtonData data:
        repoint(data.icon);
      case final HuiDecorationData data:
        repoint(data.icon);
      case final HuiToggleData data:
        repoint(data.trueIcon);
        repoint(data.falseIcon);
    }
  }
  return changed;
}

/// Human-readable byte size for quota meters and export summaries.
String huiFormatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  final double kb = bytes / 1024;
  if (kb < 1024) return '${kb.toStringAsFixed(kb < 10 ? 1 : 0)} KB';
  final double mb = kb / 1024;
  return '${mb.toStringAsFixed(mb < 10 ? 2 : 1)} MB';
}
