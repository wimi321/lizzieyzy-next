package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask;
import featurecat.lizzie.analysis.ForegroundRestoreResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Physical startup and attempt-bound worker use. Task timing and retry policy live in the task. */
final class AutomaticQuickAnalysisEngineAdapter implements AutomaticQuickAnalysisTask.Environment {
  private final LizzieFrame frame;
  private final WorkerFactory factory;
  private final BiConsumer<String, Runnable> executor;
  private final Timing timing;
  private final List<Acquisition> waiting = new ArrayList<>();
  private Startup startup;
  private volatile EngineUse owner;
  private long epoch;
  private boolean preloadRequested;

  AutomaticQuickAnalysisEngineAdapter(LizzieFrame frame) {
    this(
        frame,
        persistent ->
            persistent ? new AnalysisEngine(true) : AnalysisEngine.createAutomaticQuickAnalysis(),
        AutomaticQuickAnalysisEngineAdapter::startThread);
  }

  AutomaticQuickAnalysisEngineAdapter(
      LizzieFrame frame, WorkerFactory factory, BiConsumer<String, Runnable> executor) {
    this(frame, factory, executor, new SwingTiming());
  }

  AutomaticQuickAnalysisEngineAdapter(
      LizzieFrame frame,
      WorkerFactory factory,
      BiConsumer<String, Runnable> executor,
      Timing timing) {
    this.frame = frame;
    this.factory = factory;
    this.executor = executor;
    this.timing = timing;
  }

  interface WorkerFactory {
    AnalysisEngine create(boolean persistent) throws IOException;
  }

  interface Timing {
    AutomaticQuickAnalysisTask.Cancellable schedule(int delayMillis, Runnable action);

    long nowMillis();
  }

  private static final class SwingTiming implements Timing {
    @Override
    public AutomaticQuickAnalysisTask.Cancellable schedule(int delayMillis, Runnable action) {
      Timer timer = new Timer(delayMillis, event -> action.run());
      timer.setRepeats(false);
      timer.start();
      return timer::stop;
    }

    @Override
    public long nowMillis() {
      return System.nanoTime() / 1_000_000L;
    }
  }

  @Override
  public AutomaticQuickAnalysisTask.Readiness readiness() {
    return frame.automaticQuickAnalysisReadiness();
  }

  @Override
  public Object foregroundIdentity() {
    return Lizzie.leelaz;
  }

  @Override
  public AutomaticQuickAnalysisTask.Registration acquire(
      Consumer<AutomaticQuickAnalysisTask.Use> ready) {
    Acquisition registration = new Acquisition(ready);
    waiting.add(registration);
    ensureWorker();
    return registration;
  }

  @Override
  public AutomaticQuickAnalysisTask.Cancellable schedule(int delayMillis, Runnable action) {
    return timing.schedule(delayMillis, action);
  }

  @Override
  public long nowMillis() {
    return timing.nowMillis();
  }

  @Override
  public void onEdt(Runnable action) {
    if (SwingUtilities.isEventDispatchThread()) action.run();
    else SwingUtilities.invokeLater(action);
  }

  void preload() {
    preloadRequested = true;
    ensureWorker();
  }

  boolean isStarting() {
    return startup != null;
  }

  AnalysisEngine ownedEngine() {
    return owner != null && owner.ownsWorker() ? owner.engine : null;
  }

  void invalidateStartup() {
    epoch++;
    preloadRequested = false;
    // Registrations are revoked by their task, not by unrelated preload/switch callbacks.
  }

  private void ensureWorker() {
    if (startup != null || owner != null) return;
    if (waiting.isEmpty() && !preloadRequested) return;
    AnalysisEngine existing = frame.analysisEngine;
    if (existing != null
        && !waiting.isEmpty()
        && frame.shouldReplaceQuickAnalysisWorker(existing)) {
      Startup replacement = new Startup(epoch, preloadRequested);
      startup = replacement;
      frame.analysisEngine = null;
      existing.clearRequestCallbacks();
      background(
          "quick-analysis-worker-release",
          () ->
              existing.normalQuitWithRestoreResult(
                  result ->
                      onEdt(
                          () -> {
                            if (startup != replacement) return;
                            startup = null;
                            if (result == ForegroundRestoreResult.FAILED) failWaiting();
                            else ensureWorker();
                          })));
      return;
    }
    if (frame.isAnalysisEngineReusable(existing)) {
      preloadRequested = false;
      handoff(existing);
      return;
    }
    Startup pending = new Startup(epoch, preloadRequested);
    startup = pending;
    preloadRequested = false;
    background(
        "quick-analysis-engine-preloader",
        () -> {
          AnalysisEngine engine = null;
          try {
            engine = factory.create(pending.persistent);
          } catch (IOException | RuntimeException failure) {
            failure.printStackTrace();
          }
          AnalysisEngine warmed = engine;
          onEdt(() -> finishStartup(pending, warmed));
        });
  }

  private void finishStartup(Startup pending, AnalysisEngine warmed) {
    if (startup != pending) {
      closeUnclaimed(warmed);
      return;
    }
    startup = null;
    if (pending.epoch != epoch || frame.isWholeGameAnalysisStartingOrRunning()) {
      closeUnclaimed(warmed);
      ensureWorker();
      return;
    }
    if (!frame.isAnalysisEngineReusable(warmed)) {
      closeUnclaimed(warmed);
      failWaiting();
      return;
    }
    if (frame.isAnalysisEngineReusable(frame.analysisEngine)) {
      if (frame.analysisEngine != warmed) closeUnclaimed(warmed);
      handoff(frame.analysisEngine);
    } else if (!waiting.isEmpty() || pending.persistent || preloadRequested) {
      frame.analysisEngine = warmed;
      preloadRequested = false;
      handoff(warmed);
    } else {
      closeUnclaimed(warmed);
    }
  }

