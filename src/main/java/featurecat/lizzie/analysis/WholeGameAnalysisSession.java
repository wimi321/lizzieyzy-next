package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.io.IOException;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Coordinates the quick-overview and deep-analysis stages without blocking board navigation. */
public final class WholeGameAnalysisSession {
  public enum State {
    IDLE,
    PREPARING,
    BASELINE,
    DEEP,
    COMPLETE,
    CANCELLED,
    FAILED
  }

  @FunctionalInterface
  public interface Listener {
    void onSnapshot(Snapshot snapshot);
  }

  public static final class Snapshot {
    public final State state;
    public final int overallPercent;
    public final int completedPositions;
    public final int totalPositions;
    public final int targetVisits;
    public final long estimatedRemainingMillis;
    public final boolean remoteBackend;
    public final String detailKey;

    private Snapshot(
        State state,
        int overallPercent,
        int completedPositions,
        int totalPositions,
        int targetVisits,
        long estimatedRemainingMillis,
        boolean remoteBackend,
        String detailKey) {
      this.state = state;
      this.overallPercent = overallPercent;
      this.completedPositions = completedPositions;
      this.totalPositions = totalPositions;
      this.targetVisits = targetVisits;
      this.estimatedRemainingMillis = estimatedRemainingMillis;
      this.remoteBackend = remoteBackend;
      this.detailKey = detailKey;
    }
  }

  private static final int BASELINE_PERCENT_WEIGHT = 20;
  private static final int DEEP_PERCENT_WEIGHT = 80;

  private final LizzieFrame frame;
  private final WholeGameAnalysisPlan plan;
  private final Listener listener;
  private volatile State state = State.IDLE;
  private volatile boolean terminal;
  private AnalysisEngine engine;
  private boolean remoteBackend;
  private boolean resumeForegroundAnalysis;
  private int baselineCompleted;
  private int deepCompleted;
  private int stageInitialCompleted;
  private int stageCompletedByEngine;
  private int lastTargetVisits;
  private long stageStartedAtMillis;
  private Timer gameGuardTimer;

  public WholeGameAnalysisSession(
      LizzieFrame frame, WholeGameAnalysisPlan plan, Listener listener) {
    this.frame = frame;
    this.plan = plan;
    this.listener = listener;
    baselineCompleted = plan.completedAtLeast(plan.baselineVisits());
    deepCompleted = plan.completedAtLeast(plan.deepVisits());
  }

  public void start() {
    synchronized (this) {
      if (state != State.IDLE || terminal) {
        return;
      }
      state = State.PREPARING;
    }
    publish("WholeGameAnalysis.preparing", 0, 0, -1L);
    Thread starter =
        new Thread(
            () -> {
              try {
                AnalysisEngine created =
                    new AnalysisEngine(true, AnalysisEngine.Workload.WHOLE_GAME, plan.deepVisits());
                SwingUtilities.invokeLater(() -> acceptEngine(created));
              } catch (IOException | RuntimeException ex) {
                ex.printStackTrace();
                fail("WholeGameAnalysis.error.engine");
              }
            },
            "whole-game-analysis-engine-starter");
    starter.setDaemon(true);
    starter.start();
  }

  public synchronized State state() {
    return state;
  }

  public synchronized boolean isRunning() {
    return !terminal
        && (state == State.PREPARING || state == State.BASELINE || state == State.DEEP);
  }

  public void cancel() {
    finish(State.CANCELLED, "WholeGameAnalysis.cancelled");
  }

  private void acceptEngine(AnalysisEngine created) {
    if (isTerminal()) {
      closeEngine(created);
      return;
    }
    if (!currentGameMatches()) {
      closeEngine(created);
      fail("WholeGameAnalysis.error.gameChanged");
      return;
    }
    if (!created.isLoaded()) {
      closeEngine(created);
      fail("WholeGameAnalysis.error.engine");
      return;
    }
    engine = created;
    remoteBackend = created.usesRemoteBackend();
    frame.attachWholeGameAnalysisEngine(this, created);
    if (!created.usesSharedForegroundEngine()
        && Lizzie.leelaz != null
        && Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.notPondering();
      Lizzie.leelaz.nameCmd();
      resumeForegroundAnalysis = true;
    }
    startGameGuard();
    beginBaselineStage();
  }

