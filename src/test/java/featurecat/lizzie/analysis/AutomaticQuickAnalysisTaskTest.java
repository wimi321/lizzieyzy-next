package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.CancelReason;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.EndReason;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.Opportunity;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.Readiness;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.RequestResult;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.Settlement;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomaticQuickAnalysisTaskTest {
  private Board board;
  private ControlledEnvironment environment;
  private RecordingListener listener;
  private Runnable restoreGlobals;

  @BeforeEach
  void createRealBoardWithoutApplicationSideEffects() {
    var frame = Lizzie.frame;
    var engine = Lizzie.leelaz;
    var renderer = LizzieFrame.boardRenderer;
    var secondRenderer = LizzieFrame.boardRenderer2;
    var title = LizzieFrame.fileNameTitle;
    var file = LizzieFrame.curFile;
    boolean recreate = LizzieFrame.forceRecreate;
    restoreGlobals =
        () -> {
          Lizzie.frame = frame;
          Lizzie.leelaz = engine;
          LizzieFrame.boardRenderer = renderer;
          LizzieFrame.boardRenderer2 = secondRenderer;
          LizzieFrame.fileNameTitle = title;
          LizzieFrame.curFile = file;
          LizzieFrame.forceRecreate = recreate;
        };
    Lizzie.frame = null;
    Lizzie.leelaz = null;
    LizzieFrame.boardRenderer = null;
    LizzieFrame.boardRenderer2 = null;
    board = new Board();
    appendMove();
    environment = new ControlledEnvironment();
    listener = new RecordingListener();
  }

  @AfterEach
  void restoreApplicationGlobals() {
    restoreGlobals.run();
  }

  @Test
  void cancelledStartupCannotCloseOrAdvanceSuccessorUse() {
    AutomaticQuickAnalysisTask first = task(false);
    first.start();
    PendingAcquisition old = environment.acquisitions.get(0);
    first.cancel(CancelReason.GAME_CHANGED);
    AutomaticQuickAnalysisTask second = task(false);
    second.start();
    PendingAcquisition current = environment.acquisitions.get(1);
    ControlledUse successor = new ControlledUse();
    current.deliver(successor);
    current.deliver(successor);
    ControlledUse late = new ControlledUse();
    old.deliver(late);
    old.deliver(late);
    old.deliver(null);

    assertEquals(1, old.cancellations);
    assertEquals(1, late.releases);
    assertEquals(0, late.requests);
    assertEquals(0, successor.releases);
    assertEquals(1, successor.requests);
    assertTrue(second.isActive());
    assertFalse(first.isActive());
    List<Settlement> settled = new ArrayList<>();
    first.whenSettled(settled::add);
    assertEquals(EndReason.CANCELLED, settled.get(0).reason());
  }

  private AutomaticQuickAnalysisTask task(boolean confirmed) {
    return new AutomaticQuickAnalysisTask(board, environment, listener, confirmed);
  }

  @Test
  void oldAttemptAndDuplicateCompletionCannotSettleNewAttempt() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    ControlledUse old = new ControlledUse();
    environment.acquisitions.get(0).deliver(old);
    old.finish(true, ForegroundRestoreResult.NOT_REQUIRED);
    old.finish(false, ForegroundRestoreResult.SUCCEEDED);
    assertEquals(1, old.releases);
    old.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    environment.advance(3600);
    ControlledUse current = new ControlledUse();
    environment.acquisitions.get(1).deliver(current);
    old.finish(false, ForegroundRestoreResult.SUCCEEDED);
    old.releaseFinished(ForegroundRestoreResult.FAILED);
    environment.acquisitions.get(0).deliver(old);
    assertEquals(1, old.releases);
    assertEquals(0, current.releases);
    assertTrue(task.isActive());
    assertTrue(listener.completions.isEmpty());
    task.cancel(CancelReason.USER_PAUSE);
    task.cancel(CancelReason.SHUTDOWN);
    assertEquals(1, current.releases);
    List<Settlement> outcomes = new ArrayList<>();
    task.whenSettled(outcomes::add);
    current.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    current.releaseFinished(ForegroundRestoreResult.FAILED);
    assertEquals(1, outcomes.size());
    assertEquals(EndReason.CANCELLED, outcomes.get(0).reason());
  }

  @Test
  void leaseAppearingAfterAcquisitionStaysVisibleUntilReleaseCompletes() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    assertFalse(task.requiresForegroundRestore());
    use.shared = true;
    assertTrue(task.requiresForegroundRestore());
    task.cancel(CancelReason.GAME_CHANGED);
    use.shared = false;
    assertTrue(task.requiresForegroundRestore());
    use.releaseFinished(ForegroundRestoreResult.SUCCEEDED);
    assertFalse(task.requiresForegroundRestore());
  }

  @Test
  void requestRestoreFailureSurvivesCleanupAndPause() {
    AutomaticQuickAnalysisTask task = task(true);
    task.start();
    ControlledUse use = new ControlledUse();
    use.shared = true;
    environment.acquisitions.get(0).deliver(use);
    use.finish(true, ForegroundRestoreResult.FAILED);
    assertTrue(task.requiresForegroundRestore());
    assertTrue(listener.opportunities.isEmpty());
    task.cancel(CancelReason.USER_PAUSE);
    List<Settlement> outcomes = new ArrayList<>();
    task.whenSettled(outcomes::add);
    assertTrue(outcomes.isEmpty());
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertEquals(ForegroundRestoreResult.FAILED, outcomes.get(0).restore());
    assertFalse(outcomes.get(0).positionAlreadyConfirmed());
    assertFalse(task.requiresForegroundRestore());
    assertTrue(listener.opportunities.isEmpty());
    assertTrue(listener.completions.isEmpty());
    environment.advance(100_000);
    assertEquals(1, environment.acquisitions.size());
  }

  @Test
  void failureOffersForegroundAfterReleaseWhileRetryRemainsActive() {
    AutomaticQuickAnalysisTask task = task(true);
    task.start();
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    use.finish(true, ForegroundRestoreResult.SUCCEEDED);
    assertTrue(listener.opportunities.isEmpty());
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertEquals(ForegroundRestoreResult.SUCCEEDED, listener.opportunities.get(0).restore());
    assertTrue(listener.opportunities.get(0).positionAlreadyConfirmed());
    assertTrue(task.isActive());
    assertTrue(task.isRetrying());
    assertTrue(listener.completions.isEmpty());
    environment.advance(3599);
    assertEquals(1, environment.acquisitions.size());
    environment.advance(1);
    assertEquals(2, environment.acquisitions.size());
  }

  @Test
  void pauseFromFailureOpportunityWinsOverRetry() {
    AutomaticQuickAnalysisTask task = task(false);
    listener.onFailure = ignored -> task.cancel(CancelReason.USER_PAUSE);
    task.start();
    environment.acquisitions.get(0).deliver(null);
    assertEquals(1, listener.opportunities.size());
    assertFalse(task.isActive());
    assertFalse(task.isRetrying());
    environment.advance(100_000);
    assertEquals(1, environment.acquisitions.size());
  }

  @Test
  void completingAllRealMovesAndPassesPreservesBrowsedNode() {
    BoardData move = board.getHistory().getData();
    BoardData pass =
        BoardData.pass(
            move.stones.clone(),
            Stone.WHITE,
            true,
            move.zobrist.clone(),
            2,
            new int[move.stones.length],
            0,
            0,
            50,
            0);
    board.getHistory().add(pass);
    BoardHistoryNode passNode = board.getHistory().getCurrentHistoryNode();
    AutomaticQuickAnalysisTask task = task(true);
    task.start();
    ControlledUse first = new ControlledUse();
    environment.acquisitions.get(0).deliver(first);
    analyzed(move);
    board.getHistory().previous();
    BoardHistoryNode browsed = board.getHistory().getCurrentHistoryNode();
    task.navigationChanged();
    first.finish(false, ForegroundRestoreResult.NOT_REQUIRED);
    first.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertTrue(task.isActive(), "the explicit PASS still lacks analysis");
    environment.advance(1800);
    ControlledUse second = new ControlledUse();
    environment.acquisitions.get(1).deliver(second);
    analyzed(passNode.getData());
    second.finish(false, ForegroundRestoreResult.SUCCEEDED);
    assertTrue(listener.completions.isEmpty());
    second.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertEquals(EndReason.COMPLETED, listener.completions.get(0).reason());
    assertEquals(ForegroundRestoreResult.SUCCEEDED, listener.completions.get(0).restore());
    assertFalse(listener.completions.get(0).positionAlreadyConfirmed());
    assertSame(browsed, board.getHistory().getCurrentHistoryNode());
    assertTrue(move.hasDisplayablePrimaryAnalysis());
    assertTrue(pass.hasDisplayablePrimaryAnalysis());
  }

  @Test
  void snapshotsSetupAndDummyPassDoNotRequireAnalysis() {
    analyzed(board.getHistory().getData());
    BoardData snapshot = BoardData.empty(Board.boardWidth, Board.boardHeight);
    snapshot.stones[1] = Stone.WHITE;
    snapshot.zobrist.toggleStone(0, 1, Stone.WHITE);
    snapshot.moveNumber = 17;
    snapshot.addProperty("AW", "ab");
    board.getHistory().add(snapshot);
    BoardData dummy =
        BoardData.pass(
            snapshot.stones.clone(),
            Stone.WHITE,
            true,
            snapshot.zobrist.clone(),
            18,
            new int[snapshot.stones.length],
            0,
            0,
            50,
            0);
    dummy.dummy = true;
    board.getHistory().add(dummy);
    BoardHistoryNode browsed = board.getHistory().getCurrentHistoryNode();
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    assertEquals(EndReason.COMPLETED, listener.completions.get(0).reason());
    assertTrue(environment.acquisitions.isEmpty());
    assertSame(browsed, board.getHistory().getCurrentHistoryNode());
    assertFalse(snapshot.hasDisplayablePrimaryAnalysis());
    assertFalse(dummy.hasDisplayablePrimaryAnalysis());
  }

  @Test
  void placeholderVisitCountDoesNotHideMissingAnalysis() {
    board.getHistory().getData().setPlayouts(500);
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    assertTrue(task.isActive());
    assertEquals(1, environment.acquisitions.size());
    assertTrue(listener.completions.isEmpty());
  }

  @Test
  void navigationDebouncesIdleRetryAndInvalidatesConfirmationPermanently() {
    AutomaticQuickAnalysisTask task = task(true);
    task.start();
    environment.acquisitions.get(0).deliver(null);
    environment.advance(100);
    board.getHistory().previous();
    task.navigationChanged();
    environment.advance(699);
    board.getHistory().next();
    task.navigationChanged();
    environment.advance(699);
    assertEquals(1, environment.acquisitions.size());
    environment.advance(1);
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(1).deliver(use);
    analyzed(board.getHistory().getData());
    use.finish(false, ForegroundRestoreResult.NOT_REQUIRED);
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertFalse(listener.completions.get(0).positionAlreadyConfirmed());
  }

  @Test
  void replacingForegroundInvalidatesPreviouslyConfirmedPosition() {
    AutomaticQuickAnalysisTask task = task(true);
    task.start();
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    environment.foreground = new Object();
    analyzed(board.getHistory().getData());
    use.finish(false, ForegroundRestoreResult.NOT_REQUIRED);
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertFalse(listener.completions.get(0).positionAlreadyConfirmed());
  }

  @Test
  void manualFlashTransfersUseAndLateAutomaticCompletionDoesNotReleaseItAgain() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    task.cancel(CancelReason.MANUAL_FLASH);
    assertTrue(use.transfer);
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    use.finish(false, ForegroundRestoreResult.SUCCEEDED);
    task.cancel(CancelReason.USER_PAUSE);
    assertEquals(1, use.releases);
    assertTrue(listener.completions.isEmpty());
    assertTrue(listener.opportunities.isEmpty());
  }

  @Test
  void watchdogLeavesLiveStartupAndRequestAloneButRetriesAbandonedDispatch() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    PendingAcquisition pending = environment.acquisitions.get(0);
    environment.advance(30_000);
    assertEquals(0, pending.cancellations);
    ControlledUse use = new ControlledUse();
    pending.deliver(use);
    environment.advance(30_000);
    assertEquals(0, use.releases);
    use.progressing = false;
    environment.advance(30_000);
    assertEquals(1, use.releases);
    assertTrue(listener.opportunities.isEmpty());
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertEquals(1, listener.opportunities.size());
    environment.advance(3600);
    assertEquals(2, environment.acquisitions.size());
  }

  @Test
  void abandonedAcquisitionTimesOutAndReleasesOnlyItsLateUse() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    PendingAcquisition old = environment.acquisitions.get(0);
    old.progressing = false;
    environment.advance(29_999);
    assertEquals(0, old.cancellations);
    environment.advance(1);
    assertEquals(1, old.cancellations);
    environment.advance(3600);
    ControlledUse late = new ControlledUse();
    old.deliver(late);
    assertEquals(1, late.releases);
    assertTrue(task.isActive());
    assertEquals(0, environment.acquisitions.get(1).cancellations);
  }

  @Test
  void readinessWaitDoesNotIncreaseFailureBackoffAndStopSettlesUnavailable() {
    environment.readiness = Readiness.WAIT;
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    environment.advance(1800);
    assertTrue(environment.acquisitions.isEmpty());
    environment.readiness = Readiness.READY;
    environment.advance(1800);
    int[] delays = {3600, 7200, 14400, 28800, 28800};
    for (int index = 0; index < delays.length; index++) {
      environment.acquisitions.get(index).deliver(null);
      environment.advance(delays[index] - 1);
      assertEquals(index + 1, environment.acquisitions.size());
      environment.advance(1);
      assertEquals(index + 2, environment.acquisitions.size());
    }
    ControlledUse last = new ControlledUse();
    environment.acquisitions.get(delays.length).deliver(last);
    last.finish(false, ForegroundRestoreResult.NOT_REQUIRED);
    environment.readiness = Readiness.STOP;
    last.releaseFinished(ForegroundRestoreResult.FAILED);
    assertEquals(EndReason.UNAVAILABLE, listener.completions.get(0).reason());
    assertEquals(ForegroundRestoreResult.FAILED, listener.completions.get(0).restore());
    assertFalse(task.isActive());
  }

  @Test
  void constructorDoesNotAcquireAndPreStartCancellationCannotReviveTask() {
    AutomaticQuickAnalysisTask task = task(true);
    assertTrue(environment.acquisitions.isEmpty());
    assertTrue(environment.timers.isEmpty());
    assertFalse(task.isActive());
    List<Settlement> outcomes = new ArrayList<>();
    task.whenSettled(outcomes::add);
    task.cancel(CancelReason.USER_PAUSE);
    task.start();
    task.start();
    task.cancel(CancelReason.SHUTDOWN);
    assertTrue(environment.acquisitions.isEmpty());
    assertEquals(1, outcomes.size());
    assertEquals(EndReason.CANCELLED, outcomes.get(0).reason());
  }

  @Test
  void synchronousHandoffAndCompletionDoNotLeaveStaleWatchdogOrRegistration() {
    AutomaticQuickAnalysisTask task = task(true);
    ControlledUse use = new ControlledUse();
    use.immediateResult = new RequestResult(false, ForegroundRestoreResult.SUCCEEDED);
    use.immediateRelease = ForegroundRestoreResult.NOT_REQUIRED;
    environment.immediateUse = use;
    // Start still sees missing work; the engine result fills the real Board before completion.
    environment.onAcquired = () -> analyzed(board.getHistory().getData());
    task.start();
    task.start();
    assertEquals(EndReason.COMPLETED, listener.completions.get(0).reason());
    assertEquals(1, use.releases);
    assertEquals(1, environment.acquisitions.get(0).cancellations);
    environment.advance(100_000);
    assertEquals(1, environment.acquisitions.size());
    assertEquals(1, listener.completions.size());
  }

  @Test
  void backgroundCompletionCannotBeatQueuedPauseAndLateObserversRunOnEdt() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    environment.queueEdt = true;
    task.cancel(CancelReason.USER_PAUSE);
    use.finish(false, ForegroundRestoreResult.SUCCEEDED);
    environment.drainEdt();
    assertEquals(1, use.releases);
    assertTrue(listener.completions.isEmpty());
    use.releaseFinished(ForegroundRestoreResult.FAILED);
    environment.drainEdt();
    List<Settlement> outcomes = new ArrayList<>();
    task.whenSettled(outcomes::add);
    assertTrue(outcomes.isEmpty());
    environment.drainEdt();
    assertEquals(ForegroundRestoreResult.FAILED, outcomes.get(0).restore());
    assertEquals(EndReason.CANCELLED, outcomes.get(0).reason());
  }

  @Test
  void cancellingOnePendingRegistrationLeavesOtherPendingTaskIntact() {
    AutomaticQuickAnalysisTask first = task(false);
    AutomaticQuickAnalysisTask second = task(false);
    first.start();
    second.start();
    first.cancel(CancelReason.ENGINE_SWITCH);
    assertEquals(1, environment.acquisitions.get(0).cancellations);
    assertEquals(0, environment.acquisitions.get(1).cancellations);
    ControlledUse owned = new ControlledUse();
    environment.acquisitions.get(1).deliver(owned);
    assertEquals(1, owned.requests);
    assertEquals(0, owned.releases);
    assertTrue(second.isActive());
  }

  @Test
  void failedRestoreIsReportedBeforeRetryEvenWhenCleanupNeedsNoRestore() {
    AutomaticQuickAnalysisTask task = task(true);
    task.start();
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    use.finish(true, ForegroundRestoreResult.FAILED);
    use.releaseFinished(ForegroundRestoreResult.NOT_REQUIRED);
    assertEquals(ForegroundRestoreResult.FAILED, listener.opportunities.get(0).restore());
    assertFalse(listener.opportunities.get(0).positionAlreadyConfirmed());
    assertTrue(task.isActive());
    environment.advance(3600);
    assertEquals(2, environment.acquisitions.size());
  }

  @Test
  void requestWatchdogStartsAtHandoffRatherThanEarlierStartup() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    environment.advance(29_000);
    ControlledUse use = new ControlledUse();
    environment.acquisitions.get(0).deliver(use);
    use.progressing = false;
    environment.advance(29_999);
    assertEquals(0, use.releases);
    environment.advance(1);
    assertEquals(1, use.releases);
  }

  @Test
  void synchronousTimerDispatchAndStaleTimerCannotRestartCancelledTask() {
    AutomaticQuickAnalysisTask task = task(false);
    task.start();
    environment.inlineNextSchedule = true;
    environment.acquisitions.get(0).deliver(null);
    assertEquals(2, environment.acquisitions.size());
    task.cancel(CancelReason.SHUTDOWN);
    for (Scheduled scheduled : new ArrayList<>(environment.timers)) scheduled.action.run();
    assertEquals(2, environment.acquisitions.size());
    assertEquals(1, environment.acquisitions.get(1).cancellations);
    assertFalse(task.isActive());
  }

  private BoardData appendMove() {
    BoardData previous = board.getHistory().getData();
    Stone[] stones = previous.stones.clone();
    stones[0] = Stone.BLACK;
    var zobrist = previous.zobrist.clone();
    zobrist.toggleStone(0, 0, Stone.BLACK);
    BoardData move =
        BoardData.move(
            stones,
            new int[] {0, 0},
            Stone.BLACK,
            false,
            zobrist,
            previous.moveNumber + 1,
            new int[stones.length],
            0,
            0,
            50,
            0);
    board.getHistory().add(move);
    return move;
  }

  private static void analyzed(BoardData data) {
    data.setPlayouts(500);
    data.winrate = 61;
    data.analysisHeaderSlots = 3;
  }

  private static final class RecordingListener implements AutomaticQuickAnalysisTask.Listener {
    private final List<Opportunity> opportunities = new ArrayList<>();
    private final List<Settlement> completions = new ArrayList<>();
    private Consumer<Opportunity> onFailure = ignored -> {};

    @Override
    public void failedAttempt(Opportunity opportunity) {
      opportunities.add(opportunity);
      onFailure.accept(opportunity);
    }

    @Override
    public void completed(Settlement settlement) {
      completions.add(settlement);
    }
  }

  private static final class ControlledEnvironment
      implements AutomaticQuickAnalysisTask.Environment {
    private final List<PendingAcquisition> acquisitions = new ArrayList<>();
    private final List<Scheduled> timers = new ArrayList<>();
    private final List<Runnable> edt = new ArrayList<>();
    private Readiness readiness = Readiness.READY;
    private Object foreground = new Object();
    private long now;
    private boolean queueEdt;
    private ControlledUse immediateUse;
    private Runnable onAcquired = () -> {};
    private boolean inlineNextSchedule;

    @Override
    public Readiness readiness() {
      return readiness;
    }

    @Override
    public Object foregroundIdentity() {
      return foreground;
    }

    @Override
    public PendingAcquisition acquire(Consumer<AutomaticQuickAnalysisTask.Use> callback) {
      PendingAcquisition pending = new PendingAcquisition(callback);
      acquisitions.add(pending);
      onAcquired.run();
      if (immediateUse != null) pending.deliver(immediateUse);
      return pending;
    }

    @Override
    public Scheduled schedule(int delayMillis, Runnable action) {
      Scheduled scheduled = new Scheduled(now + delayMillis, action);
      timers.add(scheduled);
      if (inlineNextSchedule) {
        inlineNextSchedule = false;
        now = scheduled.at;
        scheduled.action.run();
      }
      return scheduled;
    }

    @Override
    public long nowMillis() {
      return now;
    }

    @Override
    public void onEdt(Runnable action) {
      if (queueEdt) edt.add(action);
      else action.run();
    }

    private void drainEdt() {
      while (!edt.isEmpty()) edt.remove(0).run();
    }

    private void advance(long millis) {
      long target = now + millis;
      while (true) {
        Scheduled next =
            timers.stream()
                .filter(timer -> !timer.cancelled && timer.at <= target)
                .min(Comparator.comparingLong(timer -> timer.at))
                .orElse(null);
        if (next == null) break;
        now = next.at;
        next.cancelled = true;
        next.action.run();
      }
      now = target;
    }
  }

  private static final class Scheduled implements AutomaticQuickAnalysisTask.Cancellable {
    private final long at;
    private final Runnable action;
    private boolean cancelled;

    private Scheduled(long at, Runnable action) {
      this.at = at;
      this.action = action;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }

  private static final class PendingAcquisition implements AutomaticQuickAnalysisTask.Registration {
    private final Consumer<AutomaticQuickAnalysisTask.Use> callback;
    private boolean progressing = true;
    private int cancellations;

    private PendingAcquisition(Consumer<AutomaticQuickAnalysisTask.Use> callback) {
      this.callback = callback;
    }

    private void deliver(ControlledUse use) {
      callback.accept(use);
    }

    @Override
    public boolean inProgress() {
      return progressing;
    }

    @Override
    public void cancel() {
      cancellations++;
    }
  }

  private static final class ControlledUse implements AutomaticQuickAnalysisTask.Use {
    private Consumer<RequestResult> result;
    private Consumer<ForegroundRestoreResult> released;
    private boolean progressing = true;
    private boolean shared;
    private boolean transfer;
    private int requests;
    private int releases;
    private RequestResult immediateResult;
    private ForegroundRestoreResult immediateRelease;

    @Override
    public void requestMissing(Consumer<RequestResult> finished) {
      requests++;
      result = finished;
      if (immediateResult != null) finished.accept(immediateResult);
    }

    @Override
    public boolean inProgress() {
      return progressing;
    }

    @Override
    public boolean requiresForegroundRestore() {
      return shared;
    }

    @Override
    public void release(boolean transfer, Consumer<ForegroundRestoreResult> finished) {
      releases++;
      this.transfer = transfer;
      released = finished;
      if (immediateRelease != null) finished.accept(immediateRelease);
    }

    private void finish(boolean failed, ForegroundRestoreResult restore) {
      progressing = false;
      result.accept(new RequestResult(failed, restore));
    }

    private void releaseFinished(ForegroundRestoreResult restore) {
      released.accept(restore);
    }
  }
}
