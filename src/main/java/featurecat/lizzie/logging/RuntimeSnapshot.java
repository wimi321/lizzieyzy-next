package featurecat.lizzie.logging;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Formats a point-in-time JVM / work-dir resource snapshot for the diagnostic package {@code
 * snapshots/runtime.json}.
 *
 * <p>Each metric is independent: a single probe failure becomes an explicit missing state and does
 * not abort the rest of the snapshot. Absolute work-dir paths are never written.
 */
public final class RuntimeSnapshot {
  static final String ENTRY_NAME = "runtime.json";
  static final String MISSING_UNREADABLE = "unreadable";
  static final String MISSING_UNDEFINED = "undefined";
  static final String MISSING_UNAVAILABLE = "unavailable";
  static final List<String> REQUIRED_FIELDS =
      List.of(
          "processors",
          "heapUsedMiB",
          "heapCommittedMiB",
          "heapMaxMiB",
          "nonHeapUsedMiB",
          "workDirUsableGiB",
          "uptimeSeconds");

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * 1024L * 1024L;
  private static final int MAX_GC_NAMES = 8;
  private static final int MAX_TOKEN_UTF8_BYTES = 96;

  @FunctionalInterface
  interface DiskSpaceProbe {
    long usableBytes(Path workDirectory) throws Exception;
  }

  private RuntimeSnapshot() {}

  public static String capture(Path workDirectory, ExportSanitizer sanitizer) {
    return capture(workDirectory, sanitizer, RuntimeSnapshot::usableBytes);
  }

  static String capture(Path workDirectory, ExportSanitizer sanitizer, DiskSpaceProbe disk) {
    try {
      ExportSanitizer active = sanitizer == null ? new ExportSanitizer() : sanitizer;
      DiskSpaceProbe probe = disk == null ? RuntimeSnapshot::usableBytes : disk;
      return render(workDirectory, active, probe).toString(2);
    } catch (RuntimeException | Error ignored) {
      return unavailableJson();
    }
  }

  static String unavailableJson() {
    return missingDocument(MISSING_UNREADABLE).toString(2);
  }

  static JSONObject render(Path workDirectory, ExportSanitizer sanitizer, DiskSpaceProbe disk) {
    JSONObject json = new JSONObject();
    JSONObject missing = new JSONObject();
    putProcessors(json, missing);
    putHeap(json, missing);
    putNonHeap(json, missing);
    putWorkDirUsable(json, missing, workDirectory, disk);
    putUptime(json, missing);
    putGcNames(json, missing, sanitizer);
    putToken(
        json,
        missing,
        "jvmVendor",
        sanitizer,
        () -> ManagementFactory.getRuntimeMXBean().getVmVendor());
    putToken(
        json,
        missing,
        "jvmVersion",
        sanitizer,
        () -> {
          RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
          String version = runtime.getVmVersion();
          if (version == null || version.isBlank()) {
            version = runtime.getSpecVersion();
          }
          return version;
        });
    putSystemLoad(json, missing);
    if (missing.length() > 0) {
      json.put("missing", missing);
    }
    return json;
  }

  static JSONObject missingDocument(String reason) {
    String why = reason == null || reason.isBlank() ? MISSING_UNREADABLE : reason;
    JSONObject json = new JSONObject();
    JSONObject missing = new JSONObject();
    for (String field : REQUIRED_FIELDS) {
      json.put(field, JSONObject.NULL);
      missing.put(field, why);
    }
    json.put("missing", missing);
    return json;
  }

  static void putByteQuantity(
      JSONObject json, JSONObject missing, String field, long bytes, long unit) {
    if (bytes < 0L) {
      json.put(field, JSONObject.NULL);
      missing.put(field, MISSING_UNDEFINED);
      return;
    }
    json.put(field, bytes / unit);
  }

  private static void putProcessors(JSONObject json, JSONObject missing) {
    putNonNegative(
        json,
        missing,
        "processors",
        () -> Runtime.getRuntime().availableProcessors(),
        MISSING_UNDEFINED);
  }

  private static void putHeap(JSONObject json, JSONObject missing) {
    MemoryUsage heap = readHeapUsage();
    putMemoryMiB(json, missing, "heapUsedMiB", heap, MemoryUsage::getUsed);
    putMemoryMiB(json, missing, "heapCommittedMiB", heap, MemoryUsage::getCommitted);
    putMemoryMiB(json, missing, "heapMaxMiB", heap, MemoryUsage::getMax);
  }

  private static void putNonHeap(JSONObject json, JSONObject missing) {
    MemoryUsage nonHeap = readNonHeapUsage();
    putMemoryMiB(json, missing, "nonHeapUsedMiB", nonHeap, MemoryUsage::getUsed);
  }

