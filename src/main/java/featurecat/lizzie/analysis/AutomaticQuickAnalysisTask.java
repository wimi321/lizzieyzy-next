package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** One loaded game's automatic mainline completion, including its retries and owned handback. */
public final class AutomaticQuickAnalysisTask {
  public enum Readiness {
    READY,
    WAIT,
    STOP
  }

  public enum CancelReason {
    USER_PAUSE,
    GAME_CHANGED,
    MANUAL_AUTO,
    MANUAL_FLASH,
    ENGINE_SWITCH,
    SHUTDOWN
  }

  public enum EndReason {
    COMPLETED,
    UNAVAILABLE,
    CANCELLED
  }

  public record Settlement(
      EndReason reason, ForegroundRestoreResult restore, boolean positionAlreadyConfirmed) {}

  public record Opportunity(ForegroundRestoreResult restore, boolean positionAlreadyConfirmed) {}

  public record RequestResult(boolean failed, ForegroundRestoreResult restore) {}

  public interface Listener {
    void failedAttempt(Opportunity opportunity);

    void completed(Settlement settlement);
  }

  public interface Environment {
    Readiness readiness();

    Object foregroundIdentity();

    Registration acquire(Consumer<Use> ready);

    Cancellable schedule(int delayMillis, Runnable action);

    long nowMillis();

    void onEdt(Runnable action);
  }

  public interface Cancellable {
    void cancel();
  }

  public interface Registration extends Cancellable {
    boolean inProgress();
  }

  public interface Use {
    void requestMissing(Consumer<RequestResult> finished);

    boolean inProgress();

    boolean requiresForegroundRestore();

    void release(boolean transfer, Consumer<ForegroundRestoreResult> finished);
  }

  private static final int RETRY_MILLIS = 1800;
  private static final int MAX_RETRY_MILLIS = 30_000;
  private static final int WATCHDOG_MILLIS = 30_000;
  private static final int NAVIGATION_MILLIS = 700;

  private final Board board;
  private final BoardHistoryNode root;
  private final BoardHistoryNode confirmedNode;
  private final long confirmedRevision;
  private final Object confirmedForeground;
  private final Environment environment;
  private final Listener listener;
  private final List<Consumer<Settlement>> observers = new ArrayList<>();
  private boolean confirmationValid;
  private boolean started;
  private volatile boolean active;
  private volatile boolean retrying;
  private volatile boolean foregroundRestoreRequired;
  private int failures;
  private long navigationDeadline;
  private EndReason ending;
  private Settlement settlement;
  private ForegroundRestoreResult lastRestore = ForegroundRestoreResult.NOT_REQUIRED;
  private Attempt attempt;
  private Timer retryTimer;
  private Timer watchdog;

  public AutomaticQuickAnalysisTask(
      Board board, Environment environment, Listener listener, boolean positionAlreadyConfirmed) {
    this.board = board;
    this.environment = environment;
    this.listener = listener;
    root = board.getHistory().getStart();
    confirmedNode = board.getHistory().getCurrentHistoryNode();
    confirmedRevision = board.getContextRevision();
    confirmedForeground = environment.foregroundIdentity();
    confirmationValid = positionAlreadyConfirmed;
  }

  public void start() {
    environment.onEdt(
        () -> {
          if (started || ending != null || settlement != null) return;
          started = true;
          active = true;
          progress();
        });
  }

  public void navigationChanged() {
    environment.onEdt(
        () -> {
          confirmationValid = false;
          if (!active) return;
          navigationDeadline = environment.nowMillis() + NAVIGATION_MILLIS;
          if (attempt == null) scheduleRetry(NAVIGATION_MILLIS);
        });
  }

  public void cancel(CancelReason reason) {
    environment.onEdt(
        () -> {
          if (ending == EndReason.CANCELLED || settlement != null) return;
          ending = EndReason.CANCELLED;
          active = false;
          clearRetry();
          clearWatchdog();
          if (attempt == null) settle();
          else
            finishAttempt(
                attempt,
                false,
                ForegroundRestoreResult.NOT_REQUIRED,
                reason == CancelReason.MANUAL_FLASH);
        });
  }

