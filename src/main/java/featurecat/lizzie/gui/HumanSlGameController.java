package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisRequestBuilder;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Drives a casual human-vs-AI game where the AI imitates a chosen amateur/professional rank using
 * KataGo's HumanSL policy. The controller owns its own analysis-engine process so it never disturbs
 * the regular analysis engine, and it deliberately keeps move suggestions and the winrate graph
 * hidden until the game is finished.
 */
public final class HumanSlGameController {
  private static final int AI_MOVE_RETRIES = 3;
  private static final Duration WARMUP_TIMEOUT = Duration.ofSeconds(180);
  private static final long MIN_MOVE_DELAY_MILLIS = 800L;
  private static final long MAX_MOVE_DELAY_MILLIS = 4000L;

  private final HumanSlAnalysisRunner runner;
  private final String profile;
  private final boolean humanIsBlack;
  private final int handicap;
  private final double komi;
  private final Duration moveTimeout;
  private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

  private boolean candidatesBlackBefore;
  private boolean candidatesWhiteBefore;
  private boolean showWinrateGraphBefore;
  private boolean showWinrateInSuggestionBefore;
  private boolean showKataGoEstimateBefore;
  private boolean showingPolicyBefore;
  private boolean showingHeatmapBefore;
  private boolean ponderingBefore;
  private volatile boolean finished;
  private HumanSlGameControlPanel controlPanel;

  public HumanSlGameController(
      HumanSlAnalysisRunner runner,
      String profile,
      boolean humanIsBlack,
      int handicap,
      double komi,
      int moveTimeoutSeconds) {
    this.runner = runner;
    this.profile = profile;
    this.humanIsBlack = humanIsBlack;
    this.handicap = handicap;
    this.komi = komi;
    this.moveTimeout = Duration.ofSeconds(Math.max(2, moveTimeoutSeconds));
  }

  public boolean isFinished() {
    return finished;
  }

  public boolean isHumanTurn() {
    return Lizzie.board.getHistory().isBlacksTurn() == humanIsBlack;
  }

  /** Sets up the board and starts the game. Must be called on the EDT. */
  public void start() {
    Lizzie.board.clear(false);
    Lizzie.board.getHistory().getGameInfo().setKomi(komi);
    Lizzie.board.getHistory().getGameInfo().setHandicap(handicap);
    if (handicap >= 2 && Board.boardWidth == 19 && Board.boardHeight == 19) {
      placeHandicap(handicap);
    }
    Lizzie.board.getHistory().getGameInfo().setKomi(komi);
    String me = Lizzie.resourceBundle.getString("HumanSlGame.humanPlayer");
    String ai = Lizzie.resourceBundle.getString("HumanSlGame.aiPlayer") + " (" + rankLabel() + ")";
    Lizzie.board.getHistory().getGameInfo().setPlayerBlack(humanIsBlack ? me : ai);
    Lizzie.board.getHistory().getGameInfo().setPlayerWhite(humanIsBlack ? ai : me);

    hideAnalysisVisuals();
    Lizzie.frame.humanSlGame = this;

    Lizzie.frame.refresh();
    showControlPanel();

    warmUpEngine();
    if (!isHumanTurn()) {
      scheduleAiMove();
    }
  }

  /**
   * Forces the HumanSL engine to load its weights before any timed move so the first real move
   * respects the configured time budget instead of paying the model-load cost. Runs on the single
   * AI executor thread, so it always completes before the first scheduled move.
   */
  private void warmUpEngine() {
    aiExecutor.execute(
        () -> {
          if (finished) {
            return;
          }
          BoardHistoryNode positionNode = Lizzie.board.getHistory().getCurrentHistoryNode();
          runner.bestHumanMove(positionNode, profile, WARMUP_TIMEOUT);
        });
  }

  /** Opens the in-game controls (pass/resign/count/save). */
  public void showControlPanel() {
    if (finished) {
      return;
    }
    if (controlPanel == null) {
      controlPanel = new HumanSlGameControlPanel(this);
    }
    controlPanel.setVisible(true);
    controlPanel.toFront();
  }

  /** Called from LizzieFrame.onClicked when a HumanSL game is active. */
  public void onBoardClicked(int x, int y) {
    if (finished || !isHumanTurn()) {
      return;
    }
    if (!Board.isValid(x, y)) {
      return;
    }
    if (Lizzie.board.getHistory().getStones()[Board.getIndex(x, y)] != Stone.EMPTY) {
      return;
    }
    Stone humanColor = humanIsBlack ? Stone.BLACK : Stone.WHITE;
    placeLocal(x, y, humanColor);
    if (finished) {
      return;
    }
    scheduleAiMove();
  }

