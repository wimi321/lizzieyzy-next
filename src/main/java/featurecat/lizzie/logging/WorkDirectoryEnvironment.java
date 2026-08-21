package featurecat.lizzie.logging;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jdesktop.swingx.util.OS;

public final class WorkDirectoryEnvironment {
  public static final String WORK_DIR_PROPERTY = "lizzie.work.dir";

  private final boolean windows;
  private final String userHome;
  private final String userDir;
  private final String explicitWorkDir;
  private final String publicDirectory;
  private final String programDataDirectory;
  private final List<Path> portableSeedPaths;

  public WorkDirectoryEnvironment(
      boolean windows,
      String userHome,
      String userDir,
      String explicitWorkDir,
      String publicDirectory,
      String programDataDirectory,
      List<Path> portableSeedPaths) {
    this.windows = windows;
    this.userHome = userHome == null ? "" : userHome;
    this.userDir = userDir == null ? "" : userDir;
    this.explicitWorkDir = explicitWorkDir == null ? "" : explicitWorkDir;
    this.publicDirectory = publicDirectory;
    this.programDataDirectory = programDataDirectory;
    this.portableSeedPaths =
        List.copyOf(Objects.requireNonNull(portableSeedPaths, "portableSeedPaths"));
  }

  public static WorkDirectoryEnvironment system() {
    List<Path> seeds = new ArrayList<>();
    try {
      URI location =
          WorkDirectoryEnvironment.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toURI();
      File codeSource = new File(location);
      seeds.add(codeSource.isFile() ? codeSource.toPath().getParent() : codeSource.toPath());
    } catch (Exception ignored) {
    }
    try {
      seeds.add(Path.of("").toAbsolutePath().normalize());
    } catch (Exception ignored) {
    }
    try {
      seeds.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
    } catch (Exception ignored) {
    }
    return new WorkDirectoryEnvironment(
        OS.isWindows(),
        System.getProperty("user.home", ""),
        System.getProperty("user.dir", ""),
        System.getProperty(WORK_DIR_PROPERTY, ""),
        System.getenv("PUBLIC"),
        System.getenv("PROGRAMDATA"),
        seeds);
  }

  public boolean windows() {
    return windows;
  }

  public String userHome() {
    return userHome;
  }

  public String userDir() {
    return userDir;
  }

  public String explicitWorkDir() {
    return explicitWorkDir;
  }

  public String publicDirectory() {
    return publicDirectory;
  }

  public String programDataDirectory() {
    return programDataDirectory;
  }

  public List<Path> portableSeedPaths() {
    return portableSeedPaths;
  }
}
