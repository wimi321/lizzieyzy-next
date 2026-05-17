package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Estimates a player's practical strength from already cached main-line analysis. */
public final class PlayerStrengthEstimator {
  private static final int MIN_REPORT_SAMPLES = 6;
  private static final double WINRATE_TO_SCORE_LOSS = 6.0;
  private static final int ADDITIONAL_MOVE_ORDER = 999;
  private static final double MIN_DIFFICULTY_WEIGHT = 0.05;
  private static final double KATRAIN_ACCURACY_BASE = 0.75;
  private static final double RANK_SCORE_LOSS_SCALE = 2.5;
  private static final int STRONG_KYU_LEVEL = 4;
  private static final int LOW_DAN_LEVEL = 6;
  private static final int MID_DAN_LEVEL = 7;
  private static final int HIGH_DAN_LEVEL = 9;
  private static final double LOW_DIFFICULTY_EVIDENCE = 25.0;
  private static final double LOW_DIFFICULTY_TOP_FIRST_CHOICE_RATE = 0.35;
  private static final double LOW_DIFFICULTY_TOP_GOOD_MOVE_RATE = 0.74;
  private static final double TOP_PRO_FIRST_CHOICE_RATE = 0.50;
  private static final double TOP_PRO_GOOD_MOVE_RATE = 0.86;
  private static final double TOP_PRO_MISTAKE_RATE = 0.05;
  private static final double TOP_PRO_WEIGHTED_LOSS = 3.20;
  private static final double PRO_FIRST_CHOICE_RATE = 0.45;
  private static final double PRO_GOOD_MOVE_RATE = 0.86;
  private static final double PRO_MISTAKE_RATE = 0.06;
  private static final double PRO_WEIGHTED_LOSS = 3.00;

  private static final double EXCELLENT_SCORE_LOSS = 0.2;
  private static final double GREAT_SCORE_LOSS = 0.6;
  private static final double GOOD_SCORE_LOSS = 1.2;
  private static final double INACCURACY_SCORE_LOSS = 4.0;
  private static final double MISTAKE_SCORE_LOSS = 10.0;
  private static final String[] STRENGTH_BANDS = {
    "Beginner",
    "11-15k",
    "6-10k",
    "3-5k",
    "1-2k",
    "1-2d",
    "3-4d",
    "5-6d",
    "7d",
    "8d",
    "9d",
    "10d\u804c\u4e1a",
    "11d\u4e00\u7ebf\u804c\u4e1a",
    "12d AI"
  };
  private static final LevelThreshold[] QUALITY_LEVELS = {
    new LevelThreshold(96.0, 13),
    new LevelThreshold(92.0, 12),
    new LevelThreshold(88.0, 11),
    new LevelThreshold(84.0, 10),
    new LevelThreshold(80.0, 9),
    new LevelThreshold(76.0, 8),
    new LevelThreshold(72.0, 7),
    new LevelThreshold(63.0, 6),
    new LevelThreshold(55.0, 5),
    new LevelThreshold(51.0, 4),
    new LevelThreshold(46.0, 3),
    new LevelThreshold(36.0, 2),
    new LevelThreshold(24.0, 1)
  };
  private static final LevelThreshold[] FIRST_CHOICE_CAPS = {
    new LevelThreshold(0.62, 13),
    new LevelThreshold(0.50, 12),
    new LevelThreshold(0.46, 11),
    new LevelThreshold(0.42, 10),
    new LevelThreshold(0.38, 9),
    new LevelThreshold(0.34, 8),
    new LevelThreshold(0.30, 7),
    new LevelThreshold(0.24, 7),
    new LevelThreshold(0.18, 6),
    new LevelThreshold(0.12, 5),
    new LevelThreshold(0.06, 4)
  };
  private static final LevelThreshold[] GOOD_MOVE_CAPS = {
    new LevelThreshold(0.96, 13),
    new LevelThreshold(0.88, 12),
    new LevelThreshold(0.82, 11),
    new LevelThreshold(0.78, 10),
    new LevelThreshold(0.74, 9),
    new LevelThreshold(0.68, 8),
    new LevelThreshold(0.62, 7),
    new LevelThreshold(0.56, 6),
    new LevelThreshold(0.50, 5),
    new LevelThreshold(0.42, 4),
    new LevelThreshold(0.32, 3),
    new LevelThreshold(0.22, 2)
  };
  private static final LevelThreshold[] MISTAKE_CAPS = {
    new LevelThreshold(0.005, 13),
    new LevelThreshold(0.050, 12),
    new LevelThreshold(0.060, 11),
    new LevelThreshold(0.070, 10),
    new LevelThreshold(0.100, 9),
    new LevelThreshold(0.140, 8),
    new LevelThreshold(0.180, 7),
    new LevelThreshold(0.240, 6),
    new LevelThreshold(0.310, 5),
    new LevelThreshold(0.400, 4),
    new LevelThreshold(0.520, 3),
    new LevelThreshold(0.660, 2)
  };
  private static final LevelThreshold[] MEDIAN_LOSS_CAPS = {
    new LevelThreshold(0.05, 13),
    new LevelThreshold(0.25, 12),
    new LevelThreshold(0.50, 11),
    new LevelThreshold(0.80, 10),
    new LevelThreshold(1.10, 9),
    new LevelThreshold(1.50, 8),
    new LevelThreshold(2.00, 7),
    new LevelThreshold(2.60, 6),
    new LevelThreshold(3.40, 5),
    new LevelThreshold(4.60, 4),
    new LevelThreshold(6.50, 3),
    new LevelThreshold(9.00, 2)
  };
  private static final LevelThreshold[] WEIGHTED_LOSS_CAPS = {
    new LevelThreshold(0.35, 13),
    new LevelThreshold(3.20, 12),
    new LevelThreshold(4.00, 11),
    new LevelThreshold(5.00, 10),
    new LevelThreshold(6.20, 9),
    new LevelThreshold(7.00, 8),
    new LevelThreshold(7.50, 7),
    new LevelThreshold(9.50, 6),
    new LevelThreshold(12.50, 5),
    new LevelThreshold(16.50, 4),
    new LevelThreshold(22.00, 3),
    new LevelThreshold(30.00, 2)
  };

