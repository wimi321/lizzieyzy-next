package featurecat.lizzie.logging;

public final class LoggingLimits {
  public static final int APP_QUEUE_CAPACITY = 4096;
  public static final int ENGINE_TRACE_QUEUE_CAPACITY = 8192;
  public static final int READBOARD_TRACE_QUEUE_CAPACITY = 4096;
  public static final int NETWORK_TRACE_QUEUE_CAPACITY = 4096;
  public static final int RETENTION_DAYS = 7;
  public static final long TOTAL_SIZE_CAP_BYTES = 100L * 1024 * 1024;
  public static final long ACTIVE_FILE_SIZE_BYTES = 10L * 1024 * 1024;
  public static final long SHUTDOWN_BUDGET_NANOS = 3_000_000_000L;

  private final int appQueueCapacity;
  private final int engineTraceQueueCapacity;
  private final int readboardTraceQueueCapacity;
  private final int networkTraceQueueCapacity;
  private final int retentionDays;
  private final long totalSizeCapBytes;
  private final long activeFileSizeBytes;

  public LoggingLimits(
      int appQueueCapacity,
      int engineTraceQueueCapacity,
      int readboardTraceQueueCapacity,
      int networkTraceQueueCapacity,
      int retentionDays,
      long totalSizeCapBytes,
      long activeFileSizeBytes) {
    this.appQueueCapacity = appQueueCapacity;
    this.engineTraceQueueCapacity = engineTraceQueueCapacity;
    this.readboardTraceQueueCapacity = readboardTraceQueueCapacity;
    this.networkTraceQueueCapacity = networkTraceQueueCapacity;
    this.retentionDays = retentionDays;
    this.totalSizeCapBytes = totalSizeCapBytes;
    this.activeFileSizeBytes = activeFileSizeBytes;
  }

  public static LoggingLimits production() {
    return new LoggingLimits(
        APP_QUEUE_CAPACITY,
        ENGINE_TRACE_QUEUE_CAPACITY,
        READBOARD_TRACE_QUEUE_CAPACITY,
        NETWORK_TRACE_QUEUE_CAPACITY,
        RETENTION_DAYS,
        TOTAL_SIZE_CAP_BYTES,
        ACTIVE_FILE_SIZE_BYTES);
  }

  public int appQueueCapacity() {
    return appQueueCapacity;
  }

  public int engineTraceQueueCapacity() {
    return engineTraceQueueCapacity;
  }

  public int readboardTraceQueueCapacity() {
    return readboardTraceQueueCapacity;
  }

  public int networkTraceQueueCapacity() {
    return networkTraceQueueCapacity;
  }

  public int retentionDays() {
    return retentionDays;
  }

  public long totalSizeCapBytes() {
    return totalSizeCapBytes;
  }

  public long activeFileSizeBytes() {
    return activeFileSizeBytes;
  }

  public int queueCapacity(LogStream stream) {
    switch (stream) {
      case ENGINE_TRACE:
        return engineTraceQueueCapacity;
      case READBOARD_TRACE:
        return readboardTraceQueueCapacity;
      case NETWORK_TRACE:
        return networkTraceQueueCapacity;
      case APP:
      case CRASH:
      default:
        return appQueueCapacity;
    }
  }
}
