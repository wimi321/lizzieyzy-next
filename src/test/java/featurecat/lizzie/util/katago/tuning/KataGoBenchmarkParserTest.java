package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KataGoBenchmarkParserTest {
  @Test
  void parsesCrLfMetricsMuxMarkersAndBaselineRecommendation() {
    String output =
        "You are currently using the Metal version of KataGo.\r\n"
            + "Your GTP config is currently set to use numSearchThreads = 6\n"
            + "Metal backend 0: GPU mode - using MPSGraph (GPU)\r"
            + "Metal backend 2: Mux ANE mode - using CoreML (CPU+ANE)\n"
            + "numSearchThreads =  1: 0 / 6 positions, visits/s = nan (0.0 secs)\r"
            + "numSearchThreads =  1: 6 / 6 positions, visits/s = 74.25 nnEvals/s = 63.50 "
            + "nnBatches/s = 40.00 avgBatchSize = 1.59 (64.6 secs) (EloDiff baseline)\n"
            + "numSearchThreads =  1: (baseline) (recommended)\n";

    KataGoBenchmarkObservation observation = KataGoBenchmarkParser.parse(output, 0);

    assertEquals("Metal", observation.backend());
    assertEquals(6, observation.currentThreads());
    assertEquals(1, observation.recommendedThreads());
    assertTrue(observation.mixedMetalInitialized());
    assertFalse(observation.failureDetected());
    assertEquals(1, observation.metrics().size());
    KataGoBenchmarkObservation.ThreadMetrics metrics = observation.metrics().get(0);
    assertEquals(74.25, metrics.visitsPerSecond());
    assertEquals(63.50, metrics.nnEvalsPerSecond());
    assertEquals(40.00, metrics.nnBatchesPerSecond());
    assertEquals(1.59, metrics.averageBatchSize());
  }

  @Test
  void explicitSingleThreadBenchmarkDoesNotNeedRecommendedMarker() {
    String output =
        "numSearchThreads =  7: 3 / 3 positions, visits/s = 120.0 nnEvals/s = 100.0 "
            + "nnBatches/s = 25.0 avgBatchSize = 4.0 (10.0 secs) (EloDiff baseline)";

    KataGoBenchmarkObservation observation = KataGoBenchmarkParser.parse(output, 7);

    assertEquals(7, observation.recommendedThreads());
    assertTrue(observation.recommendedMetric().isPresent());
  }

  @Test
  void detectsHardProcessFailureWithoutInventingMetrics() {
    KataGoBenchmarkObservation observation =
        KataGoBenchmarkParser.parse("Segmentation fault: 11\n", 4);

    assertTrue(observation.failureDetected());
    assertFalse(observation.hasMetrics());
    assertEquals(4, observation.recommendedThreads());
  }
}