  private PlayerStrengthEstimator() {}

  public static Report estimate(BoardHistoryNode start) {
    if (start == null) {
      return Report.empty();
    }

    Accumulator black = new Accumulator();
    Accumulator white = new Accumulator();
    BoardHistoryNode previous = start;
    Optional<BoardHistoryNode> next = previous.next(true);
    while (next.isPresent()) {
      BoardHistoryNode current = next.get();
      Sample sample = sample(previous.getData(), current.getData());
      if (sample != null) {
        if (sample.color.isBlack()) {
          black.add(sample);
        } else if (sample.color.isWhite()) {
          white.add(sample);
        }
      }
      previous = current;
      next = current.next(true);
    }

    SideReport blackReport = black.toReport();
    SideReport whiteReport = white.toReport();
    SideReport overallReport = Accumulator.merge(black, white).toReport();
    return new Report(blackReport, whiteReport, overallReport);
  }

  private static Sample sample(BoardData previous, BoardData current) {
    if (previous == null || current == null || !current.isHistoryActionNode()) {
      return null;
    }
    if (!current.lastMoveColor.isBlack() && !current.lastMoveColor.isWhite()) {
      return null;
    }
    if (!hasPrimaryAnalysisPayload(previous) || !hasPrimaryAnalysisPayload(current)) {
      return null;
    }

    CandidateMatch playedCandidate = findPlayedCandidate(previous, current);
    LossEstimate lossEstimate = lossEstimate(previous, current, playedCandidate);
    double scoreEquivalentLoss = positive(lossEstimate.scoreEquivalentLoss());
    double complexity = complexity(previous);
    double adjustedWeight = adjustedWeight(complexity, scoreEquivalentLoss);
    boolean firstChoice =
        playedCandidate.rank == 0 || playedCoordinate(current).equalsIgnoreCase(topMove(previous));
    return new Sample(
        current.lastMoveColor,
        positive(lossEstimate.winrateLoss),
        lossEstimate.scoreLoss.map(PlayerStrengthEstimator::positive),
        firstChoice,
        playedCandidate.rank,
        categorize(scoreEquivalentLoss),
        scoreEquivalentLoss,
        complexity,
        adjustedWeight);
  }

