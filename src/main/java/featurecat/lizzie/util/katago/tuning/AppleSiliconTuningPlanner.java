package featurecat.lizzie.util.katago.tuning;

import featurecat.lizzie.util.katago.tuning.AppleSiliconHardwareProbe.HardwareProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Produces a bounded, memory-aware set of Metal topology and batch candidates. */
public final class AppleSiliconTuningPlanner {
  static final long GIB = 1024L * 1024L * 1024L;
  static final long GGA_MIN_MEMORY_BYTES = 12L * GIB;
  static final long GGAA_MIN_MEMORY_BYTES = 16L * GIB;
  static final long MULTI_LANE_MAX_MODEL_BYTES = (3L * GIB) / 2L;

  private AppleSiliconTuningPlanner() {}

  public static List<KataGoTuningCandidate> candidates(HardwareProfile hardware, long modelBytes) {
    Objects.requireNonNull(hardware, "hardware");
    if (modelBytes < 0) {
      throw new IllegalArgumentException("modelBytes must not be negative");
    }

    List<KataGoTuningCandidate> candidates = new ArrayList<KataGoTuningCandidate>();
    addBatchCandidates(candidates, "G", List.of(0), 1, 2, 4);
    addBatchCandidates(candidates, "GG", List.of(0, 0), 2, 4);
    addBatchCandidates(candidates, "GA", List.of(0, 100), 2, 4);

    long memoryBytes = hardware.memoryBytes();
    boolean memoryKnown = memoryBytes > 0;
    boolean modelKnown = modelBytes > 0;
    if (memoryKnown
        && memoryBytes >= GGA_MIN_MEMORY_BYTES
        && (!modelKnown || modelBytes <= MULTI_LANE_MAX_MODEL_BYTES)) {
      addBatchCandidates(candidates, "GGA", List.of(0, 0, 100), 3, 6);
    }
    if (memoryKnown
        && memoryBytes >= GGAA_MIN_MEMORY_BYTES
        && (!modelKnown || modelBytes <= MULTI_LANE_MAX_MODEL_BYTES)) {
      addBatchCandidates(candidates, "GGAA", List.of(0, 0, 100, 100), 4, 8);
    }

    return List.copyOf(candidates);
  }

  private static void addBatchCandidates(
      List<KataGoTuningCandidate> candidates,
      String topologyId,
      List<Integer> devices,
      int... batchSizes) {
    for (int batchSize : batchSizes) {
      candidates.add(new KataGoTuningCandidate(topologyId, devices, batchSize));
    }
  }
}
