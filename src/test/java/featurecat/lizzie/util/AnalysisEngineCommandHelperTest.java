package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.EngineData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisEngineCommandHelperTest {
  @TempDir Path tempDir;

  @Test
  void convertsSavedKataGoEngineAndCreatesMissingAnalysisConfig() throws Exception {
    Path gtpConfig = tempDir.resolve("katago_configs").resolve("default_gtp.cfg");
    Path analysisConfig = gtpConfig.resolveSibling("analysis.cfg");
    Files.createDirectories(gtpConfig.getParent());
    Files.writeString(gtpConfig, "gtp config", StandardCharsets.UTF_8);
    EngineData engine =
        engine(
            "KataGo",
            quote(tempDir.resolve("katago.exe"))
                + " gtp -model "
                + quote(tempDir.resolve("weights").resolve("model.bin.gz"))
                + " -config "
                + quote(gtpConfig));

    AnalysisEngineCommandHelper.Result result = AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals("analysis", parts.get(1));
    assertEquals(analysisConfig.toString(), parts.get(parts.indexOf("-config") + 1));
    assertTrue(parts.contains("-quit-without-waiting"));
    assertTrue(result.generatedConfig());
    assertEquals(analysisConfig, result.getAnalysisConfigPath());
    assertTrue(Files.exists(analysisConfig));
    assertTrue(
        Files.readString(analysisConfig, StandardCharsets.UTF_8)
            .contains("Config for KataGo C++ Analysis engine"));
    assertTrue(result.getMessage().contains("analysis.cfg"));
    assertTrue(result.getMessage().contains(analysisConfig.toString()));
  }

  @Test
  void doesNotReplaceGtpInsidePathsAndDoesNotDuplicateQuitFlag() throws Exception {
    Path executable = tempDir.resolve("tools with gtp").resolve("katago.exe");
    Path gtpConfig = tempDir.resolve("katago_configs").resolve("gtp.cfg");
    Path analysisConfig = gtpConfig.resolveSibling("analysis.cfg");
    Files.createDirectories(executable.getParent());
    Files.createDirectories(gtpConfig.getParent());
    Files.writeString(analysisConfig, "existing analysis config", StandardCharsets.UTF_8);
    EngineData engine =
        engine(
            "KataGo",
            quote(executable)
                + " gtp -model model.bin.gz -config "
                + quote(gtpConfig)
                + " -quit-without-waiting");

    AnalysisEngineCommandHelper.Result result = AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals(executable.toString(), parts.get(0));
    assertEquals("analysis", parts.get(1));
    assertEquals(analysisConfig.toString(), parts.get(parts.indexOf("-config") + 1));
    assertEquals(1, parts.stream().filter("-quit-without-waiting"::equals).count());
    assertFalse(result.generatedConfig());
    assertEquals(
        "existing analysis config", Files.readString(analysisConfig, StandardCharsets.UTF_8));
  }

  @Test
  void rejectsRemoteSavedEngines() {
    EngineData engine = engine("Remote", "katago gtp -model model.bin.gz -config gtp.cfg");
    engine.useJavaSSH = true;

    AnalysisEngineCommandHelper.Result result = AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().toLowerCase(java.util.Locale.ROOT).contains("remote"));
  }

  @Test
  void rejectsCommandsWithoutStandaloneGtp() {
    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromSavedEngine(
            engine("No gtp", "katago analysis -model model.bin.gz -config analysis.cfg"));

    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("gtp"));
  }

  @Test
  void rejectsCommandsWithoutConfig() {
    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromSavedEngine(
            engine("No config", "katago gtp -model model.bin.gz"));

    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("config"));
  }

  @Test
  void convertsCurrentDefaultEngineWhenFlashCommandIsNotCustomized() throws Exception {
    Path firstConfig = tempDir.resolve("first").resolve("gtp.cfg");
    Path defaultConfig = tempDir.resolve("default").resolve("gtp.cfg");
    Files.createDirectories(firstConfig.getParent());
    Files.createDirectories(defaultConfig.getParent());
    ArrayList<EngineData> engines = new ArrayList<>();
    engines.add(engine("first", "katago gtp -model first.bin.gz -config " + quote(firstConfig)));
    engines.add(
        engine("default", "katago gtp -model default.bin.gz -config " + quote(defaultConfig)));
    engines.get(1).isDefault = true;

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromDefaultEngine(engines);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals("default.bin.gz", parts.get(parts.indexOf("-model") + 1));
    assertEquals(
        defaultConfig.resolveSibling("analysis.cfg").toString(),
        parts.get(parts.indexOf("-config") + 1));
  }

  @Test
  void detectsLegacyCustomizedAnalysisCommandsConservatively() {
    assertFalse(AnalysisEngineCommandHelper.isLegacyAnalysisCommandCustomized(""));
    assertFalse(
        AnalysisEngineCommandHelper.isLegacyAnalysisCommandCustomized(
            "katago analysis -model model.bin.gz -config analysis.cfg -quit-without-waiting"));
    assertTrue(
        AnalysisEngineCommandHelper.isLegacyAnalysisCommandCustomized(
            "katago analysis -model custom.bin.gz -config analysis.cfg"));
    assertFalse(AnalysisEngineCommandHelper.isAnalysisCommandCustomized(true, false, "custom"));
    assertTrue(AnalysisEngineCommandHelper.isAnalysisCommandCustomized(true, true, ""));
  }

  @Test
  void bundledAnalysisConfigTemplateIsAvailable() throws Exception {
    assertNotNull(
        AnalysisEngineCommandHelperTest.class
            .getClassLoader()
            .getResource("katago/analysis_example.cfg"));
  }

  private static EngineData engine(String name, String command) {
    EngineData engine = new EngineData();
    engine.name = name;
    engine.commands = command;
    return engine;
  }

  private static String quote(Path path) {
    return "\"" + path.toString() + "\"";
  }
}
