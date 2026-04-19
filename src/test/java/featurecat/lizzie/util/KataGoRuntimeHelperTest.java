package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class KataGoRuntimeHelperTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String PATH_SEPARATOR = System.getProperty("path.separator");
  private static final String WINDOWS_OS_NAME = "Windows 11";

  @Test
  void externalEngineKeepsOriginalDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-external");
    Path enginePath = touch(tempRoot.resolve("external-engine").resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
    ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

    withConfig(
        runtimeWorkDirectory,
        () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

    assertEquals(
        normalize(originalDirectory),
        normalize(processBuilder.directory().toPath()),
        "External engine should keep its directory.");
    assertEquals(
        originalPath,
        processBuilder.environment().get("PATH"),
        "External engine should keep PATH unchanged.");
  }

  @Test
  void bundledOpenclEngineUsesRuntimeDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-bundled-opencl");
    Path enginePath =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-opencl")
                .resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
    ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

    withConfig(
        runtimeWorkDirectory,
        () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

    assertEquals(
        normalize(runtimeWorkDirectory),
        normalize(processBuilder.directory().toPath()),
        "Bundled OpenCL engine should use runtime directory.");
    assertEquals(
        normalize(enginePath.getParent()),
        firstPathEntry(processBuilder.environment().get("PATH")),
        "Bundled OpenCL engine should prepend its engine directory.");
  }

  @Test
  void bundledNvidiaEnginePrependsRuntimePath() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-bundled-nvidia");
          Path enginePath =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia")
                      .resolve("katago.exe"));
          Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
          ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

          withConfig(
              runtimeWorkDirectory,
              () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

          assertEquals(
              normalize(runtimeWorkDirectory),
              normalize(processBuilder.directory().toPath()),
              "Bundled NVIDIA engine should use runtime directory.");
          assertEquals(
              normalize(runtimeDir),
              firstPathEntry(processBuilder.environment().get("PATH")),
              "Bundled NVIDIA engine should prepend runtime directory first.");
          assertEquals(
              normalize(enginePath.getParent()),
              secondPathEntry(processBuilder.environment().get("PATH")),
              "Bundled NVIDIA engine should keep engine directory after runtime directory.");
        });
  }

  private static ProcessBuilder createProcessBuilder(Path directory, String pathValue) {
    ProcessBuilder processBuilder = new ProcessBuilder("echo");
    processBuilder.directory(directory.toFile());
    processBuilder.environment().put("PATH", pathValue);
    return processBuilder;
  }

  private static void withConfig(Path runtimeWorkDirectory, ThrowingRunnable action)
      throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Lizzie.config = createTestConfig(runtimeWorkDirectory);
      action.run();
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  private static Config createTestConfig(Path runtimeWorkDirectory) {
    return ConfigTestHelper.createForTests(runtimeWorkDirectory);
  }

  private static void withOsName(String osName, ThrowingRunnable action) throws Exception {
    String previousOsName = System.getProperty(OS_NAME_PROPERTY);
    try {
      System.setProperty(OS_NAME_PROPERTY, osName);
      action.run();
    } finally {
      restoreOsName(previousOsName);
    }
  }

  private static void restoreOsName(String previousOsName) {
    if (previousOsName == null) {
      System.clearProperty(OS_NAME_PROPERTY);
      return;
    }
    System.setProperty(OS_NAME_PROPERTY, previousOsName);
  }

  private static Path firstPathEntry(String pathValue) {
    return Path.of(pathValue.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))[0])
        .toAbsolutePath()
        .normalize();
  }

  private static Path secondPathEntry(String pathValue) {
    return Path.of(pathValue.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))[1])
        .toAbsolutePath()
        .normalize();
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
