package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.Timer;

/** EDT-owned dialog request. Cancelling retires callbacks, not an already submitted engine. */
final class RemoteComputeEngineSwitch {
  interface Backend {
    boolean alreadyActive(int index);

    boolean isZhizi(int index);

    Long submit(int index);

    Result poll(long token, int index);
  }

  record Result(boolean success, String detail) {}

  private final Function<Runnable, Runnable> monitor;
  private Request current;

  RemoteComputeEngineSwitch() {
    this(
        callback -> {
          Timer timer = new Timer(250, event -> callback.run());
          timer.setInitialDelay(0);
          timer.start();
          return timer::stop;
        });
  }

  RemoteComputeEngineSwitch(Function<Runnable, Runnable> monitor) {
    this.monitor = monitor;
  }

  void start(
      int index, Consumer<Runnable> defer, Supplier<Backend> backend, Consumer<Result> complete) {
    cancel();
    Backend target = backend.get();
    Request request = new Request(complete, target != null && target.isZhizi(index));
    current = request;
    defer.accept(
        () -> {
          if (current != request) return;
          if (target == null) {
            finish(request, new Result(false, ""));
          } else if (target.alreadyActive(index)) {
            finish(request, new Result(true, ""));
          } else {
            Long token = target.submit(index);
            if (current != request) return;
            if (token == null) {
              finish(request, new Result(false, ""));
              return;
            }
            request.stop =
                monitor.apply(
                    () -> {
                      if (current != request) return;
                      Result result = target.poll(token, index);
                      if (result != null) finish(request, result);
                    });
          }
        });
  }

  boolean isPending() {
    return current != null;
  }

  boolean isZhiziPending() {
    return current != null && current.zhizi;
  }

  void cancelZhizi() {
    if (isZhiziPending()) cancel();
  }

  void cancel() {
    Request previous = current;
    current = null;
    if (previous != null && previous.stop != null) previous.stop.run();
  }

  private void finish(Request request, Result result) {
    if (current != request) return;
    cancel();
    request.complete.accept(result);
  }

  private static final class Request {
    private final Consumer<Result> complete;
    private final boolean zhizi;
    private Runnable stop;

    private Request(Consumer<Result> complete, boolean zhizi) {
      this.complete = complete;
      this.zhizi = zhizi;
    }
  }

  static Backend backend(
      EngineManager manager,
      Supplier<EngineManager> currentManager,
      Supplier<Leelaz> primary,
      int targetIndex) {
    if (manager == null || targetIndex < 0 || targetIndex >= manager.engineList.size()) return null;
    Leelaz target = manager.engineList.get(targetIndex);
    if (target == null) return null;
    String command = target.getEngineCommand();
    return new Backend() {
      private boolean current(int index) {
        return index == targetIndex
            && currentManager.get() == manager
            && index >= 0
            && index < manager.engineList.size()
            && manager.engineList.get(index) == target
            && java.util.Objects.equals(command, target.getEngineCommand());
      }

      public boolean isZhizi(int index) {
        return RemoteComputeConfig.isZhiziEngineCommand(command);
      }

      public boolean alreadyActive(int index) {
        return current(index) && primary.get() == target && target.isLoaded();
      }

      public Long submit(int index) {
        if (!current(index)) return null;
        Optional<EngineManager.EngineSwitchUiSnapshot> submitted =
            manager.switchEngineTrackedIfAvailable(index, true);
        return submitted.map(EngineManager.EngineSwitchUiSnapshot::token).orElse(null);
      }

      public Result poll(long token, int index) {
        if (!current(index)) return new Result(false, "");
        EngineManager.EngineSwitchUiSnapshot state = manager.engineSwitchUiSnapshot(true);
        if (state.token() != token) return new Result(false, "");
        if (state.phase() == EngineManager.EngineSwitchUiPhase.ACTIVE
            && state.activeIndex() == index) return new Result(true, "");
        if (state.phase() == EngineManager.EngineSwitchUiPhase.FAILED)
          return new Result(false, state.failureDetail().trim());
        return null;
      }
    };
  }
}
