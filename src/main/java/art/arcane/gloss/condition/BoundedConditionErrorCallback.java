package art.arcane.gloss.condition;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class BoundedConditionErrorCallback {

  private static final BoundedConditionErrorCallback SILENT = new BoundedConditionErrorCallback(
      0, error -> {
      });

  private final int limit;
  private final Consumer<ConditionEvaluationError> callback;
  private final AtomicInteger reports;

  private BoundedConditionErrorCallback(int limit, Consumer<ConditionEvaluationError> callback) {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    this.limit = limit;
    this.callback = Objects.requireNonNull(callback);
    this.reports = new AtomicInteger();
  }

  public static BoundedConditionErrorCallback bounded(
      int limit, Consumer<ConditionEvaluationError> callback) {
    return new BoundedConditionErrorCallback(limit, callback);
  }

  public static BoundedConditionErrorCallback silent() {
    return SILENT;
  }

  public int reportCount() {
    return reports.get();
  }

  void report(ConditionEvaluationError error) {
    if (!reserveReport()) {
      return;
    }
    try {
      callback.accept(error);
    } catch (RuntimeException ignored) {
    }
  }

  private boolean reserveReport() {
    int current = reports.get();
    while (current < limit) {
      if (reports.compareAndSet(current, current + 1)) {
        return true;
      }
      current = reports.get();
    }
    return false;
  }
}
