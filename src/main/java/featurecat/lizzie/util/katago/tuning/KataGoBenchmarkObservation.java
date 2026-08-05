package featurecat.lizzie.util.katago.tuning;

import java.util.List;
import java.util.Optional;

/** Structured, process-independent data parsed from one KataGo benchmark invocation. */
public record KataGoBenchmarkObservation(
    String backend,
    int currentThreads,
    int recommendedThreads,
    List<ThreadMetrics> metrics,
    boolean mpsGraphInitialized,
    boolean coreMlInitialized,
    boolean failureDetected) {

  public KataGoBenchmarkObservation {
    backend = backend == null ? "" : backend.trim();
    if (currentThreads < 0 || recommendedThreads < 0) {
      throw new IllegalArgumentException("thread counts must not be negative");
    }
    metrics = metrics == null ? List.of() : List.copyOf(metrics);
  }

  public boolean hasMetrics() {
    return !metrics.isEmpty();
  }

  public boolean mixedMetalInitialized() {
    return mpsGraphInitialized && coreMlInitialized;
  }

  public Optional<ThreadMetrics> metricForThreads(int numSearchThreads) {
    return metrics.stream()
        .filter(metric -> metric.numSearchThreads() == numSearchThreads)
        .findFirst();
  }

  public Optional<ThreadMetrics> recommendedMetric() {
    return recommendedThreads <= 0 ? Optional.empty() : metricForThreads(recommendedThreads);
  }

  /** Per-thread-count metrics from a completed detailed benchmark row. */
  public record ThreadMetrics(
      int numSearchThreads,
      int positionsCompleted,
      int positionsTotal,
      double visitsPerSecond,
      double nnEvalsPerSecond,
      double nnBatchesPerSecond,
      double averageBatchSize) {

    public ThreadMetrics {
      if (numSearchThreads <= 0) {
        throw new IllegalArgumentException("numSearchThreads must be positive");
      }
      if (positionsCompleted < 0 || positionsTotal < 0 || positionsCompleted > positionsTotal) {
        throw new IllegalArgumentException("invalid position counts");
      }
    }

    public boolean validForThroughputSelection() {
      return positionsTotal > 0
          && positionsCompleted == positionsTotal
          && Double.isFinite(visitsPerSecond)
          && visitsPerSecond > 0.0;
    }
  }
}
