package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsUpdateApplierTest {
  @TempDir Path tempDir;

  @Test
  void appliesCoreUpdateAndPreservesUserData() throws Exception {
    Fixture fixture = fixture();
    Path coreZip = tempDir.resolve("core.zip");
    writeZip(
        coreZip,
        entry("lizzieyzy-next-core.jar", "new-core"),
        entry("app/lizzie-yzy2.5.3-shaded.jar", "manual-overlay-core"));
    Path request =
        request(
            fixture,
            component("core", "replace-core", coreZip, "lizzieyzy-next-core.jar", null),
            installedManifest());

    WindowsUpdateApplier.apply(request);

    assertEquals("new-core", Files.readString(fixture.currentJar));
    assertEquals("user-save", Files.readString(fixture.appRoot.resolve("user-data").resolve("save.txt")));
    assertTrue(Files.isRegularFile(fixture.appDir.resolve(InstalledUpdateState.INSTALLED_MANIFEST_NAME)));
  }

  @Test
  void replacesMappedResourceDirectory() throws Exception {
    Fixture fixture = fixture();
    Path engineDir = fixture.appDir.resolve("engines/katago/windows-x64");
    Files.createDirectories(engineDir);
    Files.writeString(engineDir.resolve("katago.exe"), "old-engine");
    Path resourceZip = tempDir.resolve("katago.zip");
    writeZip(resourceZip, entry("engines/katago/windows-x64/katago.exe", "new-engine"));
    Path request =
        request(
            fixture,
            component(
                "katago-opencl",
                "replace-app-path",
                resourceZip,
                "engines/katago/windows-x64",
                "engines/katago/windows-x64"),
            null);

    WindowsUpdateApplier.apply(request);

    assertEquals("new-engine", Files.readString(engineDir.resolve("katago.exe")));
  }

  @Test
  void rollsBackWhenLaterComponentFails() throws Exception {
    Fixture fixture = fixture();
    Path coreZip = tempDir.resolve("core.zip");
    writeZip(coreZip, entry("lizzieyzy-next-core.jar", "new-core"));
    Path badZip = tempDir.resolve("bad.zip");
    writeZip(badZip, entry("wrong/path.txt", "missing source"));
    Path request =
        request(
            fixture,
            new JSONObject[] {
              component("core", "replace-core", coreZip, "lizzieyzy-next-core.jar", null),
              component(
                  "katago-opencl",
                  "replace-app-path",
                  badZip,
                  "engines/katago/windows-x64",
                  "engines/katago/windows-x64")
            },
            installedManifest());

    assertThrows(IOException.class, () -> WindowsUpdateApplier.apply(request));
    assertEquals("old-core", Files.readString(fixture.currentJar));
  }

  private Fixture fixture() throws IOException {
    Path appRoot = Files.createDirectories(tempDir.resolve("LizzieYzy Next"));
    Path appDir = Files.createDirectories(appRoot.resolve("app"));
    Path userData = Files.createDirectories(appRoot.resolve("user-data"));
    Path currentJar = appDir.resolve("lizzie-yzy2.5.3-shaded.jar");
    Files.writeString(currentJar, "old-core");
    Files.writeString(userData.resolve("save.txt"), "user-save");
    Path staging = Files.createDirectories(tempDir.resolve("staging"));
    return new Fixture(appRoot, appDir, currentJar, staging);
  }

  private Path request(Fixture fixture, JSONObject component, JSONObject installedManifest)
      throws IOException {
    return request(fixture, new JSONObject[] {component}, installedManifest);
  }

  private Path request(Fixture fixture, JSONObject[] components, JSONObject installedManifest)
      throws IOException {
    JSONObject request = new JSONObject();
    request.put("schemaVersion", 1);
    request.put("releaseTag", "next-2026-06-12.1");
    request.put("appRoot", fixture.appRoot.toString());
    request.put("appDir", fixture.appDir.toString());
    request.put("currentJar", fixture.currentJar.toString());
    request.put("stagingDir", fixture.staging.toString());
    request.put("mainPid", -1);
    JSONArray array = new JSONArray();
    for (JSONObject component : components) {
      array.put(component);
    }
    request.put("components", array);
    if (installedManifest != null) {
      request.put("installedManifest", installedManifest);
    }
    request.put("restartCommand", new JSONArray());
    Path requestPath = fixture.staging.resolve("update-request.json");
    Files.writeString(requestPath, request.toString(2), StandardCharsets.UTF_8);
    return requestPath;
  }

  private JSONObject component(
      String id, String action, Path archive, String sourcePath, String targetPath) {
    JSONObject component = new JSONObject();
    component.put("id", id);
    component.put("assetName", archive.getFileName().toString());
    component.put("archivePath", archive.toString());
    component.put("installAction", action);
    if (sourcePath != null) {
      component.put("sourcePath", sourcePath);
    }
    if (targetPath != null) {
      component.put("targetPath", targetPath);
    }
    return component;
  }

  private JSONObject installedManifest() {
    return new JSONObject()
        .put("schemaVersion", 1)
        .put("releaseTag", "next-2026-06-12.1")
        .put("platform", "windows")
        .put("flavor", "opencl")
        .put("components", new JSONArray().put(new JSONObject().put("id", "core")));
  }

  private ZipEntryData entry(String name, String content) {
    return new ZipEntryData(name, content);
  }

  private void writeZip(Path zipPath, ZipEntryData... entries) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (ZipEntryData entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name));
        zip.write(entry.content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
  }

  private static final class Fixture {
    final Path appRoot;
    final Path appDir;
    final Path currentJar;
    final Path staging;

    Fixture(Path appRoot, Path appDir, Path currentJar, Path staging) {
      this.appRoot = appRoot;
      this.appDir = appDir;
      this.currentJar = currentJar;
      this.staging = staging;
    }
  }

  private static final class ZipEntryData {
    final String name;
    final String content;

    ZipEntryData(String name, String content) {
      this.name = name;
      this.content = content;
    }
  }
}
