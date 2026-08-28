package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingSession;
import featurecat.lizzie.training.OpponentPreset;
import featurecat.lizzie.util.Utils;
import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one HumanSL coaching game, optionally alongside normal foreground analysis. */
public final class HumanSlGameController {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.APP);
  private static final int AI_MOVE_RETRIES = 2;
  private static final int FOREGROUND_RESTORE_ATTEMPTS = 2;
  private static final int PRIMARY_RESYNC_STABILITY_ATTEMPTS = 3;
  private static final long MIN_MOVE_DELAY_MILLIS = 800L;
  private static final long MAX_MOVE_DELAY_MILLIS = 4000L;
  private final HumanSlAnalysisRunner runner;
  private final HumanSlTrainingConfig config;
  private final HumanSlTrainingSession trainingSession;
  private final boolean humanIsBlack;
  private final String profile;
  private final Duration moveTimeout;
  private final ExecutorService gameExecutor = Executors.newSingleThreadExecutor();
  private final AtomicLong requestGeneration = new AtomicLong();

  private boolean candidatesBlackBefore;
  private boolean candidatesWhiteBefore;
  private boolean analyzeBlackBefore;
  private boolean analyzeWhiteBefore;
  private boolean showWinrateGraphBefore;
  private boolean showWinrateInSuggestionBefore;
  private boolean showKataGoEstimateBefore;
  private boolean showingPolicyBefore;
  private boolean showingHeatmapBefore;
  private boolean analysisVisualsCaptured;
  private volatile Leelaz temporaryLiveAnalysisEngine;
  private volatile ForegroundAnalysisPause.RestoreLease foregroundAnalysisRestoreLease =
      ForegroundAnalysisPause.RestoreLease.inactive();
  /** Stops coaching work immediately without claiming that resource teardown has completed. */
  private volatile boolean finished;
  /** Published only after close, exact replay, foreground restore, and UI cleanup all succeed. */
  private volatile boolean teardownComplete;
  private volatile boolean aiThinking;
  private volatile boolean aiFailed;
  private volatile boolean exitInProgress;
  private volatile boolean exitRecoveryPending;
  private volatile long humanElapsedMillis;
  private volatile long aiElapsedMillis;
  private volatile long turnStartedAt;
  private BoardHistoryNode trainingStartNode;
  private Runnable successfulExitCompletion;
  private Runnable successfulExitContinuation;
  private Consumer<Runnable> exitBackgroundDispatcher = HumanSlGameController::dispatchExitWorker;
  private Consumer<Runnable> exitCompletionDispatcher = SwingUtilities::invokeLater;
  private Supplier<BooleanSupplier> primaryEngineResyncPreparation =
      HumanSlGameController::preparePrimaryEngineResync;
  private Runnable successfulExitCompletionOverride;
  private BooleanSupplier failedStartPrimaryResync;

  /** Exact in-memory state replaced by {@link Board#clear(boolean)} for a new coaching game. */
  private static final class StartBoardSnapshot {
    private final Board board;
    private final Board.ClearStateSnapshot boardState;
    private final File currentFile;
    private final String fileNameTitle;
    private final ReadBoard readBoard;
    private final boolean readBoardFirstSync;
    private final Leelaz foregroundEngine;
    private final boolean outOfPlayoutsLimit;
    private final boolean stopByPlayouts;
    private final WinrateGraph winrateGraph;
    private final double maxScoreLead;
    private final LizzieFrame frame;
    private final boolean wasTrying;

    private StartBoardSnapshot(Board board) {
      this.board = board;
      boardState = board.captureClearState();
      currentFile = LizzieFrame.curFile;
      fileNameTitle = LizzieFrame.fileNameTitle;
      readBoard = Lizzie.frame == null ? null : Lizzie.frame.readBoard;
      readBoardFirstSync = readBoard != null && readBoard.firstSync;
      foregroundEngine = Lizzie.leelaz;
      outOfPlayoutsLimit = foregroundEngine != null && foregroundEngine.outOfPlayoutsLimit;
      stopByPlayouts = foregroundEngine != null && foregroundEngine.stopByPlayouts;
      winrateGraph = LizzieFrame.winrateGraph;
      maxScoreLead = winrateGraph == null ? 0.0 : winrateGraph.maxScoreLeadForModeHandoff();
      frame = Lizzie.frame;
      wasTrying = frame != null && frame.isTrying;
    }

    static StartBoardSnapshot capture() {
      if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
        throw new IllegalStateException("Cannot start AI Coach without an active board history.");
      }
      return new StartBoardSnapshot(Lizzie.board);
    }

    void restore() {
      Throwable restoreFailure = null;
      try {
        if (Lizzie.board != board) {
          throw new IllegalStateException("The active board changed while AI Coach was starting.");
        }
        board.restoreClearState(boardState);
      } catch (RuntimeException | Error failure) {
        restoreFailure = failure;
      } finally {
        LizzieFrame.curFile = currentFile;
        LizzieFrame.fileNameTitle = fileNameTitle;
        if (readBoard != null
            && Lizzie.frame != null
            && Lizzie.frame.readBoard == readBoard) {
          readBoard.firstSync = readBoardFirstSync;
        }
        if (foregroundEngine != null && Lizzie.leelaz == foregroundEngine) {
          foregroundEngine.outOfPlayoutsLimit = outOfPlayoutsLimit;
          foregroundEngine.stopByPlayouts = stopByPlayouts;
        }
        if (winrateGraph != null && LizzieFrame.winrateGraph == winrateGraph) {
          winrateGraph.restoreMaxScoreLeadAfterFailedModeHandoff(maxScoreLead);
        }
        if (frame != null && Lizzie.frame == frame) {
          frame.isTrying = wasTrying;
        }
        LizzieFrame.forceRecreate = true;
      }
      try {
        if (Lizzie.frame != null) {
          Lizzie.frame.updateTitle();
          Lizzie.frame.refresh();
        }
      } catch (RuntimeException | Error failure) {
        if (restoreFailure == null) {
          restoreFailure = failure;
        } else if (restoreFailure != failure) {
          restoreFailure.addSuppressed(failure);
        }
      }
      if (restoreFailure instanceof RuntimeException) {
        throw (RuntimeException) restoreFailure;
      }
      if (restoreFailure instanceof Error) {
        throw (Error) restoreFailure;
      }
    }
  }

  public HumanSlGameController(
      HumanSlAnalysisRunner runner,
      HumanSlTrainingConfig config,
      HumanSlTrainingSession trainingSession) {
    this.runner = runner;
    this.config = config;
    this.trainingSession = trainingSession == null ? new HumanSlTrainingSession() : trainingSession;
    humanIsBlack = config.resolveHumanIsBlack();
    profile = config.humanSlProfile();
    moveTimeout = Duration.ofSeconds(Math.max(2, config.moveTimeSeconds));
  }

  /** Compatibility constructor for existing integrations and tests. */
  public HumanSlGameController(
      HumanSlAnalysisRunner runner,
      String profile,
      boolean humanIsBlack,
      int handicap,
      double komi,
      int moveTimeoutSeconds) {
    this(
        runner,
        legacyConfig(profile, humanIsBlack, handicap, komi, moveTimeoutSeconds),
        new HumanSlTrainingSession());
  }

  public boolean isFinished() {
    return teardownComplete;
  }

  boolean isStopRequested() {
    return finished;
  }

  boolean isExitInProgress() {
    return exitInProgress;
  }

  boolean isExitRecoveryPending() {
    return exitRecoveryPending;
  }

  void setExitLifecycleForTesting(
      Consumer<Runnable> backgroundDispatcher,
      Consumer<Runnable> completionDispatcher,
      BooleanSupplier resync,
      Runnable successCompletionOverride) {
    setExitLifecyclePreparationForTesting(
        backgroundDispatcher,
        completionDispatcher,
        () -> resync,
        successCompletionOverride);
  }

  void setExitLifecyclePreparationForTesting(
      Consumer<Runnable> backgroundDispatcher,
      Consumer<Runnable> completionDispatcher,
      Supplier<BooleanSupplier> resyncPreparation,
      Runnable successCompletionOverride) {
    exitBackgroundDispatcher = backgroundDispatcher;
    exitCompletionDispatcher = completionDispatcher;
    primaryEngineResyncPreparation = resyncPreparation;
    successfulExitCompletionOverride = successCompletionOverride;
  }

  void setExitDispatchersForTesting(
      Consumer<Runnable> backgroundDispatcher,
      Consumer<Runnable> completionDispatcher,
      Runnable successCompletionOverride) {
    exitBackgroundDispatcher = backgroundDispatcher;
    exitCompletionDispatcher = completionDispatcher;
    successfulExitCompletionOverride = successCompletionOverride;
  }

  public boolean isHumanTurn() {
    return Lizzie.board.getHistory().isBlacksTurn() == humanIsBlack;
  }

  public boolean isAiThinking() {
    return aiThinking;
  }

  public boolean hasAiFailure() {
    return aiFailed;
  }

  public boolean isReviewing() {
    return trainingSession.state() == HumanSlTrainingSession.State.REVIEWING;
  }

  public boolean isLiveAnalysisMode() {
    return config.mode.isLiveAnalysis();
  }

  public long humanElapsedMillis() {
    return humanElapsedMillis + liveElapsed(true);
  }

  public long aiElapsedMillis() {
    return aiElapsedMillis + liveElapsed(false);
  }

  public String opponentLabel() {
    switch (config.opponentPreset) {
      case MODERN_PRO:
        return text("HumanSlTraining.pro.modern", "Modern pro style");
      case ONLINE_9D:
        return text("HumanSlTraining.pro.online9d", "Online 9 dan");
      case RANK:
      default:
        return rankLabel(config.rank, config.danRank);
    }
  }

  /** Compatibility accessor retained after resign became identical to a normal finish. */
  @Deprecated
  public String gameResult() {
    return "";
  }

  /** Sets up the board and starts the game. Must be called on the EDT. */
  public void start() {
    ForegroundAnalysisPause pause = ForegroundAnalysisPause.pauseCurrent();
    start(pause.transferRestoreResponsibility());
  }

  /** Starts after setup has already paused foreground analysis to free GPU resources. */
  void start(ForegroundAnalysisPause.RestoreLease restoreLease) {
    startInternal(restoreLease, false);
  }

  /** Dialog handoff variant: the dialog owns worker close/resync/lease recovery on failure. */
  void startWithExternalFailureCleanup(ForegroundAnalysisPause.RestoreLease restoreLease) {
    startInternal(restoreLease, true);
  }

  private void startInternal(
      ForegroundAnalysisPause.RestoreLease restoreLease, boolean externalFailureCleanup) {
    failedStartPrimaryResync = null;
    foregroundAnalysisRestoreLease =
        restoreLease == null
            ? ForegroundAnalysisPause.RestoreLease.inactive()
            : restoreLease;
    HumanSlTrainingSession.State previousSessionState = trainingSession.state();
    StartBoardSnapshot boardSnapshot = null;
    try {
      if (LizzieFrame.urlSgf) {
        throw new IllegalStateException(
            text(
                "HumanSlGame.error.liveUrlSgfActive",
                "AI Coach cannot start while URL-SGF live sync is active. Stop live sync first, then try again."));
      }
      if (config.fromCurrentPosition) {
        // Keep the original SGF metadata and main line untouched. The first training move is forced
        // into a variation from this node, including after "retry this move" returns here.
        trainingStartNode = Lizzie.board.getHistory().getCurrentHistoryNode();
      } else {
        boardSnapshot = StartBoardSnapshot.capture();
        Lizzie.board.clear(false);
        Lizzie.board.getHistory().getGameInfo().setKomi(config.komi);
        Lizzie.board.getHistory().getGameInfo().setHandicap(config.handicap);
        if (config.handicap >= 2 && Board.boardWidth == 19 && Board.boardHeight == 19) {
          Lizzie.board.setupFixedHandicap(config.handicap);
        }
        Lizzie.board.getHistory().getGameInfo().setKomi(config.komi);
        configurePlayerNames();
      }
      configureAnalysisVisuals();
      Lizzie.frame.humanSlGame = this;
      trainingSession.setState(HumanSlTrainingSession.State.PLAYING);
      turnStartedAt = System.currentTimeMillis();
      Lizzie.frame.showHumanSlTrainingBar(this);
      Lizzie.frame.refresh();
      activateLiveAnalysisIfRequested();

      if (!isHumanTurn()) {
        scheduleAiMove();
      }
    } catch (RuntimeException | Error startupFailure) {
      rollbackFailedStart(
          previousSessionState, boardSnapshot, startupFailure, externalFailureCleanup);
      throw startupFailure;
    }
  }

  private void rollbackFailedStart(
      HumanSlTrainingSession.State previousSessionState,
      StartBoardSnapshot boardSnapshot,
      Throwable startupFailure,
      boolean externalFailureCleanup) {
    requestGeneration.incrementAndGet();
    aiThinking = false;
    aiFailed = false;
    try {
      gameExecutor.shutdownNow();
    } catch (RuntimeException | Error cleanupFailure) {
      appendExitFailure(startupFailure, cleanupFailure);
    }
    if (!externalFailureCleanup) {
      try {
        runner.cancelActiveRequests();
      } catch (RuntimeException | Error cleanupFailure) {
        appendExitFailure(startupFailure, cleanupFailure);
      }
    }
    if (Lizzie.frame != null && Lizzie.frame.humanSlGame == this) {
      Lizzie.frame.humanSlGame = null;
    }
    try {
      trainingSession.setState(previousSessionState);
    } catch (RuntimeException | Error cleanupFailure) {
      appendExitFailure(startupFailure, cleanupFailure);
    }
    if (boardSnapshot != null) {
      try {
        boardSnapshot.restore();
        failedStartPrimaryResync = primaryEngineResyncPreparation.get();
        if (failedStartPrimaryResync == null
            && Lizzie.board != null
            && Lizzie.leelaz != null
            && !EngineManager.isEmpty) {
          failedStartPrimaryResync = () -> false;
        }
      } catch (RuntimeException | Error cleanupFailure) {
        appendExitFailure(startupFailure, cleanupFailure);
        failedStartPrimaryResync = () -> false;
      }
    }
    if (externalFailureCleanup) {
      // The dialog retains runner + lease and performs blocking close, exact ACK replay, then
      // foreground restoration on its lifecycle workers.
      return;
    }
    Throwable closeFailure = null;
    try {
      closeRunnerNow();
    } catch (RuntimeException | Error cleanupFailure) {
      closeFailure = cleanupFailure;
      appendExitFailure(startupFailure, cleanupFailure);
    }
    Throwable resyncFailure = null;
    if (closeFailure == null && failedStartPrimaryResync != null) {
      try {
        if (!failedStartPrimaryResync.getAsBoolean()) {
          throw new IllegalStateException(
              text(
                  "HumanSlGame.error.primaryResyncFailed",
                  "The foreground engine did not accept the restored pre-coach position."));
        }
        failedStartPrimaryResync = null;
      } catch (RuntimeException | Error failure) {
        resyncFailure = failure;
        appendExitFailure(startupFailure, failure);
      }
    }
    // Never resume foreground analysis while close/exact replay is unresolved.
    Throwable visualRestoreFailure =
        closeFailure == null && resyncFailure == null
            ? restoreAnalysisVisualsBestEffort()
            : restoreTemporaryLiveAnalysisAndSettingsBestEffort();
    appendExitFailure(startupFailure, visualRestoreFailure);
  }

  synchronized ForegroundAnalysisPause.RestoreLease releaseFailedStartRestoreLease() {
    ForegroundAnalysisPause.RestoreLease restoreLease = foregroundAnalysisRestoreLease;
    foregroundAnalysisRestoreLease = ForegroundAnalysisPause.RestoreLease.inactive();
    return restoreLease;
  }

  synchronized BooleanSupplier releaseFailedStartPrimaryResync() {
    BooleanSupplier resync = failedStartPrimaryResync;
    failedStartPrimaryResync = null;
    return resync;
  }

  Throwable restoreFailedStartAnalysisSettingsBestEffort() {
    return restoreTemporaryLiveAnalysisAndSettingsBestEffort();
  }

  void closeRunnerAfterFailedHandoff() {
    Throwable closeFailure = null;
    try {
      runner.cancelActiveRequests();
    } catch (RuntimeException | Error cancelFailure) {
      closeFailure = cancelFailure;
    }
    try {
      closeRunnerNow();
    } catch (RuntimeException | Error launchFailure) {
      if (closeFailure == null) {
        closeFailure = launchFailure;
      } else {
        closeFailure = appendExitFailure(closeFailure, launchFailure);
      }
    }
    if (closeFailure instanceof RuntimeException) {
      throw (RuntimeException) closeFailure;
    }
    if (closeFailure instanceof Error) {
      throw (Error) closeFailure;
    }
  }

  private void configurePlayerNames() {
    String me = text("HumanSlGame.humanPlayer", "You");
    String ai = text("HumanSlGame.aiPlayer", "HumanSL AI") + " (" + opponentLabel() + ")";
    Lizzie.board.getHistory().getGameInfo().setPlayerBlack(humanIsBlack ? me : ai);
    Lizzie.board.getHistory().getGameInfo().setPlayerWhite(humanIsBlack ? ai : me);
  }

  /** Brings the integrated controls back into view. */
  public void showControlPanel() {
    if (!teardownComplete) {
      Lizzie.frame.showHumanSlTrainingBar(this);
      Lizzie.frame.setMainPanelFocus();
    }
  }

  /** Called from LizzieFrame when a coaching game is active. */
  public void onBoardClicked(int x, int y) {
    if (finished || !isHumanTurn() || !Board.isValid(x, y)) {
      return;
    }
    if (Lizzie.board.getHistory().getStones()[Board.getIndex(x, y)] != Stone.EMPTY) {
      return;
    }
    if (!placeLocal(x, y, humanIsBlack ? Stone.BLACK : Stone.WHITE)) {
      return;
    }
    recordTurnElapsed(true);
    scheduleAiMove();
  }

  public void humanPass() {
    if (finished || !isHumanTurn()) {
      return;
    }
    if (!passLocal(humanIsBlack ? Stone.BLACK : Stone.WHITE)) {
      return;
    }
    recordTurnElapsed(true);
    scheduleAiMove();
  }

  /** Compatibility alias; ending by resignation now follows the same path as Finish. */
  @Deprecated
  public void humanResign() {
    finishAndReturnToBoard();
  }

  /** Ends AI Coach and keeps the completed game on the main board for normal review. */
  public void finishAndReturnToBoard() {
    finishAndReturnToBoardInternal();
  }

  /** Compatibility alias retained for existing toolbar and plugin integrations. */
  public void finishAndReview() {
    finishAndReturnToBoard();
  }

  /** Legacy alias retained for existing menu/control integrations. */
  public void countAndFinish() {
    finishAndReturnToBoard();
  }

  public void saveKifu() {
    LizzieFrame.saveFile(false);
  }

  private void scheduleAiMove() {
    scheduleAiMove(false);
  }

  private void scheduleAiMove(boolean cancelBeforeRequest) {
    if (finished || isReviewing() || aiThinking) {
      return;
    }
    aiFailed = false;
    aiThinking = true;
    long generation = requestGeneration.get();
    try {
      Lizzie.frame.updateHumanSlTrainingBar();
      Board requestBoard = Lizzie.board;
      BoardHistoryNode positionNode = requestBoard.getHistory().getCurrentHistoryNode();
      long positionContextRevision = requestBoard.getContextRevision();
      gameExecutor.execute(
          () -> {
            try {
              if (cancelBeforeRequest) {
                runner.cancelActiveRequests();
              }
              long startedAt = System.currentTimeMillis();
              Optional<String> move = Optional.empty();
              for (int attempt = 0;
                  attempt < AI_MOVE_RETRIES
                      && !Thread.currentThread().isInterrupted()
                      && !finished
                      && generation == requestGeneration.get();
                  attempt++) {
                move =
                    runner.bestHumanMove(
                        positionNode,
                        profile,
                        config.analysisVisits(),
                        config.rootSymmetries(),
                        moveTimeout);
                if (move.isPresent()) {
                  break;
                }
                if (attempt + 1 < AI_MOVE_RETRIES && generation == requestGeneration.get()) {
                  runner.cancelActiveRequests();
                }
              }
              waitMinimumThinkTime(
                  startedAt, generation, requestBoard, positionNode, positionContextRevision);
              Optional<String> resolved = move;
              SwingUtilities.invokeLater(
                  () -> {
                    if (generation == requestGeneration.get()) {
                      applyAiMoveIfCurrent(
                          resolved, requestBoard, positionNode, positionContextRevision);
                    }
                  });
            } catch (RuntimeException | Error failure) {
              dispatchGameWorkerFailure(generation, "AI move", failure);
            }
          });
    } catch (RuntimeException | Error failure) {
      dispatchGameWorkerFailure(generation, "AI move dispatch", failure);
    }
  }

  private void waitMinimumThinkTime(
      long startedAt,
      long generation,
      Board requestBoard,
      BoardHistoryNode positionNode,
      long positionContextRevision) {
    if (finished
        || generation != requestGeneration.get()
        || !isCurrentAiRequestPosition(requestBoard, positionNode, positionContextRevision)) {
      return;
    }
    long target =
        MIN_MOVE_DELAY_MILLIS
            + (long)
                (ThreadLocalRandom.current().nextDouble()
                    * (MAX_MOVE_DELAY_MILLIS - MIN_MOVE_DELAY_MILLIS));
    long remaining = target - (System.currentTimeMillis() - startedAt);
    if (remaining <= 0L) {
      return;
    }
    try {
      Thread.sleep(remaining);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void applyAiMoveIfCurrent(
      Optional<String> move,
      Board requestBoard,
      BoardHistoryNode positionNode,
      long positionContextRevision) {
    if (!isCurrentAiRequestPosition(requestBoard, positionNode, positionContextRevision)) {
      // A coaching game owns one live board position. If another action navigated or replaced that
      // position while HumanSL was thinking, ending the session is safer than applying a response
      // to an unrelated node or leaving a half-active controller behind.
      abort();
      return;
    }
    applyAiMove(move);
  }

  private boolean isCurrentAiRequestPosition(
      Board requestBoard, BoardHistoryNode positionNode, long positionContextRevision) {
    return Lizzie.board == requestBoard
        && requestBoard.getContextRevision() == positionContextRevision
        && requestBoard.getHistory().getCurrentHistoryNode() == positionNode;
  }

  private void applyAiMove(Optional<String> move) {
    if (finished) {
      return;
    }
    aiThinking = false;
    recordTurnElapsed(false);
    Stone aiColor = humanIsBlack ? Stone.WHITE : Stone.BLACK;
    if (!move.isPresent()) {
      aiFailed = true;
      Utils.showMsgNoModalForTime(text("HumanSlGame.aiMoveFailed", "The AI did not respond."), 4);
      turnStartedAt = System.currentTimeMillis();
      Lizzie.frame.updateHumanSlTrainingBar();
      return;
    }
    aiFailed = false;
    if ("pass".equalsIgnoreCase(move.get().trim())) {
      passLocal(aiColor);
      Utils.showMsgNoModalForTime(text("HumanSlGame.aiPassed", "AI passed."), 3);
      turnStartedAt = System.currentTimeMillis();
      return;
    }
    int[] coords = Board.convertNameToCoordinates(move.get().trim());
    if (coords == null
        || coords == LizzieFrame.outOfBoundCoordinate
        || !Board.isValid(coords[0], coords[1])
        || Lizzie.board.getHistory().getStones()[Board.getIndex(coords[0], coords[1])]
            != Stone.EMPTY) {
      passLocal(aiColor);
    } else if (!placeLocal(coords[0], coords[1], aiColor)) {
      // A stale or illegal HumanSL result must not leave the game stuck on the AI turn.
      passLocal(aiColor);
    }
    turnStartedAt = System.currentTimeMillis();
    Lizzie.frame.updateHumanSlTrainingBar();
  }

  public void retryAiMove() {
    long generation = requestGeneration.get();
    try {
      if (finished || isReviewing() || !aiFailed || isHumanTurn()) {
        return;
      }
      scheduleAiMove(true);
    } catch (RuntimeException | Error failure) {
      dispatchGameWorkerFailure(generation, "AI move retry", failure);
    }
  }

  private void dispatchGameWorkerFailure(long generation, String phase, Throwable failure) {
    logExitFailure(phase, failure);
    if (finished || generation != requestGeneration.get()) {
      return;
    }
    aiThinking = false;
    aiFailed = true;
    Runnable completion =
        () -> {
          if (finished || generation != requestGeneration.get()) {
            return;
          }
          try {
            Lizzie.frame.updateHumanSlTrainingBar();
            Utils.showMsgNoModalForTime(
                text("HumanSlGame.aiMoveFailed", "The AI did not respond."), 4);
          } catch (RuntimeException | Error uiFailure) {
            logExitFailure("game worker failure UI", uiFailure);
          }
        };
    try {
      SwingUtilities.invokeLater(completion);
    } catch (RuntimeException | Error dispatchFailure) {
      logExitFailure("game worker failure dispatch", dispatchFailure);
    }
  }

  /** Stops AI Coach and leaves its current position on the main board. */
  public void abort() {
    finishAndReturnToBoard();
  }

  private void finishAndReturnToBoardInternal() {
    if (exitInProgress) {
      return;
    }
    if (exitRecoveryPending) {
      beginExitLifecycle(null);
      return;
    }
    if (finished) {
      return;
    }
    finished = true;
    requestGeneration.incrementAndGet();
    aiThinking = false;
    aiFailed = false;
    try {
      gameExecutor.shutdownNow();
    } catch (RuntimeException | Error failure) {
      logExitFailure("game executor shutdown", failure);
    }
    Runnable uiCompletion =
        () -> {
          Lizzie.frame.hideHumanSlTrainingBar(this);
          if (Lizzie.frame.humanSlGame == this) {
            Lizzie.frame.humanSlGame = null;
          }
          Lizzie.frame.refresh();
        };
    Runnable completionOverride = successfulExitCompletionOverride;
    beginExitLifecycle(
        () -> {
          trainingSession.setState(HumanSlTrainingSession.State.FINISHED);
          (completionOverride == null ? uiCompletion : completionOverride).run();
        });
  }

  /**
   * Stops AI Coach and runs {@code continuation} only after the teardown transaction succeeds.
   * The first caller owns the same retryable teardown; later clicks cannot start additional modes
   * beside it or beside a still-running companion process.
   */
  public void abortAndThen(Runnable continuation) {
    tryAbortAndThen(continuation);
  }

  /** Returns false only when another mutually-exclusive continuation already owns this exit. */
  boolean tryAbortAndThen(Runnable continuation) {
    if (continuation == null) {
      abort();
      return true;
    }
    boolean runNow;
    boolean accepted;
    synchronized (this) {
      runNow = teardownComplete;
      accepted = runNow || successfulExitContinuation == null;
      if (accepted && !runNow) {
        // The first accepted mode transition owns this teardown. Later clicks while stopping must
        // not launch several mutually-exclusive dialogs/modes after the same resource handoff.
        successfulExitContinuation = continuation;
      }
    }
    if (runNow) {
      runExitContinuation(continuation);
      return true;
    }
    abort();
    return accepted;
  }

  private void beginExitLifecycle(Runnable successCompletion) {
    if (successCompletion != null) {
      successfulExitCompletion = successCompletion;
    }
    if (exitInProgress) {
      return;
    }
    exitInProgress = true;
    exitRecoveryPending = false;
    refreshTrainingBarBestEffort();
    BooleanSupplier preparedResync = null;
    Throwable preparationFailure = null;
    try {
      // Freeze the final board for strict handoff. In live-analysis mode this is also a final
      // consistency check after ordinary move forwarding.
      preparedResync = primaryEngineResyncPreparation.get();
    } catch (RuntimeException | Error failure) {
      preparationFailure = failure;
    }
    BooleanSupplier immutableResync = preparedResync;
    Throwable immutablePreparationFailure = preparationFailure;
    NewHumanSlGameDialog.closeRunnerBeforeCompletion(
        () ->
            closeCompanionThenResyncPrimary(
                immutableResync, immutablePreparationFailure),
        null,
        this::completeExitLifecycle,
        task -> exitBackgroundDispatcher.accept(task),
        task -> exitCompletionDispatcher.accept(task));
  }

  private void closeCompanionThenResyncPrimary(
      BooleanSupplier preparedResync, Throwable preparationFailure) {
    Throwable cancelFailure = null;
    try {
      runner.cancelActiveRequests();
    } catch (RuntimeException | Error failure) {
      cancelFailure = failure;
    }
    try {
      runner.close();
    } catch (RuntimeException | Error closeFailure) {
      throwUnchecked(appendExitFailure(cancelFailure, closeFailure));
    }

    if (preparationFailure != null) {
      throwUnchecked(preparationFailure);
    }
    if (preparedResync != null && !preparedResync.getAsBoolean()) {
      throw new IllegalStateException(
          text(
              "HumanSlGame.error.primaryResyncFailed",
              "The foreground engine did not accept the final AI Coach position."));
    }
    Throwable liveAnalysisStopFailure = stopTemporaryLiveAnalysisBestEffort();
    if (liveAnalysisStopFailure != null) {
      throwUnchecked(liveAnalysisStopFailure);
    }
    Throwable leaseFailure = restoreForegroundLeaseBestEffort();
    if (leaseFailure != null || foregroundAnalysisRestoreLease.isRestorePending()) {
      if (leaseFailure != null) {
        throwUnchecked(leaseFailure);
      }
      throw new IllegalStateException("Foreground analysis restore lease remains pending.");
    }
    logExitFailure("companion cancellation", cancelFailure);
  }

  private static BooleanSupplier preparePrimaryEngineResync() {
    if (Lizzie.board == null || Lizzie.leelaz == null || EngineManager.isEmpty) {
      return null;
    }
    Optional<Board.FrozenPrimaryPosition> frozen =
        Lizzie.board.freezeCurrentPositionForPrimaryEngineExactRestore();
    if (frozen.isEmpty()) {
      return () -> false;
    }
    PrimaryEngineResyncAttempt first = new FrozenPrimaryEngineResyncAttempt(frozen.get());
    return () ->
        executeStablePrimaryEngineResync(first, PRIMARY_RESYNC_STABILITY_ATTEMPTS);
  }

  interface PrimaryEngineResyncAttempt {
    boolean execute();

    boolean matchesCurrentBoardAndPrimary();

    PrimaryEngineResyncAttempt recaptureCurrentPositionForSamePrimary();
  }

  private static final class FrozenPrimaryEngineResyncAttempt
      implements PrimaryEngineResyncAttempt {
    private final Board.FrozenPrimaryPosition frozen;

    private FrozenPrimaryEngineResyncAttempt(Board.FrozenPrimaryPosition frozen) {
      this.frozen = frozen;
    }

    @Override
    public boolean execute() {
      return frozen.execute();
    }

    @Override
    public boolean matchesCurrentBoardAndPrimary() {
      return frozen.matchesCurrentBoardAndPrimary();
    }

    @Override
    public PrimaryEngineResyncAttempt recaptureCurrentPositionForSamePrimary() {
      return frozen
          .recaptureCurrentPositionForSamePrimary()
          .map(FrozenPrimaryEngineResyncAttempt::new)
          .orElse(null);
    }
  }

  static boolean executeStablePrimaryEngineResync(
      PrimaryEngineResyncAttempt first, int maximumAttempts) {
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException("maximumAttempts");
    }
    PrimaryEngineResyncAttempt current = first;
    for (int attempt = 0; attempt < maximumAttempts; attempt++) {
      if (current == null || !current.execute()) {
        return false;
      }
      if (current.matchesCurrentBoardAndPrimary()) {
        return true;
      }
      if (attempt + 1 < maximumAttempts) {
        current = current.recaptureCurrentPositionForSamePrimary();
      }
    }
    return false;
  }

  private void completeExitLifecycle(Throwable workerFailure) {
    exitInProgress = false;
    if (workerFailure != null) {
      exitRecoveryPending = true;
      logExitFailure("close/resync", workerFailure);
      showExitRecoveryFailure(workerFailure);
      refreshTrainingBarBestEffort();
      return;
    }

    Throwable restoreFailure = restoreAnalysisSettingsBestEffort();
    if (restoreFailure != null || foregroundAnalysisRestoreLease.isRestorePending()) {
      exitRecoveryPending = true;
      logExitFailure("foreground restore", restoreFailure);
      showExitRecoveryFailure(restoreFailure);
      refreshTrainingBarBestEffort();
      return;
    }

    Runnable completion = successfulExitCompletion;
    if (completion != null) {
      try {
        completion.run();
      } catch (RuntimeException | Error completionFailure) {
        restoreFailure = appendExitFailure(restoreFailure, completionFailure);
      }
    }
    if (restoreFailure != null) {
      exitRecoveryPending = true;
      logExitFailure("exit completion", restoreFailure);
      showExitRecoveryFailure(restoreFailure);
      refreshTrainingBarBestEffort();
      return;
    }

    successfulExitCompletion = null;
    exitRecoveryPending = false;
    teardownComplete = true;
    Runnable continuation;
    synchronized (this) {
      continuation = successfulExitContinuation;
      successfulExitContinuation = null;
    }
    if (continuation != null) {
      runExitContinuation(continuation);
    } else {
      startPostGameAnalysisAfterSuccessfulExit();
    }
  }

  private void startPostGameAnalysisAfterSuccessfulExit() {
    if (config.mode.isLiveAnalysis() || Lizzie.frame == null) {
      return;
    }
    Runnable start =
        () -> {
          if (Lizzie.frame == null || Lizzie.frame.humanSlGame != null) {
            return;
          }
          try {
            Lizzie.frame.ensureAnalysisResumedAfterLoad();
          } catch (RuntimeException | Error failure) {
            logExitFailure("post-game analysis start", failure);
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      start.run();
    } else {
      try {
        SwingUtilities.invokeLater(start);
      } catch (RuntimeException | Error failure) {
        logExitFailure("post-game analysis dispatch", failure);
      }
    }
  }

  private static void runExitContinuation(Runnable continuation) {
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        continuation.run();
      } else {
        SwingUtilities.invokeLater(
            () -> {
              try {
                continuation.run();
              } catch (RuntimeException | Error failure) {
                logExitFailure("exit continuation", failure);
              }
            });
      }
    } catch (RuntimeException | Error failure) {
      logExitFailure("exit continuation dispatch", failure);
    }
  }

  private void refreshTrainingBarBestEffort() {
    try {
      if (Lizzie.frame != null && Lizzie.frame.humanSlGame == this) {
        Lizzie.frame.showHumanSlTrainingBar(this);
        Lizzie.frame.updateHumanSlTrainingBar();
      }
    } catch (RuntimeException | Error failure) {
      logExitFailure("exit recovery bar", failure);
    }
  }

  private static void dispatchExitWorker(Runnable task) {
    Thread worker = new Thread(task, "humansl-coach-exit");
    worker.setDaemon(true);
    worker.start();
  }

  private static Throwable appendExitFailure(Throwable primary, Throwable added) {
    if (added == null) {
      return primary;
    }
    if (primary == null) {
      return added;
    }
    if (primary != added) {
      try {
        primary.addSuppressed(added);
      } catch (RuntimeException | Error ignored) {
        // Exit cleanup must not be interrupted by failure aggregation.
      }
    }
    return primary;
  }

  private static void throwUnchecked(Throwable failure) {
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
  }

  private static void logExitFailure(String phase, Throwable failure) {
    if (failure == null) {
      return;
    }
    try {
      LOG.error("HumanSL exit phase={} failed", phase, failure);
    } catch (RuntimeException | Error ignored) {
      // Logging must not create an uncaught EDT exception.
    }
  }

  private void showExitRecoveryFailure(Throwable failure) {
    if (failure == null || Lizzie.frame == null) {
      return;
    }
    String detail = failure.getLocalizedMessage();
    String message =
        text(
            "HumanSlGame.error.exitRecoveryFailed",
            "AI Coach stopped, but the foreground engine could not be restored safely. Click Stop and return again to retry.");
    try {
      Utils.showMsg(Utils.isBlank(detail) ? message : message + " (" + detail + ")", Lizzie.frame);
    } catch (RuntimeException | Error dialogFailure) {
      logExitFailure("exit recovery message", dialogFailure);
    }
  }

  private void closeRunnerNow() {
    runner.close();
  }

  private boolean placeLocal(int x, int y, Stone color) {
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    BoardHistoryNode before = Lizzie.board.getHistory().getCurrentHistoryNode();
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = !config.mode.isLiveAnalysis();
    }
    try {
      Lizzie.board.place(x, y, color, shouldForceTrainingBranch(before));
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
    return Lizzie.board.getHistory().getCurrentHistoryNode() != before;
  }

  private boolean passLocal(Stone color) {
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    BoardHistoryNode before = Lizzie.board.getHistory().getCurrentHistoryNode();
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = !config.mode.isLiveAnalysis();
    }
    try {
      Lizzie.board.pass(color, shouldForceTrainingBranch(before), false, true);
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
    return Lizzie.board.getHistory().getCurrentHistoryNode() != before;
  }

  private boolean shouldForceTrainingBranch(BoardHistoryNode currentNode) {
    return config.fromCurrentPosition
        && trainingStartNode != null
        && trainingStartNode == currentNode;
  }

  private void configureAnalysisVisuals() {
    candidatesBlackBefore = Lizzie.config.showBlackCandidates;
    candidatesWhiteBefore = Lizzie.config.showWhiteCandidates;
    analyzeBlackBefore = Lizzie.config.analyzeBlack;
    analyzeWhiteBefore = Lizzie.config.analyzeWhite;
    showWinrateGraphBefore = Lizzie.config.showWinrateGraph;
    showWinrateInSuggestionBefore = Lizzie.config.showWinrateInSuggestion;
    showKataGoEstimateBefore = Lizzie.config.showKataGoEstimate;
    showingPolicyBefore = Lizzie.frame != null && Lizzie.frame.isShowingPolicy;
    showingHeatmapBefore = Lizzie.frame != null && Lizzie.frame.isShowingHeatmap;
    analysisVisualsCaptured = true;
    if (config.mode.isLiveAnalysis()) {
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.analyzeBlack = true;
      Lizzie.config.analyzeWhite = true;
      return;
    }
    Lizzie.config.showBlackCandidates = false;
    Lizzie.config.showWhiteCandidates = false;
    Lizzie.config.showWinrateGraph = false;
    Lizzie.config.showWinrateInSuggestion = false;
    Lizzie.config.showKataGoEstimate = false;
    if (Lizzie.frame != null) {
      Lizzie.frame.isShowingPolicy = false;
      Lizzie.frame.isShowingHeatmap = false;
      Lizzie.frame.clearKataEstimate();
    }
  }

  private void activateLiveAnalysisIfRequested() {
    if (!config.mode.isLiveAnalysis()) {
      return;
    }

    Leelaz pausedEngine = Lizzie.leelaz;
    boolean restoreWasPending = foregroundAnalysisRestoreLease.isRestorePending();
    boolean foregroundReady = isForegroundReadyForLiveAnalysis(pausedEngine);
    BooleanSupplier preparedResync = primaryEngineResyncPreparation.get();
    if (preparedResync == null && (foregroundReady || restoreWasPending)) {
      throw new IllegalStateException(
          text(
              "HumanSlGame.error.primaryResyncFailed",
              "The foreground engine could not be synchronized for live analysis."));
    }
    if (preparedResync != null && !preparedResync.getAsBoolean()) {
      throw new IllegalStateException(
          text(
              "HumanSlGame.error.primaryResyncFailed",
              "The foreground engine could not be synchronized for live analysis."));
    }

    Throwable restoreFailure = restoreForegroundLeaseBestEffort();
    if (restoreFailure != null || foregroundAnalysisRestoreLease.isRestorePending()) {
      if (restoreFailure != null) {
        throwUnchecked(restoreFailure);
      }
      throw new IllegalStateException("Foreground analysis restore lease remains pending.");
    }

    Leelaz activeEngine = Lizzie.leelaz;
    if (!isForegroundReadyForLiveAnalysis(activeEngine) || activeEngine.isPondering()) {
      return;
    }
    activeEngine.ponder();
    if (!restoreWasPending || activeEngine != pausedEngine) {
      temporaryLiveAnalysisEngine = activeEngine;
    }
    if (Lizzie.frame != null) {
      Lizzie.frame.refresh();
    }
  }

  private static boolean isForegroundReadyForLiveAnalysis(Leelaz engine) {
    return engine != null
        && engine == Lizzie.leelaz
        && !EngineManager.isEmpty
        && engine.isStarted()
        && engine.isLoaded();
  }

  private Throwable stopTemporaryLiveAnalysisBestEffort() {
    Leelaz engine = temporaryLiveAnalysisEngine;
    if (engine == null) {
      return null;
    }
    try {
      if (Lizzie.leelaz == engine && engine.isPondering()) {
        engine.notPondering();
      }
      temporaryLiveAnalysisEngine = null;
      return null;
    } catch (RuntimeException | Error failure) {
      return failure;
    }
  }

  private Throwable restoreAnalysisVisualsBestEffort() {
    Throwable liveAnalysisStopFailure = stopTemporaryLiveAnalysisBestEffort();
    if (liveAnalysisStopFailure != null) {
      return liveAnalysisStopFailure;
    }
    Throwable leaseFailure = restoreForegroundLeaseBestEffort();
    if (leaseFailure != null || foregroundAnalysisRestoreLease.isRestorePending()) {
      return leaseFailure;
    }
    return restoreAnalysisSettingsBestEffort();
  }

  private Throwable restoreForegroundLeaseBestEffort() {
    Throwable leaseFailure =
        foregroundAnalysisRestoreLease.restoreBestEffort(FOREGROUND_RESTORE_ATTEMPTS);
    if (!foregroundAnalysisRestoreLease.isRestorePending()) {
      foregroundAnalysisRestoreLease = ForegroundAnalysisPause.RestoreLease.inactive();
    }
    return leaseFailure;
  }

  private Throwable restoreAnalysisSettingsBestEffort() {
    Throwable restoreFailure = null;
    if (analysisVisualsCaptured) {
      try {
        Lizzie.config.showBlackCandidates = candidatesBlackBefore;
        Lizzie.config.showWhiteCandidates = candidatesWhiteBefore;
        Lizzie.config.analyzeBlack = analyzeBlackBefore;
        Lizzie.config.analyzeWhite = analyzeWhiteBefore;
        Lizzie.config.showWinrateGraph = showWinrateGraphBefore;
        Lizzie.config.showWinrateInSuggestion = showWinrateInSuggestionBefore;
        Lizzie.config.showKataGoEstimate = showKataGoEstimateBefore;
        if (Lizzie.frame != null) {
          Lizzie.frame.isShowingPolicy = showingPolicyBefore;
          Lizzie.frame.isShowingHeatmap = showingHeatmapBefore;
        }
        analysisVisualsCaptured = false;
      } catch (RuntimeException | Error visualFailure) {
        restoreFailure = visualFailure;
      }
    }
    return restoreFailure;
  }

  private Throwable restoreTemporaryLiveAnalysisAndSettingsBestEffort() {
    Throwable stopFailure = stopTemporaryLiveAnalysisBestEffort();
    Throwable settingsFailure = restoreAnalysisSettingsBestEffort();
    return appendExitFailure(stopFailure, settingsFailure);
  }

  private void showForegroundRestoreFailure(Throwable restoreFailure) {
    if (restoreFailure == null || Lizzie.frame == null) {
      return;
    }
    String detail = restoreFailure.getLocalizedMessage();
    String message =
        text(
            "AnalysisEngine.foregroundRestoreFailed",
            "Failed to restore the foreground engine. Restart it before continuing.");
    Utils.showMsg(Utils.isBlank(detail) ? message : message + " (" + detail + ")", Lizzie.frame);
  }

  private void recordTurnElapsed(boolean humanTurn) {
    long now = System.currentTimeMillis();
    long elapsed = Math.max(0L, now - turnStartedAt);
    if (humanTurn) {
      humanElapsedMillis += elapsed;
    } else {
      aiElapsedMillis += elapsed;
    }
    turnStartedAt = now;
  }

  private long liveElapsed(boolean human) {
    if (finished || turnStartedAt <= 0L || isHumanTurn() != human) {
      return 0L;
    }
    return Math.max(0L, System.currentTimeMillis() - turnStartedAt);
  }

  private String text(String key, String fallback) {
    try {
      return Lizzie.resourceBundle.getString(key);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private String rankLabel(int rank, boolean danRank) {
    String key =
        danRank ? "HumanSlTraining.rank.danValue" : "HumanSlTraining.rank.kyuValue";
    String fallback = danRank ? "{0} dan" : "{0} kyu";
    return MessageFormat.format(text(key, fallback), rank);
  }

  public static Path resolveDefaultHumanModel() {
    featurecat.lizzie.util.KataGoAutoSetupHelper.HumanSlModelStatus status =
        featurecat.lizzie.util.KataGoAutoSetupHelper.inspectHumanSlModel();
    if (status == null || !status.isInstalled()) {
      return null;
    }
    return status.modelPath;
  }

  private static HumanSlTrainingConfig legacyConfig(
      String profile,
      boolean humanIsBlack,
      int handicap,
      double komi,
      int moveTimeoutSeconds) {
    String normalized = profile == null ? "rank_3k" : profile.toLowerCase(java.util.Locale.ROOT);
    boolean dan = normalized.endsWith("d");
    int rank = 3;
    try {
      int underscore = normalized.lastIndexOf('_');
      rank = Integer.parseInt(normalized.substring(underscore + 1, normalized.length() - 1));
    } catch (Exception ignored) {
    }
    OpponentPreset preset =
        normalized.startsWith("proyear_") ? OpponentPreset.MODERN_PRO : OpponentPreset.RANK;
    return HumanSlTrainingConfig.builder()
        .opponentPreset(preset)
        .rank(rank, dan)
        .playerColor(
            humanIsBlack
                ? HumanSlTrainingConfig.PlayerColor.BLACK
                : HumanSlTrainingConfig.PlayerColor.WHITE)
        .moveTimeSeconds(moveTimeoutSeconds)
        .handicap(handicap)
        .komi(komi)
        .build();
  }

}