  private static void putWorkDirUsable(
      JSONObject json, JSONObject missing, Path workDirectory, DiskSpaceProbe disk) {
    putNonNegative(
        json,
        missing,
        "workDirUsableGiB",
        () -> {
          if (disk == null) {
            throw new IllegalStateException("disk-probe-missing");
          }
          long bytes = disk.usableBytes(workDirectory);
          if (bytes < 0L) {
            return bytes;
          }
          return bytes / GIB;
        },
        MISSING_UNDEFINED);
  }

  private static void putUptime(JSONObject json, JSONObject missing) {
    putNonNegative(
        json,
        missing,
        "uptimeSeconds",
        () -> TimeUnit.MILLISECONDS.toSeconds(ManagementFactory.getRuntimeMXBean().getUptime()),
        MISSING_UNDEFINED);
  }

  private static void putGcNames(JSONObject json, JSONObject missing, ExportSanitizer sanitizer) {
    try {
      List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
      JSONArray names = new JSONArray();
      if (collectors != null) {
        for (GarbageCollectorMXBean collector : collectors) {
          if (names.length() >= MAX_GC_NAMES) {
            break;
          }
          if (collector == null) {
            continue;
          }
          try {
            String name = boundedToken(collector.getName(), sanitizer);
            if (name != null && !name.isEmpty()) {
              names.put(name);
            }
          } catch (RuntimeException ignoredCollector) {
            // One collector name must not drop the remaining names.
          }
        }
      }
      if (names.length() == 0) {
        json.put("gcNames", JSONObject.NULL);
        missing.put("gcNames", MISSING_UNREADABLE);
        return;
      }
      json.put("gcNames", names);
    } catch (RuntimeException ignored) {
      json.put("gcNames", JSONObject.NULL);
      missing.put("gcNames", MISSING_UNREADABLE);
    }
  }

  private static void putSystemLoad(JSONObject json, JSONObject missing) {
    try {
      OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
      double load = os.getSystemLoadAverage();
      if (load < 0D) {
        json.put("systemLoadAverage", JSONObject.NULL);
        missing.put("systemLoadAverage", MISSING_UNAVAILABLE);
        return;
      }
      json.put("systemLoadAverage", load);
    } catch (RuntimeException ignored) {
      json.put("systemLoadAverage", JSONObject.NULL);
      missing.put("systemLoadAverage", MISSING_UNREADABLE);
    }
  }

  private static void putToken(
      JSONObject json,
      JSONObject missing,
      String field,
      ExportSanitizer sanitizer,
      TokenReader reader) {
    try {
      String value = boundedToken(reader.read(), sanitizer);
      if (value == null || value.isEmpty()) {
        json.put(field, JSONObject.NULL);
        missing.put(field, MISSING_UNAVAILABLE);
        return;
      }
      json.put(field, value);
    } catch (Exception ignored) {
      json.put(field, JSONObject.NULL);
      missing.put(field, MISSING_UNREADABLE);
    }
  }

  private static void putMemoryMiB(
      JSONObject json,
      JSONObject missing,
      String field,
      MemoryUsage usage,
      MemoryQuantity quantity) {
    if (usage == null) {
      json.put(field, JSONObject.NULL);
      missing.put(field, MISSING_UNREADABLE);
      return;
    }
    try {
      putByteQuantity(json, missing, field, quantity.read(usage), MIB);
    } catch (RuntimeException ignored) {
      json.put(field, JSONObject.NULL);
      missing.put(field, MISSING_UNREADABLE);
    }
  }

  private static void putNonNegative(
      JSONObject json, JSONObject missing, String field, LongMetric metric, String negativeReason) {
    try {
      long value = metric.read();
      if (value < 0L) {
        json.put(field, JSONObject.NULL);
        missing.put(field, negativeReason);
        return;
      }
      json.put(field, value);
    } catch (Exception ignored) {
      json.put(field, JSONObject.NULL);
      missing.put(field, MISSING_UNREADABLE);
    }
  }

  private static MemoryUsage readHeapUsage() {
    try {
      return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static MemoryUsage readNonHeapUsage() {
    try {
      return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static long usableBytes(Path workDirectory) throws Exception {
    if (workDirectory == null) {
      throw new IllegalStateException("work-directory-missing");
    }
    return Files.getFileStore(workDirectory).getUsableSpace();
  }

  private static String boundedToken(String value, ExportSanitizer sanitizer) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    String bounded = ObservationText.boundedUtf8(trimmed, MAX_TOKEN_UTF8_BYTES, 1);
    String sanitized = sanitizer.sanitizeText(bounded);
    if (sanitized == null) {
      return null;
    }
    String safe = sanitized.trim();
    return safe.isEmpty() ? null : safe;
  }

  @FunctionalInterface
  private interface LongMetric {
    long read() throws Exception;
  }

  @FunctionalInterface
  private interface TokenReader {
    String read() throws Exception;
  }

  @FunctionalInterface
  private interface MemoryQuantity {
    long read(MemoryUsage usage);
  }
}
