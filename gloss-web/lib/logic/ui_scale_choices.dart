/// The preview uiScale values the canvas and 3D preview menus offer.
library;

/// One pick each; Settings can set any value from 0.25 to 4.
const List<double> uiScalePresets = <double>[0.5, 0.75, 1, 1.25, 1.5, 2];

/// The presets plus [current] in its place when it is not one of them, so
/// the menu always shows the value in use as checked.
List<double> uiScaleChoices(double current) => <double>[
  ...uiScalePresets,
  if (!uiScalePresets.contains(current)) current,
]..sort();

String uiScaleLabel(double value) => value.toStringAsFixed(2);

double? parseUiScaleLabel(String label) => double.tryParse(label);
