package featurecat.lizzie.gui;

public class ProblemMoveEntry {
  public final boolean isBlack;
  public final int moveNumber;
  public final String coords;
  public final double winrateLossAbs;
  public final double scoreLossAbs;
  public final boolean hasScoreLoss;
  public final int playouts;
  public final boolean isCurrent;
  public final int severityTier;

  public ProblemMoveEntry(
      boolean isBlack,
      int moveNumber,
      String coords,
      double winrateLossAbs,
      double scoreLossAbs,
      boolean hasScoreLoss,
      int playouts,
      boolean isCurrent,
      int severityTier) {
    this.isBlack = isBlack;
    this.moveNumber = moveNumber;
    this.coords = coords;
    this.winrateLossAbs = winrateLossAbs;
    this.scoreLossAbs = scoreLossAbs;
    this.hasScoreLoss = hasScoreLoss;
    this.playouts = playouts;
    this.isCurrent = isCurrent;
    this.severityTier = severityTier;
  }

  public double metricValue(ProblemListMetric metric) {
    return metric == ProblemListMetric.SCORE_LOSS ? scoreLossAbs : winrateLossAbs;
  }
}