  private static boolean hasPrimaryAnalysisPayload(BoardData data) {
    return data != null
        && (data.getPlayouts() > 0
            || data.analysisHeaderSlots > 0
            || !isBlank(data.engineName)
            || (data.bestMoves != null && !data.bestMoves.isEmpty())
            || data.isKataData
            || (data.estimateArray != null && !data.estimateArray.isEmpty()));
  }

  private static LossEstimate lossEstimate(
      BoardData previous, BoardData current, CandidateMatch playedCandidate) {
    if (playedCandidate.move != null
        && previous.bestMoves != null
        && !previous.bestMoves.isEmpty()) {
      MoveData top = previous.bestMoves.get(0);
      Optional<Double> scoreLoss = candidateScoreLoss(top, playedCandidate.move);
      Optional<Double> winrateLoss = candidateWinrateLoss(top, playedCandidate.move);
      if (scoreLoss.isPresent() || winrateLoss.isPresent()) {
        return new LossEstimate(
            winrateLoss.orElseGet(() -> fallbackWinrateLoss(previous, current)), scoreLoss);
      }
    }
    return new LossEstimate(
        fallbackWinrateLoss(previous, current), fallbackScoreLoss(previous, current));
  }

  private static Optional<Double> candidateScoreLoss(MoveData top, MoveData actual) {
    if (top == null
        || actual == null
        || !hasMoveScorePayload(top)
        || !hasMoveScorePayload(actual)) {
      return Optional.empty();
    }
    return Optional.of(top.scoreMean - actual.scoreMean);
  }

  private static Optional<Double> candidateWinrateLoss(MoveData top, MoveData actual) {
    if (top == null
        || actual == null
        || !hasMoveWinratePayload(top)
        || !hasMoveWinratePayload(actual)) {
      return Optional.empty();
    }
    return Optional.of(top.winrate - actual.winrate);
  }

  private static Optional<Double> candidateScoreEquivalentLoss(MoveData top, MoveData actual) {
    Optional<Double> scoreLoss = candidateScoreLoss(top, actual);
    if (scoreLoss.isPresent()) {
      return scoreLoss;
    }
    return candidateWinrateLoss(top, actual).map(loss -> loss / WINRATE_TO_SCORE_LOSS);
  }

  private static boolean hasMoveScorePayload(MoveData move) {
    return move != null && move.isKataData && Double.isFinite(move.scoreMean);
  }

  private static boolean hasMoveWinratePayload(MoveData move) {
    return move != null
        && Double.isFinite(move.winrate)
        && (move.winrate > 0.0 || move.playouts > 0 || move.isKataData);
  }

  private static Optional<Double> fallbackScoreLoss(BoardData previous, BoardData current) {
    if (!hasScorePayload(previous, current)) {
      return Optional.empty();
    }
    return Optional.of(scoreLoss(previous, current));
  }

  private static boolean hasScorePayload(BoardData previous, BoardData current) {
    return (previous.isKataData || current.isKataData)
        && Double.isFinite(previous.scoreMean)
        && Double.isFinite(current.scoreMean);
  }

  private static double fallbackWinrateLoss(BoardData previous, BoardData current) {
    double expectedOpponentWinrate =
        previous.blackToPlay == current.blackToPlay ? previous.winrate : 100.0 - previous.winrate;
    return current.winrate - expectedOpponentWinrate;
  }

  private static double scoreLoss(BoardData previous, BoardData current) {
    double expectedOpponentScore =
        previous.blackToPlay == current.blackToPlay ? previous.scoreMean : -previous.scoreMean;
    return current.scoreMean - expectedOpponentScore;
  }

