package featurecat.lizzie.util.katago.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A fixed Metal topology and neural-network batch cap to benchmark as one tuning candidate. */
public record KataGoTuningCandidate(String id, List<Integer> devices, int batch) {
  public static final int METAL_GPU = 0;
  public static final int METAL_ANE = 100;

  public KataGoTuningCandidate {
    if (id == null || id.trim().isEmpty()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    id = id.trim();
    if (devices == null || devices.isEmpty()) {
      throw new IllegalArgumentException("devices must not be empty");
    }
    devices = List.copyOf(devices);
    for (Integer device : devices) {
      if (device == null || (device != METAL_GPU && device != METAL_ANE)) {
        throw new IllegalArgumentException("Metal devices must be 0 (GPU) or 100 (ANE)");
      }
    }
    if (batch <= 0 || batch > 65536) {
      throw new IllegalArgumentException("batch must be between 1 and 65536");
    }
    if (batch == 1 && devices.contains(METAL_GPU) && devices.contains(METAL_ANE)) {
      throw new IllegalArgumentException("mixed GPU/ANE topology must not use batch 1");
    }
  }

  public int serverCount() {
    return devices.size();
  }

  public boolean mixed() {
    return devices.contains(METAL_GPU) && devices.contains(METAL_ANE);
  }

  /** A stable label that distinguishes batch variants of the same Metal topology. */
  public String displayId() {
    return id + "-b" + batch;
  }

  /**
   * Returns the topology overrides used by an isolated benchmark process.
   *
   * <p>The model-0 device keys deliberately have higher KataGo precedence than the commonly used
   * {@code metalDeviceToUseThreadN} aliases, so a base config cannot silently change the candidate.
   */
  public Map<String, String> benchmarkOverrides() {
    LinkedHashMap<String, String> overrides = new LinkedHashMap<String, String>();
    overrides.put("numNNServerThreadsPerModel", String.valueOf(devices.size()));
    for (int i = 0; i < devices.size(); i++) {
      overrides.put("metalDeviceToUseModel0Thread" + i, String.valueOf(devices.get(i)));
    }
    overrides.put("metalUseFP16-0", "true");
    return Collections.unmodifiableMap(overrides);
  }

  /** Returns the benchmark topology plus the batch and search-thread settings used at runtime. */
  public Map<String, String> runtimeOverrides(int searchThreads) {
    if (searchThreads <= 0 || searchThreads > 4096) {
      throw new IllegalArgumentException("searchThreads must be between 1 and 4096");
    }
    LinkedHashMap<String, String> overrides =
        new LinkedHashMap<String, String>(benchmarkOverrides());
    overrides.put("nnMaxBatchSize", String.valueOf(batch));
    overrides.put("numSearchThreads", String.valueOf(searchThreads));
    return Collections.unmodifiableMap(overrides);
  }

  public String benchmarkOverrideConfig() {
    return toOverrideConfig(benchmarkOverrides());
  }

  public String runtimeOverrideConfig(int searchThreads) {
    return toOverrideConfig(runtimeOverrides(searchThreads));
  }

  private static String toOverrideConfig(Map<String, String> overrides) {
    return overrides.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(","));
  }
}
