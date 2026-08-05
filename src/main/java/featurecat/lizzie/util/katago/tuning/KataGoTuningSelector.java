package featurecat.lizzie.util.katago.tuning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Selects a safe high-throughput profile from completed candidate observations. */
public final class KataGoTuningSelector {
  static final double SIMPLE_TOPOLOGY_TOLERANCE = 0.05;

  private KataGoTuningSelector() {}

  public static Optional<Selection> select(
      Map<KataGoTuningCandidate, KataGoBenchmarkObservation> observations) {
    if (observations == null || observations.isEmpty()) {
      return Optional.empty();
    }
    List<CandidateObservation> entries = new ArrayList<CandidateObservation>();
    for (Map.Entry<KataGoTuningCandidate, KataGoBenchmarkObservation> entry :
        observations.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null) {
        entries.add(new CandidateObservation(entry.getKey(), entry.getValue()));
      }
    }
    return select(entries);
  }

  public static Optional<Selection> select(Collection<CandidateObservation> observations) {
    if (observations == null || observations.isEmpty()) {
      return Optional.empty();
    }

    List<Selection> usable = new ArrayList<Selection>();
    for (CandidateObservation candidateObservation : observations) {
      if (candidateObservation == null || candidateObservation.observation().failureDetected()) {
        continue;
      }
      bestThroughputMetric(candidateObservation.observation())
          .ifPresent(
              metric ->
                  usable.add(
                      new Selection(
                          candidateObservation.candidate(),
                          metric.numSearchThreads(),
                          metric,
                          candidateObservation.observation())));
    }
    if (usable.isEmpty()) {
      return Optional.empty();
    }

    double fastestVisitsPerSecond =
        usable.stream()
            .mapToDouble(selection -> selection.metrics().visitsPerSecond())
            .max()
            .orElse(0.0);
    double simpleCandidateFloor = fastestVisitsPerSecond * (1.0 - SIMPLE_TOPOLOGY_TOLERANCE);

    return usable.stream()
        .filter(selection -> selection.metrics().visitsPerSecond() >= simpleCandidateFloor)
        .min(simpleTopologyComparator());
  }

  private static Optional<KataGoBenchmarkObservation.ThreadMetrics> bestThroughputMetric(
      KataGoBenchmarkObservation observation) {
    return observation.metrics().stream()
        .filter(KataGoBenchmarkObservation.ThreadMetrics::validForThroughputSelection)
        .max(
            Comparator.comparingDouble(KataGoBenchmarkObservation.ThreadMetrics::visitsPerSecond)
                .thenComparingInt(metric -> -metric.numSearchThreads()));
  }

  private static Comparator<Selection> simpleTopologyComparator() {
    return Comparator.comparingInt((Selection selection) -> selection.candidate().serverCount())
        .thenComparing(selection -> selection.candidate().mixed())
        .thenComparingInt(selection -> aneCount(selection.candidate()))
        .thenComparing(
            Comparator.comparingDouble(
                    (Selection selection) -> selection.metrics().visitsPerSecond())
                .reversed())
        .thenComparingInt(selection -> selection.candidate().batch())
        .thenComparing(selection -> selection.candidate().id());
  }

  private static int aneCount(KataGoTuningCandidate candidate) {
    int count = 0;
    for (Integer device : candidate.devices()) {
      if (device == KataGoTuningCandidate.METAL_ANE) {
        count++;
      }
    }
    return count;
  }

  public record CandidateObservation(
      KataGoTuningCandidate candidate, KataGoBenchmarkObservation observation) {
    public CandidateObservation {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(observation, "observation");
    }
  }

  public record Selection(
      KataGoTuningCandidate candidate,
      int searchThreads,
      KataGoBenchmarkObservation.ThreadMetrics metrics,
      KataGoBenchmarkObservation observation) {
    public Selection {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(metrics, "metrics");
      Objects.requireNonNull(observation, "observation");
      if (searchThreads <= 0) {
        throw new IllegalArgumentException("searchThreads must be positive");
      }
    }
  }
}
