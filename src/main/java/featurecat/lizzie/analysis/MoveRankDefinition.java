package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.Color;
import java.util.Optional;

/** Shared display definition for move quality ranks used by board marks and review UI. */
public final class MoveRankDefinition {
  private static final double DEFAULT_WIN_LOSS_THRESHOLD_1 = -1.0;
  private static final double DEFAULT_WIN_LOSS_THRESHOLD_2 = -3.0;
  private static final double DEFAULT_WIN_LOSS_THRESHOLD_3 = -6.0;
  private static final double DEFAULT_WIN_LOSS_THRESHOLD_4 = -12.0;
  private static final double DEFAULT_WIN_LOSS_THRESHOLD_5 = -24.0;
  private static final double DEFAULT_SCORE_LOSS_THRESHOLD_1 = -0.5;
  private static final double DEFAULT_SCORE_LOSS_THRESHOLD_2 = -1.5;
  private static final double DEFAULT_SCORE_LOSS_THRESHOLD_3 = -3.0;
  private static final double DEFAULT_SCORE_LOSS_THRESHOLD_4 = -6.0;
  private static final double DEFAULT_SCORE_LOSS_THRESHOLD_5 = -12.0;

  private MoveRankDefinition() {}

  public enum Rank {
    BEST(
        0,
        "PlayerStrengthEstimate.moveRank.best",
        true,
        new Color(0, 180, 0),
        MorandiPalette.SUGGESTION_BEST,
        0.10f),
    GOOD(
        1,
        "PlayerStrengthEstimate.moveRank.good",
        true,
        new Color(140, 202, 34),
        MorandiPalette.SUGGESTION_GOOD,
        0.10f),
    NORMAL(
        2,
        "PlayerStrengthEstimate.moveRank.normal",
        false,
        new Color(180, 180, 0),
        MorandiPalette.SUGGESTION_CAUTION,
        0.1225f),
    INACCURACY(
        3,
        "PlayerStrengthEstimate.moveRank.inaccuracy",
        false,
        new Color(200, 140, 50),
        MorandiPalette.SUGGESTION_SLOW,
        0.145f),
    MISTAKE(
        4,
        "PlayerStrengthEstimate.moveRank.mistake",
        false,
        new Color(208, 16, 19),
        MorandiPalette.SUGGESTION_MISTAKE,
        0.1675f),
    BLUNDER(
        5,
        "PlayerStrengthEstimate.moveRank.blunder",
        false,
        new Color(155, 25, 150),
        MorandiPalette.SUGGESTION_BLUNDER,
        0.19f);

    private final int severityLevel;
    private final String nameKey;
    private final boolean goodMove;
    private final Color color;
    private final Color morandiColor;
    private final float boardMarkRadiusFactor;

    Rank(
        int severityLevel,
        String nameKey,
        boolean goodMove,
        Color color,
        Color morandiColor,
        float boardMarkRadiusFactor) {
      this.severityLevel = severityLevel;
      this.nameKey = nameKey;
      this.goodMove = goodMove;
      this.color = color;
      this.morandiColor = morandiColor;
      this.boardMarkRadiusFactor = boardMarkRadiusFactor;
    }

    public int severityLevel() {
      return severityLevel;
    }

    public String nameKey() {
      return nameKey;
    }

    public boolean isGoodMove() {
      return goodMove;
    }

    public Color color(boolean useMorandiColors) {
      return useMorandiColors ? morandiColor : color;
    }

    public float boardMarkRadiusFactor() {
      return boardMarkRadiusFactor;
    }
  }

  public static Rank classifyLosses(double winrateLoss, Optional<Double> scoreLoss, Config config) {
    double winrateDiff = -positive(winrateLoss);
    Optional<Double> scoreDiff = scoreLoss.map(loss -> -positive(loss));
    return classifyDiffs(winrateDiff, scoreDiff, config);
  }

  public static Rank classifyDiffs(double winrateDiff, double scoreDiff, Config config) {
    return classifyDiffs(winrateDiff, Optional.of(scoreDiff), config);
  }

  public static Rank classifyDiffs(double winrateDiff, Optional<Double> scoreDiff, Config config) {
    if (reachesThreshold(winrateDiff, scoreDiff, config, 5)) {
      return Rank.BLUNDER;
    }
    if (reachesThreshold(winrateDiff, scoreDiff, config, 4)) {
      return Rank.MISTAKE;
    }
    if (reachesThreshold(winrateDiff, scoreDiff, config, 3)) {
      return Rank.INACCURACY;
    }
    if (reachesThreshold(winrateDiff, scoreDiff, config, 2)) {
      return Rank.NORMAL;
    }
    if (reachesThreshold(winrateDiff, scoreDiff, config, 1)) {
      return Rank.GOOD;
    }
    return Rank.BEST;
  }

  public static double severity(double winrateDiff, double scoreDiff, Config config) {
    return severity(winrateDiff, Optional.of(scoreDiff), config);
  }

