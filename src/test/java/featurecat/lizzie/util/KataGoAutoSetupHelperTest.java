package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class KataGoAutoSetupHelperTest {
  @Test
  void weightDisplayNameKeepsUserModelNameAndHidesTrainingHashes() {
    assertEquals(
        "zhizi 28B muonfd2",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-zhizi-b28c512nbt-s12763923712-d5805955894-muonfd2.bin.gz"));
    assertEquals(
        "zhizi 40B",
        KataGoAutoSetupHelper.resolveWeightDisplayName("kata1-zhizi-b40c768nbt-fdx6d.bin.gz"));
    assertEquals(
        "28B",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-b28c512nbt-s12763923712-d5805955894.bin.gz"));
  }

  @Test
  void importWeightCopiesToLocalWeightsWithoutChangingPreferredWeight() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-weight");
    Path source = Files.write(tempRoot.resolve("custom.bin.gz"), new byte[] {1, 2, 3, 4});

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", "old.bin.gz");

          Path imported = KataGoAutoSetupHelper.importWeight(source);

          assertTrue(imported.startsWith(tempRoot.resolve("weights")));
          assertTrue(Files.isRegularFile(imported));
          assertEquals(
              "old.bin.gz", Lizzie.config.uiConfig.optString("katago-preferred-weight-path"));
          assertFalse(imported.equals(source.toAbsolutePath().normalize()));
        });
  }

  private static void withUserDirAndConfig(Path userDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", userDir.toString());
      Lizzie.config = ConfigTestHelper.createForTests(userDir);
      Lizzie.config.config = new org.json.JSONObject();
      Lizzie.config.leelazConfig = new org.json.JSONObject();
      Lizzie.config.uiConfig = new org.json.JSONObject();
      action.run();
    } finally {
      if (previousUserDir == null) {
        System.clearProperty("user.dir");
      } else {
        System.setProperty("user.dir", previousUserDir);
      }
      Lizzie.config = previousConfig;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