  private static CandidateMatch findPlayedCandidate(BoardData previous, BoardData current) {
    if (previous.bestMoves == null || previous.bestMoves.isEmpty()) {
      return CandidateMatch.missing();
    }
    String played = playedCoordinate(current);
    if (isBlank(played)) {
      return CandidateMatch.missing();
    }
    for (int index = 0; index < previous.bestMoves.size(); index++) {
      MoveData move = previous.bestMoves.get(index);
      if (move != null && played.equalsIgnoreCase(move.coordinate)) {
        return new CandidateMatch(index, move);
      }
    }
    return CandidateMatch.missing();
  }

  private static String playedCoordinate(BoardData current) {
    if (current == null) {
      return "";
    }
    if (current.isPassNode()) {
      return "pass";
    }
    return current
        .lastMove
        .map(move -> Board.convertCoordinatesToName(move[0], move[1]))
        .orElse("");
  }

  private static String topMove(BoardData previous) {
    if (previous == null || previous.bestMoves == null || previous.bestMoves.isEmpty()) {
      return "";
    }
    String topMove = previous.bestMoves.get(0).coordinate;
    return isBlank(topMove) ? "" : topMove;
  }

  private static double complexity(BoardData data) {
    if (data == null || data.bestMoves == null || data.bestMoves.isEmpty()) {
      return 0.0;
    }
    MoveData top = data.bestMoves.get(0);
    double weightedLossSum = 0.0;
    double priorSum = 0.0;
    for (int index = 0; index < data.bestMoves.size(); index++) {
      MoveData move = data.bestMoves.get(index);
      if (move == null || candidateOrder(move, index) >= ADDITIONAL_MOVE_ORDER) {
        continue;
      }
      double prior = policyWeight(move);
      if (prior <= 0.0) {
        continue;
      }
      Optional<Double> candidateLoss = candidateScoreEquivalentLoss(top, move);
      if (candidateLoss.isEmpty()) {
        continue;
      }
      weightedLossSum += positive(candidateLoss.get()) * prior;
      priorSum += prior;
    }
    if (priorSum > 0.0) {
      return clamp(weightedLossSum / priorSum, 0.0, 1.0);
    }
    return fallbackComplexity(data.bestMoves);
  }

  private static double fallbackComplexity(List<MoveData> moves) {
    if (moves == null || moves.size() < 2) {
      return 0.0;
    }
    MoveData top = moves.get(0);
    MoveData second = moves.get(1);
    return candidateScoreEquivalentLoss(top, second)
        .map(loss -> clamp(positive(loss) / GOOD_SCORE_LOSS, 0.0, 1.0))
        .orElse(0.0);
  }

  private static int candidateOrder(MoveData move, int fallbackOrder) {
    return move.order > 0 ? move.order : fallbackOrder;
  }

  private static double policyWeight(MoveData move) {
    if (move == null || !Double.isFinite(move.policy)) {
      return 0.0;
    }
    return Math.max(0.0, move.policy);
  }

  private static double adjustedWeight(double complexity, double scoreEquivalentLoss) {
    return clamp(
        Math.max(complexity, positive(scoreEquivalentLoss) / INACCURACY_SCORE_LOSS),
        MIN_DIFFICULTY_WEIGHT,
        1.0);
  }

