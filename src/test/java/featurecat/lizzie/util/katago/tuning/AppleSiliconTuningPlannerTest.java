package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import featurecat.lizzie.util.katago.tuning.AppleSiliconHardwareProbe.HardwareProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppleSiliconTuningPlannerTest {
  @Test
  void wellProvisionedMacGetsBoundedTopologyAndBatchCombinations() {
    List<KataGoTuningCandidate> candidates =
        AppleSiliconTuningPlanner.candidates(hardwareWithMemory(24L << 30), 100L << 20);

    assertEquals(
        List.of(
            "G-b1",
            "G-b2",
            "G-b4",
            "GG-b2",
            "GG-b4",
            "GA-b2",
            "GA-b4",
            "GGA-b3",
            "GGA-b6",
            "GGAA-b4",
            "GGAA-b8"),
        ids(candidates));
    assertFalse(
        candidates.stream().anyMatch(candidate -> candidate.mixed() && candidate.batch() == 1));
  }

  @Test
  void unknownOrConstrainedMemoryKeepsOnlyConservativeCandidates() {
    assertEquals(
        List.of("G-b1", "G-b2", "G-b4", "GG-b2", "GG-b4", "GA-b2", "GA-b4"),
        ids(AppleSiliconTuningPlanner.candidates(hardwareWithMemory(0), 100L << 20)));
    assertEquals(
        List.of("G-b1", "G-b2", "G-b4", "GG-b2", "GG-b4", "GA-b2", "GA-b4"),
        ids(AppleSiliconTuningPlanner.candidates(hardwareWithMemory(8L << 30), 100L << 20)));
  }

  @Test
  void mediumMemoryAddsGgaButNotFourLaneMux() {
    assertEquals(
        List.of(
            "G-b1",
            "G-b2",
            "G-b4",
            "GG-b2",
            "GG-b4",
            "GA-b2",
            "GA-b4",
            "GGA-b3",
            "GGA-b6"),
        ids(AppleSiliconTuningPlanner.candidates(hardwareWithMemory(14L << 30), 100L << 20)));
  }

  @Test
  void veryLargeModelRemovesHigherMemoryPressureTopologies() {
    assertEquals(
        List.of("G-b1", "G-b2", "G-b4", "GG-b2", "GG-b4", "GA-b2", "GA-b4"),
        ids(AppleSiliconTuningPlanner.candidates(hardwareWithMemory(32L << 30), 2L << 30)));
  }

  private static HardwareProfile hardwareWithMemory(long memoryBytes) {
    return new HardwareProfile("Mac16,1", "Apple M5", "arm64", 12, memoryBytes, "25A123", false);
  }

  private static List<String> ids(List<KataGoTuningCandidate> candidates) {
    return candidates.stream().map(KataGoTuningCandidate::displayId).toList();
  }
}
