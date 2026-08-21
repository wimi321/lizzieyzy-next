package featurecat.lizzie.logging;

import java.util.Objects;

public final class WorkDirectoryDiagnostic {
  public enum Kind {
    INFO,
    WARNING,
    ERROR
  }

  private final Kind kind;
  private final String code;
  private final String message;

  public WorkDirectoryDiagnostic(Kind kind, String code, String message) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.code = Objects.requireNonNull(code, "code");
    this.message = Objects.requireNonNull(message, "message");
  }

  public Kind kind() {
    return kind;
  }

  public String code() {
    return code;
  }

  public String message() {
    return message;
  }
}