  private void beginBaselineStage() {
    if (!currentGameMatches()) {
      fail("WholeGameAnalysis.error.gameChanged");
      return;
    }
    List<BoardHistoryNode> pending = plan.positionsBelow(plan.baselineVisits());
    baselineCompleted = plan.positionCount() - pending.size();
    if (pending.isEmpty()) {
      baselineCompleted = plan.positionCount();
      beginDeepStage();
      return;
    }
    state = State.BASELINE;
    dispatchStage(pending, plan.baselineVisits(), false, this::finishBaselineStage);
  }

  private void finishBaselineStage() {
    if (isTerminal()) {
      return;
    }
    baselineCompleted = plan.positionCount();
    beginDeepStage();
  }

  private void beginDeepStage() {
    if (!currentGameMatches()) {
      fail("WholeGameAnalysis.error.gameChanged");
      return;
    }
    List<BoardHistoryNode> pending = plan.positionsBelow(plan.deepVisits());
    deepCompleted = plan.positionCount() - pending.size();
    if (pending.isEmpty()) {
      deepCompleted = plan.positionCount();
      finish(State.COMPLETE, "WholeGameAnalysis.complete");
      return;
    }
    state = State.DEEP;
    boolean includeOwnership = Lizzie.config != null && Lizzie.config.showKataGoEstimate;
    dispatchStage(pending, plan.deepVisits(), includeOwnership, this::finishDeepStage);
  }

  private void finishDeepStage() {
    if (isTerminal()) {
      return;
    }
    deepCompleted = plan.positionCount();
    finish(State.COMPLETE, "WholeGameAnalysis.complete");
  }

  private void dispatchStage(
      List<BoardHistoryNode> pending,
      int targetVisits,
      boolean includeOwnership,
      Runnable successfulCompletion) {
    AnalysisEngine activeEngine = engine;
    if (activeEngine == null) {
      fail("WholeGameAnalysis.error.engine");
      return;
    }
    stageInitialCompleted = plan.positionCount() - pending.size();
    stageCompletedByEngine = 0;
    lastTargetVisits = targetVisits;
    stageStartedAtMillis = System.currentTimeMillis();
    publish(stageDetailKey(), stageInitialCompleted, targetVisits, -1L);
    // The session owns engine shutdown so completion callbacks cannot race process teardown.
    activeEngine.setKeepAliveAfterCurrentRequest(true);
    activeEngine.setProgressListener(this::onEngineProgress);
    activeEngine.setFailureCallback(this::onEngineFailure);
    activeEngine.setCompletionCallback(successfulCompletion);

    Thread dispatcher =
        new Thread(
            () -> {
              int count =
                  activeEngine.startWholeGameRequest(pending, targetVisits, includeOwnership);
              if (count < 0) {
                SwingUtilities.invokeLater(this::onEngineFailure);
              } else if (count == 0) {
                activeEngine.clearRequestCallbacks();
                SwingUtilities.invokeLater(successfulCompletion);
              }
            },
            "whole-game-analysis-request-dispatcher");
    dispatcher.setDaemon(true);
    dispatcher.start();
  }

  private void onEngineProgress(int completed, int total) {
    if (isTerminal()) {
      return;
    }
    stageCompletedByEngine = Math.max(stageCompletedByEngine, completed);
    int completedPositions =
        Math.min(plan.positionCount(), stageInitialCompleted + stageCompletedByEngine);
    if (state == State.BASELINE) {
      baselineCompleted = completedPositions;
    } else if (state == State.DEEP) {
      deepCompleted = completedPositions;
    }
    long elapsed = Math.max(1L, System.currentTimeMillis() - stageStartedAtMillis);
    long remaining = -1L;
    if (stageCompletedByEngine > 0) {
      int remainingRequests = Math.max(0, total - stageCompletedByEngine);
      remaining = elapsed * remainingRequests / stageCompletedByEngine;
    }
    publish(stageDetailKey(), completedPositions, currentTargetVisits(), remaining);
  }

  private void onEngineFailure() {
    fail(
        currentGameMatches()
            ? "WholeGameAnalysis.error.request"
            : "WholeGameAnalysis.error.gameChanged");
  }

