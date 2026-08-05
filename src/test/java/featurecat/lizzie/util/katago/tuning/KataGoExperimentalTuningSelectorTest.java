package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KataGoExperimentalTuningSelectorTest {
  private static final KataGoTuningCandidate BASELINE =
      new KataGoTuningCandidate("arbitrary-baseline-label", List.of(0), 1);

  @Test
  void identifiesBaselineByDevicesAndBatchInsteadOfLabel() {
    assertTrue(KataGoExperimentalTuningSelector.isSingleGpuBaseline(BASELINE));
    assertFalse(
        KataGoExperimentalTuningSelector.isSingleGpuBaseline(
            new KataGoTuningCandidate("G", List.of(0), 2)));
    assertFalse(
        KataGoExperimentalTuningSelector.isSingleGpuBaseline(
            new KataGoTuningCandidate("G", List.of(0, 0), 1)));
  }

  @Test
  void shortlistAlwaysKeepsValidBaselineAndCapsAtThree() {
    KataGoTuningCandidate fastest = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    KataGoTuningCandidate second = new KataGoTuningCandidate("GA", List.of(0, 100), 2);
    KataGoTuningCandidate third = new KataGoTuningCandidate("GGA", List.of(0, 0, 100), 3);
    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> smoke = new LinkedHashMap<>();
    smoke.put(third, observation(6, 120.0));
    smoke.put(BASELINE, observation(6, 70.0));
    smoke.put(second, observation(6, 130.0));
    smoke.put(fastest, observation(6, 140.0));

    assertEquals(
        List.of(BASELINE, fastest, second), KataGoExperimentalTuningSelector.shortlist(smoke, 3));
  }

  @Test
  void shortlistReturnsEmptyWithoutAValidBaselineAndSkipsInvalidChallengers() {
    KataGoTuningCandidate challenger = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    assertTrue(
        KataGoExperimentalTuningSelector.shortlist(Map.of(challenger, observation(4, 200.0)), 3)
            .isEmpty());

    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> smoke = new LinkedHashMap<>();
    smoke.put(BASELINE, observation(4, 80.0));
    smoke.put(challenger, failedObservation(4, 400.0));
    assertEquals(List.of(BASELINE), KataGoExperimentalTuningSelector.shortlist(smoke, 3));
    assertThrows(
        IllegalArgumentException.class, () -> KataGoExperimentalTuningSelector.shortlist(smoke, 0));
  }

  @Test
  void aggregateUsesTheMedianRunAndComputesRelativeSpread() {
    List<KataGoBenchmarkObservation> samples =
        List.of(
            observation(3, 110.0, 101.0), observation(3, 100.0, 91.0), observation(3, 105.0, 96.0));

    KataGoExperimentalTuningSelector.Aggregate aggregate =
        KataGoExperimentalTuningSelector.aggregate(BASELINE, 3, samples).orElseThrow();

    assertEquals(105.0, aggregate.medianVisitsPerSecond());
    assertEquals(96.0, aggregate.representativeMetrics().nnEvalsPerSecond());
    assertEquals(10.0 / 105.0, aggregate.relativeSpread(), 1.0e-12);
    assertEquals(3, aggregate.sampleCount());
    assertTrue(aggregate.stable());
  }

  @Test
  void aggregateRejectsMissingWrongThreadIncompleteAndNonFiniteSamples() {
    assertTrue(
        KataGoExperimentalTuningSelector.aggregate(
                BASELINE, 3, List.of(observation(3, 100.0), observation(3, 101.0)))
            .isEmpty());

    assertTrue(
        KataGoExperimentalTuningSelector.aggregate(
                BASELINE,
                3,
                List.of(observation(3, 100.0), observation(4, 101.0), observation(3, 102.0)))
            .isEmpty());

    KataGoBenchmarkObservation incomplete =
        new KataGoBenchmarkObservation(
            "Metal", 3, 3, List.of(metrics(3, 2, 3, 101.0, 90.0)), true, false, false);
    assertTrue(
        KataGoExperimentalTuningSelector.aggregate(
                BASELINE, 3, List.of(observation(3, 100.0), incomplete, observation(3, 102.0)))
            .isEmpty());

    KataGoBenchmarkObservation nonFinite =
        new KataGoBenchmarkObservation(
            "Metal", 3, 3, List.of(metrics(3, 3, 3, 101.0, Double.NaN)), true, false, false);
    assertTrue(
        KataGoExperimentalTuningSelector.aggregate(
                BASELINE, 3, List.of(observation(3, 100.0), nonFinite, observation(3, 102.0)))
            .isEmpty());
  }

  @Test
  void unstableBaselineAlwaysFallsBackToItsOfficialRecommendation() {
    KataGoTuningCandidate challenger = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> official =
        Map.of(BASELINE, observation(1, 100.0), challenger, observation(3, 140.0));
    Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> samples =
        Map.of(
            BASELINE, samples(1, 80.0, 100.0, 120.0),
            challenger, samples(3, 139.0, 140.0, 141.0));

    KataGoExperimentalTuningSelector.Selection selected =
        KataGoExperimentalTuningSelector.selectValidated(official, samples).orElseThrow();

    assertEquals(BASELINE, selected.candidate());
    assertEquals(1, selected.searchThreads());
    assertTrue(selected.usesBaselineFallback());
    assertFalse(selected.verification().orElseThrow().stable());
  }

  @Test
  void requiresTheFullFifteenPercentMedianGain() {
    KataGoTuningCandidate challenger = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> official =
        Map.of(BASELINE, observation(1, 100.0), challenger, observation(3, 120.0));
    Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> belowThreshold =
        Map.of(
            BASELINE, samples(1, 99.0, 100.0, 101.0),
            challenger, samples(3, 113.9, 114.9, 115.9));
    Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> atThreshold =
        Map.of(
            BASELINE, samples(1, 99.0, 100.0, 101.0),
            challenger, samples(3, 114.0, 115.0, 116.0));

    KataGoExperimentalTuningSelector.Selection rejected =
        KataGoExperimentalTuningSelector.selectValidated(official, belowThreshold).orElseThrow();
    KataGoExperimentalTuningSelector.Selection accepted =
        KataGoExperimentalTuningSelector.selectValidated(official, atThreshold).orElseThrow();

    assertEquals(BASELINE, rejected.candidate());
    assertTrue(rejected.usesBaselineFallback());
    assertEquals(challenger, accepted.candidate());
    assertTrue(accepted.challengerAccepted());
    assertEquals(0.15, accepted.gainOverBaseline(), 1.0e-12);
  }

  @Test
  void fastestStableChallengerWinsAndExactTiePrefersSimplerTopology() {
    KataGoTuningCandidate simple = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    KataGoTuningCandidate complex = new KataGoTuningCandidate("GGA", List.of(0, 0, 100), 3);
    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> official =
        Map.of(
            BASELINE,
            observation(1, 100.0),
            simple,
            observation(3, 130.0),
            complex,
            observation(4, 130.0));
    Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> samples =
        Map.of(
            BASELINE,
            samples(1, 99.0, 100.0, 101.0),
            simple,
            samples(3, 129.0, 130.0, 131.0),
            complex,
            samples(4, 129.0, 130.0, 131.0));

    KataGoExperimentalTuningSelector.Selection selected =
        KataGoExperimentalTuningSelector.selectValidated(official, samples).orElseThrow();

    assertEquals(simple, selected.candidate());
    assertEquals(3, selected.searchThreads());
    assertEquals(130.0, selected.metrics().visitsPerSecond());
  }

  @Test
  void invalidOrMissingChallengerSamplesCannotBeatTheBaseline() {
    KataGoTuningCandidate challenger = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> official =
        Map.of(BASELINE, observation(1, 100.0), challenger, observation(3, 150.0));
    Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> samples =
        Map.of(BASELINE, samples(1, 99.0, 100.0, 101.0));

    KataGoExperimentalTuningSelector.Selection selected =
        KataGoExperimentalTuningSelector.selectValidated(official, samples).orElseThrow();

    assertEquals(BASELINE, selected.candidate());
    assertTrue(selected.usesBaselineFallback());
  }

  @Test
  void deterministicShuffleAndBalancedRoundsNeverMutateTheInput() {
    KataGoTuningCandidate second = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    KataGoTuningCandidate third = new KataGoTuningCandidate("GA", List.of(0, 100), 2);
    ArrayList<KataGoTuningCandidate> input =
        new ArrayList<KataGoTuningCandidate>(List.of(BASELINE, second, third));
    List<KataGoTuningCandidate> original = List.copyOf(input);

    List<KataGoTuningCandidate> first = KataGoExperimentalTuningSelector.shuffledCopy(input, 42L);
    List<KataGoTuningCandidate> secondRun =
        KataGoExperimentalTuningSelector.shuffledCopy(input, 42L);
    List<List<KataGoTuningCandidate>> rounds =
        KataGoExperimentalTuningSelector.verificationRounds(input, 3, 17L);

    assertEquals(first, secondRun);
    assertEquals(original, input);
    assertEquals(3, rounds.size());
    for (List<KataGoTuningCandidate> round : rounds) {
      assertEquals(3, round.size());
      assertTrue(round.containsAll(original));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KataGoExperimentalTuningSelector.verificationRounds(
                List.of(BASELINE, BASELINE), 3, 1L));
  }

  private static List<KataGoBenchmarkObservation> samples(
      int threads, double first, double second, double third) {
    return List.of(
        observation(threads, first), observation(threads, second), observation(threads, third));
  }

  private static KataGoBenchmarkObservation observation(int threads, double visitsPerSecond) {
    return observation(threads, visitsPerSecond, visitsPerSecond - 10.0);
  }

  private static KataGoBenchmarkObservation observation(
      int threads, double visitsPerSecond, double nnEvalsPerSecond) {
    return new KataGoBenchmarkObservation(
        "Metal",
        threads,
        threads,
        List.of(metrics(threads, 3, 3, visitsPerSecond, nnEvalsPerSecond)),
        true,
        false,
        false);
  }

  private static KataGoBenchmarkObservation failedObservation(int threads, double visitsPerSecond) {
    return new KataGoBenchmarkObservation(
        "Metal",
        threads,
        threads,
        List.of(metrics(threads, 3, 3, visitsPerSecond, visitsPerSecond - 10.0)),
        true,
        false,
        true);
  }

  private static KataGoBenchmarkObservation.ThreadMetrics metrics(
      int threads,
      int positionsCompleted,
      int positionsTotal,
      double visitsPerSecond,
      double nnEvalsPerSecond) {
    return new KataGoBenchmarkObservation.ThreadMetrics(
        threads, positionsCompleted, positionsTotal, visitsPerSecond, nnEvalsPerSecond, 20.0, 2.0);
  }
}
