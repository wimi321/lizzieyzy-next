package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.Branch;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** One application worker, FIFO host turns, and one replaceable pending request per host. */
final class VariationPreviewScheduler {
  private static final class Shared {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "variation-preview");
      thread.setDaemon(true);
      return thread;
    });
    static final VariationPreviewScheduler INSTANCE =
        new VariationPreviewScheduler(WORKER, SwingUtilities::invokeLater);
  }

  static VariationPreviewScheduler shared() {
    return Shared.INSTANCE;
  }

  static void shutdown() {
    Shared.INSTANCE.close();
    Shared.WORKER.shutdownNow();
  }

  private final Executor worker;
  private final Executor completion;
  private final LinkedHashMap<Object, Work> pending = new LinkedHashMap<>();
  private Work active;
  private boolean closed;

  VariationPreviewScheduler(Executor worker, Executor completion) {
    this.worker = worker;
    this.completion = completion;
  }

  synchronized void submit(Object host, Branch.Input input,
      VariationPreviewGenerator.Geometry geometry, VariationPreviewGenerator.Style style,
      Consumer<VariationPreviewGenerator.Result> publish) {
    if (closed) return;
    pending.put(host, new Work(host, input, geometry, style, publish));
    startNext();
  }

  synchronized void cancel(Object host) {
    pending.remove(host);
    if (active != null && active.host == host) {
      active.cancelled = true;
      active.publish = null;
    }
  }

  synchronized void close() {
    closed = true;
    pending.clear();
    if (active != null) {
      active.cancelled = true;
      active.publish = null;
    }
  }

  private void startNext() {
    if (closed || active != null || pending.isEmpty()) return;
    var iterator = pending.values().iterator();
    Work work = iterator.next();
    iterator.remove();
    active = work;
    worker.execute(() -> generate(work));
  }

  private void generate(Work work) {
    VariationPreviewGenerator.Result result = null;
    try {
      if (!work.cancelled) {
        result = VariationPreviewGenerator.generate(work.input, work.geometry, work.style);
      }
    } catch (RuntimeException failure) {
      // A failed preview never falls back to simulation on the EDT.
      failure.printStackTrace();
    }
    VariationPreviewGenerator.Result completed = result;
    completion.execute(() -> finish(work, completed));
  }

  private void finish(Work work, VariationPreviewGenerator.Result result) {
    Consumer<VariationPreviewGenerator.Result> publish;
    synchronized (this) {
      publish = !closed && !work.cancelled && result != null ? work.publish : null;
    }
    try {
      // Host validation acquires Board; Board cleanup may concurrently cancel this turn.
      if (publish != null) publish.accept(result);
    } finally {
      synchronized (this) {
        // Keep the active turn until publication returns, bounding completed images on EDT.
        active = null;
        startNext();
      }
    }
  }

  private static final class Work {
    final Object host;
    final Branch.Input input;
    final VariationPreviewGenerator.Geometry geometry;
    final VariationPreviewGenerator.Style style;
    Consumer<VariationPreviewGenerator.Result> publish;
    volatile boolean cancelled;

    Work(Object host, Branch.Input input, VariationPreviewGenerator.Geometry geometry,
        VariationPreviewGenerator.Style style, Consumer<VariationPreviewGenerator.Result> publish) {
      this.host = host;
      this.input = input;
      this.geometry = geometry;
      this.style = style;
      this.publish = publish;
    }
  }
}
