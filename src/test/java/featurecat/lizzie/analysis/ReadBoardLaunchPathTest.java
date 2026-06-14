package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  void resolveJavaReadBoardJarRecognizesJpackageAppLayout() throws Exception {
    Path appRoot = Files.createDirectories(tempDir.resolve("LizzieYzy Next"));
    Path javaReadBoardDir =
        Files.createDirectories(appRoot.resolve("app").resolve("readboard_java"));
    Path jar = Files.write(javaReadBoardDir.resolve("readboard-1.6.2-shaded.jar"), new byte[] {0});

    assertEquals(
        jar.toFile().getAbsolutePath(),
        ReadBoard.resolveJavaReadBoardJar(
                ReadBoard.javaReadBoardDirectoryCandidatesForBase(appRoot.toFile()),
                "readboard-1.6.2-shaded.jar")
            .getAbsolutePath());
  }

  @Test
  void defaultJavaReadBoardDirectoryCandidatesIncludesUserRuntimeExtractionDirectory()
      throws Exception {
    String userHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", tempDir.toString());
      String expected =
          tempDir.resolve(".lizzieyzy-next").resolve("runtime").resolve("readboard_java")
              .toFile()
              .getAbsolutePath();

      assertTrue(
          ReadBoard.defaultJavaReadBoardDirectoryCandidates().stream()
              .map(java.io.File::getAbsolutePath)
              .anyMatch(expected::equals));
    } finally {
      System.setProperty("user.home", userHome);
    }
  }

  @Test
  void extractBundledJavaReadBoardCopiesHelperToWritableRuntimeDirectory() throws Exception {
    Path targetDir = tempDir.resolve("runtime").resolve("readboard_java");

    java.io.File jar =
        ReadBoard.extractBundledJavaReadBoard("readboard-1.6.2-shaded.jar", targetDir.toFile());

    assertTrue(jar.isFile());
    assertTrue(jar.length() > 10_000_000L);
    assertTrue(targetDir.resolve("help.docx").toFile().isFile());
    assertTrue(targetDir.resolve("help_en.docx").toFile().isFile());
    assertTrue(targetDir.resolve("help_jp.docx").toFile().isFile());
  }

  @Test
  void buildJavaReadBoardProcessBuilderUsesAbsoluteJarAndJarDirectory() throws Exception {
    Path javaReadBoardDir =
        Files.createDirectories(tempDir.resolve("app").resolve("readboard_java"));
    Path jar = Files.write(javaReadBoardDir.resolve("readboard-1.6.2-shaded.jar"), new byte[] {0});

    ProcessBuilder processBuilder =
        ReadBoard.buildJavaReadBoardProcessBuilder(
            jar.toFile(), Arrays.asList("cn", "false", "14", "19", "19"), Arrays.asList("-Xmx64m"));

    assertEquals("-jar", processBuilder.command().get(2));
    assertEquals(jar.toFile().getAbsolutePath(), processBuilder.command().get(3));
    assertEquals(
        javaReadBoardDir.toFile().getAbsolutePath(), processBuilder.directory().getAbsolutePath());
    assertTrue(processBuilder.redirectErrorStream());
  }

  @Test
  void buildJavaReadBoardProcessBuilderUsesExplicitWorkingDirectory() throws Exception {
    Path javaReadBoardDir =
        Files.createDirectories(tempDir.resolve("app").resolve("readboard_java"));
    Path workingDir = Files.createDirectories(tempDir.resolve("runtime").resolve("readboard_java"));
    Path jar = Files.write(javaReadBoardDir.resolve("readboard-1.6.2-shaded.jar"), new byte[] {0});

    ProcessBuilder processBuilder =
        ReadBoard.buildJavaReadBoardProcessBuilder(
            jar.toFile(),
            workingDir.toFile(),
            Arrays.asList("cn", "false", "14", "19", "19"),
            Arrays.asList("-Xmx64m"));

    assertEquals(jar.toFile().getAbsolutePath(), processBuilder.command().get(3));
    assertEquals(
        workingDir.toFile().getAbsolutePath(), processBuilder.directory().getAbsolutePath());
    assertTrue(processBuilder.redirectErrorStream());
  }

  @Test
  void canUseJavaReadBoardWorkingDirectoryRequiresWritableDirectory() throws Exception {
    Path directory = Files.createDirectories(tempDir.resolve("writable"));
    Path file = Files.write(tempDir.resolve("not-a-directory"), new byte[] {0});

    assertTrue(ReadBoard.canUseJavaReadBoardWorkingDirectory(directory.toFile()));
    assertFalse(ReadBoard.canUseJavaReadBoardWorkingDirectory(file.toFile()));
  }
}
