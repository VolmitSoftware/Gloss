/// Where focus lands when a dialog or sheet opens, kept free of the DOM.
library;

enum ModalStart {
  /// The dialog or sheet itself: nothing is typed into, so a phone keeps
  /// its on-screen keyboard down.
  panel,

  /// The first field, else the first control.
  firstField,
}

/// A phone or tablet (a coarse primary pointer) starts on the panel, and so
/// does a dialog opened with a mouse click: a focused field would show its
/// ring as if it were in use. Opened from the keyboard, it starts in the
/// first field.
ModalStart modalStart({required bool coarsePointer, required bool keyboard}) =>
    !coarsePointer && keyboard ? ModalStart.firstField : ModalStart.panel;
