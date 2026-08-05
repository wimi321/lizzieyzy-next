package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KataGoTuningCandidateTest {
  @Test
  void candidateCopiesDevicesAndBuildsHighestPriorityBenchmarkOverrides() {
    ArrayList<Integer> source = new ArrayList<Integer>(List.of(0, 100));
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GA", source, 2);
    source.set(0, 100);

    assertEquals(List.of(0, 100), candidate.devices());
    assertThrows(UnsupportedOperationException.class, () -> candidate.devices().add(0));
    assertEquals(
        Map.of(
            "numNNServerThreadsPerModel",
            "2",
            "metalDeviceToUseModel0Thread0",
            "0",
            "metalDeviceToUseModel0Thread1",
            "100",
            "metalUseFP16-0",
            "true"),
        candidate.benchmarkOverrides());
    assertFalse(candidate.benchmarkOverrides().containsKey("nnMaxBatchSize"));
    assertFalse(candidate.benchmarkOverrides().containsKey("numSearchThreads"));
  }

  @Test
  void runtimeOverridesAddTheMeasuredBatchAndSelectedThreads() {
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GGA", List.of(0, 0, 100), 3);

    Map<String, String> overrides = candidate.runtimeOverrides(6);

    assertEquals("3", overrides.get("nnMaxBatchSize"));
    assertEquals("6", overrides.get("numSearchThreads"));
    assertEquals("GGA-b3", candidate.displayId());
    assertTrue(candidate.runtimeOverrideConfig(6).endsWith("nnMaxBatchSize=3,numSearchThreads=6"));
  }

  @Test
  void invalidDevicesAndMixedBatchOneAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new KataGoTuningCandidate("invalid", List.of(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new KataGoTuningCandidate("unsafe", List.of(0, 100), 1));
  }
}
