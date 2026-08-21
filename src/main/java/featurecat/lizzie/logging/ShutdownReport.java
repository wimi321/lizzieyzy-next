package featurecat.lizzie.logging;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public final class ShutdownReport {
  private final Map<LogStream, Long> unwrittenCounts;

  public ShutdownReport(Map<LogStream, Long> unwrittenCounts) {
    this.unwrittenCounts = Collections.unmodifiableMap(Objects.requireNonNull(unwrittenCounts));
  }

  public Map<LogStream, Long> unwrittenCounts() {
    return unwrittenCounts;
  }

  public long unwritten(LogStream stream) {
    return unwrittenCounts.getOrDefault(stream, 0L);
  }
}