  private void fail(String detailKey) {
    finish(State.FAILED, detailKey);
  }

  private void finish(State terminalState, String detailKey) {
    AnalysisEngine engineToClose;
    boolean shouldResume;
    State previousState;
    synchronized (this) {
      if (terminal) {
        return;
      }
      terminal = true;
      previousState = state;
      state = terminalState;
      engineToClose = engine;
      shouldResume = resumeForegroundAnalysis;
    }
    stopGameGuard();
    if (engineToClose != null) {
      engineToClose.clearRequestCallbacks();
      closeEngine(engineToClose);
    }
    int completedForSnapshot;
    if (terminalState == State.COMPLETE) {
      baselineCompleted = plan.positionCount();
      deepCompleted = plan.positionCount();
      completedForSnapshot = plan.positionCount();
    } else if (previousState == State.BASELINE) {
      completedForSnapshot = baselineCompleted;
    } else {
      completedForSnapshot = deepCompleted;
    }
    publish(detailKey, completedForSnapshot, lastTargetVisits, 0L);
    SwingUtilities.invokeLater(
        () -> frame.onWholeGameAnalysisFinished(this, engineToClose, shouldResume));
  }

  private void closeEngine(AnalysisEngine engineToClose) {
    if (engineToClose == null) {
      return;
    }
    Thread closer =
        new Thread(
            () -> {
              try {
                engineToClose.normalQuit();
              } catch (RuntimeException ex) {
                ex.printStackTrace();
              }
            },
            "whole-game-analysis-engine-closer");
    closer.setDaemon(true);
    closer.start();
  }

  private void startGameGuard() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::startGameGuard);
      return;
    }
    gameGuardTimer =
        new Timer(
            500,
            event -> {
              if (!currentGameMatches()) {
                fail("WholeGameAnalysis.error.gameChanged");
              }
            });
    gameGuardTimer.start();
  }

  private void stopGameGuard() {
    Timer timer = gameGuardTimer;
    gameGuardTimer = null;
    if (timer == null) {
      return;
    }
    if (SwingUtilities.isEventDispatchThread()) {
      timer.stop();
    } else {
      SwingUtilities.invokeLater(timer::stop);
    }
  }

  private boolean currentGameMatches() {
    return Lizzie.board != null
        && Lizzie.board.getHistory() != null
        && plan.stillMatches(Lizzie.board.getHistory().getStart());
  }

  private synchronized boolean isTerminal() {
    return terminal;
  }

  private int currentTargetVisits() {
    if (state == State.BASELINE) {
      return plan.baselineVisits();
    }
    return plan.deepVisits();
  }

  private String stageDetailKey() {
    return state == State.BASELINE ? "WholeGameAnalysis.baseline" : "WholeGameAnalysis.deep";
  }

  private void publish(
      String detailKey, int completedPositions, int targetVisits, long remainingMillis) {
    int percent = overallPercent(plan.positionCount(), baselineCompleted, deepCompleted, state);
    Snapshot snapshot =
        new Snapshot(
            state,
            percent,
            Math.max(0, Math.min(plan.positionCount(), completedPositions)),
            plan.positionCount(),
            targetVisits,
            remainingMillis,
            remoteBackend,
            detailKey);
    if (SwingUtilities.isEventDispatchThread()) {
      listener.onSnapshot(snapshot);
    } else {
      SwingUtilities.invokeLater(() -> listener.onSnapshot(snapshot));
    }
  }

  static int overallPercent(
      int totalPositions, int baselineCompleted, int deepCompleted, State state) {
    if (state == State.COMPLETE) {
      return 100;
    }
    if (totalPositions <= 0 || state == State.IDLE || state == State.PREPARING) {
      return 0;
    }
    double baselineRatio =
        Math.max(0.0, Math.min(1.0, baselineCompleted / (double) totalPositions));
    double deepRatio = Math.max(0.0, Math.min(1.0, deepCompleted / (double) totalPositions));
    return Math.min(
        99,
        (int)
            Math.floor(baselineRatio * BASELINE_PERCENT_WEIGHT + deepRatio * DEEP_PERCENT_WEIGHT));
  }
}
