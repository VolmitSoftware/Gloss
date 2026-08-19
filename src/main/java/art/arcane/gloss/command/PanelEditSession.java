package art.arcane.gloss.command;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;

import java.util.Objects;

final class PanelEditSession {
  private final PanelDefinition base;
  private PanelDefinition staged;
  private PanelTransform effectiveTransform;
  private boolean saving;

  PanelEditSession(PanelDefinition base, PanelTransform effectiveTransform) {
    this.base = Objects.requireNonNull(base, "base");
    this.staged = base;
    this.effectiveTransform = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
  }

  synchronized String id() {
    return base.id();
  }

  synchronized long expectedRevision() {
    return base.revision();
  }

  synchronized Snapshot snapshot() {
    return new Snapshot(staged, effectiveTransform);
  }

  synchronized Snapshot synchronizeEffective(PanelTransform effective) {
    if (saving || staged.follow().targetPlayerUuid() == null) {
      return snapshot();
    }
    effectiveTransform = Objects.requireNonNull(effective, "effective");
    return snapshot();
  }

  synchronized Snapshot stage(Snapshot expected, PanelDefinition changed,
                              PanelTransform changedEffectiveTransform) {
    if (saving) {
      return null;
    }
    Snapshot requiredExpected = Objects.requireNonNull(expected, "expected");
    if (!staged.equals(requiredExpected.definition())
        || !effectiveTransform.equals(requiredExpected.effectiveTransform())) {
      throw new IllegalStateException("staged board changed while the operation was being prepared");
    }
    PanelDefinition requiredChanged = Objects.requireNonNull(changed, "changed");
    PanelTransform requiredEffective = Objects.requireNonNull(
        changedEffectiveTransform, "changedEffectiveTransform");
    if (!requiredChanged.id().equals(base.id()) || !requiredChanged.uuid().equals(base.uuid())
        || requiredChanged.revision() != base.revision()) {
      throw new IllegalArgumentException("staged edits cannot change board identity or revision");
    }
    staged = requiredChanged;
    effectiveTransform = requiredEffective;
    return new Snapshot(staged, effectiveTransform);
  }

  synchronized PanelDefinition beginSave() {
    if (saving) {
      return null;
    }
    saving = true;
    return staged;
  }

  synchronized PanelDefinition cancel() {
    if (saving) {
      return null;
    }
    saving = true;
    return staged;
  }

  synchronized void retrySave() {
    saving = false;
  }

  record Snapshot(PanelDefinition definition, PanelTransform effectiveTransform) {
    Snapshot {
      definition = Objects.requireNonNull(definition, "definition");
      effectiveTransform = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
    }
  }
}