  private static MoveCategory categorize(double scoreEquivalentLoss) {
    double loss = positive(scoreEquivalentLoss);
    if (loss < EXCELLENT_SCORE_LOSS) {
      return MoveCategory.EXCELLENT;
    }
    if (loss < GREAT_SCORE_LOSS) {
      return MoveCategory.GREAT;
    }
    if (loss < GOOD_SCORE_LOSS) {
      return MoveCategory.GOOD;
    }
    if (loss < INACCURACY_SCORE_LOSS) {
      return MoveCategory.INACCURACY;
    }
    if (loss < MISTAKE_SCORE_LOSS) {
      return MoveCategory.MISTAKE;
    }
    return MoveCategory.BLUNDER;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static double positive(double loss) {
    return Math.max(0.0, loss);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  public static final class Report {
    public final SideReport black;
    public final SideReport white;
    public final SideReport overall;

    private Report(SideReport black, SideReport white, SideReport overall) {
      this.black = black;
      this.white = white;
      this.overall = overall;
    }

    private static Report empty() {
      SideReport empty = new Accumulator().toReport();
      return new Report(empty, empty, empty);
    }

    public boolean hasEnoughData() {
      return overall.sampleCount >= MIN_REPORT_SAMPLES;
    }
  }

  public static final class SideReport {
    public final int sampleCount;
    public final int scoreSampleCount;
    public final double qualityScore;
    public final double averageWinrateLoss;
    public final double averageScoreLoss;
    public final double medianScoreLoss;
    public final double weightedScoreLoss;
    public final double averageScoreEquivalentLoss;
    public final double firstChoiceRate;
    public final double goodMoveRate;
    public final double badMoveRate;
    public final double mistakeRate;
    public final double blunderRate;
    public final double averageDifficulty;
    public final String strengthBand;
    public final Confidence confidence;
    public final List<Sample> samples;

    private SideReport(
        int sampleCount,
        int scoreSampleCount,
        double qualityScore,
        double averageWinrateLoss,
        double averageScoreLoss,
        double medianScoreLoss,
        double weightedScoreLoss,
        double averageScoreEquivalentLoss,
        double firstChoiceRate,
        double goodMoveRate,
        double badMoveRate,
        double mistakeRate,
        double blunderRate,
        double averageDifficulty,
        String strengthBand,
        Confidence confidence,
        List<Sample> samples) {
      this.sampleCount = sampleCount;
      this.scoreSampleCount = scoreSampleCount;
      this.qualityScore = qualityScore;
      this.averageWinrateLoss = averageWinrateLoss;
      this.averageScoreLoss = averageScoreLoss;
      this.medianScoreLoss = medianScoreLoss;
      this.weightedScoreLoss = weightedScoreLoss;
      this.averageScoreEquivalentLoss = averageScoreEquivalentLoss;
      this.firstChoiceRate = firstChoiceRate;
      this.goodMoveRate = goodMoveRate;
      this.badMoveRate = badMoveRate;
      this.mistakeRate = mistakeRate;
      this.blunderRate = blunderRate;
      this.averageDifficulty = averageDifficulty;
      this.strengthBand = strengthBand;
      this.confidence = confidence;
      this.samples = Collections.unmodifiableList(samples);
    }

    public boolean hasSamples() {
      return sampleCount > 0;
    }

    public String qualityScoreText() {
      return String.format(Locale.US, "%.0f", qualityScore);
    }

    public String averageWinrateLossText() {
      return String.format(Locale.US, "%.1f%%", averageWinrateLoss);
    }

    public String averageScoreLossText() {
      if (scoreSampleCount <= 0) {
        return "-";
      }
      return String.format(Locale.US, "%.1f", averageScoreLoss);
    }

    public String medianScoreLossText() {
      if (scoreSampleCount <= 0) {
        return "-";
      }
      return String.format(Locale.US, "%.1f", medianScoreLoss);
    }

    public String weightedScoreLossText() {
      return String.format(Locale.US, "%.1f", weightedScoreLoss);
    }

    public String percentText(double value) {
      return String.format(Locale.US, "%.0f%%", value * 100.0);
    }

    public String difficultyText() {
      return String.format(Locale.US, "%.0f", averageDifficulty);
    }
  }

  public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }

  public enum MoveCategory {
    EXCELLENT,
    GREAT,
    GOOD,
    INACCURACY,
    MISTAKE,
    BLUNDER;

    private boolean isGoodMove() {
      return this == EXCELLENT || this == GREAT || this == GOOD;
    }

    private boolean isMistake() {
      return this == MISTAKE || this == BLUNDER;
    }
  }

  public static final class Sample {
    public final Stone color;
    public final double winrateLoss;
    public final Optional<Double> scoreLoss;
    public final boolean firstChoice;
    public final int aiRank;
    public final MoveCategory category;
    public final double scoreEquivalentLoss;
    public final double complexity;
    public final double adjustedWeight;

    private Sample(
        Stone color,
        double winrateLoss,
        Optional<Double> scoreLoss,
        boolean firstChoice,
        int aiRank,
        MoveCategory category,
        double scoreEquivalentLoss,
        double complexity,
        double adjustedWeight) {
      this.color = color;
      this.winrateLoss = winrateLoss;
      this.scoreLoss = scoreLoss;
      this.firstChoice = firstChoice;
      this.aiRank = aiRank;
      this.category = category;
      this.scoreEquivalentLoss = scoreEquivalentLoss;
      this.complexity = complexity;
      this.adjustedWeight = adjustedWeight;
    }
  }

  private static final class Accumulator {
    private final List<Sample> samples = new ArrayList<>();

    private Accumulator() {}

    private void add(Sample sample) {
      samples.add(sample);
    }

    private static Accumulator merge(Accumulator first, Accumulator second) {
      Accumulator merged = new Accumulator();
      merged.samples.addAll(first.samples);
      merged.samples.addAll(second.samples);
      return merged;
    }

    private SideReport toReport() {
      int sampleCount = samples.size();
      if (sampleCount == 0) {
        return new SideReport(
            0,
            0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            "-",
            Confidence.LOW,
            new ArrayList<>());
      }

      double winrateLossSum = 0.0;
      double scoreLossSum = 0.0;
      double scoreEquivalentLossSum = 0.0;
      double weightedPointLossSum = 0.0;
      double weightSum = 0.0;
      double complexitySum = 0.0;
      List<Double> scoreLosses = new ArrayList<>();
      int firstChoices = 0;
      int goodMoves = 0;
      int badMoves = 0;
      int mistakes = 0;
      int blunders = 0;
      for (Sample sample : samples) {
        double winrateLoss = positive(sample.winrateLoss);
        winrateLossSum += winrateLoss;
        double scoreEquivalentLoss = positive(sample.scoreEquivalentLoss);
        scoreEquivalentLossSum += positive(scoreEquivalentLoss);
        weightedPointLossSum += scoreEquivalentLoss * sample.adjustedWeight;
        weightSum += sample.adjustedWeight;
        if (sample.scoreLoss.isPresent()) {
          double scoreLoss = positive(sample.scoreLoss.get());
          scoreLossSum += scoreLoss;
          scoreLosses.add(scoreLoss);
        }
        if (sample.firstChoice) {
          firstChoices++;
        }
        if (sample.category.isGoodMove()) {
          goodMoves++;
        } else {
          badMoves++;
        }
        if (sample.category.isMistake()) {
          mistakes++;
        }
        if (sample.category == MoveCategory.BLUNDER) {
          blunders++;
        }
        complexitySum += sample.complexity;
      }

      int scoreSampleCount = scoreLosses.size();
      double averageWinrateLoss = winrateLossSum / sampleCount;
      double averageScoreLoss = scoreSampleCount == 0 ? 0.0 : scoreLossSum / scoreSampleCount;
      double medianScoreLoss = scoreSampleCount == 0 ? 0.0 : median(scoreLosses);
      double averageScoreEquivalentLoss = scoreEquivalentLossSum / sampleCount;
      double weightedPointLoss =
          weightSum == 0.0 ? averageScoreEquivalentLoss : weightedPointLossSum / weightSum;
      double firstChoiceRate = (double) firstChoices / sampleCount;
      double goodMoveRate = (double) goodMoves / sampleCount;
      double badMoveRate = (double) badMoves / sampleCount;
      double mistakeRate = (double) mistakes / sampleCount;
      double blunderRate = (double) blunders / sampleCount;
      double averageDifficulty = complexitySum * 100.0 / sampleCount;
      double qualityScore =
          qualityScore(
              weightedPointLoss,
              averageScoreEquivalentLoss,
              scoreSampleCount == 0 ? averageScoreEquivalentLoss : medianScoreLoss,
              firstChoiceRate,
              goodMoveRate,
              mistakeRate,
              averageDifficulty);

      return new SideReport(
          sampleCount,
          scoreSampleCount,
          qualityScore,
          averageWinrateLoss,
          averageScoreLoss,
          medianScoreLoss,
          weightedPointLoss,
          averageScoreEquivalentLoss,
          firstChoiceRate,
          goodMoveRate,
          badMoveRate,
          mistakeRate,
          blunderRate,
          averageDifficulty,
          strengthBand(
              qualityScore,
              weightedPointLoss,
              scoreSampleCount == 0 ? averageScoreEquivalentLoss : medianScoreLoss,
              firstChoiceRate,
              goodMoveRate,
              mistakeRate,
              averageDifficulty),
          confidence(sampleCount, scoreSampleCount),
          new ArrayList<>(samples));
    }

    private static double median(List<Double> values) {
      Collections.sort(values);
      int middle = values.size() / 2;
      if (values.size() % 2 == 1) {
        return values.get(middle);
      }
      return (values.get(middle - 1) + values.get(middle)) / 2.0;
    }

    private static double qualityScore(
        double weightedPointLoss,
        double averagePointLoss,
        double medianPointLoss,
        double firstChoiceRate,
        double goodMoveRate,
        double mistakeRate,
        double averageDifficulty) {
      double robustPointLoss =
          0.50 * positive(weightedPointLoss)
              + 0.30 * positive(averagePointLoss)
              + 0.20 * positive(medianPointLoss);
      double lossScore =
          100.0 * Math.pow(KATRAIN_ACCURACY_BASE, robustPointLoss / RANK_SCORE_LOSS_SCALE);
      double goodMoveScore = 100.0 * clamp(goodMoveRate, 0.0, 1.0);
      double mistakeScore = 100.0 * (1.0 - clamp(mistakeRate / 0.18, 0.0, 1.0));
      double firstChoiceScore = 100.0 * clamp((firstChoiceRate - 0.20) / 0.45, 0.0, 1.0);
      double difficultyBonus = clamp((averageDifficulty - 20.0) / 60.0, 0.0, 1.0) * 6.0;
      return clamp(
          0.48 * lossScore
              + 0.27 * goodMoveScore
              + 0.15 * mistakeScore
              + 0.07 * firstChoiceScore
              + difficultyBonus,
          0.0,
          100.0);
    }

    private Confidence confidence(int sampleCount, int scoreSampleCount) {
      if (sampleCount >= 40 && scoreSampleCount >= sampleCount * 0.7) {
        return Confidence.HIGH;
      }
      if (sampleCount >= 16) {
        return Confidence.MEDIUM;
      }
      return Confidence.LOW;
    }
  }

  private static String strengthBand(
      double qualityScore,
      double weightedPointLoss,
      double medianPointLoss,
      double firstChoiceRate,
      double goodMoveRate,
      double mistakeRate,
      double averageDifficulty) {
    int baseLevel =
        Math.max(
            baseLevel(qualityScore),
            eliteEvidenceLevel(weightedPointLoss, firstChoiceRate, goodMoveRate, mistakeRate));
    int level =
        Math.min(
            baseLevel,
            metricCapLevel(
                weightedPointLoss, medianPointLoss, firstChoiceRate, goodMoveRate, mistakeRate));
    level = evidenceAdjustedLevel(level, firstChoiceRate, goodMoveRate, averageDifficulty);
    return STRENGTH_BANDS[level];
  }

  private static int baseLevel(double qualityScore) {
    return levelAtLeast(qualityScore, QUALITY_LEVELS, 0);
  }

  private static int eliteEvidenceLevel(
      double weightedPointLoss, double firstChoiceRate, double goodMoveRate, double mistakeRate) {
    if (firstChoiceRate >= TOP_PRO_FIRST_CHOICE_RATE
        && goodMoveRate >= TOP_PRO_GOOD_MOVE_RATE
        && mistakeRate <= TOP_PRO_MISTAKE_RATE
        && weightedPointLoss <= TOP_PRO_WEIGHTED_LOSS) {
      return 12;
    }
    if (firstChoiceRate >= PRO_FIRST_CHOICE_RATE
        && goodMoveRate >= PRO_GOOD_MOVE_RATE
        && mistakeRate <= PRO_MISTAKE_RATE
        && weightedPointLoss <= PRO_WEIGHTED_LOSS) {
      return 11;
    }
    return 0;
  }

  private static int metricCapLevel(
      double weightedPointLoss,
      double medianPointLoss,
      double firstChoiceRate,
      double goodMoveRate,
      double mistakeRate) {
    int cap = 13;
    cap = Math.min(cap, capByFirstChoice(firstChoiceRate));
    cap = Math.min(cap, capByGoodMoveRate(goodMoveRate));
    cap = Math.min(cap, capByMistakeRate(mistakeRate));
    cap = Math.min(cap, capByMedianLoss(medianPointLoss));
    cap = Math.min(cap, capByWeightedLoss(weightedPointLoss));
    cap = Math.min(cap, evidenceCapLevel(weightedPointLoss, firstChoiceRate, goodMoveRate));
    return cap;
  }

  private static int evidenceCapLevel(
      double weightedPointLoss, double firstChoiceRate, double goodMoveRate) {
    int cap = 13;
    if (goodMoveRate < 0.50 && firstChoiceRate < 0.22) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    if (goodMoveRate < 0.62 && firstChoiceRate < 0.30) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    if (weightedPointLoss > 7.50 && firstChoiceRate < 0.26) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    return cap;
  }

  private static int evidenceAdjustedLevel(
      int level, double firstChoiceRate, double goodMoveRate, double averageDifficulty) {
    int adjusted = level;
    if (adjusted >= HIGH_DAN_LEVEL
        && averageDifficulty < LOW_DIFFICULTY_EVIDENCE
        && firstChoiceRate < LOW_DIFFICULTY_TOP_FIRST_CHOICE_RATE
        && goodMoveRate < LOW_DIFFICULTY_TOP_GOOD_MOVE_RATE) {
      adjusted = Math.min(adjusted, MID_DAN_LEVEL);
    }
    return adjusted;
  }

  private static int capByFirstChoice(double firstChoiceRate) {
    return levelAtLeast(firstChoiceRate, FIRST_CHOICE_CAPS, LOW_DAN_LEVEL);
  }

  private static int capByGoodMoveRate(double goodMoveRate) {
    return levelAtLeast(goodMoveRate, GOOD_MOVE_CAPS, 1);
  }

  private static int capByMistakeRate(double mistakeRate) {
    return levelAtMost(mistakeRate, MISTAKE_CAPS, 1);
  }

  private static int capByMedianLoss(double medianPointLoss) {
    return levelAtMost(medianPointLoss, MEDIAN_LOSS_CAPS, 1);
  }

  private static int capByWeightedLoss(double weightedPointLoss) {
    return levelAtMost(weightedPointLoss, WEIGHTED_LOSS_CAPS, 1);
  }

  private static int levelAtLeast(double value, LevelThreshold[] thresholds, int fallbackLevel) {
    for (LevelThreshold threshold : thresholds) {
      if (value >= threshold.value) {
        return threshold.level;
      }
    }
    return fallbackLevel;
  }

  private static int levelAtMost(double value, LevelThreshold[] thresholds, int fallbackLevel) {
    for (LevelThreshold threshold : thresholds) {
      if (value <= threshold.value) {
        return threshold.level;
      }
    }
    return fallbackLevel;
  }

  private static final class LevelThreshold {
    private final double value;
    private final int level;

    private LevelThreshold(double value, int level) {
      this.value = value;
      this.level = level;
    }
  }

  private static final class CandidateMatch {
    private final int rank;
    private final MoveData move;

    private CandidateMatch(int rank, MoveData move) {
      this.rank = rank;
      this.move = move;
    }

    private static CandidateMatch missing() {
      return new CandidateMatch(-1, null);
    }
  }

  private static final class LossEstimate {
    private final double winrateLoss;
    private final Optional<Double> scoreLoss;

    private LossEstimate(double winrateLoss, Optional<Double> scoreLoss) {
      this.winrateLoss = winrateLoss;
      this.scoreLoss = scoreLoss;
    }

    private double scoreEquivalentLoss() {
      return scoreLoss
          .map(PlayerStrengthEstimator::positive)
          .orElse(positive(winrateLoss) / WINRATE_TO_SCORE_LOSS);
    }
  }
}
