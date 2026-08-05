package featurecat.lizzie.util.katago.tuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Conservative selection policy for the opt-in Apple Silicon hardware tuning experiment.
 *
 * <p>KataGo remains responsible for selecting the search-thread count for every shortlisted
 * hardware layout. This class only screens hardware candidates, aggregates fixed-thread
 * verification runs, and rejects a hardware override unless it is both stable and materially faster
 * than the single-GPU baseline.
 */
public final class KataGoExperimentalTuningSelector {
  public static final int DEFAULT_SHORTLIST_LIMIT = 3;
  public static final int REQUIRED_VERIFICATION_SAMPLES = 3;
  public static final double MAX_RELATIVE_SPREAD = 0.15;
  public static final double MIN_GAIN_OVER_BASELINE = 0.15;

  private static final double COMPARISON_EPSILON = 1.0e-12;

  private KataGoExperimentalTuningSelector() {}

  /** Identifies the mandatory single-GPU, batch-one baseline without depending on its label. */
  public static boolean isSingleGpuBaseline(KataGoTuningCandidate candidate) {
    return candidate != null
        && candidate.batch() == 1
        && candidate.devices().equals(List.of(KataGoTuningCandidate.METAL_GPU));
  }

  /**
   * Builds a deterministic shortlist from smoke-test results.
   *
   * <p>The valid single-GPU baseline is always first and cannot be eliminated by the smoke-test
   * ranking. Remaining slots are filled by the fastest valid challengers. An empty result means no
   * valid baseline was observed.
   */
  public static List<KataGoTuningCandidate> shortlist(
      Map<KataGoTuningCandidate, KataGoBenchmarkObservation> smokeResults, int maxCandidates) {
    if (maxCandidates <= 0) {
      throw new IllegalArgumentException("maxCandidates must be positive");
    }
    List<ObservedCandidate> usable = usableCandidates(smokeResults);
    Optional<ObservedCandidate> baseline =
        usable.stream()
            .filter(entry -> isSingleGpuBaseline(entry.candidate()))
            .min(observedThroughputComparator());
    if (baseline.isEmpty()) {
      return List.of();
    }

    List<KataGoTuningCandidate> selected = new ArrayList<KataGoTuningCandidate>();
    selected.add(baseline.get().candidate());
    usable.stream()
        .filter(entry -> !isSingleGpuBaseline(entry.candidate()))
        .sorted(observedThroughputComparator())
        .limit(Math.max(0, maxCandidates - 1))
        .map(ObservedCandidate::candidate)
        .forEach(selected::add);
    return List.copyOf(selected);
  }

  /** Uses the production shortlist cap of the baseline plus at most two challengers. */
  public static List<KataGoTuningCandidate> shortlist(
      Map<KataGoTuningCandidate, KataGoBenchmarkObservation> smokeResults) {
    return shortlist(smokeResults, DEFAULT_SHORTLIST_LIMIT);
  }

  /**
   * Aggregates exactly three comparable fixed-thread observations.
   *
   * <p>The representative metrics are the complete row whose visits/s is the median, avoiding a
   * synthetic row made from unrelated per-column values. Missing, failed, incomplete, wrong-thread,
   * or non-finite observations are rejected.
   */
  public static Optional<Aggregate> aggregate(
      KataGoTuningCandidate candidate,
      int searchThreads,
      List<KataGoBenchmarkObservation> fixedThreadObservations) {
    Objects.requireNonNull(candidate, "candidate");
    if (searchThreads <= 0 || searchThreads > 4096) {
      throw new IllegalArgumentException("searchThreads must be between 1 and 4096");
    }
    if (fixedThreadObservations == null
        || fixedThreadObservations.size() != REQUIRED_VERIFICATION_SAMPLES) {
      return Optional.empty();
    }

    List<KataGoBenchmarkObservation.ThreadMetrics> rows =
        new ArrayList<KataGoBenchmarkObservation.ThreadMetrics>();
    for (KataGoBenchmarkObservation observation : fixedThreadObservations) {
      if (observation == null
          || observation.failureDetected()
          || observation.recommendedThreads() != searchThreads) {
        return Optional.empty();
      }
      Optional<KataGoBenchmarkObservation.ThreadMetrics> metric =
          observation
              .metricForThreads(searchThreads)
              .filter(KataGoExperimentalTuningSelector::isValidMetric);
      if (metric.isEmpty()) {
        return Optional.empty();
      }
      rows.add(metric.get());
    }

    rows.sort(
        Comparator.comparingDouble(KataGoBenchmarkObservation.ThreadMetrics::visitsPerSecond));
    KataGoBenchmarkObservation.ThreadMetrics representative = rows.get(rows.size() / 2);
    double median = representative.visitsPerSecond();
    double minimum = rows.get(0).visitsPerSecond();
    double maximum = rows.get(rows.size() - 1).visitsPerSecond();
    double relativeSpread = (maximum - minimum) / median;
    if (!Double.isFinite(relativeSpread) || relativeSpread < 0.0) {
      return Optional.empty();
    }

    return Optional.of(
        new Aggregate(
            candidate, searchThreads, representative, median, relativeSpread, rows.size()));
  }

