package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        ("KataGo version: v1.16.4\nModel source: kata1-zhizi-b28c512nbt-muonfd2.bin.gz\n")
            .getBytes(StandardCharsets.UTF_8));

    String command =
        "\"/tmp/katago\" gtp -model \""
            + weightPath.toAbsolutePath()
            + "\" -config \"/tmp/gtp.cfg\"";

    assertEquals("zhizi 28B muonfd2", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }

  @Test
  void downloadedOfficialWeightKeepsFamilyAndRunMarkers() {
    String command =
        "\"/tmp/katago\" gtp -model \"/tmp/weights/kata1-b28c512nbt-s12763923712-d5805955894.bin.gz\""
            + " -config \"/tmp/gtp.cfg\"";

    assertEquals(
        "28B s12763923712 d5805955894", Leelaz.friendlyEngineName("KataGo Auto Setup", command));
  }
}
