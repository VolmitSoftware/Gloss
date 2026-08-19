package art.arcane.gloss.panel;

import java.util.concurrent.TimeUnit;

@FunctionalInterface
interface PanelTaskRunner {
  PanelTaskHandle submit(Runnable task);

  default void shutdown() {
  }

  default boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return true;
  }

  @FunctionalInterface
  interface PanelTaskHandle {
    void cancel();
  }
}