  /**
   * Selects a verified challenger or safely falls back to the official single-GPU result.
   *
   * <p>A challenger is eligible only when both the baseline and challenger have three stable
   * fixed-thread samples and the challenger's median visits/s is at least 15% higher. Thread counts
   * always come from each candidate's official KataGo benchmark observation.
   */
  public static Optional<Selection> selectValidated(
      Map<KataGoTuningCandidate, KataGoBenchmarkObservation> officialResults,
      Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> verificationSamples) {
    List<ObservedCandidate> official = usableCandidates(officialResults);
    Optional<ObservedCandidate> baselineEntry =
        official.stream()
            .filter(entry -> isSingleGpuBaseline(entry.candidate()))
            .min(observedThroughputComparator());
    if (baselineEntry.isEmpty()) {
      return Optional.empty();
    }

    ObservedCandidate baseline = baselineEntry.get();
    Optional<Aggregate> baselineAggregate =
        aggregate(
            baseline.candidate(),
            baseline.observation().recommendedThreads(),
            samplesFor(verificationSamples, baseline.candidate()));
    Selection fallback = baselineSelection(baseline, baselineAggregate);
    if (baselineAggregate.isEmpty() || !baselineAggregate.get().stable()) {
      return Optional.of(fallback);
    }

    double baselineMedian = baselineAggregate.get().medianVisitsPerSecond();
    List<EligibleChallenger> eligible = new ArrayList<EligibleChallenger>();
    for (ObservedCandidate entry : official) {
      if (isSingleGpuBaseline(entry.candidate())) {
        continue;
      }
      Optional<Aggregate> aggregate =
          aggregate(
              entry.candidate(),
              entry.observation().recommendedThreads(),
              samplesFor(verificationSamples, entry.candidate()));
      if (aggregate.isEmpty() || !aggregate.get().stable()) {
        continue;
      }
      double gain = aggregate.get().medianVisitsPerSecond() / baselineMedian - 1.0;
      if (!Double.isFinite(gain) || gain + COMPARISON_EPSILON < MIN_GAIN_OVER_BASELINE) {
        continue;
      }
      eligible.add(new EligibleChallenger(entry, aggregate.get(), gain));
    }
    if (eligible.isEmpty()) {
      return Optional.of(fallback);
    }

    EligibleChallenger winner = eligible.stream().min(eligibleChallengerComparator()).orElseThrow();
    return Optional.of(
        new Selection(
            winner.observed().candidate(),
            winner.observed().observation().recommendedThreads(),
            winner.aggregate().representativeMetrics(),
            Optional.of(winner.aggregate()),
            winner.gainOverBaseline(),
            true));
  }

  /** Returns a shuffled immutable copy while leaving the caller's list unchanged. */
  public static <T> List<T> shuffledCopy(List<T> values, long seed) {
    Objects.requireNonNull(values, "values");
    List<T> shuffled = new ArrayList<T>(values);
    Collections.shuffle(shuffled, new Random(seed));
    return List.copyOf(shuffled);
  }

  /**
   * Builds balanced verification rounds: every candidate occurs exactly once per round, and every
   * round has an independently shuffled order derived from the supplied seed.
   */
  public static List<List<KataGoTuningCandidate>> verificationRounds(
      List<KataGoTuningCandidate> candidates, int rounds, long seed) {
    Objects.requireNonNull(candidates, "candidates");
    if (rounds <= 0) {
      throw new IllegalArgumentException("rounds must be positive");
    }
    List<KataGoTuningCandidate> stableInput = List.copyOf(candidates);
    Set<KataGoTuningCandidate> unique = new HashSet<KataGoTuningCandidate>(stableInput);
    if (unique.size() != stableInput.size()) {
      throw new IllegalArgumentException("candidates must not contain duplicates");
    }

    Random random = new Random(seed);
    List<List<KataGoTuningCandidate>> scheduled =
        new ArrayList<List<KataGoTuningCandidate>>(rounds);
    for (int round = 0; round < rounds; round++) {
      List<KataGoTuningCandidate> order = new ArrayList<KataGoTuningCandidate>(stableInput);
      Collections.shuffle(order, random);
      scheduled.add(List.copyOf(order));
    }
    return List.copyOf(scheduled);
  }

  private static Selection baselineSelection(
      ObservedCandidate baseline, Optional<Aggregate> baselineAggregate) {
    KataGoBenchmarkObservation.ThreadMetrics metrics =
        baselineAggregate.map(Aggregate::representativeMetrics).orElse(baseline.metrics());
    return new Selection(
        baseline.candidate(),
        baseline.observation().recommendedThreads(),
        metrics,
        baselineAggregate,
        0.0,
        false);
  }

  private static List<KataGoBenchmarkObservation> samplesFor(
      Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> verificationSamples,
      KataGoTuningCandidate candidate) {
    if (verificationSamples == null) {
      return null;
    }
    return verificationSamples.get(candidate);
  }