  public void humanPass() {
    if (finished || !isHumanTurn()) {
      return;
    }
    Stone humanColor = humanIsBlack ? Stone.BLACK : Stone.WHITE;
    passLocal(humanColor);
    scheduleAiMove();
  }

  public void humanResign() {
    if (finished) {
      return;
    }
    String result =
        humanIsBlack
            ? Lizzie.resourceBundle.getString("Leelaz.whiteWin")
            : Lizzie.resourceBundle.getString("Leelaz.blackWin");
    finishGame(result, Lizzie.resourceBundle.getString("HumanSlGame.humanResigned"));
  }

  /** Evaluates the final position and ends the game, showing winrate/score info. */
  public void countAndFinish() {
    if (finished) {
      return;
    }
    aiExecutor.execute(
        () -> {
          PositionEvaluation evaluation = evaluateCurrentPosition();
          SwingUtilities.invokeLater(
              () -> {
                String result = describeScoreResult(evaluation);
                finishGame(result, describeEvaluation(evaluation));
              });
        });
  }

  public void saveKifu() {
    LizzieFrame.saveFile(false);
  }

  private void scheduleAiMove() {
    if (finished) {
      return;
    }
    aiExecutor.execute(
        () -> {
          long startedAt = System.currentTimeMillis();
          BoardHistoryNode positionNode = Lizzie.board.getHistory().getCurrentHistoryNode();
          Optional<String> move = Optional.empty();
          for (int attempt = 0; attempt < AI_MOVE_RETRIES && !finished; attempt++) {
            move = runner.bestHumanMove(positionNode, profile, moveTimeout);
            if (move.isPresent()) {
              break;
            }
          }
          waitMinimumThinkTime(startedAt);
          Optional<String> resolved = move;
          SwingUtilities.invokeLater(() -> applyAiMove(resolved));
        });
  }

  /**
   * Keeps the AI from snapping a move down instantly. Raw policy play returns in milliseconds,
   * which feels unnatural, so we pad short responses up to a small randomized delay similar to
   * KataGo's delayMoveScale/delayMoveMax pacing.
   */
  private void waitMinimumThinkTime(long startedAt) {
    if (finished) {
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

  private void applyAiMove(Optional<String> move) {
    if (finished) {
      return;
    }
    Stone aiColor = humanIsBlack ? Stone.WHITE : Stone.BLACK;
    if (!move.isPresent()) {
      Utils.showMsg(Lizzie.resourceBundle.getString("HumanSlGame.aiMoveFailed"));
      return;
    }
    if ("pass".equalsIgnoreCase(move.get().trim())) {
      passLocal(aiColor);
      Utils.showMsg(Lizzie.resourceBundle.getString("HumanSlGame.aiPassed"));
      return;
    }
    int[] coords = Board.convertNameToCoordinates(move.get().trim());
    if (coords == null
        || coords == LizzieFrame.outOfBoundCoordinate
        || !Board.isValid(coords[0], coords[1])
        || Lizzie.board.getHistory().getStones()[Board.getIndex(coords[0], coords[1])]
            != Stone.EMPTY) {
      passLocal(aiColor);
      return;
    }
    placeLocal(coords[0], coords[1], aiColor);
  }

  private void placeLocal(int x, int y, Stone color) {
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = true;
    }
    try {
      Lizzie.board.place(x, y, color);
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
  }

  private void passLocal(Stone color) {
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = true;
    }
    try {
      Lizzie.board.pass(color, false, false, true);
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
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
      double blackWinrate = rootInfo.optDouble("winrate", Double.NaN);
      double scoreLead = rootInfo.optDouble("scoreLead", Double.NaN);
      return new PositionEvaluation(blackWinrate, scoreLead);
    } catch (TimeoutException | IOException e) {
      return PositionEvaluation.unavailable();
    }
  }

  private String describeScoreResult(PositionEvaluation evaluation) {
    if (!evaluation.available || Double.isNaN(evaluation.scoreLead)) {
      return Lizzie.resourceBundle.getString("HumanSlGame.resultUnknown");
    }
    double scoreLead = evaluation.scoreLead;
    if (Math.abs(scoreLead) < 0.05) {
      return Lizzie.resourceBundle.getString("HumanSlGame.draw");
    }
    boolean blackWins = scoreLead > 0;
    String winner =
        blackWins
            ? Lizzie.resourceBundle.getString("Menu.Black")
            : Lizzie.resourceBundle.getString("Menu.White");
    return winner + " +" + String.format(java.util.Locale.US, "%.1f", Math.abs(scoreLead));
  }

  private String describeEvaluation(PositionEvaluation evaluation) {
    if (!evaluation.available) {
      return Lizzie.resourceBundle.getString("HumanSlGame.evaluationUnavailable");
    }
    String blackWinrate =
        Double.isNaN(evaluation.blackWinrate)
            ? "-"
            : String.format(java.util.Locale.US, "%.1f%%", evaluation.blackWinrate * 100.0);
    String scoreLead =
        Double.isNaN(evaluation.scoreLead)
            ? "-"
            : String.format(java.util.Locale.US, "%+.1f", evaluation.scoreLead);
    return java.text.MessageFormat.format(
        Lizzie.resourceBundle.getString("HumanSlGame.evaluationDetail"), blackWinrate, scoreLead);
  }

  private void finishGame(String result, String detail) {
    if (finished) {
      return;
    }
    finished = true;
    Lizzie.board.getHistory().getGameInfo().setResult(result);
    StringBuilder message = new StringBuilder();
    message.append(Lizzie.resourceBundle.getString("HumanSlGame.gameOver")).append("\n");
    message
        .append(Lizzie.resourceBundle.getString("HumanSlGame.result"))
        .append(" ")
        .append(result);
    if (detail != null && !detail.isEmpty()) {
      message.append("\n").append(detail);
    }
    Utils.showMsg(message.toString());
    teardown();
  }

  /** Stops the game without showing a result (used when the dialog is closed early). */
  public void abort() {
    if (finished) {
      return;
    }
    finished = true;
    teardown();
  }

  private void teardown() {
    restoreAnalysisVisuals();
    Lizzie.frame.humanSlGame = null;
    if (controlPanel != null) {
      controlPanel.dispose();
      controlPanel = null;
    }
    aiExecutor.execute(
        () -> {
          try {
            runner.close();
          } catch (Exception ignored) {
          }
        });
    aiExecutor.shutdown();
    Lizzie.frame.refresh();
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

  private String rankLabel() {
    return profile == null ? "" : profile.replace("rank_", "").toUpperCase(java.util.Locale.ROOT);
  }

  private void placeHandicap(int handicap) {
    int[][] points = handicapPoints(handicap);
    boolean previous = Lizzie.leelaz != null && Lizzie.leelaz.isInputCommand;
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.isInputCommand = true;
    }
    try {
      for (int[] point : points) {
        Lizzie.board.place(point[0], point[1], Stone.BLACK);
      }
    } finally {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isInputCommand = previous;
      }
    }
    Lizzie.board.hasStartStone = true;
    Lizzie.board.addStartListAll();
    Lizzie.board.flatten();
  }

