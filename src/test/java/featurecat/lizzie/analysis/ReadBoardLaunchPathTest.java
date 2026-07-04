package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadBoardLaunchPathTest {
  @TempDir Path tempDir;

  @Test
  void buildLegacyNativeReadBoardProcessBuilderUsesAbsoluteExePath() throws Exception {
    Path readBoardDir = Files.createDirectories(tempDir.resolve("readboard"));
    Path executable = Files.write(readBoardDir.resolve("readboard.exe"), new byte[] {0});
    List<String> arguments = Arrays.asList("yzy", "10", "10000", "3000", "0", "cn", "-1");

    ProcessBuilder processBuilder =
        ReadBoard.buildLegacyNativeReadBoardProcessBuilder(readBoardDir.toFile(), true, arguments);

    assertEquals(executable.toFile().getAbsolutePath(), processBuilder.command().get(0));
    assertEquals(
        readBoardDir.toFile().getAbsolutePath(), processBuilder.directory().getAbsolutePath());
    assertTrue(processBuilder.redirectErrorStream());
    assertEquals(arguments, processBuilder.command().subList(1, processBuilder.command().size()));
  }

  @Test
  void buildLegacyNativeReadBoardProcessBuilderUsesAbsoluteBatchPathForSocketFallback()
      throws Exception {
    Path readBoardDir = Files.createDirectories(tempDir.resolve("readboard"));
    Path batch = Files.write(readBoardDir.resolve("readboard.bat"), new byte[] {0});

    ProcessBuilder processBuilder =
        ReadBoard.buildLegacyNativeReadBoardProcessBuilder(
            readBoardDir.toFile(), false, Arrays.asList("yzy", " ", " ", " ", "1", "cn", "12345"));

    assertEquals(batch.toFile().getAbsolutePath(), processBuilder.command().get(0));
    assertEquals(
        readBoardDir.toFile().getAbsolutePath(), processBuilder.directory().getAbsolutePath());
  }

  @Test
  void buildLegacyNativeReadBoardProcessBuilderPipeModeDoesNotSilentlyUseBatchFallback()
      throws Exception {
    Path readBoardDir = Files.createDirectories(tempDir.resolve("readboard"));
    Files.write(readBoardDir.resolve("readboard.bat"), new byte[] {0});

    ProcessBuilder processBuilder =
        ReadBoard.buildLegacyNativeReadBoardProcessBuilder(
            readBoardDir.toFile(),
            true,
            Arrays.asList("yzy", "10", "10000", "3000", "0", "cn", "-1"));

    assertEquals(
        readBoardDir.resolve("readboard.exe").toFile().getAbsolutePath(),
        processBuilder.command().get(0));
  }

  @Test
  void isLegacyNativeReadBoardAvailableRequiresLegacyCommandInReadboardDirectory()
      throws Exception {
    assertFalse(ReadBoard.isLegacyNativeReadBoardAvailable(tempDir.resolve("readboard").toFile()));

    Path readBoardDir = Files.createDirectories(tempDir.resolve("readboard"));
    Files.write(readBoardDir.resolve("readboard.exe"), new byte[] {0});

    assertTrue(ReadBoard.isLegacyNativeReadBoardAvailable(readBoardDir.toFile()));
  }

  @Test
  void isLegacyNativeReadBoardAvailableRecognizesBatchOnlyLegacyInstall() throws Exception {
    Path readBoardDir = Files.createDirectories(tempDir.resolve("readboard"));
    Files.write(readBoardDir.resolve("readboard.bat"), new byte[] {0});

    assertTrue(ReadBoard.isLegacyNativeReadBoardAvailable(readBoardDir.toFile()));
  }

  @Test
  void resolveNativeReadBoardDirectoryRecognizesJpackageAppLayout() throws Exception {
    Path appRoot = Files.createDirectories(tempDir.resolve("LizzieYzy Next"));
    Path appReadBoardDir = Files.createDirectories(appRoot.resolve("app").resolve("readboard"));
    Files.write(appReadBoardDir.resolve("readboard.exe"), new byte[] {0});

    assertEquals(
        appReadBoardDir.toFile().getAbsolutePath(),
        ReadBoard.resolveNativeReadBoardDirectory(
                ReadBoard.nativeReadBoardDirectoryCandidatesForBase(appRoot.toFile()))
            .getAbsolutePath());
  }

  @Test
  void resolveNativeReadBoardDirectoryPrefersExecutableOverEmptyLegacyDirectory() throws Exception {
    Path appRoot = Files.createDirectories(tempDir.resolve("LizzieYzy Next"));
    Files.createDirectories(appRoot.resolve("readboard"));
    Path appReadBoardDir = Files.createDirectories(appRoot.resolve("app").resolve("readboard"));
    Files.write(appReadBoardDir.resolve("readboard.exe"), new byte[] {0});

    assertEquals(
        appReadBoardDir.toFile().getAbsolutePath(),
        ReadBoard.resolveNativeReadBoardDirectory(
                ReadBoard.nativeReadBoardDirectoryCandidatesForBase(appRoot.toFile()))
            .getAbsolutePath());
  }

  @Test
  void defaultNativeReadBoardDirectoryCandidatesIncludeJpackageRuntimeRoot() {
    String javaHome = System.getProperty("java.home");
    try {
      Path appRoot = tempDir.resolve("LizzieYzy Next");
      System.setProperty("java.home", appRoot.resolve("runtime").toString());

      List<String> candidatePaths = new ArrayList<String>();
      for (java.io.File candidate : ReadBoard.defaultNativeReadBoardDirectoryCandidates()) {
        candidatePaths.add(candidate.getAbsolutePath());
      }

      assertTrue(
          candidatePaths.contains(
              appRoot.resolve("app").resolve("readboard").toFile().getAbsolutePath()));
    } finally {
      System.setProperty("java.home", javaHome);
    }
  }

  @Test
  void constructorRejectsRetiredJavaReadBoardMode() {
    assertThrows(UnsupportedOperationException.class, () -> new ReadBoard(true, true));
  }
}