  private static List<ObservedCandidate> usableCandidates(
      Map<KataGoTuningCandidate, KataGoBenchmarkObservation> observations) {
    if (observations == null || observations.isEmpty()) {
      return List.of();
    }
    Map<KataGoTuningCandidate, ObservedCandidate> usable =
        new LinkedHashMap<KataGoTuningCandidate, ObservedCandidate>();
    for (Map.Entry<KataGoTuningCandidate, KataGoBenchmarkObservation> entry :
        observations.entrySet()) {
      KataGoTuningCandidate candidate = entry.getKey();
      KataGoBenchmarkObservation observation = entry.getValue();
      if (candidate == null || observation == null || observation.failureDetected()) {
        continue;
      }
      observation
          .recommendedMetric()
          .filter(KataGoExperimentalTuningSelector::isValidMetric)
          .ifPresent(
              metrics ->
                  usable.put(candidate, new ObservedCandidate(candidate, observation, metrics)));
    }
    return List.copyOf(usable.values());
  }

  private static boolean isValidMetric(KataGoBenchmarkObservation.ThreadMetrics metric) {
    return metric != null
        && metric.validForThroughputSelection()
        && finiteNonNegative(metric.nnEvalsPerSecond())
        && finiteNonNegative(metric.nnBatchesPerSecond())
        && finiteNonNegative(metric.averageBatchSize());
  }

  private static boolean finiteNonNegative(double value) {
    return Double.isFinite(value) && value >= 0.0;
  }

  private static Comparator<ObservedCandidate> observedThroughputComparator() {
    return Comparator.comparingDouble(
            (ObservedCandidate entry) -> entry.metrics().visitsPerSecond())
        .reversed()
        .thenComparing(ObservedCandidate::candidate, simpleCandidateComparator());
  }

  private static Comparator<EligibleChallenger> eligibleChallengerComparator() {
    return Comparator.comparingDouble(
            (EligibleChallenger challenger) -> challenger.aggregate().medianVisitsPerSecond())
        .reversed()
        .thenComparing(
            challenger -> challenger.observed().candidate(), simpleCandidateComparator());
  }

  private static Comparator<KataGoTuningCandidate> simpleCandidateComparator() {
    return Comparator.comparingInt(KataGoTuningCandidate::serverCount)
        .thenComparing(KataGoTuningCandidate::mixed)
        .thenComparingInt(KataGoExperimentalTuningSelector::aneCount)
        .thenComparingInt(KataGoTuningCandidate::batch)
        .thenComparing(KataGoTuningCandidate::id);
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

  private record ObservedCandidate(
      KataGoTuningCandidate candidate,
      KataGoBenchmarkObservation observation,
      KataGoBenchmarkObservation.ThreadMetrics metrics) {}

  private record EligibleChallenger(
      ObservedCandidate observed, Aggregate aggregate, double gainOverBaseline) {}

  /** Robust statistics derived from the three fixed-thread verification processes. */
  public record Aggregate(
      KataGoTuningCandidate candidate,
      int searchThreads,
      KataGoBenchmarkObservation.ThreadMetrics representativeMetrics,
      double medianVisitsPerSecond,
      double relativeSpread,
      int sampleCount) {
    public Aggregate {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(representativeMetrics, "representativeMetrics");
      if (searchThreads <= 0 || searchThreads > 4096) {
        throw new IllegalArgumentException("searchThreads must be between 1 and 4096");
      }
      if (!Double.isFinite(medianVisitsPerSecond) || medianVisitsPerSecond <= 0.0) {
        throw new IllegalArgumentException("medianVisitsPerSecond must be finite and positive");
      }
      if (!Double.isFinite(relativeSpread) || relativeSpread < 0.0) {
        throw new IllegalArgumentException("relativeSpread must be finite and non-negative");
      }
      if (sampleCount != REQUIRED_VERIFICATION_SAMPLES) {
        throw new IllegalArgumentException("sampleCount must equal the required sample count");
      }
    }

    public boolean stable() {
      return relativeSpread <= MAX_RELATIVE_SPREAD + COMPARISON_EPSILON;
    }
  }

  /** Final experimental decision, including whether a challenger cleared the safety gate. */
  public record Selection(
      KataGoTuningCandidate candidate,
      int searchThreads,
      KataGoBenchmarkObservation.ThreadMetrics metrics,
      Optional<Aggregate> verification,
      double gainOverBaseline,
      boolean challengerAccepted) {
    public Selection {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(metrics, "metrics");
      verification = verification == null ? Optional.empty() : verification;
      if (searchThreads <= 0 || searchThreads > 4096) {
        throw new IllegalArgumentException("searchThreads must be between 1 and 4096");
      }
      if (!isValidMetric(metrics)) {
        throw new IllegalArgumentException("metrics must be complete and finite");
      }
      if (!Double.isFinite(gainOverBaseline) || gainOverBaseline < 0.0) {
        throw new IllegalArgumentException("gainOverBaseline must be finite and non-negative");
      }
    }

    public boolean usesBaselineFallback() {
      return !challengerAccepted;
    }
  }
}
