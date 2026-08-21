package featurecat.lizzie.logging;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LoggingStatus {
  private final boolean persistenceEnabled;
  private final List<StreamStatus> streams;

  public LoggingStatus(boolean persistenceEnabled, List<StreamStatus> streams) {
    this.persistenceEnabled = persistenceEnabled;
    this.streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
  }

  public boolean persistenceEnabled() {
    return persistenceEnabled;
  }

  public List<StreamStatus> streams() {
    return streams;
  }

  public Optional<StreamStatus> stream(LogStream stream) {
    for (StreamStatus status : streams) {
      if (status.stream() == stream) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  public static final class StreamStatus {
    private final LogStream stream;
    private final String reason;
    private final Instant firstOccurrence;
    private final Instant lastOccurrence;
    private final long droppedCount;
    private final boolean recovered;

    public StreamStatus(
        LogStream stream,
        String reason,
        Instant firstOccurrence,
        Instant lastOccurrence,
        long droppedCount,
        boolean recovered) {
      this.stream = Objects.requireNonNull(stream, "stream");
      this.reason = reason;
      this.firstOccurrence = firstOccurrence;
      this.lastOccurrence = lastOccurrence;
      this.droppedCount = droppedCount;
      this.recovered = recovered;
    }

    public LogStream stream() {
      return stream;
    }

    public String reason() {
      return reason;
    }

    public Instant firstOccurrence() {
      return firstOccurrence;
    }

    public Instant lastOccurrence() {
      return lastOccurrence;
    }

    public long droppedCount() {
      return droppedCount;
    }

    public boolean recovered() {
      return recovered;
    }
  }
}
