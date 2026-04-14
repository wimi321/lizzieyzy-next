package featurecat.lizzie.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProblemListSnapshot {
  public final ProblemListMetric metric;
  public final List<ProblemMoveEntry> blackEntries;
  public final List<ProblemMoveEntry> whiteEntries;
  public final int analyzedMoves;
  public final int totalMoves;
  public final boolean analysisRunning;

  public ProblemListSnapshot(
      ProblemListMetric metric,
      List<ProblemMoveEntry> blackEntries,
      List<ProblemMoveEntry> whiteEntries,
      int analyzedMoves,
      int totalMoves,
      boolean analysisRunning) {
    this.metric = metric;
    this.blackEntries = Collections.unmodifiableList(new ArrayList<>(blackEntries));
    this.whiteEntries = Collections.unmodifiableList(new ArrayList<>(whiteEntries));
    this.analyzedMoves = analyzedMoves;
    this.totalMoves = totalMoves;
    this.analysisRunning = analysisRunning;
  }

  public boolean hasEntries() {
    return !blackEntries.isEmpty() || !whiteEntries.isEmpty();
  }
}
