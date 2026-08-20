package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class WorkDirectoryResolverTest {

  @Test
  void explicitWorkDirIsUsedAndCreatesSave() throws Exception {
    Path workDir = Files.createTempDirectory("lizzie-explicit-work");
    WorkDirectoryEnvironment environment =
        environment(false, workDir.getParent(), workDir.getParent(), workDir.toString());

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

    assertEquals(workDir.toAbsolutePath().normalize(), resolution.directory());
    assertTrue(Files.isDirectory(workDir.resolve("save")));
  }

  @Test
  void writableCwdIsUsedOnNonWindows() throws Exception {
    Path cwd = Files.createTempDirectory("lizzie-writable-cwd");
    WorkDirectoryEnvironment environment = environment(false, cwd.getParent(), cwd, "");

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

    assertEquals(cwd.toAbsolutePath().normalize(), resolution.directory());
    assertTrue(resolution.diagnostics().isEmpty());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void nonWritableInstalledLocationFallsBackWithoutUsingCwd() throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(Path.of("/tmp")).supportsFileAttributeView("posix"));
    Path home = Files.createTempDirectory("lizzie-home");
    Path installed = Files.createTempDirectory("lizzie-installed");
    Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      WorkDirectoryEnvironment environment = environment(false, home, installed, "");

      WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

      Path expected = home.resolve(WorkDirectoryResolver.USER_WORK_DIR_NAME);
      assertEquals(expected.toAbsolutePath().normalize(), resolution.directory());
      assertTrue(Files.isDirectory(expected.resolve("save")));
      assertFalse(resolution.directory().equals(installed.toAbsolutePath().normalize()));
      assertTrue(
          resolution.diagnostics().stream()
              .anyMatch(diagnostic -> "fallback".equals(diagnostic.code())));
    } finally {
      Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwxrwxrwx"));
    }
  }

  @Test
  void windowsPortableLayoutUsesUserData() throws Exception {
    Path portableRoot = Files.createTempDirectory("lizzie-portable");
    Files.writeString(portableRoot.resolve(".lizzie-portable"), "portable");
    WorkDirectoryEnvironment environment =
        new WorkDirectoryEnvironment(
            true,
            portableRoot.resolve("home").toString(),
            portableRoot.resolve("app").toString(),
            "",
            null,
            null,
            List.of(portableRoot.resolve("app")));

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

    assertEquals(
        portableRoot.resolve("user-data").toAbsolutePath().normalize(), resolution.directory());
    assertTrue(Files.isDirectory(resolution.directory().resolve("save")));
  }

  @Test
  void windowsFallbackUsesSharedPublicDocuments() throws Exception {
    Path home = Files.createTempDirectory("lizzie-win-home");
    Path cwd = Files.createTempDirectory("lizzie-win-cwd");
    Path publicRoot = Files.createTempDirectory("lizzie-public");
    WorkDirectoryEnvironment environment =
        new WorkDirectoryEnvironment(
            true, home.toString(), cwd.toString(), "", publicRoot.toString(), null, List.of(cwd));

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

    Path expected = publicRoot.resolve("Documents").resolve("LizzieYzyNext");
    assertEquals(expected.toAbsolutePath().normalize(), resolution.directory());
    assertTrue(
        resolution.diagnostics().stream()
            .anyMatch(diagnostic -> "fallback".equals(diagnostic.code())));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void migratedLegacyConfigMovesToPreferredHomeDirectory() throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(Path.of("/tmp")).supportsFileAttributeView("posix"));
    Path home = Files.createTempDirectory("lizzie-migrate-home");
    Path installed = Files.createTempDirectory("lizzie-migrate-installed");
    Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("r-xr-xr-x"));
    Path legacy = home.resolve(WorkDirectoryResolver.LEGACY_USER_WORK_DIR_NAME);
    Files.createDirectories(legacy.resolve("save"));
    Files.writeString(legacy.resolve("config.txt"), "{\"ui\":{}}");
    try {
      WorkDirectoryEnvironment environment = environment(false, home, installed, "");

      WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

      Path expected = home.resolve(WorkDirectoryResolver.USER_WORK_DIR_NAME);
      assertEquals(expected.toAbsolutePath().normalize(), resolution.directory());
      assertTrue(Files.isRegularFile(expected.resolve("config.txt")));
      assertFalse(Files.exists(legacy));
      assertTrue(
          resolution.diagnostics().stream()
              .anyMatch(diagnostic -> "migrated".equals(diagnostic.code())));
    } finally {
      Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwxrwxrwx"));
    }
  }

  @Test
  void bundledEngineWindowsCwdStaysPortable() throws Exception {
    Path cwd = Files.createTempDirectory("lizzie-bundled");
    Files.createDirectories(cwd.resolve("engines"));
    Files.createDirectories(cwd.resolve("weights"));
    WorkDirectoryEnvironment environment =
        new WorkDirectoryEnvironment(
            true,
            cwd.resolve("home").toString(),
            cwd.toString(),
            "",
            cwd.resolve("public").toString(),
            null,
            List.of(cwd));

    WorkDirectoryResolution resolution = WorkDirectoryResolver.resolve(environment);

    assertEquals(cwd.toAbsolutePath().normalize(), resolution.directory());
  }

  @Test
  void repeatedResolutionReturnsTheCachedResult() {
    WorkDirectoryResolution first = WorkDirectoryResolver.resolve();
    WorkDirectoryResolution second = WorkDirectoryResolver.resolve();
    assertSame(first, second);
    assertEquals(first.directory(), second.directory());
  }

  @Test
  void resolverDoesNotPrintDiagnostics() throws Exception {
    Path workDir = Files.createTempDirectory("lizzie-silent-work");
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      WorkDirectoryResolver.resolve(
          environment(false, workDir.getParent(), workDir.getParent(), workDir.toString()));
    } finally {
      System.setOut(original);
    }
    assertEquals("", captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  void windowsPortableMarkerKeepsMutableDataInsideExtractedFolder() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-portable-root");
    Path portableRoot = Files.createDirectories(tempRoot.resolve("LizzieYzy Next 围棋"));
    Files.writeString(portableRoot.resolve(".lizzie-portable"), "portable");
    Files.createDirectories(portableRoot.resolve("app"));
    Files.writeString(
        portableRoot.resolve("config.txt"), "{\"ui\":{},\"leelaz\":{\"legacy\":true}}");

    Path foundRoot =
        WorkDirectoryResolver.findWindowsPortablePackageRootForTests(portableRoot.resolve("app"))
            .orElseThrow();
    Path workDir = WorkDirectoryResolver.prepareWindowsPortableWorkDirForTests(foundRoot);

    assertEquals(portableRoot.toAbsolutePath().normalize(), foundRoot);
    assertEquals(portableRoot.resolve("user-data").toAbsolutePath().normalize(), workDir);
    assertTrue(Files.exists(workDir.resolve("save")));
    assertTrue(Files.exists(workDir.resolve("config.txt")));
    assertFalse(workDir.equals(portableRoot));
  }

  @Test
  void migratedConfigIsCopiedIntoPortableUserData() throws Exception {
    Path previous = Files.createTempDirectory("lizzie-prev-data");
    Files.writeString(
        previous.resolve("config.txt"),
        new JSONObject()
            .put("ui", new JSONObject())
            .put("leelaz", new JSONObject().put("engine-command-list", List.of("katago")))
            .toString(2));
    Path currentRoot = Files.createTempDirectory("lizzie-current-portable");
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");

    Path workDir =
        WorkDirectoryResolver.prepareWindowsPortableWorkDirWithSourcesForTests(
            currentRoot, previous);

    JSONObject migrated = new JSONObject(Files.readString(workDir.resolve("config.txt")));
    assertEquals(
        "katago",
        migrated.getJSONObject("leelaz").getJSONArray("engine-command-list").getString(0));
  }

  private static WorkDirectoryEnvironment environment(
      boolean windows, Path home, Path userDir, String explicit) {
    return new WorkDirectoryEnvironment(
        windows, home.toString(), userDir.toString(), explicit, null, null, List.of(userDir));
  }
}