  private static int[][] handicapPoints(int handicap) {
    switch (handicap) {
      case 2:
        return new int[][] {{3, 15}, {15, 3}};
      case 3:
        return new int[][] {{3, 3}, {15, 3}, {3, 15}};
      case 4:
        return new int[][] {{3, 3}, {3, 15}, {15, 3}, {15, 15}};
      case 5:
        return new int[][] {{3, 3}, {3, 15}, {15, 3}, {15, 15}, {9, 9}};
      case 6:
        return new int[][] {{3, 3}, {3, 15}, {15, 3}, {15, 15}, {3, 9}, {15, 9}};
      case 7:
        return new int[][] {{3, 3}, {3, 15}, {15, 3}, {15, 15}, {15, 9}, {3, 9}, {9, 9}};
      case 8:
        return new int[][] {{3, 3}, {3, 15}, {15, 3}, {15, 15}, {9, 3}, {9, 15}, {3, 9}, {15, 9}};
      case 9:
        return new int[][] {
          {3, 3}, {3, 15}, {15, 3}, {15, 15}, {9, 3}, {9, 15}, {3, 9}, {15, 9}, {9, 9}
        };
      default:
        return new int[0][];
    }
  }

  public static Path resolveDefaultHumanModel() {
    featurecat.lizzie.util.KataGoAutoSetupHelper.HumanSlModelStatus status =
        featurecat.lizzie.util.KataGoAutoSetupHelper.inspectHumanSlModel();
    if (status == null || !status.isInstalled()) {
      return null;
    }
    return status.modelPath;
  }

  private static final class PositionEvaluation {
    private final boolean available;
    private final double blackWinrate;
    private final double scoreLead;

    private PositionEvaluation(double blackWinrate, double scoreLead) {
      this.available = true;
      this.blackWinrate = blackWinrate;
      this.scoreLead = scoreLead;
    }

    private PositionEvaluation() {
      this.available = false;
      this.blackWinrate = Double.NaN;
      this.scoreLead = Double.NaN;
    }

    private static PositionEvaluation unavailable() {
      return new PositionEvaluation();
    }
  }
}