  public boolean isActive() {
    return active;
  }

  public boolean isRetrying() {
    return retrying;
  }

  public boolean requiresForegroundRestore() {
    Attempt owner = attempt;
    Use use = owner == null ? null : owner.use;
    return foregroundRestoreRequired || (use != null && use.requiresForegroundRestore());
  }

  public void whenSettled(Consumer<Settlement> observer) {
    environment.onEdt(
        () -> {
          if (settlement == null) observers.add(observer);
          else observer.accept(settlement);
        });
  }

  private void progress() {
    if (!active || attempt != null) return;
    invalidateChangedConfirmation();
    Readiness readiness = environment.readiness();
    if (board.getHistory().getStart() != root || readiness == Readiness.STOP) {
      ending = EndReason.UNAVAILABLE;
      settle();
      return;
    }
    if (!hasMissingMainlineAnalysis()) {
      ending = EndReason.COMPLETED;
      settle();
      return;
    }
    long navigationWait = navigationDeadline - environment.nowMillis();
    if (navigationWait > 0) {
      scheduleRetry((int) navigationWait);
      return;
    }
    if (readiness == Readiness.WAIT) {
      scheduleRetry(RETRY_MILLIS);
      return;
    }
    Attempt next = new Attempt();
    attempt = next;
    Registration registration =
        environment.acquire(use -> environment.onEdt(() -> acquired(next, use)));
    // Acquisition may deliver, finish and even cancel synchronously before returning its handle.
    if (attempt != next || next.delivered || next.closing) registration.cancel();
    else next.registration = registration;
    if (attempt == next && !next.delivered && !next.closing) armWatchdog(next);
  }

  private void acquired(Attempt owner, Use use) {
    if (use != null && !owner.handled.add(use)) return;
    if (attempt != owner || owner.delivered || owner.closing || !active) {
      if (use != null) use.release(false, ignored -> {});
      return;
    }
    owner.delivered = true;
    cancelRegistration(owner);
    if (use == null) {
      finishAttempt(owner, true, ForegroundRestoreResult.NOT_REQUIRED, false);
      return;
    }
    owner.use = use;
    foregroundRestoreRequired = use.requiresForegroundRestore();
    use.requestMissing(
        result ->
            environment.onEdt(
                () -> {
                  if (attempt != owner || owner.closing || !active) return;
                  finishAttempt(owner, result.failed(), result.restore(), false);
                }));
    if (attempt == owner && !owner.closing) armWatchdog(owner);
  }

  private void armWatchdog(Attempt owner) {
    clearWatchdog();
    Timer timer = new Timer();
    watchdog = timer;
    timer.install(
        environment.schedule(
            WATCHDOG_MILLIS,
            () ->
                environment.onEdt(
                    () -> {
                      if (watchdog != timer || attempt != owner || owner.closing || !active) return;
                      watchdog = null;
                      timer.cancel();
                      boolean progressing =
                          owner.use != null
                              ? owner.use.inProgress()
                              : owner.registration != null && owner.registration.inProgress();
                      if (progressing) armWatchdog(owner);
                      else finishAttempt(owner, true, ForegroundRestoreResult.NOT_REQUIRED, false);
                    })));
  }

  private void finishAttempt(
      Attempt owner, boolean failed, ForegroundRestoreResult restore, boolean transfer) {
    if (attempt != owner || owner.closing) return;
    owner.closing = true;
    owner.failed = failed;
    owner.restore = restore;
    clearWatchdog();
    cancelRegistration(owner);
    if (owner.use == null) {
      released(owner, ForegroundRestoreResult.NOT_REQUIRED);
    } else {
      foregroundRestoreRequired |= owner.use.requiresForegroundRestore();
      owner.use.release(transfer, result -> environment.onEdt(() -> released(owner, result)));
    }
  }

