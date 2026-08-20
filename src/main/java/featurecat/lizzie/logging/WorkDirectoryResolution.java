package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class WorkDirectoryResolution {
  private final Path directory;
  private final List<WorkDirectoryDiagnostic> diagnostics;

  public WorkDirectoryResolution(Path directory, List<WorkDirectoryDiagnostic> diagnostics) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }

  public Path directory() {
    return directory;
  }

  public List<WorkDirectoryDiagnostic> diagnostics() {
    return diagnostics;
  }
}
