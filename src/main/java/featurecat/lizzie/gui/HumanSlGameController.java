package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisRequestBuilder;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.training.HumanMoveDecision;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingSession;
import featurecat.lizzie.training.OpponentPreset;
import featurecat.lizzie.training.TrainingMode;
import featurecat.lizzie.training.TrainingMoveAssessment;
import featurecat.lizzie.training.TrainingSessionReport;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs one HumanSL coaching game and its optional correction/review flow. */
public final class HumanSlGameController {
  private static final int AI_MOVE_RETRIES = 2;
  private static final int QUICK_REVIEW_VISITS = 32;
  private static final int DEEP_REVIEW_VISITS = 500;
  private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(30);
  private static final long MIN_MOVE_DELAY_MILLIS = 800L;
  private static final long MAX_MOVE_DELAY_MILLIS = 4000L;
  private static final String REPORT_BEGIN = "[[LIZZIEYZY_AI_COACH_REPORT_BEGIN]]";
  private static final String REPORT_END = "[[LIZZIEYZY_AI_COACH_REPORT_END]]";

  private final HumanSlAnalysisRunner runner;
  private final HumanSlTrainingConfig config;
  private final HumanSlTrainingSession trainingSession;
  private final boolean humanIsBlack;
  private final String profile;
  private final Duration moveTimeout;
  private final ExecutorService gameExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService reviewExecutor = Executors.newSingleThreadExecutor();
  private final AtomicLong requestGeneration = new AtomicLong();
  private final List<PendingHumanMove> pendingHumanMoves = new ArrayList<PendingHumanMove>();
  private final Set<Integer> assessedMoveNumbers = new HashSet<Integer>();

  private boolean candidatesBlackBefore;
  private boolean candidatesWhiteBefore;
  private boolean showWinrateGraphBefore;
  private boolean showWinrateInSuggestionBefore;
  private boolean showKataGoEstimateBefore;
  private boolean showingPolicyBefore;
  private boolean showingHeatmapBefore;
  private boolean ponderingBefore;
  private volatile boolean finished;
  private volatile boolean awaitingCorrection;
  private volatile boolean aiThinking;
  private volatile boolean aiFailed;
  private volatile long humanElapsedMillis;
  private volatile long aiElapsedMillis;
  private volatile long turnStartedAt;
  private volatile String gameResult = "";
  private BoardHistoryNode trainingStartNode;
  private HumanSlTrainingReportDialog reportDialog;

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
    return finished;
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

  public String gameResult() {
    return gameResult;
  }

  /** Sets up the board and starts the game. Must be called on the EDT. */
  public void start() {
    start(false);
  }

