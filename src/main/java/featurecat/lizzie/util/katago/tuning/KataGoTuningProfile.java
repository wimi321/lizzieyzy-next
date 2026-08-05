package featurecat.lizzie.util.katago.tuning;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

/** The selected runtime settings and benchmark evidence for one tuning fingerprint. */
public record KataGoTuningProfile(
    String fingerprintDigest,
    Mode mode,
    List<Integer> devices,
    int batch,
    int threads,
    Metrics metrics,
    String backend,
    long updatedAt) {

  public static final int SCHEMA_VERSION = 2;

  /**
   * Whether a profile owns only KataGo's thread recommendation or the Metal hardware layout too.
   */
  public enum Mode {
    OFFICIAL_THREADS,
    EXPERIMENTAL_HARDWARE
  }

  public KataGoTuningProfile {
    fingerprintDigest = normalize(fingerprintDigest);
    if (fingerprintDigest.isEmpty()) {
      throw new IllegalArgumentException("fingerprintDigest must not be blank");
    }
    Objects.requireNonNull(mode, "mode");
    devices = devices == null ? List.of() : List.copyOf(devices);
    if (mode == Mode.OFFICIAL_THREADS) {
      if (!devices.isEmpty() || batch != 0) {
        throw new IllegalArgumentException(
            "official thread profiles must not contain hardware settings");
      }
    } else {
      if (devices.isEmpty()) {
        throw new IllegalArgumentException("experimental hardware profiles must contain devices");
      }
      for (Integer device : devices) {
        if (device == null
            || (device != KataGoTuningCandidate.METAL_GPU
                && device != KataGoTuningCandidate.METAL_ANE)) {
          throw new IllegalArgumentException("devices must contain only Metal GPU or ANE lanes");
        }
      }
      if (batch <= 0 || batch > 65536) {
        throw new IllegalArgumentException("batch must be between 1 and 65536");
      }
      if (batch == 1
          && devices.contains(KataGoTuningCandidate.METAL_GPU)
          && devices.contains(KataGoTuningCandidate.METAL_ANE)) {
        throw new IllegalArgumentException("mixed GPU/ANE topology must not use batch 1");
      }
    }
    if (threads <= 0 || threads > 4096) {
      throw new IllegalArgumentException("threads must be between 1 and 4096");
    }
    Objects.requireNonNull(metrics, "metrics");
    backend = normalize(backend);
    if (updatedAt < 0L) {
      throw new IllegalArgumentException("updatedAt must not be negative");
    }
  }

  /** Backwards-compatible constructor for the original hardware-owning tuning profile. */
  public KataGoTuningProfile(
      String fingerprintDigest,
      List<Integer> devices,
      int batch,
      int threads,
      Metrics metrics,
      String backend,
      long updatedAt) {
    this(
        fingerprintDigest,
        Mode.EXPERIMENTAL_HARDWARE,
        devices,
        batch,
        threads,
        metrics,
        backend,
        updatedAt);
  }

  public KataGoTuningProfile(
      KataGoTuningFingerprint fingerprint,
      List<Integer> devices,
      int batch,
      int threads,
      Metrics metrics,
      String backend,
      long updatedAt) {
    this(
        Objects.requireNonNull(fingerprint, "fingerprint").canonicalDigest(),
        devices,
        batch,
        threads,
        metrics,
        backend,
        updatedAt);
  }

  /** Creates a profile that applies only the thread count chosen by KataGo's official benchmark. */
  public static KataGoTuningProfile officialThreads(
      KataGoTuningFingerprint fingerprint,
      int threads,
      Metrics metrics,
      String backend,
      long updatedAt) {
    return officialThreads(
        Objects.requireNonNull(fingerprint, "fingerprint").canonicalDigest(),
        threads,
        metrics,
        backend,
        updatedAt);
  }

  /** Digest-based variant useful when the caller already has a canonical fingerprint. */
  public static KataGoTuningProfile officialThreads(
      String fingerprintDigest, int threads, Metrics metrics, String backend, long updatedAt) {
    return new KataGoTuningProfile(
        fingerprintDigest,
        Mode.OFFICIAL_THREADS,
        List.of(),
        0,
        threads,
        metrics,
        backend,
        updatedAt);
  }

  /** Official profiles leave Metal devices and batch size under KataGo's own defaults. */
  public boolean managesHardwareSettings() {
    return mode == Mode.EXPERIMENTAL_HARDWARE;
  }

  public KataGoTuningProfile(
      KataGoTuningFingerprint fingerprint,
      List<Integer> devices,
      int batch,
      int threads,
      KataGoBenchmarkObservation.ThreadMetrics metrics,
      String backend,
      long updatedAt) {
    this(fingerprint, devices, batch, threads, Metrics.from(metrics), backend, updatedAt);
  }

  /** Compatibility alias for callers that use KataGo's full setting name. */
  public int searchThreads() {
    return threads;
  }

  /** Compatibility alias that makes the timestamp unit explicit. */
  public long updatedAtMillis() {
    return updatedAt;
  }

  public JSONObject toJson() {
    JSONObject json =
        new JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("mode", mode.name())
            .put("fingerprint", fingerprintDigest)
            .put("threads", threads)
            .put("metrics", metrics.toJson())
            .put("backend", backend)
            .put("updatedAt", updatedAt);
    if (managesHardwareSettings()) {
      JSONArray deviceArray = new JSONArray();
      for (Integer device : devices) {
        deviceArray.put(device.intValue());
      }
      json.put("devices", deviceArray).put("batch", batch);
    }
    return json;
  }

  /** Parses and validates persisted data, returning empty for any corrupt or incompatible value. */
  public static Optional<KataGoTuningProfile> fromJson(JSONObject json) {
    if (json == null) {
      return Optional.empty();
    }
    try {
      boolean legacyHardwareProfile = !json.has("schemaVersion") && !json.has("mode");
      Mode parsedMode;
      if (legacyHardwareProfile) {
        if (!json.has("devices") || !json.has("batch")) {
          return Optional.empty();
        }
        parsedMode = Mode.EXPERIMENTAL_HARDWARE;
      } else {
        if (json.getInt("schemaVersion") != SCHEMA_VERSION) {
          return Optional.empty();
        }
        parsedMode = Mode.valueOf(json.getString("mode"));
      }
      String fingerprint =
          json.has("fingerprint")
              ? json.getString("fingerprint")
              : json.getString("fingerprintDigest");
      List<Integer> parsedDevices = List.of();
      int parsedBatch = 0;
      if (parsedMode == Mode.EXPERIMENTAL_HARDWARE) {
        JSONArray deviceArray = json.getJSONArray("devices");
        if (deviceArray.isEmpty()) {
          return Optional.empty();
        }
        Integer[] devices = new Integer[deviceArray.length()];
        for (int i = 0; i < deviceArray.length(); i++) {
          devices[i] = Integer.valueOf(deviceArray.getInt(i));
        }
        parsedDevices = List.of(devices);
        parsedBatch = json.getInt("batch");
      } else if (json.has("devices") || json.has("batch")) {
        return Optional.empty();
      }
      Metrics parsedMetrics = Metrics.fromJson(json.getJSONObject("metrics"));
      return Optional.of(
          new KataGoTuningProfile(
              fingerprint,
              parsedMode,
              parsedDevices,
              parsedBatch,
              json.has("threads") ? json.getInt("threads") : json.getInt("searchThreads"),
              parsedMetrics,
              json.optString("backend", ""),
              json.has("updatedAt") ? json.getLong("updatedAt") : json.getLong("updatedAtMillis")));
    } catch (RuntimeException invalidJson) {
      return Optional.empty();
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  /** A lossless snapshot of the benchmark row that justified the selected profile. */
  public record Metrics(
      int positionsCompleted,
      int positionsTotal,
      double visitsPerSecond,
      double nnEvalsPerSecond,
      double nnBatchesPerSecond,
      double averageBatchSize) {
    public Metrics {
      if (positionsCompleted < 0 || positionsTotal < 0 || positionsCompleted > positionsTotal) {
        throw new IllegalArgumentException("invalid benchmark position counts");
      }
      requireFiniteNonNegative(visitsPerSecond, "visitsPerSecond");
      requireFiniteNonNegative(nnEvalsPerSecond, "nnEvalsPerSecond");
      requireFiniteNonNegative(nnBatchesPerSecond, "nnBatchesPerSecond");
      requireFiniteNonNegative(averageBatchSize, "averageBatchSize");
    }

    public Metrics(
        double visitsPerSecond,
        double nnEvalsPerSecond,
        double nnBatchesPerSecond,
        double averageBatchSize) {
      this(0, 0, visitsPerSecond, nnEvalsPerSecond, nnBatchesPerSecond, averageBatchSize);
    }

    public static Metrics from(KataGoBenchmarkObservation.ThreadMetrics metrics) {
      Objects.requireNonNull(metrics, "metrics");
      return new Metrics(
          metrics.positionsCompleted(),
          metrics.positionsTotal(),
          metrics.visitsPerSecond(),
          metrics.nnEvalsPerSecond(),
          metrics.nnBatchesPerSecond(),
          metrics.averageBatchSize());
    }

    private JSONObject toJson() {
      return new JSONObject()
          .put("positionsCompleted", positionsCompleted)
          .put("positionsTotal", positionsTotal)
          .put("visitsPerSecond", visitsPerSecond)
          .put("nnEvalsPerSecond", nnEvalsPerSecond)
          .put("nnBatchesPerSecond", nnBatchesPerSecond)
          .put("averageBatchSize", averageBatchSize);
    }

    private static Metrics fromJson(JSONObject json) {
      return new Metrics(
          json.optInt("positionsCompleted", 0),
          json.optInt("positionsTotal", 0),
          json.getDouble("visitsPerSecond"),
          json.getDouble("nnEvalsPerSecond"),
          json.getDouble("nnBatchesPerSecond"),
          json.getDouble("averageBatchSize"));
    }

    private static void requireFiniteNonNegative(double value, String name) {
      if (!Double.isFinite(value) || value < 0.0) {
        throw new IllegalArgumentException(name + " must be finite and non-negative");
      }
    }
  }
}