  public static double severity(
      double winrateDiff, Optional<Double> scoreDiff, Config config) {
    double winrateSeverity =
        metricSeverity(
            winrateDiff,
            winLossThreshold(config, 1),
            winLossThreshold(config, 2),
            winLossThreshold(config, 3),
            winLossThreshold(config, 4),
            winLossThreshold(config, 5));
    double scoreSeverity =
        scoreDiff
            .map(
                diff ->
                    metricSeverity(
                        diff,
                        scoreLossThreshold(config, 1),
                        scoreLossThreshold(config, 2),
                        scoreLossThreshold(config, 3),
                        scoreLossThreshold(config, 4),
                        scoreLossThreshold(config, 5)))
            .orElse(0.0);
    switch (evaluationMode(config)) {
      case SCORE:
        return scoreDiff.isPresent() ? scoreSeverity : 0;
      case WINRATE:
        return winrateSeverity;
      case COMBINED:
        return Math.max(winrateSeverity, scoreSeverity);
      case AUTO:
      default:
        if (scoreDiff.isEmpty()) {
          return winrateSeverity;
        }
        return winrateSeverity >= Rank.MISTAKE.severityLevel()
            ? Math.max(winrateSeverity, scoreSeverity)
            : scoreSeverity;
    }
  }

  public static Color severityColor(double severity) {
    Color[] stops = {
      new Color(31, 157, 92),
      new Color(121, 184, 74),
      new Color(215, 176, 61),
      new Color(232, 132, 58),
      new Color(221, 75, 58),
      new Color(180, 38, 78),
      new Color(122, 26, 138)
    };
    double clamped = Math.max(0, Math.min(stops.length - 1, severity));
    int lower = (int) Math.floor(clamped);
    int upper = Math.min(stops.length - 1, lower + 1);
    double ratio = clamped - lower;
    return interpolateColor(stops[lower], stops[upper], ratio);
  }

  private static boolean reachesThreshold(
      double winrateDiff, Optional<Double> scoreDiff, Config config, int level) {
    boolean reachesWinLoss = winrateDiff <= winLossThreshold(config, level);
    boolean reachesScoreLoss =
        scoreDiff.isPresent() && scoreDiff.get() <= scoreLossThreshold(config, level);
    switch (evaluationMode(config)) {
      case SCORE:
        return reachesScoreLoss;
      case WINRATE:
        return reachesWinLoss;
      case COMBINED:
        return reachesWinLoss || reachesScoreLoss;
      case AUTO:
      default:
        if (scoreDiff.isEmpty()) {
          return reachesWinLoss;
        }
        return reachesScoreLoss || (level >= Rank.MISTAKE.severityLevel() && reachesWinLoss);
    }
  }

  private static MoveRankEvaluationMode evaluationMode(Config config) {
    if (config == null) {
      return MoveRankEvaluationMode.AUTO;
    }
    if (config.moveRankEvaluationMode != null) {
      return config.moveRankEvaluationMode;
    }
    return MoveRankEvaluationMode.fromConfig(
        "", config.useWinLossInMoveRank, config.useScoreLossInMoveRank);
  }

  private static double metricSeverity(
      double diff,
      double threshold1,
      double threshold2,
      double threshold3,
      double threshold4,
      double threshold5) {
    if (diff > threshold1) {
      return 0;
    }
    double[] thresholds = {threshold1, threshold2, threshold3, threshold4, threshold5};
    for (int i = 0; i < thresholds.length - 1; i++) {
      double upper = thresholds[i];
      double lower = thresholds[i + 1];
      if (diff > lower) {
        double span = Math.max(0.0001, upper - lower);
        return (i + 1) + (upper - diff) / span;
      }
    }
    double extremeSpan = Math.max(1.0, Math.abs(threshold5));
    return 5 + Math.min(1.0, (threshold5 - diff) / extremeSpan);
  }

  private static double winLossThreshold(Config config, int level) {
    switch (level) {
      case 1:
        return config == null ? DEFAULT_WIN_LOSS_THRESHOLD_1 : config.winLossThreshold1;
      case 2:
        return config == null ? DEFAULT_WIN_LOSS_THRESHOLD_2 : config.winLossThreshold2;
      case 3:
        return config == null ? DEFAULT_WIN_LOSS_THRESHOLD_3 : config.winLossThreshold3;
      case 4:
        return config == null ? DEFAULT_WIN_LOSS_THRESHOLD_4 : config.winLossThreshold4;
      case 5:
        return config == null ? DEFAULT_WIN_LOSS_THRESHOLD_5 : config.winLossThreshold5;
      default:
        throw new IllegalArgumentException("Unsupported move-rank threshold level: " + level);
    }
  }

  private static double scoreLossThreshold(Config config, int level) {
    switch (level) {
      case 1:
        return config == null ? DEFAULT_SCORE_LOSS_THRESHOLD_1 : config.scoreLossThreshold1;
      case 2:
        return config == null ? DEFAULT_SCORE_LOSS_THRESHOLD_2 : config.scoreLossThreshold2;
      case 3:
        return config == null ? DEFAULT_SCORE_LOSS_THRESHOLD_3 : config.scoreLossThreshold3;
      case 4:
        return config == null ? DEFAULT_SCORE_LOSS_THRESHOLD_4 : config.scoreLossThreshold4;
      case 5:
        return config == null ? DEFAULT_SCORE_LOSS_THRESHOLD_5 : config.scoreLossThreshold5;
      default:
        throw new IllegalArgumentException("Unsupported move-rank threshold level: " + level);
    }
  }

  private static double positive(double loss) {
    return Math.max(0.0, loss);
  }

  private static Color interpolateColor(Color start, Color end, double ratio) {
    int red = (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * ratio);
    int green = (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * ratio);
    int blue = (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * ratio);
    return new Color(red, green, blue);
  }
}
