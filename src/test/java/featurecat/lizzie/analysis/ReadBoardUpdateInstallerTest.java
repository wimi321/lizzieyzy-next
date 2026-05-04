package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadBoardUpdateInstallerTest {
  @TempDir Path tempDir;

  private final ReadBoardUpdateInstaller installer = new ReadBoardUpdateInstaller();

  @Test
  void validateRequestRejectsEmptyRequest() {
    IOException failure = assertThrows(IOException.class, () -> installer.validateRequest(null));

    assertTrue(failure.getMessage().contains("request is empty"));
  }

  @Test
  void validateRequestAcceptsFlatRootPackage() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    assertDoesNotThrow(() -> installer.validateRequest(request));
  }

  @Test
  void validateRequestAcceptsSingleTopLevelDirectoryPackage() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "bundle/readboard.exe", "new-exe",
                "bundle/readboard.dll", "new-dll",
                "bundle/readboard.runtimeconfig.json", "{}",
                "bundle/readboard.deps.json", "{}",
                "bundle/language_cn.txt", "cn"));

    assertDoesNotThrow(() -> installer.validateRequest(request));
  }

  @Test
  void validateRequestRejectsNestedRequiredFilesBelowInstallRoot() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "bundle/bin/readboard.exe", "new-exe",
                "bundle/bin/readboard.dll", "new-dll",
                "bundle/bin/readboard.runtimeconfig.json", "{}",
                "bundle/bin/readboard.deps.json", "{}",
                "bundle/bin/language_cn.txt", "cn"));

    IOException failure = assertThrows(IOException.class, () -> installer.validateRequest(request));
    assertTrue(failure.getMessage().contains("missing required runtime files"));
  }

  @Test
  void validateRequestRejectsPathTraversalEntries() throws Exception {
    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            Map.of(
                "../evil.txt", "x",
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    assertThrows(IOException.class, () -> installer.validateRequest(request));
  }

  @Test
  void installReplacesExistingDirectoryAndKeepsBackup() throws Exception {
    Path installDirectory = Files.createDirectories(tempDir.resolve("readboard"));
    Files.writeString(installDirectory.resolve("old.txt"), "old", StandardCharsets.UTF_8);
    Files.writeString(installDirectory.resolve("readboard.exe"), "old-exe", StandardCharsets.UTF_8);

    ReadBoardUpdateRequest request =
        createRequest(
            "v3.0.2",
            orderedEntries(
                "readboard.exe", "new-exe",
                "readboard.dll", "new-dll",
                "readboard.runtimeconfig.json", "{}",
                "readboard.deps.json", "{}",
                "language_cn.txt", "cn"));

    installer.install(request, installDirectory);

    assertEquals(
        "new-exe",
        Files.readString(installDirectory.resolve("readboard.exe"), StandardCharsets.UTF_8));
    assertEquals(
        "new-dll",
        Files.readString(installDirectory.resolve("readboard.dll"), StandardCharsets.UTF_8));
    Path backupDirectory =
        Files.list(tempDir)
            .filter(path -> path.getFileName().toString().startsWith("readboard.backup-"))
            .findFirst()
            .orElse(null);
    assertNotNull(backupDirectory);
    assertEquals(
        "old", Files.readString(backupDirectory.resolve("old.txt"), StandardCharsets.UTF_8));
  }

  private ReadBoardUpdateRequest createRequest(String versionTag, Map<String, String> entries)
      throws Exception {
    Path zipPath =
        createZip(tempDir.resolve("readboard-github-release-" + versionTag + ".zip"), entries);
    ReadBoardUpdateRequest request =
        ReadBoardUpdateRequest.tryParse(
            "readboardUpdateReady\t" + versionTag + "\t" + zipPath.toAbsolutePath());
    assertNotNull(request);
    return request;
  }

  private static Path createZip(Path zipPath, Map<String, String> entries) throws Exception {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return zipPath;
  }

  private static Map<String, String> orderedEntries(String... values) {
    Map<String, String> entries = new LinkedHashMap<String, String>();
    for (int index = 0; index < values.length; index += 2) {
      entries.put(values[index], values[index + 1]);
    }
    return entries;
  }
}
