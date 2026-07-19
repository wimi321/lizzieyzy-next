package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LeelazDisplayNameTest {

  @Test
  void bundledDefaultWeightUsesBundledModelAlias() throws Exception {
    Path root = Files.createTempDirectory("leelaz-display-default");
    Path weightsDir = Files.createDirectories(root.resolve("weights"));
    Path enginesDir = Files.createDirectories(root.resolve("engines").resolve("katago"));
    Path weightPath = Files.createFile(weightsDir.resolve("default.bin.gz"));
    Files.write(
        enginesDir.resolve("VERSION.txt"),
        ("KataGo version: v1.16.5\nModel source: kata1-zhizi-b28c512nbt-muonfd2.bin.gz\n")
            .getBytes(StandardCharsets.UTF_8));

    String command =
        "\"/tmp/katago\" gtp -model \""
            + weightPath.toAbsolutePath()
            + "\" -config \"/tmp/gtp.cfg\"";

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void downloadedOfficialWeightHidesInternalTrainingHashes() {
    String command =
        "\"/tmp/katago\" gtp -model \"/tmp/weights/kata1-b28c512nbt-s12763923712-d5805955894.bin.gz\""
            + " -config \"/tmp/gtp.cfg\"";

    assertEquals("28B", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void tensorRtBackendNameDoesNotReplaceWeightDisplayName() {
    String command =
        "\"/tmp/katago\" gtp -model \"/tmp/weights/kata1-zhizi-b28c512nbt-muonfd2.bin.gz\""
            + " -config \"/tmp/gtp.cfg\"";

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo TensorRT", command));
  }

  @Test
  void remoteComputeNameUsesSavedArgsInsteadOfStaleEngineName() {
    java.util.ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle = AppLocale.SIMPLIFIED_CHINESE.loadBundle();
      assertEquals(
          "智子云算力 VIP 包月 · 28B NBT · TensorRT",
          Leelaz.friendlyEngineName(
              "智子云算力 28B TensorRT", RemoteComputeConfig.COMMAND_ZHIZI));
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void weightDisplayHandlesMultipleSpacesAfterModelFlag() {
    String command =
        "\"/tmp/katago\" gtp -model  \"/tmp/weights/kata1-b28c512nbt-s12763923712-d5805955894.bin.gz\""
            + " -config \"/tmp/gtp.cfg\"";

    assertEquals("28B", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void weightDisplayHandlesEqualsStyleWeightsFlag() {
    String command =
        "\"/tmp/leelaz\" --weights=/tmp/weights/kata1-zhizi-b28c512nbt-muonfd2.bin.gz";

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }
}
