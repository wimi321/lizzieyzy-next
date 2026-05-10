package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserFrameJcefBundleTest {
  @TempDir Path tempDir;

  @Test
  void findsPackagedJcefBundleUnderAppDirectory() throws IOException {
    Path root = tempDir.resolve("LizzieYzy Next");
    Path bundle = root.resolve("app").resolve(BrowserFrame.JCEF_BUNDLE_DIRECTORY);
    writeBundle(bundle, BrowserFrame.JCEF_RELEASE_TAG, "*");

    Optional<java.io.File> resolved = BrowserFrame.findBundledJcefFolder(List.of(root.toFile()));

    assertTrue(resolved.isPresent());
    assertEquals(bundle.toFile().getCanonicalFile(), resolved.get());
  }

  @Test
  void rejectsStaleJcefBundle() throws IOException {
    Path bundle = tempDir.resolve(BrowserFrame.JCEF_BUNDLE_DIRECTORY);
    writeBundle(bundle, "jcef-old", "*");

    Optional<java.io.File> resolved = BrowserFrame.findBundledJcefFolder(List.of(tempDir.toFile()));

    assertTrue(resolved.isEmpty());
  }

  @Test
  void acceptsBundleWithCurrentReleaseMetadata() throws IOException {
    Path bundle = tempDir.resolve(BrowserFrame.JCEF_BUNDLE_DIRECTORY);
    writeBundle(bundle, BrowserFrame.JCEF_RELEASE_TAG, "*");

    assertTrue(BrowserFrame.isExpectedJcefBundle(bundle.toFile()));
  }

  private static void writeBundle(Path bundle, String releaseTag, String platform)
      throws IOException {
    Files.createDirectories(bundle);
    Files.writeString(
        bundle.resolve("build_meta.json"),
        "{\n"
            + "  \"release_tag\": \""
            + releaseTag
            + "\",\n"
            + "  \"platform\": \""
            + platform
            + "\"\n"
            + "}\n");
    Files.writeString(bundle.resolve("install.lock"), "");
    Files.writeString(bundle.resolve("libcef.dll"), "");
  }
}
