package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.HumanMoveChoice;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.training.AiResignation;
import featurecat.lizzie.training.GameResult;
import featurecat.lizzie.training.GameTreePosition;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingSession;
import featurecat.lizzie.training.HumanThinkTime;
import featurecat.lizzie.training.MoveFeedback;
import featurecat.lizzie.training.MoveVerdict;
import featurecat.lizzie.training.OpponentPreset;
import featurecat.lizzie.training.TrainingMode;
import featurecat.lizzie.util.Utils;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
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
  private static final long THINK_TIME_POLL_MILLIS = 50L;

  /** How long the AI's reply waits for the rating of the player's move, to hold it on a 恶手. */
  private static final long JUDGEMENT_WAIT_MILLIS = 4000L;

  private static final Duration COUNT_TIMEOUT = Duration.ofSeconds(20);
  private static final String RESIGN = "resign";
  private static final int FOREGROUND_RESTORE_ATTEMPTS = 2;
  private static final int PRIMARY_RESYNC_STABILITY_ATTEMPTS = 3;
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

  /** Root of the history this game lives in, and the game's first node; take-backs stop there. */
  private BoardHistoryNode gameRoot;

  private BoardHistoryNode gameStartNode;

  /** The game's current position. The board may show another one while the player browses. */
  private volatile BoardHistoryNode liveNode;

  private int takeBacks;

  /** Rates the player's moves in 边下边提醒; null in the other modes. */
  private MoveReviewer reviewer;

  private final CoachAssist assist = new CoachAssist();
  private CompletableFuture<Optional<MoveFeedback>> lastJudgement = NO_JUDGEMENT;
  private static final CompletableFuture<Optional<MoveFeedback>> NO_JUDGEMENT =
      CompletableFuture.completedFuture(Optional.empty());

  /** The AI's reply to a 恶手, held until the player decides; null otherwise. */
  private HeldReply heldReply;

  private record HeldReply(
      Optional<String> move, Board board, BoardHistoryNode position, long revision) {}

  private AiResignation aiResignation;
  private GameInfo gameInfo;
  private double gameKomi;
  private volatile boolean counting;
  private volatile GameResult gameResult;
  private long thinkTimeOverrideMillis = -1L;
  private BiConsumer<String, Integer> notifier = Utils::showMsgNoModalForTime;
  private Runnable successfulExitCompletion;
  private Runnable successfulExitContinuation;
  private Consumer<Runnable> exitBackgroundDispatcher = HumanSlGameController::dispatchExitWorker;
  private Consumer<Runnable> exitCompletionDispatcher = SwingUtilities::invokeLater;
  private Supplier<BooleanSupplier> primaryEngineResyncPreparation =
      HumanSlGameController::preparePrimaryEngineResync;
  private Runnable successfulExitCompletionOverride;
  private BooleanSupplier failedStartPrimaryResync;

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
    aiResignation = AiResignation.forProfile(profile);
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

  void setThinkTimeForTesting(long millis) {
    thinkTimeOverrideMillis = millis;
  }

  void setNotifierForTesting(BiConsumer<String, Integer> value) {
    notifier = value;
  }

  void setAiResignationForTesting(AiResignation value) {
    aiResignation = value;
  }

  void setExitLifecycleForTesting(
      Consumer<Runnable> backgroundDispatcher,
      Consumer<Runnable> completionDispatcher,
      BooleanSupplier resync,
      Runnable successCompletionOverride) {
    setExitLifecyclePreparationForTesting(
        backgroundDispatcher, completionDispatcher, () -> resync, successCompletionOverride);
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
        restoreLease == null ? ForegroundAnalysisPause.RestoreLease.inactive() : restoreLease;
    HumanSlTrainingSession.State previousSessionState = trainingSession.state();
    CoachStartSnapshot boardSnapshot = null;
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
        boardSnapshot = CoachStartSnapshot.capture();
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
      BoardHistoryList history = Lizzie.board.getHistory();
      gameRoot = history.getStart();
      gameStartNode = history.getCurrentHistoryNode();
      gameInfo = history.getGameInfo();
      gameKomi = gameInfo.getKomi();
      liveNode = gameStartNode;
      Lizzie.frame.humanSlGame = this;
      trainingSession.setState(HumanSlTrainingSession.State.PLAYING);
      turnStartedAt = System.currentTimeMillis();
      Lizzie.frame.showHumanSlTrainingBar(this);
      Lizzie.frame.refresh();
      activateLiveAnalysisIfRequested();
      if (config.mode == TrainingMode.LIVE_FEEDBACK) {
        reviewer =
            new MoveReviewer(
                (position, visits, time, timeout) ->
                    runner.reviewPosition(position, profile, visits, time, timeout),
                node -> GameTreePosition.movesSince(node, liveNode) >= 0,
                this::onReviewChanged);
      }

      if (!isHumanTurn()) {
        scheduleAiMove();
      } else {
        reviewPlayerTurn();
      }
    } catch (RuntimeException | Error startupFailure) {
      rollbackFailedStart(
          previousSessionState, boardSnapshot, startupFailure, externalFailureCleanup);
      throw startupFailure;
    }
  }

  private void rollbackFailedStart(
      HumanSlTrainingSession.State previousSessionState,
      CoachStartSnapshot boardSnapshot,
      Throwable startupFailure,
      boolean externalFailureCleanup) {
    requestGeneration.incrementAndGet();
    aiThinking = false;
    aiFailed = false;
    try {
      gameExecutor.shutdownNow();
      closeReviewer();
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
    GameInfo info = Lizzie.board.getHistory().getGameInfo();
    info.setPlayerBlack(humanIsBlack ? me : ai);
    info.setPlayerWhite(humanIsBlack ? ai : me);
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
    if (finished || isGameOver() || counting || !atLiveGame() || !isHumanTurn()) {
      return;
    }
    if (!Board.isValid(x, y)) {
      return;
    }
    if (Lizzie.board.getHistory().getStones()[Board.getIndex(x, y)] != Stone.EMPTY) {
      return;
    }
    BoardHistoryNode before = liveNode;
    if (!placeLocal(x, y, humanIsBlack ? Stone.BLACK : Stone.WHITE)) {
      return;
    }
    recordTurnElapsed(true);
    judgePlayerMove(before, Board.convertCoordinatesToName(x, y));
    scheduleAiMove();
  }

  public void humanPass() {
    if (finished || isGameOver() || counting || !atLiveGame() || !isHumanTurn()) {
      return;
    }
    BoardHistoryNode before = liveNode;
    if (!passLocal(humanIsBlack ? Stone.BLACK : Stone.WHITE)) {
      return;
    }
    recordTurnElapsed(true);
    judgePlayerMove(before, "pass");
    if (bothPlayersPassed()) {
      countAndEnd();
      return;
    }
    scheduleAiMove();
  }

  /** The player resigns: the AI wins, and the game stays on the board for review. */
  public void resign() {
    if (finished || exitInProgress || isGameOver() || counting || !atLiveGame()) {
      return;
    }
    endGame(GameResult.resignation(!humanIsBlack));
  }

  public boolean canResign() {
    return !finished && !exitInProgress && !isGameOver() && !counting;
  }

  public boolean isGameOver() {
    return gameResult != null;
  }

  /** True while KataGo counts the game after both players passed. */
  public boolean isCounting() {
    return counting;
  }

  public boolean humanPlaysBlack() {
    return humanIsBlack;
  }

  public double gameKomi() {
    return gameKomi;
  }

  /** Stones the player and the AI have captured so far, in that order. */
  public int[] captures() {
    BoardHistoryNode live = liveNode;
    if (live == null) {
      return new int[] {0, 0};
    }
    int black = live.getData().blackCaptures;
    int white = live.getData().whiteCaptures;
    return humanIsBlack ? new int[] {black, white} : new int[] {white, black};
  }

  /**
   * The player's winrate in percent through this game, oldest first: in 边下边提醒 the rank-aware win
   * chance around each of the player's moves, in 边下边分析 KataGo's live analysis of each position.
   * Empty in the mode that hides analysis.
   */
  public double[] humanWinrates() {
    if (reviewer != null) {
      return reviewer.winChances();
    }
    BoardHistoryNode live = liveNode;
    if (!config.mode.isLiveAnalysis() || live == null || gameStartNode == null) {
      return new double[0];
    }
    java.util.ArrayDeque<Double> values = new java.util.ArrayDeque<>();
    for (BoardHistoryNode node = live; node != null; node = node.previous().orElse(null)) {
      BoardData data = node.getData();
      if (data.getPlayouts() > 0) {
        double black = data.blackToPlay ? data.winrate : 100.0 - data.winrate;
        values.addFirst(humanIsBlack ? black : 100.0 - black);
      }
      if (node == gameStartNode) {
        break;
      }
    }
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** The player's last move in this game: its move number and point, or null before any. */
  public String lastHumanMoveText() {
    BoardData data = lastHumanMove();
    if (data == null) {
      return null;
    }
    String point =
        data.lastMove
            .map(move -> Board.convertCoordinatesToName(move[0], move[1]))
            .orElse(text("HumanSlGame.btn.pass", "Pass"));
    return MessageFormat.format(text("CoachPanel.moveAt", "Move {0}: {1}"), data.moveNumber, point);
  }

  /** The number of the player's last move in this game, or 0 before any. */
  int lastHumanMoveNumber() {
    BoardData data = lastHumanMove();
    return data == null ? 0 : data.moveNumber;
  }

  private BoardData lastHumanMove() {
    Stone human = humanIsBlack ? Stone.BLACK : Stone.WHITE;
    for (BoardHistoryNode node = liveNode;
        node != null && node != gameStartNode;
        node = node.previous().orElse(null)) {
      if (node.getData().lastMoveColor == human) {
        return node.getData();
      }
    }
    return null;
  }

  /** How the game ended, or null while it is still on. */
  public GameResult result() {
    return gameResult;
  }

  /** The result in words, such as 黑胜 3.5 目, or empty while the game is still on. */
  public String resultText() {
    GameResult result = gameResult;
    if (result == null) {
      return "";
    }
    if (result.isDraw()) {
      return text("HumanSlGame.draw", "Draw");
    }
    if (!result.isCounted()) {
      return text("HumanSlGame.result.uncounted", "Not counted");
    }
    if (result.byResignation()) {
      return result.blackWins()
          ? text("HumanSlGame.result.blackByResignation", "Black wins by resignation")
          : text("HumanSlGame.result.whiteByResignation", "White wins by resignation");
    }
    String pattern =
        result.blackWins()
            ? text("HumanSlGame.result.blackByPoints", "Black wins by {0} points")
            : text("HumanSlGame.result.whiteByPoints", "White wins by {0} points");
    return MessageFormat.format(pattern, result.marginText());
  }

  private boolean bothPlayersPassed() {
    BoardHistoryNode live = liveNode;
    return live != null
        && live.getData().isPassNode()
        && live.previous().map(previous -> previous.getData().isPassNode()).orElse(false)
        && GameTreePosition.movesSince(gameStartNode, live) >= 2;
  }

  /** Both players passed: KataGo counts the game, and then it is over. */
  private void countAndEnd() {
    counting = true;
    aiThinking = false;
    long generation = requestGeneration.incrementAndGet();
    BoardHistoryNode finalNode = liveNode;
    notifier.accept(text("HumanSlGame.bothPassed", "Both players passed. Counting the game..."), 3);
    updateTrainingBar();
    gameExecutor.execute(
        () -> {
          OptionalDouble blackLead = runner.finalBlackScoreLead(finalNode, COUNT_TIMEOUT);
          SwingUtilities.invokeLater(
              () -> {
                if (finished || generation != requestGeneration.get()) {
                  return;
                }
                counting = false;
                endGame(
                    blackLead.isPresent()
                        ? GameResult.byScore(blackLead.getAsDouble(), gameKomi)
                        : GameResult.uncounted());
              });
        });
  }

  private void endGame(GameResult result) {
    gameResult = result;
    aiThinking = false;
    aiFailed = false;
    heldReply = null;
    assist.clearTurn();
    requestGeneration.incrementAndGet();
    if (result.isCounted() && gameInfo != null) {
      gameInfo.setResult(result.sgf());
    }
    // The coach panel shows the result; a pop-up over the board would only repeat it.
    updateTrainingBar();
  }

  /** Takes back the player's last move, and the AI's reply to it when there is one. */
  public void takeBack() {
    if (finished || exitInProgress || isGameOver() || counting || !atLiveGame()) {
      return;
    }
    int moves = takeBackMoves();
    if (moves == 0) {
      return;
    }
    // A reply still on its way, or held after a 恶手, belongs to the position being taken back.
    requestGeneration.incrementAndGet();
    aiThinking = false;
    aiFailed = false;
    heldReply = null;
    assist.clearTurn();
    Board board = Lizzie.board;
    Runnable deleteMoves =
        () -> {
          for (int i = 0; i < moves; i++) {
            board.deleteMoveNoHint();
          }
        };
    if (config.mode.isLiveAnalysis()) {
      deleteMoves.run();
    } else {
      // The hidden game's moves were never sent to the foreground engine. History navigation
      // needs the existing local-only scope too; isInputCommand only guards move placement.
      board.applyReadBoardSyncLocally(deleteMoves);
    }
    liveNode = board.getHistory().getCurrentHistoryNode();
    takeBacks++;
    turnStartedAt = System.currentTimeMillis();
    if (reviewer != null) {
      reviewer.takenBack(liveNode);
    }
    reviewPlayerTurn();
    updateTrainingBar();
  }

  public boolean canTakeBack() {
    return !finished && !exitInProgress && !isGameOver() && !counting && takeBackMoves() > 0;
  }

  /** Take-backs used in this game. */
  public int takeBackCount() {
    return takeBacks;
  }

  private int takeBackMoves() {
    BoardHistoryNode live = liveNode;
    if (live == null || gameStartNode == null) {
      return 0;
    }
    boolean humanToPlay = live.getData().blackToPlay == humanIsBlack;
    return GameTreePosition.takeBackMoves(
        humanToPlay, GameTreePosition.movesSince(gameStartNode, live));
  }

  /** Space in a mode that hides analysis during the game: say why nothing appears. */
  public void showAnalysisHiddenHint() {
    notifier.accept(
        text(
            "HumanSlGame.analysisHidden",
            "This training mode keeps analysis hidden until the game ends."),
        3);
  }

  public void showDeleteBlockedHint() {
    notifier.accept(
        text(
            "HumanSlGame.deleteBlocked",
            "Moves cannot be deleted during the game. Use Take back instead."),
        3);
  }

  /**
   * True when the board shows the game's current position. If the player was browsing other moves,
   * this brings the board back and says so, and the action that asked is not carried out.
   */
  private boolean atLiveGame() {
    Board board = Lizzie.board;
    BoardHistoryNode live = liveNode;
    if (live == null || board.getHistory().getCurrentHistoryNode() == live) {
      return true;
    }
    if (returnToGamePosition(board, live)) {
      notifier.accept(
          text(
              "HumanSlGame.backToLiveGame",
              "The game is still on, so the board went back to the current position."),
          3);
    }
    return false;
  }

  /**
   * Moves the board back to {@code position} of this game after the player browsed. Ends the
   * session when the game's history was replaced, or its moves deleted, and returns false.
   */
  private boolean returnToGamePosition(Board board, BoardHistoryNode position) {
    BoardHistoryNode root = board.getHistory().getStart();
    if (root != gameRoot) {
      abort();
      return false;
    }
    if (!GameTreePosition.isAttached(position, root)) {
      notifier.accept(
          text(
              "HumanSlGame.gameMovesDeleted",
              "Moves of the game were deleted, so AI Coach has ended."),
          4);
      abort();
      return false;
    }
    board.moveToAnyPosition(position);
    return board.getHistory().getCurrentHistoryNode() == position;
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
    if (finished || aiThinking || isGameOver() || counting) {
      return;
    }
    aiFailed = false;
    aiThinking = true;
    long generation = requestGeneration.get();
    CompletableFuture<Optional<MoveFeedback>> judgement = lastJudgement;
    lastJudgement = NO_JUDGEMENT;
    try {
      updateTrainingBar();
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
              Optional<HumanMoveChoice> choice = Optional.empty();
              for (int attempt = 0;
                  attempt < AI_MOVE_RETRIES
                      && !Thread.currentThread().isInterrupted()
                      && !finished
                      && generation == requestGeneration.get();
                  attempt++) {
                choice =
                    runner.chooseHumanMove(
                        positionNode,
                        profile,
                        config.analysisVisits(),
                        config.rootSymmetries(),
                        moveTimeout);
                if (choice.isPresent()) {
                  choice = resignIfHopeless(choice.get(), positionNode);
                  break;
                }
                if (attempt + 1 < AI_MOVE_RETRIES && generation == requestGeneration.get()) {
                  runner.cancelActiveRequests();
                }
              }
              waitMinimumThinkTime(
                  startedAt,
                  choice,
                  generation,
                  requestBoard,
                  positionNode,
                  positionContextRevision);
              Optional<String> resolved = choice.map(HumanMoveChoice::move);
              Optional<MoveFeedback> blunder = blunderToHoldFor(judgement, resolved, generation);
              SwingUtilities.invokeLater(
                  () -> {
                    if (generation != requestGeneration.get()) {
                      return;
                    }
                    if (blunder.isPresent()) {
                      holdReply(
                          new HeldReply(
                              resolved, requestBoard, positionNode, positionContextRevision),
                          blunder.get());
                    } else {
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

  /** The AI resigns instead of moving once KataGo has seen it lost for long enough. */
  private Optional<HumanMoveChoice> resignIfHopeless(
      HumanMoveChoice choice, BoardHistoryNode positionNode) {
    boolean resign =
        aiResignation.shouldResign(
            choice.aiWinrate(),
            choice.aiScoreLead(),
            positionNode.getData().moveNumber,
            Board.boardWidth * Board.boardHeight);
    return Optional.of(resign ? new HumanMoveChoice(RESIGN, choice.topHumanProbability()) : choice);
  }

  /**
   * How long the AI looks like it thinks: by how obvious the move is for players of its rank, at
   * the pace the player chose.
   */
  static long thinkTimeMillis(
      Optional<HumanMoveChoice> choice,
      int moveNumber,
      int boardArea,
      double randomValue,
      HumanThinkTime.Pace pace) {
    return HumanThinkTime.millis(
        choice.map(HumanMoveChoice::topHumanProbability).orElse(Double.NaN),
        moveNumber,
        boardArea,
        randomValue,
        pace);
  }

  private void waitMinimumThinkTime(
      long startedAt,
      Optional<HumanMoveChoice> choice,
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
        thinkTimeOverrideMillis >= 0L
            ? thinkTimeOverrideMillis
            : thinkTimeMillis(
                choice,
                positionNode.getData().moveNumber,
                Board.boardWidth * Board.boardHeight,
                ThreadLocalRandom.current().nextDouble(),
                config.pace);
    long deadline = startedAt + target;
    try {
      // A take-back or exit drops this reply, so stop waiting as soon as it happens.
      while (!finished && generation == requestGeneration.get()) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
          return;
        }
        Thread.sleep(Math.min(remaining, THINK_TIME_POLL_MILLIS));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void applyAiMoveIfCurrent(
      Optional<String> move,
      Board requestBoard,
      BoardHistoryNode positionNode,
      long positionContextRevision) {
    if (isCurrentAiRequestPosition(requestBoard, positionNode, positionContextRevision)) {
      applyAiMove(move);
      return;
    }
    // The player browsed other moves while HumanSL was thinking: play the reply on the game's
    // position. If that position was replaced or deleted, ending the session is safer than
    // applying a response to an unrelated node or leaving a half-active controller behind.
    if (Lizzie.board != requestBoard || positionNode != liveNode) {
      abort();
    } else if (returnToGamePosition(requestBoard, positionNode)) {
      applyAiMove(move);
    } else if (!finished) {
      abort();
    }
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
      updateTrainingBar();
      return;
    }
    aiFailed = false;
    if (RESIGN.equals(move.get())) {
      notifier.accept(text("HumanSlGame.aiResigned", "The AI resigned."), 3);
      endGame(GameResult.resignation(humanIsBlack));
      return;
    }
    if ("pass".equalsIgnoreCase(move.get().trim())) {
      passLocal(aiColor);
      if (bothPlayersPassed()) {
        countAndEnd();
        return;
      }
      Utils.showMsgNoModalForTime(text("HumanSlGame.aiPassed", "AI passed."), 3);
      turnStartedAt = System.currentTimeMillis();
      reviewPlayerTurn();
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
    reviewPlayerTurn();
    updateTrainingBar();
  }

  public void retryAiMove() {
    long generation = requestGeneration.get();
    try {
      if (finished || !aiFailed || isHumanTurn()) {
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
            updateTrainingBar();
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
    heldReply = null;
    assist.clearTurn();
    try {
      gameExecutor.shutdownNow();
      closeReviewer();
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
   * Stops AI Coach and runs {@code continuation} only after the teardown transaction succeeds. The
   * first caller owns the same retryable teardown; later clicks cannot start additional modes
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
        () -> closeCompanionThenResyncPrimary(immutableResync, immutablePreparationFailure),
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
    return () -> executeStablePrimaryEngineResync(first, PRIMARY_RESYNC_STABILITY_ATTEMPTS);
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

  private static void updateTrainingBar() {
    Lizzie.frame.updateHumanSlTrainingBar();
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
    Board board = Lizzie.board;
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    BoardHistoryNode before = board.getHistory().getCurrentHistoryNode();
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = !config.mode.isLiveAnalysis();
    }
    try {
      board.place(x, y, color, shouldForceTrainingBranch(before));
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
    return recordGameMove(board, before);
  }

  private boolean passLocal(Stone color) {
    Board board = Lizzie.board;
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    BoardHistoryNode before = board.getHistory().getCurrentHistoryNode();
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = !config.mode.isLiveAnalysis();
    }
    try {
      board.pass(color, shouldForceTrainingBranch(before), false, true);
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
    return recordGameMove(board, before);
  }

  /** True when the board advanced past {@code before}; the new node becomes the game's position. */
  private boolean recordGameMove(Board board, BoardHistoryNode before) {
    BoardHistoryNode after = board.getHistory().getCurrentHistoryNode();
    if (after == before) {
      return false;
    }
    liveNode = after;
    return true;
  }

  private boolean shouldForceTrainingBranch(BoardHistoryNode currentNode) {
    return config.fromCurrentPosition
        && trainingStartNode != null
        && trainingStartNode == currentNode;
  }

  /** The ratings of the player's moves in 边下边提醒, or null in the other modes. */
  MoveReviewer moveReviewer() {
    return reviewer;
  }

  TrainingMode mode() {
    return config.mode;
  }

  private void judgePlayerMove(BoardHistoryNode before, String move) {
    assist.clearTurn();
    if (reviewer != null && before != null && liveNode != before) {
      lastJudgement = reviewer.judge(before, liveNode, move);
    }
  }

  private void onReviewChanged() {
    if (reviewer != null) {
      assist.reviewArrived(reviewer.currentReview(liveNode));
    }
    updateTrainingBar();
  }

  /**
   * The 恶手 to hold the AI's reply for, waiting for the rating a little longer than the AI's think
   * time. A resignation, a failed reply or a take-back meanwhile is never held.
   */
  private Optional<MoveFeedback> blunderToHoldFor(
      CompletableFuture<Optional<MoveFeedback>> judgement,
      Optional<String> reply,
      long generation) {
    if (judgement == NO_JUDGEMENT || reply.isEmpty() || RESIGN.equals(reply.get())) {
      return Optional.empty();
    }
    long deadline = System.currentTimeMillis() + JUDGEMENT_WAIT_MILLIS;
    try {
      while (!judgement.isDone()
          && !finished
          && generation == requestGeneration.get()
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(THINK_TIME_POLL_MILLIS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    return judgement
        .getNow(Optional.empty())
        .filter(feedback -> feedback.verdict() == MoveVerdict.BLUNDER);
  }

  private void holdReply(HeldReply reply, MoveFeedback blunder) {
    if (finished || isGameOver()) {
      return;
    }
    aiThinking = false;
    recordTurnElapsed(false);
    heldReply = reply;
    assist.decideAbout(blunder);
    updateTrainingBar();
  }

  /** True while the AI holds its reply to a 恶手 for the player to decide. */
  public boolean isAwaitingDecision() {
    return heldReply != null;
  }

  /** 重下这手: the 恶手 is taken back and the AI's held reply dropped. */
  public void retryBlunder() {
    if (heldReply != null) {
      takeBack();
    }
  }

  /** 看 AI 推荐: marks the AI's choice in the position before the 恶手. */
  public void showBestMove() {
    if (heldReply != null) {
      assist.showBest();
      updateTrainingBar();
    }
  }

  /** 不重下，继续对局: the AI plays the reply it held. */
  public void continueAfterBlunder() {
    HeldReply held = heldReply;
    if (held == null || finished) {
      return;
    }
    heldReply = null;
    assist.clearTurn();
    recordTurnElapsed(true);
    applyAiMoveIfCurrent(held.move(), held.board(), held.position(), held.revision());
  }

  /**
   * 提示 and H. In 边下边提醒 the first press marks the area of the AI's choice and the second the point;
   * in the mode that hides analysis it says so instead.
   */
  public void hint() {
    if (reviewer == null) {
      if (!isLiveAnalysisMode()) {
        showAnalysisHiddenHint();
      }
      return;
    }
    if (!canHint() || !atLiveGame()) {
      return;
    }
    assist.hint(reviewer.currentReview(liveNode));
    updateTrainingBar();
  }

  /** 我自己想 or 知道了: the hint card closes; its mark stays until the player moves. */
  public void closeHint() {
    assist.closeHint();
    updateTrainingBar();
  }

  boolean canHint() {
    return reviewer != null
        && !finished
        && !isGameOver()
        && !counting
        && heldReply == null
        && isHumanTurn();
  }

  CoachAssist assist() {
    return assist;
  }

  /** Moves played in this game, the AI's included. */
  int movesPlayed() {
    BoardHistoryNode live = liveNode;
    return live == null || gameStartNode == null
        ? 0
        : Math.max(0, GameTreePosition.movesSince(gameStartNode, live));
  }

  /**
   * After the game: leaves AI 陪练 and shows the position before move {@code moveNumber} in the usual
   * analysis, so the player can see what was better there.
   */
  public void reviewFrom(int moveNumber) {
    BoardHistoryNode target = null;
    for (BoardHistoryNode node = liveNode; node != null; node = node.previous().orElse(null)) {
      if (node.getData().moveNumber == moveNumber - 1) {
        target = node;
        break;
      }
      if (node == gameStartNode) {
        break;
      }
    }
    BoardHistoryNode position = target;
    abortAndThen(
        () -> {
          Board board = Lizzie.board;
          if (position != null
              && board != null
              && GameTreePosition.isAttached(position, gameRoot)) {
            board.moveToAnyPosition(position);
          }
        });
  }

  private void reviewPlayerTurn() {
    if (reviewer != null && !finished && !isGameOver() && liveNode != null && isHumanTurn()) {
      reviewer.playerToMove(liveNode);
    }
  }

  private void closeReviewer() {
    if (reviewer != null) {
      reviewer.close();
    }
  }

  private void configureAnalysisVisuals() {
    Config settings = Lizzie.config;
    candidatesBlackBefore = settings.showBlackCandidates;
    candidatesWhiteBefore = settings.showWhiteCandidates;
    analyzeBlackBefore = settings.analyzeBlack;
    analyzeWhiteBefore = settings.analyzeWhite;
    showWinrateGraphBefore = settings.showWinrateGraph;
    showWinrateInSuggestionBefore = settings.showWinrateInSuggestion;
    showKataGoEstimateBefore = settings.showKataGoEstimate;
    showingPolicyBefore = Lizzie.frame != null && Lizzie.frame.isShowingPolicy;
    showingHeatmapBefore = Lizzie.frame != null && Lizzie.frame.isShowingHeatmap;
    analysisVisualsCaptured = true;
    // The coach panel shows the winrate curve while analysis is on, and nothing before the end
    // of the game otherwise, so the graph's column can go either way.
    settings.showWinrateGraph = false;
    if (config.mode.isLiveAnalysis()) {
      settings.showBlackCandidates = true;
      settings.showWhiteCandidates = true;
      settings.analyzeBlack = true;
      settings.analyzeWhite = true;
      return;
    }
    settings.showBlackCandidates = false;
    settings.showWhiteCandidates = false;
    settings.showWinrateInSuggestion = false;
    settings.showKataGoEstimate = false;
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
    String key = danRank ? "HumanSlTraining.rank.danValue" : "HumanSlTraining.rank.kyuValue";
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
      String profile, boolean humanIsBlack, int handicap, double komi, int moveTimeoutSeconds) {
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
        .mode(TrainingMode.POST_GAME_REVIEW)
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
