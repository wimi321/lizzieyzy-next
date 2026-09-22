package featurecat.lizzie.gui;

import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Event-thread ownership of one review/apply operation, independent of native window peers. */
final class MeasuredTuningOperation {
  private long generation;
  private Future<?> worker;
  private Consumer<Boolean> busy;

  long begin(Consumer<Boolean> busy) {
    invalidate();
    this.busy = busy;
    busy.accept(true);
    return generation;
  }

  void attach(long token, Future<?> worker) {
    if (token != generation) worker.cancel(true);
    else this.worker = worker;
  }

  boolean canDeliver(long token, boolean visible, boolean displayable) {
    return token == generation && busy != null && visible && displayable;
  }

  void finish(long token) {
    if (token != generation) return;
    worker = null;
    Consumer<Boolean> completed = busy;
    busy = null;
    if (completed != null) completed.accept(false);
  }

  void invalidate() {
    generation++;
    Future<?> cancelled = worker;
    worker = null;
    Consumer<Boolean> cancelledBusy = busy;
    busy = null;
    if (cancelled != null) cancelled.cancel(true);
    if (cancelledBusy != null) cancelledBusy.accept(false);
  }
}
