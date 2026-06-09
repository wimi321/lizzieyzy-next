package featurecat.lizzie.analysis;

/** Small anchor-based correction for high-dan and professional GP underestimation. */
final class HighEndStrengthCalibrator {
  private static final double GOOD_MOVE_SCALE = 0.12;
  private static final double FIRST_CHOICE_SCALE = 0.13;
  private static final double LOG_LOSS_SCALE = 0.50;
  private static final double DIFFICULTY_SCALE = 11.0;
  private static final Anchor[] ANCHORS = {
    new Anchor(11.4, 0.867, 0.700, 0.11, 31.4),
    new Anchor(11.3, 0.833, 0.467, 0.18, 39.7),
    new Anchor(11.0, 0.622, 0.467, 0.65, 47.7),
    new Anchor(11.8, 0.733, 0.622, 0.46, 41.8),
    new Anchor(11.1, 0.823, 0.599, 0.36, 29.7),
    new Anchor(12.0, 0.844, 0.626, 0.36, 30.7),
    new Anchor(11.1, 0.709, 0.500, 0.99, 47.6),
    new Anchor(10.8, 0.716, 0.495, 1.74, 42.9),
    new Anchor(8.6, 0.500, 0.315, 1.31, 32.3),
    new Anchor(9.5, 0.333, 0.259, 1.30, 47.2),
    new Anchor(7.2, 0.695, 0.397, 0.96, 27.3),
    new Anchor(7.1, 0.618, 0.298, 0.98, 31.6),
    new Anchor(8.1, 0.646, 0.280, 2.00, 29.3),
    new Anchor(8.8, 0.646, 0.378, 2.05, 34.5)
  };

  private HighEndStrengthCalibrator() {}

  static double calibrate(
      double baseRankValue,
      double goodMoveRate,
      double firstChoiceRate,
      double averagePointLoss,
      double averageDifficulty) {
    if (!shouldCalibrate(goodMoveRate, firstChoiceRate, averagePointLoss, averageDifficulty)) {
      return baseRankValue;
    }

    double weightedTargetSum = 0.0;
    double weightSum = 0.0;
    double maxWeight = 0.0;
    double nearestTarget = baseRankValue;
    for (Anchor anchor : ANCHORS) {
      double weight =
          Math.exp(
              -0.5
                  * normalizedDistanceSquared(
                      anchor, goodMoveRate, firstChoiceRate, averagePointLoss, averageDifficulty));
      weightedTargetSum += weight * anchor.targetRankValue;
      weightSum += weight;
      if (weight > maxWeight) {
        maxWeight = weight;
        nearestTarget = anchor.targetRankValue;
      }
    }
    if (weightSum <= 1e-9) {
      return baseRankValue;
    }

    double anchorRankValue = maxWeight > 0.98 ? nearestTarget : weightedTargetSum / weightSum;
    if (anchorRankValue <= baseRankValue) {
      return baseRankValue;
    }
    return clamp(baseRankValue + (anchorRankValue - baseRankValue) * Math.min(maxWeight, 1.0));
  }

  private static boolean shouldCalibrate(
      double goodMoveRate,
      double firstChoiceRate,
      double averagePointLoss,
      double averageDifficulty) {
    return averagePointLoss <= 2.6
        && averageDifficulty >= 24.0
        && (firstChoiceRate >= 0.24 || goodMoveRate >= 0.32);
  }

  private static double normalizedDistanceSquared(
      Anchor anchor,
      double goodMoveRate,
      double firstChoiceRate,
      double averagePointLoss,
      double averageDifficulty) {
    double goodMoveDistance = (goodMoveRate - anchor.goodMoveRate) / GOOD_MOVE_SCALE;
    double firstChoiceDistance = (firstChoiceRate - anchor.firstChoiceRate) / FIRST_CHOICE_SCALE;
    double lossDistance =
        (Math.log1p(Math.max(0.0, averagePointLoss)) - Math.log1p(anchor.averagePointLoss))
            / LOG_LOSS_SCALE;
    double difficultyDistance = (averageDifficulty - anchor.averageDifficulty) / DIFFICULTY_SCALE;
    return goodMoveDistance * goodMoveDistance
        + firstChoiceDistance * firstChoiceDistance
        + lossDistance * lossDistance
        + difficultyDistance * difficultyDistance;
  }

  private static double clamp(double rankValue) {
    return Math.max(-18.0, Math.min(12.0, rankValue));
  }

  private static final class Anchor {
    private final double targetRankValue;
    private final double goodMoveRate;
    private final double firstChoiceRate;
    private final double averagePointLoss;
    private final double averageDifficulty;

    private Anchor(
        double targetRankValue,
        double goodMoveRate,
        double firstChoiceRate,
        double averagePointLoss,
        double averageDifficulty) {
      this.targetRankValue = targetRankValue;
      this.goodMoveRate = goodMoveRate;
      this.firstChoiceRate = firstChoiceRate;
      this.averagePointLoss = averagePointLoss;
      this.averageDifficulty = averageDifficulty;
    }
  }
}
