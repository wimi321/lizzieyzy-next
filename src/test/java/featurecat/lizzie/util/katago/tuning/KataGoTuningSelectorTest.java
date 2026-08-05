package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KataGoTuningSelectorTest {
  @Test
  void prefersSimplerTopologyWhenItIsWithinFivePercentOfFastest() {
    KataGoTuningCandidate simple = new KataGoTuningCandidate("G", List.of(0), 1);
    KataGoTuningCandidate faster = new KataGoTuningCandidate("GG", List.of(0, 0), 2);

    KataGoTuningSelector.Selection selected =
        KataGoTuningSelector.select(
                Map.of(simple, observation(1, 100.0, false), faster, observation(4, 104.0, false)))
            .orElseThrow();

    assertEquals(simple, selected.candidate());
    assertEquals(1, selected.searchThreads());
  }

  @Test
  void selectsFasterTopologyWhenSimpleCandidateFallsOutsideTolerance() {
    KataGoTuningCandidate simple = new KataGoTuningCandidate("G", List.of(0), 1);
    KataGoTuningCandidate faster = new KataGoTuningCandidate("GGA", List.of(0, 0, 100), 3);

    KataGoTuningSelector.Selection selected =
        KataGoTuningSelector.select(
                Map.of(simple, observation(1, 100.0, false), faster, observation(6, 106.0, false)))
            .orElseThrow();

    assertEquals(faster, selected.candidate());
    assertEquals(6, selected.searchThreads());
  }

  @Test
  void selectsFastestBatchWithinTheSameTopology() {
    KataGoTuningCandidate batchOne = new KataGoTuningCandidate("G", List.of(0), 1);
    KataGoTuningCandidate batchFour = new KataGoTuningCandidate("G", List.of(0), 4);

    KataGoTuningSelector.Selection selected =
        KataGoTuningSelector.select(
                Map.of(
                    batchOne,
                    observation(4, 100.0, false),
                    batchFour,
                    observation(4, 104.0, false)))
            .orElseThrow();

    assertEquals(batchFour, selected.candidate());
  }

  @Test
  void excludesFailuresAndIncompleteOrMissingMetrics() {
    KataGoTuningCandidate failed = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    KataGoTuningCandidate incomplete = new KataGoTuningCandidate("G", List.of(0), 1);

    KataGoBenchmarkObservation incompleteObservation =
        new KataGoBenchmarkObservation(
            "Metal",
            1,
            1,
            List.of(new KataGoBenchmarkObservation.ThreadMetrics(1, 2, 3, 50, 40, 20, 2)),
            true,
            false,
            false);

    assertTrue(
        KataGoTuningSelector.select(
                Map.of(failed, observation(4, 500.0, true), incomplete, incompleteObservation))
            .isEmpty());
  }

  @Test
  void ranksByBestDetailedVisitsInsteadOfOfficialEloRecommendation() {
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GG", List.of(0, 0), 2);
    KataGoBenchmarkObservation observation =
        new KataGoBenchmarkObservation(
            "Metal", 1, 1, List.of(metrics(1, 80.0), metrics(4, 120.0)), true, false, false);

    KataGoTuningSelector.Selection selected =
        KataGoTuningSelector.select(Map.of(candidate, observation)).orElseThrow();

    assertEquals(4, selected.searchThreads());
    assertEquals(120.0, selected.metrics().visitsPerSecond());
  }

  private static KataGoBenchmarkObservation observation(
      int threads, double visitsPerSecond, boolean failed) {
    return new KataGoBenchmarkObservation(
        "Metal", threads, threads, List.of(metrics(threads, visitsPerSecond)), true, false, failed);
  }

  private static KataGoBenchmarkObservation.ThreadMetrics metrics(
      int threads, double visitsPerSecond) {
    return new KataGoBenchmarkObservation.ThreadMetrics(
        threads, 3, 3, visitsPerSecond, visitsPerSecond - 5.0, 20.0, 2.0);
  }
}