  private void handoff(AnalysisEngine engine) {
    if (owner != null || waiting.isEmpty()) return;
    Acquisition acquisition = waiting.remove(0);
    if (acquisition.cancelled) {
      handoff(engine);
      return;
    }
    acquisition.delivered = true;
    EngineUse use = new EngineUse(engine);
    owner = use;
    acquisition.ready.accept(use);
  }

  private void failWaiting() {
    List<Acquisition> failed = new ArrayList<>(waiting);
    waiting.clear();
    for (Acquisition acquisition : failed) {
      if (!acquisition.cancelled) {
        acquisition.delivered = true;
        acquisition.ready.accept(null);
      }
    }
  }

  private void closeUnclaimed(AnalysisEngine engine) {
    if (engine != null) background("quick-analysis-unclaimed-close", engine::normalQuit);
  }

  private void background(String name, Runnable action) {
    executor.accept(name, action);
  }

  private static void startThread(String name, Runnable action) {
    Thread thread = new Thread(action, name);
    thread.setDaemon(true);
    thread.start();
  }

  private record Startup(long epoch, boolean persistent) {}

  private final class Acquisition implements AutomaticQuickAnalysisTask.Registration {
    private final Consumer<AutomaticQuickAnalysisTask.Use> ready;
    private boolean cancelled;
    private boolean delivered;

    Acquisition(Consumer<AutomaticQuickAnalysisTask.Use> ready) {
      this.ready = ready;
    }

    @Override
    public boolean inProgress() {
      return !cancelled && !delivered && startup != null;
    }

    @Override
    public void cancel() {
      cancelled = true;
      waiting.remove(this);
    }
  }

  private final class EngineUse implements AutomaticQuickAnalysisTask.Use {
    private final AnalysisEngine engine;
    private final Object dispatchLock = new Object();
    private volatile boolean released;
    private volatile boolean dispatching;
    private boolean requestFinished;
    private boolean requestFailed;
    private ForegroundRestoreResult requestRestore = ForegroundRestoreResult.NOT_REQUIRED;

    EngineUse(AnalysisEngine engine) {
      this.engine = engine;
    }

    boolean ownsWorker() {
      return !released && owner == this && frame.analysisEngine == engine;
    }

    @Override
    public void requestMissing(Consumer<AutomaticQuickAnalysisTask.RequestResult> finished) {
      if (!ownsWorker()) return;
      engine.setCompletionCallback(restore -> complete(false, restore, finished));
      engine.setFailureCallback(restore -> complete(true, restore, finished));
      dispatching = true;
      background(
          "loaded-game-quick-analysis-request",
          () -> {
            try {
              int count;
              synchronized (dispatchLock) {
                if (!ownsWorker()) return;
                count = engine.startRequestMissingMainline(false);
              }
              if (count == 0)
                onEdt(() -> complete(false, ForegroundRestoreResult.NOT_REQUIRED, finished));
              // A dispatch failure callback carries the actual restoration result. Pre-admission
              // rejection has no lifecycle, so release supplies its result before task
              // notification.
              else if (count < 0)
                onEdt(
                    () -> {
                      if (!requestFinished
                          && ownsWorker()
                          && !engine.hasRequestLifecycleInProgress()) {
                        complete(true, ForegroundRestoreResult.NOT_REQUIRED, finished);
                      }
                    });
            } finally {
              dispatching = false;
            }
          });
    }

    private void complete(
        boolean failed,
        ForegroundRestoreResult restore,
        Consumer<AutomaticQuickAnalysisTask.RequestResult> finished) {
      onEdt(
          () -> {
            if (!ownsWorker() || requestFinished) return;
            requestFinished = true;
            requestFailed = failed;
            requestRestore = restore;
            finished.accept(new AutomaticQuickAnalysisTask.RequestResult(failed, restore));
          });
    }

    @Override
    public boolean inProgress() {
      return ownsWorker() && (dispatching || engine.hasRequestLifecycleInProgress());
    }

    @Override
    public boolean requiresForegroundRestore() {
      return ownsWorker()
          && engine.usesSharedForegroundEngine()
          && (dispatching || engine.hasRequestLifecycleInProgress());
    }

    @Override
    public void release(boolean transfer, Consumer<ForegroundRestoreResult> finished) {
      if (!ownsWorker()) {
        finished.accept(requestRestore);
        return;
      }
      released = true;
      if (transfer || (requestFinished && !requestFailed && !engine.isAutomaticBackgroundTask())) {
        background(
            "automatic-quick-analysis-return",
            () -> {
              synchronized (dispatchLock) {
                engine.clearRequestCallbacks();
              }
              onEdt(
                  () -> {
                    if (owner == this) owner = null;
                    finished.accept(requestRestore);
                    if (!transfer) ensureWorker();
                  });
            });
        return;
      }
      frame.analysisEngine = null;
      engine.requestShutdown();
      background(
          "automatic-quick-analysis-close",
          () -> {
            synchronized (dispatchLock) {
              engine.clearRequestCallbacks();
              engine.normalQuitWithRestoreResult(
                  restore ->
                      onEdt(
                          () -> {
                            if (owner == this) owner = null;
                            ForegroundRestoreResult outcome =
                                requestRestore == ForegroundRestoreResult.FAILED
                                    ? requestRestore
                                    : restore == ForegroundRestoreResult.NOT_REQUIRED
                                        ? requestRestore
                                        : restore;
                            finished.accept(outcome);
                            ensureWorker();
                          }));
            }
          });
    }
  }
}