  /** Starts after setup has already paused foreground analysis to free GPU resources. */
  void start(boolean analysisWasPonderingBeforePreparation) {
    if (config.fromCurrentPosition) {
      // Keep the original SGF metadata and main line untouched. The first training move is forced
      // into a variation from this node, including after "retry this move" returns here.
      trainingStartNode = Lizzie.board.getHistory().getCurrentHistoryNode();
    } else {
      Lizzie.board.clear(false);
      Lizzie.board.getHistory().getGameInfo().setKomi(config.komi);
      Lizzie.board.getHistory().getGameInfo().setHandicap(config.handicap);
      if (config.handicap >= 2 && Board.boardWidth == 19 && Board.boardHeight == 19) {
        Lizzie.board.setupFixedHandicap(config.handicap);
      }
      Lizzie.board.getHistory().getGameInfo().setKomi(config.komi);
      configurePlayerNames();
    }
    hideAnalysisVisuals();
    ponderingBefore = ponderingBefore || analysisWasPonderingBeforePreparation;
    Lizzie.frame.humanSlGame = this;
    trainingSession.setState(HumanSlTrainingSession.State.PLAYING);
    turnStartedAt = System.currentTimeMillis();
    Lizzie.frame.showHumanSlTrainingBar(this);
    Lizzie.frame.refresh();

    if (!isHumanTurn()) {
      scheduleAiMove();
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
    if (!finished) {
      Lizzie.frame.showHumanSlTrainingBar(this);
      Lizzie.frame.setMainPanelFocus();
    } else if (reportDialog != null) {
      reportDialog.showReport();
    }
  }

  /** Called from LizzieFrame when a coaching game is active. */
  public void onBoardClicked(int x, int y) {
    if (finished || awaitingCorrection || !isHumanTurn() || !Board.isValid(x, y)) {
      return;
    }
    if (Lizzie.board.getHistory().getStones()[Board.getIndex(x, y)] != Stone.EMPTY) {
      return;
    }
    BoardHistoryNode positionBefore = Lizzie.board.getHistory().getCurrentHistoryNode();
    String move = Board.convertCoordinatesToName(x, y);
    if (!placeLocal(x, y, humanIsBlack ? Stone.BLACK : Stone.WHITE)) {
      return;
    }
    recordTurnElapsed(true);
    rememberHumanMove(positionBefore, move);
  }

  public void humanPass() {
    if (finished || awaitingCorrection || !isHumanTurn()) {
      return;
    }
    BoardHistoryNode positionBefore = Lizzie.board.getHistory().getCurrentHistoryNode();
    if (!passLocal(humanIsBlack ? Stone.BLACK : Stone.WHITE)) {
      return;
    }
    recordTurnElapsed(true);
    rememberHumanMove(positionBefore, "pass");
  }

  private void rememberHumanMove(BoardHistoryNode positionBefore, String move) {
    synchronized (pendingHumanMoves) {
      pendingHumanMoves.add(new PendingHumanMove(positionBefore, move));
    }
    if (config.mode == TrainingMode.LIVE_CORRECTION) {
      analyzeForLiveCorrection(positionBefore, move);
    } else {
      scheduleAiMove();
    }
  }

  private void analyzeForLiveCorrection(BoardHistoryNode positionBefore, String move) {
    awaitingCorrection = true;
    long generation = requestGeneration.get();
    gameExecutor.execute(
        () -> {
          Optional<HumanMoveDecision> decision =
              runner.evaluateHumanMove(
                  positionBefore,
                  profile,
                  move,
                  config.analysisVisits(),
                  config.rootSymmetries(),
                  REVIEW_TIMEOUT);
          if (finished || generation != requestGeneration.get()) {
            return;
          }
          decision.ifPresent(this::recordDecision);
          SwingUtilities.invokeLater(
              () -> {
                if (finished || generation != requestGeneration.get()) {
                  return;
                }
                if (decision.isPresent() && decision.get().isProblemMove()) {
                  Lizzie.frame.showHumanSlCorrection(this, decision.get());
                } else {
                  awaitingCorrection = false;
                  scheduleAiMove();
                }
              });
        });
  }

  public void retryHumanMove(HumanMoveDecision decision) {
    if (finished || decision == null) {
      return;
    }
    requestGeneration.incrementAndGet();
    awaitingCorrection = false;
    discardMoveAssessment(decision.moveNumber);
    Lizzie.frame.hideHumanSlCorrection(this);
    Lizzie.board.navigateToNode(decision.positionBeforeMove);
    turnStartedAt = System.currentTimeMillis();
    Lizzie.frame.refresh();
    Lizzie.frame.setMainPanelFocus();
  }

  private void discardMoveAssessment(int moveNumber) {
    synchronized (pendingHumanMoves) {
      pendingHumanMoves.removeIf(
          move -> move.positionBefore.getData().moveNumber + 1 == moveNumber);
    }
    synchronized (assessedMoveNumbers) {
      assessedMoveNumbers.remove(moveNumber);
    }
    trainingSession.removeDecision(moveNumber);
  }

  public void continueAfterCorrection() {
    if (finished) {
      return;
    }
    awaitingCorrection = false;
    Lizzie.frame.hideHumanSlCorrection(this);
    scheduleAiMove();
  }

  public void humanResign() {
    if (finished) {
      return;
    }
    String result =
        humanIsBlack
            ? text("Leelaz.whiteWin", "White wins")
            : text("Leelaz.blackWin", "Black wins");
    beginReview(result, false);
  }

  public void finishAndReview() {
    beginReview(null, true);
  }

  /** Legacy alias retained for existing menu/control integrations. */
  public void countAndFinish() {
    finishAndReview();
  }

  public void saveKifu() {
    LizzieFrame.saveFile(false);
  }

  public void saveTrainingReport() {
    TrainingSessionReport report = trainingSession.report();
    if (report == null || report.isEmpty()) {
      saveKifu();
      return;
    }
    BoardHistoryNode root = Lizzie.board.getHistory().root();
    String original = root.getData().comment == null ? "" : root.getData().comment;
    String withoutOld = stripStoredReport(original).trim();
    String serialized = serializeReport(report);
    root.getData().comment =
        (withoutOld.isEmpty() ? "" : withoutOld + "\n\n")
            + REPORT_BEGIN
            + "\n"
            + serialized
            + "\n"
            + REPORT_END;
    saveKifu();
  }

  public void retryReportPosition(HumanMoveDecision decision) {
    if (decision == null) {
      return;
    }
    Lizzie.board.navigateToNode(decision.positionBeforeMove);
    Lizzie.frame.refresh();
    Lizzie.frame.setMainPanelFocus();
    SwingUtilities.invokeLater(() -> Lizzie.frame.startHumanSlGameDialogAtCurrentPosition());
  }

  private void scheduleAiMove() {
    if (finished || awaitingCorrection || isReviewing() || aiThinking) {
      return;
    }
    aiFailed = false;
    aiThinking = true;
    Lizzie.frame.updateHumanSlTrainingBar();
    long generation = requestGeneration.get();
    Board requestBoard = Lizzie.board;
    BoardHistoryNode positionNode = requestBoard.getHistory().getCurrentHistoryNode();
    long positionContextRevision = requestBoard.getContextRevision();
    try {
      gameExecutor.execute(
          () -> {
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
          });
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
      aiThinking = false;
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
    if (finished || awaitingCorrection) {
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
    if (finished || isReviewing() || !aiFailed || isHumanTurn()) {
      return;
    }
    runner.cancelActiveRequests();
    scheduleAiMove();
  }

  private void beginReview(String result, boolean estimateResult) {
    if (finished || trainingSession.state() == HumanSlTrainingSession.State.REVIEWING) {
      return;
    }
    long reviewGeneration = requestGeneration.incrementAndGet();
    awaitingCorrection = false;
    aiThinking = false;
    aiFailed = false;
    gameExecutor.shutdownNow();
    runner.cancelActiveRequests();
    Lizzie.frame.hideHumanSlCorrection(this);
    trainingSession.setState(HumanSlTrainingSession.State.REVIEWING);
    Lizzie.frame.updateHumanSlTrainingBar();
    reviewExecutor.execute(
        () -> {
          String resolvedResult = result;
          if (estimateResult) {
            resolvedResult = describeScoreResult(evaluateCurrentPosition());
          }
          if (finished
              || Thread.currentThread().isInterrupted()
              || reviewGeneration != requestGeneration.get()) {
            return;
          }
          analyzePendingHumanMoves();
          if (finished
              || Thread.currentThread().isInterrupted()
              || reviewGeneration != requestGeneration.get()) {
            return;
          }
          deepenKeyPositions(trainingSession.buildReport());
          TrainingSessionReport report = trainingSession.buildReport();
          String finalResult =
              resolvedResult == null
                  ? text("HumanSlGame.resultUnknown", "Result unavailable")
                  : resolvedResult;
          SwingUtilities.invokeLater(
              () -> completeReview(report, finalResult, reviewGeneration));
        });
  }

  private void analyzePendingHumanMoves() {
    List<PendingHumanMove> snapshot;
    synchronized (pendingHumanMoves) {
      snapshot = new ArrayList<PendingHumanMove>(pendingHumanMoves);
    }
    for (PendingHumanMove move : snapshot) {
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
      int moveNumber = move.positionBefore.getData().moveNumber + 1;
      synchronized (assessedMoveNumbers) {
        if (assessedMoveNumbers.contains(moveNumber)) {
          continue;
        }
      }
      runner
          .evaluateHumanMove(
              move.positionBefore,
              profile,
              move.move,
              QUICK_REVIEW_VISITS,
              config.rootSymmetries(),
              REVIEW_TIMEOUT)
          .ifPresent(this::recordDecision);
    }
  }

  private void recordDecision(HumanMoveDecision decision) {
    synchronized (assessedMoveNumbers) {
      if (!assessedMoveNumbers.add(decision.moveNumber)) {
        return;
      }
    }
    trainingSession.addDecision(decision);
  }

  private void deepenKeyPositions(TrainingSessionReport preliminaryReport) {
    if (preliminaryReport == null || preliminaryReport.isEmpty()) {
      return;
    }
    for (TrainingMoveAssessment assessment : preliminaryReport.assessments()) {
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
      HumanMoveDecision quick = assessment.decision;
      runner
          .evaluateHumanMove(
              quick.positionBeforeMove,
              profile,
              quick.actualMove,
              DEEP_REVIEW_VISITS,
              config.rootSymmetries(),
              REVIEW_TIMEOUT)
          .ifPresent(decision -> trainingSession.upsertDecision(decision, true));
    }
  }

  private void completeReview(
      TrainingSessionReport report, String result, long reviewGeneration) {
    if (finished || reviewGeneration != requestGeneration.get()) {
      return;
    }
    finished = true;
    gameResult = result;
    if (!config.fromCurrentPosition) {
      Lizzie.board.getHistory().getGameInfo().setResult(gameResult);
    }
    trainingSession.setState(HumanSlTrainingSession.State.REPORT_READY);
    restoreAnalysisVisuals();
    Lizzie.frame.hideHumanSlCorrection(this);
    Lizzie.frame.hideHumanSlTrainingBar(this);
    Lizzie.frame.humanSlGame = null;
    reviewExecutor.shutdown();
    closeRunnerNow();
    reportDialog = new HumanSlTrainingReportDialog(Lizzie.frame, this, report);
    Lizzie.frame.setHumanSlTrainingReport(reportDialog);
    reportDialog.showReport();
    Lizzie.frame.refresh();
  }

  /** Stops the game without producing a report. */
  public void abort() {
    if (finished) {
      return;
    }
    finished = true;
    requestGeneration.incrementAndGet();
    aiThinking = false;
    aiFailed = false;
    gameExecutor.shutdownNow();
    reviewExecutor.shutdownNow();
    runner.cancelActiveRequests();
    trainingSession.setState(HumanSlTrainingSession.State.FINISHED);
    restoreAnalysisVisuals();
    Lizzie.frame.hideHumanSlCorrection(this);
    Lizzie.frame.hideHumanSlTrainingBar(this);
    Lizzie.frame.humanSlGame = null;
    closeRunnerNow();
    Lizzie.frame.refresh();
  }

  private void closeRunnerNow() {
    Thread closer =
        new Thread(
            () -> {
              try {
                runner.close();
              } catch (Exception ignored) {
              }
            },
            "humansl-coach-close");
    closer.setDaemon(true);
    closer.start();
  }

  private boolean placeLocal(int x, int y, Stone color) {
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    BoardHistoryNode before = Lizzie.board.getHistory().getCurrentHistoryNode();
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = true;
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
      Lizzie.leelaz.isInputCommand = true;
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

  private PositionEvaluation evaluateCurrentPosition() {
    try {
      BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
      JSONObject request =
          AnalysisRequestBuilder.buildRequest(
              "humansl-finish-" + System.currentTimeMillis(), node, 200, false, false, false);
      JSONObject overrideSettings = request.optJSONObject("overrideSettings");
      if (overrideSettings == null) {
        overrideSettings = new JSONObject();
      }
      overrideSettings.put("reportAnalysisWinratesAs", "BLACK");
      request.put("overrideSettings", overrideSettings);
      JSONObject response = runner.request(request, Duration.ofSeconds(20));
      JSONObject rootInfo = response.optJSONObject("rootInfo");
      if (rootInfo == null) {
        JSONArray moveInfos = response.optJSONArray("moveInfos");
        if (moveInfos != null && moveInfos.length() > 0) {
          rootInfo = moveInfos.optJSONObject(0);
        }
      }
      if (rootInfo == null) {
        return PositionEvaluation.unavailable();
      }
      return new PositionEvaluation(
          rootInfo.optDouble("winrate", Double.NaN),
          rootInfo.optDouble("scoreLead", Double.NaN));
    } catch (TimeoutException | IOException e) {
      runner.cancelActiveRequests();
      return PositionEvaluation.unavailable();
    }
  }

  private String describeScoreResult(PositionEvaluation evaluation) {
    if (!evaluation.available || Double.isNaN(evaluation.scoreLead)) {
      return text("HumanSlGame.resultUnknown", "Result unavailable");
    }
    if (Math.abs(evaluation.scoreLead) < 0.05) {
      return text("HumanSlGame.draw", "Draw");
    }
    String winner =
        evaluation.scoreLead > 0
            ? text("Menu.Black", "Black")
            : text("Menu.White", "White");
    return winner
        + " +"
        + String.format(java.util.Locale.US, "%.1f", Math.abs(evaluation.scoreLead));
  }

  private void hideAnalysisVisuals() {
    candidatesBlackBefore = Lizzie.config.showBlackCandidates;
    candidatesWhiteBefore = Lizzie.config.showWhiteCandidates;
    showWinrateGraphBefore = Lizzie.config.showWinrateGraph;
    showWinrateInSuggestionBefore = Lizzie.config.showWinrateInSuggestion;
    showKataGoEstimateBefore = Lizzie.config.showKataGoEstimate;
    showingPolicyBefore = Lizzie.frame != null && Lizzie.frame.isShowingPolicy;
    showingHeatmapBefore = Lizzie.frame != null && Lizzie.frame.isShowingHeatmap;
    ponderingBefore = Lizzie.leelaz != null && Lizzie.leelaz.isPondering();
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
    if (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.notPondering();
      Lizzie.leelaz.nameCmd();
    }
  }

  private void restoreAnalysisVisuals() {
    Lizzie.config.showBlackCandidates = candidatesBlackBefore;
    Lizzie.config.showWhiteCandidates = candidatesWhiteBefore;
    Lizzie.config.showWinrateGraph = showWinrateGraphBefore;
    Lizzie.config.showWinrateInSuggestion = showWinrateInSuggestionBefore;
    Lizzie.config.showKataGoEstimate = showKataGoEstimateBefore;
    if (Lizzie.frame != null) {
      Lizzie.frame.isShowingPolicy = showingPolicyBefore;
      Lizzie.frame.isShowingHeatmap = showingHeatmapBefore;
    }
    if (ponderingBefore && Lizzie.leelaz != null && !Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.ponder();
    }
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

  private String serializeReport(TrainingSessionReport report) {
    StringBuilder text = new StringBuilder();
    text.append("AI Coach report v1").append('\n');
    text.append("Opponent: ").append(opponentLabel()).append('\n');
    text.append("Result: ").append(gameResult).append('\n');
    for (TrainingMoveAssessment assessment : report.assessments()) {
      HumanMoveDecision decision = assessment.decision;
      text.append("Move ")
          .append(decision.moveNumber)
          .append(": actual=")
          .append(decision.actualMove)
          .append(", human=")
          .append(decision.commonHumanMove)
          .append(", best=")
          .append(decision.kataGoBestMove)
          .append(", scoreLoss=")
          .append(formatMetric(decision.scoreLoss))
          .append(", winrateLoss=")
          .append(formatMetric(decision.winrateLoss))
          .append('\n');
    }
    return text.toString().trim();
  }

  private static String stripStoredReport(String comment) {
    if (comment == null) {
      return "";
    }
    int begin = comment.indexOf(REPORT_BEGIN);
    if (begin < 0) {
      return comment;
    }
    int end = comment.indexOf(REPORT_END, begin);
    if (end < 0) {
      return comment.substring(0, begin);
    }
    return comment.substring(0, begin) + comment.substring(end + REPORT_END.length());
  }

  private static String formatMetric(double value) {
    return Double.isFinite(value) ? String.format(java.util.Locale.US, "%.3f", value) : "-";
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

  private static final class PendingHumanMove {
    private final BoardHistoryNode positionBefore;
    private final String move;

    private PendingHumanMove(BoardHistoryNode positionBefore, String move) {
      this.positionBefore = positionBefore;
      this.move = move;
    }
  }

  private static final class PositionEvaluation {
    private final boolean available;
    private final double blackWinrate;
    private final double scoreLead;

    private PositionEvaluation(double blackWinrate, double scoreLead) {
      available = true;
      this.blackWinrate = blackWinrate;
      this.scoreLead = scoreLead;
    }

    private PositionEvaluation() {
      available = false;
      blackWinrate = Double.NaN;
      scoreLead = Double.NaN;
    }

    private static PositionEvaluation unavailable() {
      return new PositionEvaluation();
    }
  }
}