  private void released(Attempt owner, ForegroundRestoreResult restore) {
    if (attempt != owner || owner.released) return;
    owner.released = true;
    lastRestore = combine(owner.restore, restore);
    if (lastRestore == ForegroundRestoreResult.FAILED) confirmationValid = false;
    foregroundRestoreRequired = false;
    attempt = null;
    if (ending != null) {
      settle();
      return;
    }
    if (board.getHistory().getStart() != root || environment.readiness() == Readiness.STOP) {
      ending = EndReason.UNAVAILABLE;
      settle();
      return;
    }
    if (owner.failed) {
      failures = Math.min(4, failures + 1);
      listener.failedAttempt(new Opportunity(lastRestore, positionConfirmed()));
      // The foreground opportunity can synchronously pause or replace this task.
      if (!active) return;
    } else {
      failures = 0;
    }
    if (!hasMissingMainlineAnalysis()) {
      ending = EndReason.COMPLETED;
      settle();
    } else {
      int delay = Math.min(MAX_RETRY_MILLIS, RETRY_MILLIS << failures);
      scheduleRetry((int) Math.max(delay, navigationDeadline - environment.nowMillis()));
    }
  }

  private void scheduleRetry(int delayMillis) {
    clearRetry();
    retrying = true;
    Timer timer = new Timer();
    retryTimer = timer;
    timer.install(
        environment.schedule(
            delayMillis,
            () ->
                environment.onEdt(
                    () -> {
                      if (retryTimer != timer || !active) return;
                      retryTimer = null;
                      retrying = false;
                      timer.cancel();
                      progress();
                    })));
  }

  private void clearRetry() {
    Timer timer = retryTimer;
    retryTimer = null;
    retrying = false;
    if (timer != null) timer.cancel();
  }

  private void clearWatchdog() {
    Timer timer = watchdog;
    watchdog = null;
    if (timer != null) timer.cancel();
  }

  private static void cancelRegistration(Attempt owner) {
    Registration registration = owner.registration;
    owner.registration = null;
    if (registration != null) registration.cancel();
  }

  private void settle() {
    if (settlement != null) return;
    active = false;
    clearRetry();
    clearWatchdog();
    settlement = new Settlement(ending, lastRestore, positionConfirmed());
    List<Consumer<Settlement>> pending = new ArrayList<>(observers);
    observers.clear();
    if (ending != EndReason.CANCELLED) listener.completed(settlement);
    for (Consumer<Settlement> observer : pending) observer.accept(settlement);
  }

  private void invalidateChangedConfirmation() {
    if (board.getHistory().getStart() != root
        || board.getContextRevision() != confirmedRevision
        || board.getHistory().getCurrentHistoryNode() != confirmedNode
        || environment.foregroundIdentity() != confirmedForeground) confirmationValid = false;
  }

  private boolean positionConfirmed() {
    invalidateChangedConfirmation();
    return confirmationValid;
  }

  private boolean hasMissingMainlineAnalysis() {
    for (BoardHistoryNode node = root; node != null; node = node.next().orElse(null)) {
      BoardData data = node.getData();
      if ((data.isMoveNode() || data.isPassNode())
          && !data.dummy
          && !data.hasDisplayablePrimaryAnalysis()) return true;
    }
    return false;
  }

  private static ForegroundRestoreResult combine(
      ForegroundRestoreResult first, ForegroundRestoreResult second) {
    if (first == ForegroundRestoreResult.FAILED || second == ForegroundRestoreResult.FAILED)
      return ForegroundRestoreResult.FAILED;
    if (first == ForegroundRestoreResult.SUCCEEDED || second == ForegroundRestoreResult.SUCCEEDED)
      return ForegroundRestoreResult.SUCCEEDED;
    return ForegroundRestoreResult.NOT_REQUIRED;
  }

  private static final class Attempt {
    // Retained by this registration's callbacks, not by a task-wide history of old attempts.
    private final Set<Use> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    private Registration registration;
    private Use use;
    private boolean delivered;
    private boolean closing;
    private boolean released;
    private boolean failed;
    private ForegroundRestoreResult restore = ForegroundRestoreResult.NOT_REQUIRED;
  }

  private static final class Timer {
    private Cancellable handle;
    private boolean cancelled;

    private void install(Cancellable handle) {
      if (cancelled) handle.cancel();
      else this.handle = handle;
    }

    private void cancel() {
      cancelled = true;
      if (handle != null) {
        Cancellable owned = handle;
        handle = null;
        owned.cancel();
      }
    }
  }
}
