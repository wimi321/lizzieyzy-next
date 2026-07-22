package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

public class KataGoAutoSetupHelperTest {
  @Test
  void discoversCompleteExternalKataGoFromDefaultEngineAsOneCoherentProfile() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-external");
    Path external = Files.createDirectories(root.resolve("外部 KataGo 有空格"));
    Path engine = touch(external.resolve(testKataGoBinaryName()));
    Path configs = Files.createDirectories(external.resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(external.resolve("模型 权重.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(List.of(engineData("外部 KataGo", engine, gtp, weight, true))));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
          assertEquals(KataGoAutoSetupHelper.PackageFlavor.EXTERNAL, result.packageFlavor);
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void resolvesRelativeConfigAndWeightAgainstExecutableDirectory() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-relative");
    Path external = Files.createDirectories(root.resolve("engine dir"));
    Path engine = touch(external.resolve(testKataGoBinaryName()));
    Path configs = Files.createDirectories(external.resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(external.resolve("weights").resolve("relative.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          EngineData relative = new EngineData();
          relative.name = "KataGo Relative";
          relative.commands =
              quoteLiteral(root.relativize(engine))
                  + " gtp -model "
                  + quoteLiteral(Path.of("weights", weight.getFileName().toString()))
                  + " -config "
                  + quoteLiteral(Path.of("configs", "gtp.cfg"));
          relative.isDefault = true;
          Utils.saveEngineSettings(new ArrayList<>(List.of(relative)));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void skipsRemoteDefaultCandidateAndUsesLocalDefaultFlag() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-remote-skip");
    Path local = Files.createDirectories(root.resolve("local"));
    Path engine = touch(local.resolve(testKataGoBinaryName()));
    Path gtp = touch(local.resolve("configs").resolve("gtp.cfg"));
    touch(local.resolve("configs").resolve("analysis.cfg"));
    Path weight = touch(local.resolve("local.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          EngineData remote = new EngineData();
          remote.name = "智子云算力";
          remote.commands = "remote-compute://zhizi/default";
          remote.isDefault = false;
          EngineData localDefault = engineData("KataGo Local", engine, gtp, weight, true);
          Utils.saveEngineSettings(new ArrayList<>(List.of(remote, localDefault)));
          Lizzie.config.uiConfig.put("default-engine", 1);

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(engine, result.enginePath);
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
        });
  }

  @Test
  void startupEngineTakesPriorityOverTheDefaultEngine() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-startup-priority");
    Path startup = Files.createDirectories(root.resolve("startup"));
    Path startupEngine = touch(startup.resolve(testKataGoBinaryName()));
    Path startupGtp = touch(startup.resolve("configs").resolve("gtp.cfg"));
    touch(startup.resolve("configs").resolve("analysis.cfg"));
    Path startupWeight = touch(startup.resolve("startup.bin.gz"));
    Path defaultDir = Files.createDirectories(root.resolve("default"));
    Path defaultEngine = touch(defaultDir.resolve(testKataGoBinaryName()));
    Path defaultGtp = touch(defaultDir.resolve("configs").resolve("gtp.cfg"));
    touch(defaultDir.resolve("configs").resolve("analysis.cfg"));
    Path defaultWeight = touch(defaultDir.resolve("default.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(
                  List.of(
                      engineData("Startup KataGo", startupEngine, startupGtp, startupWeight, false),
                      engineData(
                          "Default KataGo", defaultEngine, defaultGtp, defaultWeight, true))));
          Lizzie.config.uiConfig.put("autoload-last", true);
          Lizzie.config.uiConfig.put("last-engine", 0);
          Lizzie.config.uiConfig.put("default-engine", 1);

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertEquals(KataGoAutoSetupHelper.DiscoverySource.STARTUP_ENGINE, result.source);
          assertEquals(startupEngine, result.enginePath);
          assertEquals(startupWeight, result.activeWeightPath);
        });
  }

  @Test
  void discoversRememberedSetupBeforeIndependentAnalysisCommand() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-remembered-priority");
    Path remembered = Files.createDirectories(root.resolve("remembered"));
    Path rememberedEngine = touch(remembered.resolve(testKataGoBinaryName()));
    Path rememberedGtp = touch(remembered.resolve("configs").resolve("gtp.cfg"));
    Path rememberedAnalysis = touch(remembered.resolve("configs").resolve("analysis.cfg"));
    Path rememberedWeight = touch(remembered.resolve("remembered.bin.gz"));
    Path analysisDir = Files.createDirectories(root.resolve("analysis"));
    Path analysisEngine = touch(analysisDir.resolve(testKataGoBinaryName()));
    Path analysisConfig = touch(analysisDir.resolve("configs").resolve("analysis.cfg"));
    touch(analysisDir.resolve("configs").resolve("gtp.cfg"));
    Path analysisWeight = touch(analysisDir.resolve("analysis.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Lizzie.config.uiConfig.put("katago-auto-setup-engine-path", rememberedEngine.toString());
          Lizzie.config.uiConfig.put("katago-auto-setup-gtp-config-path", rememberedGtp.toString());
          Lizzie.config.uiConfig.put(
              "katago-auto-setup-analysis-config-path", rememberedAnalysis.toString());
          Lizzie.config.uiConfig.put("katago-auto-setup-weight-path", rememberedWeight.toString());
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(analysisEngine)
                  + " analysis -model "
                  + quote(analysisWeight)
                  + " -config "
                  + quote(analysisConfig));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertEquals(KataGoAutoSetupHelper.DiscoverySource.REMEMBERED_SETUP, result.source);
          assertEquals(rememberedEngine, result.enginePath);
          assertEquals(rememberedWeight, result.activeWeightPath);
        });
  }

  @Test
  void discoversACompleteIndependentAnalysisCommand() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-analysis-command");
    Path engine = touch(root.resolve("analysis").resolve(testKataGoBinaryName()));
    Path analysisConfig =
        touch(root.resolve("analysis").resolve("configs").resolve("analysis.cfg"));
    Path gtpConfig = touch(root.resolve("analysis").resolve("configs").resolve("gtp.cfg"));
    Path weight = touch(root.resolve("analysis").resolve("analysis.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(engine)
                  + " analysis -model "
                  + quote(weight)
                  + " -config "
                  + quote(analysisConfig));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.ANALYSIS_COMMAND, result.source);
          assertEquals(engine, result.enginePath);
          assertEquals(gtpConfig, result.gtpConfigPath);
          assertEquals(analysisConfig, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void doesNotTreatAnotherGtpEngineAsLocalKataGo() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-non-katago");
    Path engine = touch(root.resolve("other-engine").resolve("leela-zero"));
    Path config = touch(root.resolve("other-engine").resolve("configs").resolve("gtp.cfg"));
    touch(root.resolve("other-engine").resolve("configs").resolve("analysis.cfg"));
    Path weight = touch(root.resolve("other-engine").resolve("network.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(
                  List.of(engineData("Other GTP engine", engine, config, weight, true))));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertEquals(KataGoAutoSetupHelper.DiscoverySource.NONE, result.source);
          assertNull(result.enginePath);
        });
  }

  @Test
  void incompleteExternalProfileFallsBackWithoutMixingBundledFiles() throws Exception {
    Path root = Files.createTempDirectory("katago-discovery-no-mix");
    Path external = Files.createDirectories(root.resolve("external"));
    Path externalEngine = touch(external.resolve(testKataGoBinaryName()));
    Path externalGtp = touch(external.resolve("configs").resolve("gtp.cfg"));
    Path externalWeight = touch(external.resolve("external.bin.gz"));
    Path bundledEngine =
        touch(
            root.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path bundledConfigs =
        Files.createDirectories(root.resolve("engines").resolve("katago").resolve("configs"));
    Path bundledGtp = touch(bundledConfigs.resolve("gtp.cfg"));
    Path bundledAnalysis = touch(bundledConfigs.resolve("analysis.cfg"));
    Path bundledWeight = touch(root.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          Utils.saveEngineSettings(
              new ArrayList<>(
                  List.of(
                      engineData(
                          "Incomplete KataGo",
                          externalEngine,
                          externalGtp,
                          externalWeight,
                          true))));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.BUNDLED_PACKAGE, result.source);
          assertEquals(bundledEngine, result.enginePath);
          assertEquals(bundledGtp, result.gtpConfigPath);
          assertEquals(bundledAnalysis, result.analysisConfigPath);
          assertEquals(bundledWeight, result.activeWeightPath);
          assertFalse(result.enginePath.equals(externalEngine));
          assertFalse(result.activeWeightPath.equals(externalWeight));
        });
  }

  @Test
  void recognizesNoEngineAndStandaloneCoreUpdatePackages() throws Exception {
    Path noEngine = Files.createTempDirectory("katago-no-engine-package");
    Files.writeString(
        noEngine.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"windows\",\"flavor\":\"without.engine\"}");
    withUserDirAndConfig(
        noEngine,
        () ->
            assertEquals(
                KataGoAutoSetupHelper.PackageFlavor.WITHOUT_ENGINE,
                KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor));

    Path coreUpdate = Files.createTempDirectory("katago-core-update-package");
    Files.writeString(coreUpdate.resolve("lizzieyzy-next-core-update-manifest.json"), "{}");
    withUserDirAndConfig(
        coreUpdate,
        () ->
            assertEquals(
                KataGoAutoSetupHelper.PackageFlavor.CORE_UPDATE_ONLY,
                KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor));

    Path incomplete = Files.createTempDirectory("katago-incomplete-package");
    Files.writeString(
        incomplete.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"windows\",\"flavor\":\"opencl\"}");
    withUserDirAndConfig(
        incomplete,
        () ->
            assertEquals(
                KataGoAutoSetupHelper.PackageFlavor.INCOMPLETE_BUNDLE,
                KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor));
  }

  @Test
  void recognizesEverySupportedCompletePackageFlavor() throws Exception {
    Object[][] cases = {
      {"opencl", KataGoAutoSetupHelper.PackageFlavor.OPENCL},
      {"nvidia", KataGoAutoSetupHelper.PackageFlavor.NVIDIA},
      {"nvidia50.cuda", KataGoAutoSetupHelper.PackageFlavor.NVIDIA50_CUDA},
      {"nvidia-tensorrt", KataGoAutoSetupHelper.PackageFlavor.TENSORRT},
      {"cpu", KataGoAutoSetupHelper.PackageFlavor.CPU},
      {"with-katago", KataGoAutoSetupHelper.PackageFlavor.WITH_KATAGO}
    };
    for (Object[] testCase : cases) {
      String flavor = (String) testCase[0];
      Path root = Files.createTempDirectory("katago-package-" + flavor.replace('.', '-'));
      Files.writeString(
          root.resolve("lizzieyzy-next-installed-manifest.json"),
          "{\"platform\":\"windows\",\"flavor\":\"" + flavor + "\"}");
      touch(
          root.resolve("engines")
              .resolve("katago")
              .resolve(detectTestPlatformDir())
              .resolve(testKataGoBinaryName()));
      touch(root.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
      touch(root.resolve("engines").resolve("katago").resolve("configs").resolve("analysis.cfg"));
      touch(root.resolve("weights").resolve("default.bin.gz"));

      withUserDirAndConfig(
          root,
          () ->
              assertEquals(
                  testCase[1], KataGoAutoSetupHelper.inspectLocalKataGo().packageFlavor, flavor));
    }
  }

  @Test
  void discoversJpackagePortableAssetsInsideAppDirectory() throws Exception {
    Path portableRoot = Files.createTempDirectory("katago-jpackage-portable");
    Path app = Files.createDirectories(portableRoot.resolve("app"));
    Path workDir = Files.createDirectories(portableRoot.resolve("user-data"));
    touch(portableRoot.resolve(".lizzie-portable"));
    touch(app.resolve("PROJECT_INFO.txt"));
    Files.writeString(
        app.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"windows\",\"flavor\":\"opencl\"}");
    Path engine =
        touch(
            app.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(app.resolve("engines").resolve("katago").resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(app.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        portableRoot,
        workDir,
        () -> {
          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(portableRoot, result.appRoot);
          assertEquals(KataGoAutoSetupHelper.PackageFlavor.OPENCL, result.packageFlavor);
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.BUNDLED_PACKAGE, result.source);
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void resolvesSavedRelativeCommandAgainstJpackageAppDirectory() throws Exception {
    Path portableRoot = Files.createTempDirectory("katago-jpackage-relative-command");
    Path app = Files.createDirectories(portableRoot.resolve("app"));
    Path workDir = Files.createDirectories(portableRoot.resolve("user-data"));
    touch(portableRoot.resolve(".lizzie-portable"));
    touch(app.resolve("PROJECT_INFO.txt"));
    Path engine =
        touch(
            app.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(app.resolve("engines").resolve("katago").resolve("configs"));
    Path gtp = touch(configs.resolve("gtp.cfg"));
    Path analysis = touch(configs.resolve("analysis.cfg"));
    Path weight = touch(app.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        portableRoot,
        workDir,
        () -> {
          EngineData relative = new EngineData();
          relative.name = "KataGo Portable";
          relative.commands =
              quoteLiteral(app.relativize(engine))
                  + " gtp -model "
                  + quoteLiteral(app.relativize(weight))
                  + " -config "
                  + quoteLiteral(app.relativize(gtp));
          relative.isDefault = true;
          Utils.saveEngineSettings(new ArrayList<>(List.of(relative)));

          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectLocalKataGo();

          assertTrue(result.isComplete());
          assertEquals(KataGoAutoSetupHelper.DiscoverySource.DEFAULT_ENGINE, result.source);
          assertEquals(engine, result.enginePath);
          assertEquals(gtp, result.gtpConfigPath);
          assertEquals(analysis, result.analysisConfigPath);
          assertEquals(weight, result.activeWeightPath);
        });
  }

  @Test
  void repairsMissingAnalysisConfigOnlyAfterExplicitRequest() throws Exception {
    Path root = Files.createTempDirectory("katago-analysis-repair");
    Path engine = touch(root.resolve("external").resolve(testKataGoBinaryName()));
    Path gtp = touch(root.resolve("external").resolve("configs").resolve("gtp.cfg"));
    Path weight = touch(root.resolve("external").resolve("weight.bin.gz"));

    withUserDirAndConfig(
        root,
        () -> {
          KataGoAutoSetupHelper.SetupSnapshot snapshot =
              KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtp, weight).toSnapshot();
          assertFalse(snapshot.hasConfigs());
          assertNotNull(snapshot.analysisConfigPath);
          assertFalse(Files.exists(snapshot.analysisConfigPath));

          Path repaired = KataGoAutoSetupHelper.repairAnalysisConfig(snapshot);

          assertNotNull(repaired);
          assertTrue(Files.isRegularFile(repaired));
          assertTrue(Files.size(repaired) > 0L);
        });
  }

  @Test
  void manualExternalSelectionDoesNotBorrowBundledConfigOrWeight() throws Exception {
    Path appRoot = Files.createTempDirectory("katago-manual-no-mix-app");
    touch(appRoot.resolve("PROJECT_INFO.txt"));
    touch(appRoot.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
    touch(appRoot.resolve("weights").resolve("bundled.bin.gz"));
    Path externalRoot = Files.createTempDirectory("katago-manual-no-mix-external");
    Path externalEngine = touch(externalRoot.resolve(testKataGoBinaryName()));

    withUserDirAndConfig(
        appRoot,
        () -> {
          KataGoAutoSetupHelper.LocalKataGoDiscoveryResult result =
              KataGoAutoSetupHelper.inspectSelectedLocalKataGo(externalEngine, null, null);

          assertEquals(externalEngine, result.enginePath);
          assertNull(result.gtpConfigPath);
          assertNull(result.activeWeightPath);
          assertTrue(
              result.missingComponents.contains(KataGoAutoSetupHelper.MissingComponent.GTP_CONFIG));
          assertTrue(
              result.missingComponents.contains(KataGoAutoSetupHelper.MissingComponent.WEIGHT));
        });
  }

  @Test
  void validatesAWorkingKataGoExecutableWithoutBlockingTheCallerIndefinitely() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path root = Files.createTempDirectory("katago-version-validation");
    Path engine = root.resolve("katago");
    Files.writeString(engine, "#!/bin/sh\nprintf 'KataGo test version\\n'\n");
    assertTrue(engine.toFile().setExecutable(true));

    KataGoAutoSetupHelper.EngineValidationResult result =
        KataGoAutoSetupHelper.validateLocalEngine(engine, 3L);

    assertEquals(KataGoAutoSetupHelper.EngineValidationStatus.VALID, result.status);
    assertTrue(result.detail.contains("KataGo test version"));
  }

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
    assertEquals(
        "28B 2026-06",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-b28c512nbt-s13255194368-d5935380940.bin.gz"));
  }

  @Test
  void officialWeightChoicesKeepTwoPerPreferredFamilyAndPrioritizeBadges() throws Exception {
    String latestModel = officialModel("b28", 3);
    String strongestModel = officialModel("b40", 3);
    StringBuilder html =
        new StringBuilder()
            .append("<span>Strongest confidently-rated network:</span>")
            .append(officialLink(strongestModel))
            .append("<span>Latest network:</span>")
            .append(officialLink(latestModel))
            .append("<table class=\"table mt-3\">");
    for (String family : List.of("b6", "b10", "b15", "b18", "b20", "b28", "b40", "b60", "b80")) {
      for (int version = 1; version <= 3; version++) {
        String model = officialModel(family, version);
        html.append("<tr>")
            .append("<td>")
            .append(model)
            .append("</td>")
            .append("<td>2026-06-")
            .append(10 + version)
            .append("</td>")
            .append("<td>")
            .append(15000 + version)
            .append(" Elo</td>")
            .append("<td>")
            .append(officialLink(model))
            .append("</td>")
            .append("</tr>");
      }
    }
    html.append("</table>");

    List<KataGoAutoSetupHelper.RemoteWeightInfo> choices =
        KataGoAutoSetupHelper.parseOfficialWeights(html.toString());

    assertEquals(16, choices.size());
    assertEquals(
        List.of(
            "b28", "b28", "b40", "b40", "b60", "b60", "b20", "b20", "b18", "b18", "b15", "b15",
            "b10", "b10", "b6", "b6"),
        choices.stream().map(KataGoAutoSetupHelperTest::officialFamily).toList());
    assertTrue(
        choices.stream().anyMatch(info -> info.modelName.equals(latestModel) && info.latest));
    assertTrue(
        choices.stream()
            .anyMatch(info -> info.modelName.equals(strongestModel) && info.recommended));
    assertFalse(choices.stream().anyMatch(info -> officialFamily(info).equals("b80")));
  }

  @Test
  void officialWeightChoicesKeepBundledZhiziAlongsideTheLatest28b() throws Exception {
    String latestModel = officialModel("b28", 3);
    String olderModel = officialModel("b28", 2);
    String bundledModel = "kata1-zhizi-b28c512nbt-muonfd2";
    String html =
        new StringBuilder()
            .append("<span>Latest network:</span>")
            .append(officialLink(latestModel))
            .append("<table class=\"table mt-3\">")
            .append(officialRow(latestModel, "2026-06-28", "14180 Elo"))
            .append(officialRow(olderModel, "2026-06-20", "14130 Elo"))
            .append(officialRow(bundledModel, "2026-03-22", "14158 Elo"))
            .append("</table>")
            .toString();

    List<KataGoAutoSetupHelper.RemoteWeightInfo> choices =
        KataGoAutoSetupHelper.parseOfficialWeights(html);
    List<KataGoAutoSetupHelper.RemoteWeightInfo> family =
        choices.stream().filter(info -> officialFamily(info).equals("b28")).toList();

    assertEquals(2, family.size());
    assertTrue(family.stream().anyMatch(KataGoAutoSetupHelper::isDefaultGeneralUseWeight));
    assertTrue(family.stream().anyMatch(info -> info.modelName.equals(latestModel) && info.latest));
    assertFalse(family.stream().anyMatch(info -> info.modelName.equals(olderModel)));
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

  @Test
  void importedWeightDoesNotReplaceWeightConfiguredByDefaultEngine() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-keeps-active");
    Path appRoot = Files.createDirectories(tempRoot.resolve("portable with spaces"));
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path engine =
        touch(
            appRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Files.writeString(
        appRoot.resolve("engines").resolve("katago").resolve("VERSION.txt"),
        "Model source: kata1-zhizi-b28c512nbt-muonfd2.bin.gz\n");
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(configDir.resolve("estimate.cfg"));
    Path bundledWeight = touch(appRoot.resolve("weights").resolve("default.bin.gz"));
    Path source = touch(tempRoot.resolve("incoming").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        appRoot,
        workDir,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          engines.add(engineData("KataGo Bundled", engine, gtpConfig, bundledWeight, true));
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 0);

          assertEquals(bundledWeight, KataGoAutoSetupHelper.inspectLocalSetup().activeWeightPath);
          assertEquals(
              "zhizi 28B muonfd2", KataGoAutoSetupHelper.resolveWeightDisplayName(bundledWeight));

          Path imported = KataGoAutoSetupHelper.importWeight(source);
          KataGoAutoSetupHelper.SetupSnapshot refreshed = KataGoAutoSetupHelper.inspectLocalSetup();

          assertTrue(Files.isRegularFile(imported));
          assertTrue(refreshed.weightCandidates.contains(imported));
          assertEquals(bundledWeight, refreshed.activeWeightPath);
          assertFalse(imported.equals(refreshed.activeWeightPath));
          assertEquals("default", KataGoAutoSetupHelper.resolveWeightDisplayName(imported));
        });
  }

  @Test
  void importHumanSlModelCopiesToSeparateDirectoryAndDoesNotChangeActiveWeight() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-humansl");
    Path source = Files.write(tempRoot.resolve("custom-human.bin.gz"), new byte[2 * 1024 * 1024]);
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", weight.toString());

          Path imported = KataGoAutoSetupHelper.importHumanSlModel(source);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.HumanSlModelStatus status =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertTrue(imported.startsWith(tempRoot.resolve("human-sl-models")));
          assertTrue(Files.isRegularFile(imported));
          assertEquals(weight, snapshot.activeWeightPath);
          assertEquals(
              weight.toString(), Lizzie.config.uiConfig.optString("katago-preferred-weight-path"));
          assertEquals(
              imported.toString(), Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
          assertTrue(status.isInstalled());
          assertEquals(imported, status.modelPath);
        });
  }

  @Test
  void inspectHumanSlModelUsesRememberedPathBeforeDirectoryScan() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-status");
    Path first = touchModel(tempRoot.resolve("human-sl-models").resolve("first-human.bin.gz"));
    Path remembered = touchModel(tempRoot.resolve("external").resolve("remembered-human.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-human-sl-model-path", remembered.toString());

          KataGoAutoSetupHelper.HumanSlModelStatus status =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertTrue(status.isInstalled());
          assertEquals(remembered, status.modelPath);
          assertEquals(remembered, status.candidates.get(0));
          assertTrue(status.candidates.contains(first));
        });
  }

  @Test
  void inspectHumanSlModelPrefersBundledDefaultFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-bundled");
    Path olderCustom =
        touchModel(tempRoot.resolve("human-sl-models").resolve("custom-human.bin.gz"));
    byte[] officialBytes = repeatedBytes(4096, (byte) 3);
    Path bundled =
        writeModel(
            tempRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
            officialBytes);

    withUserDirAndConfig(
        tempRoot,
        () ->
            withHumanSlDownloadProperties(
                "http://127.0.0.1/model.bin.gz",
                sha256(officialBytes),
                officialBytes.length,
                () -> {
                  KataGoAutoSetupHelper.HumanSlModelStatus status =
                      KataGoAutoSetupHelper.inspectHumanSlModel();

                  assertTrue(status.isInstalled());
                  assertEquals(bundled, status.modelPath);
                  assertEquals(bundled, status.candidates.get(0));
                  assertTrue(status.candidates.contains(olderCustom));
                }));
  }

  @Test
  void inspectHumanSlModelFindsBundledFileFromAppRootWithoutEngine() throws Exception {
    Path appRoot = Files.createTempDirectory("katago-humansl-app-root");
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    byte[] officialBytes = repeatedBytes(4096, (byte) 5);
    Path bundled =
        writeModel(
            appRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
            officialBytes);

    withProcessDirAndConfig(
        processDir,
        workDir,
        () ->
            withHumanSlDownloadProperties(
                "http://127.0.0.1/model.bin.gz",
                sha256(officialBytes),
                officialBytes.length,
                () -> {
                  KataGoAutoSetupHelper.HumanSlModelStatus status =
                      KataGoAutoSetupHelper.inspectHumanSlModel();

                  assertTrue(status.isInstalled());
                  assertEquals(bundled, status.modelPath);
                  assertEquals(bundled, status.candidates.get(0));
                }));
  }

  @Test
  void inspectLocalSetupKeepsAppRootWhenWorkingDirHasHumanSlModels() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-app-root-priority");
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    Files.createDirectories(workDir.resolve("human-sl-models"));
    Path engine =
        touch(
            appRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfig = touch(configDir.resolve("analysis.cfg"));
    Path estimateConfig = touch(configDir.resolve("estimate.cfg"));
    touch(appRoot.resolve("weights").resolve("default.bin.gz"));
    Path customWeight = touch(workDir.resolve("weights").resolve("custom.bin.gz"));
    Path humanSlSource =
        Files.write(tempRoot.resolve("custom-human.bin.gz"), new byte[2 * 1024 * 1024]);

    withProcessDirAndConfig(
        processDir,
        workDir,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", customWeight.toString());

          Path importedModel = KataGoAutoSetupHelper.importHumanSlModel(humanSlSource);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.HumanSlModelStatus humanSlStatus =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertEquals(workDir.toAbsolutePath().normalize(), snapshot.workingDir);
          assertEquals(appRoot.toAbsolutePath().normalize(), snapshot.appRoot);
          assertEquals(engine, snapshot.enginePath);
          assertEquals(gtpConfig, snapshot.gtpConfigPath);
          assertEquals(analysisConfig, snapshot.analysisConfigPath);
          assertEquals(estimateConfig, snapshot.estimateConfigPath);
          assertEquals(customWeight, snapshot.activeWeightPath);
          assertTrue(importedModel.startsWith(workDir.resolve("human-sl-models")));
          assertEquals(
              importedModel.toString(),
              Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
          assertTrue(humanSlStatus.isInstalled());
          assertEquals(importedModel, humanSlStatus.modelPath);
        });
  }

  @Test
  void downloadingHumanSlAfterCustomWeightKeepsCompleteAppRoot() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download-root-priority");
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    Path engine =
        touch(
            appRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(configDir.resolve("estimate.cfg"));
    touch(appRoot.resolve("weights").resolve("default.bin.gz"));
    Path customWeight = touch(workDir.resolve("weights").resolve("custom.bin.gz"));
    byte[] modelBytes = repeatedBytes(4096, (byte) 11);

    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withProcessDirAndConfig(
          processDir,
          workDir,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  sha256(modelBytes),
                  modelBytes.length,
                  () -> {
                    Lizzie.config.uiConfig.put(
                        "katago-preferred-weight-path", customWeight.toString());

                    Path downloaded = KataGoAutoSetupHelper.downloadHumanSlModel(null);
                    KataGoAutoSetupHelper.SetupSnapshot snapshot =
                        KataGoAutoSetupHelper.inspectLocalSetup();

                    assertTrue(downloaded.startsWith(workDir.resolve("human-sl-models")));
                    assertEquals(appRoot.toAbsolutePath().normalize(), snapshot.appRoot);
                    assertEquals(engine, snapshot.enginePath);
                    assertEquals(gtpConfig, snapshot.gtpConfigPath);
                    assertEquals(customWeight, snapshot.activeWeightPath);
                  }));
    }
  }

  @Test
  void humanSlCandidatesPreferWorkingDirAncestorOverSeparateEngineRoot() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-candidate-roots");
    Path portableRoot = Files.createDirectories(tempRoot.resolve("portable"));
    Path workDir = Files.createDirectories(portableRoot.resolve("user-data"));
    Path selectedAppRoot = Files.createDirectories(tempRoot.resolve("engine-app"));
    Path portableModel =
        touchModel(
            portableRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME));
    Path selectedRootModel =
        touchModel(
            selectedAppRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME));

    List<Path> candidates =
        KataGoAutoSetupHelper.collectHumanSlModelCandidates(workDir, selectedAppRoot);

    assertEquals(portableModel, candidates.get(0));
    assertTrue(candidates.contains(selectedRootModel));
  }

  @Test
  void downloadHumanSlModelVerifiesChecksumAndRemembersPath() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download");
    byte[] modelBytes = repeatedBytes(4096, (byte) 7);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  sha256(modelBytes),
                  modelBytes.length,
                  () -> {
                    Path downloaded = KataGoAutoSetupHelper.downloadHumanSlModel(null);
                    KataGoAutoSetupHelper.HumanSlModelStatus status =
                        KataGoAutoSetupHelper.inspectHumanSlModel();

                    assertEquals(modelBytes.length, Files.size(downloaded));
                    assertEquals(
                        downloaded.toString(),
                        Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
                    assertTrue(status.isInstalled());
                    assertEquals(downloaded, status.modelPath);
                  }));
    }
  }

  @Test
  void downloadHumanSlModelRejectsChecksumMismatchAndDeletesPartialFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download-bad");
    byte[] modelBytes = repeatedBytes(4096, (byte) 9);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  "0000000000000000000000000000000000000000000000000000000000000000",
                  modelBytes.length,
                  () -> {
                    assertThrows(
                        IOException.class, () -> KataGoAutoSetupHelper.downloadHumanSlModel(null));

                    Path modelsDir = tempRoot.resolve("human-sl-models");
                    assertFalse(
                        Files.exists(
                            modelsDir.resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME)));
                    assertFalse(
                        Files.exists(
                            modelsDir.resolve(
                                KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME + ".part")));
                    assertFalse(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled());
                  }));
    }
  }

  @Test
  void inspectHumanSlModelRejectsTruncatedOfficialModel() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-truncated");
    Files.createDirectories(tempRoot.resolve("human-sl-models"));
    Files.write(
        tempRoot.resolve("human-sl-models").resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
        new byte[] {1, 2, 3, 4});

    withUserDirAndConfig(
        tempRoot, () -> assertFalse(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled()));
  }

  @Test
  void inspectLocalSetupUsesConfiguredWorkDirectoryInsteadOfProcessDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-configured-workdir");
    Path workDir = Files.createDirectories(tempRoot.resolve("portable").resolve("user-data"));
    Path processDir = Files.createDirectories(tempRoot.resolve("outside-process-dir"));
    Path engine =
        touch(
            workDir
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(workDir.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configs.resolve("gtp.cfg"));
    Path weight = touch(workDir.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        processDir,
        workDir,
        () -> {
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();

          assertEquals(workDir.toAbsolutePath().normalize(), snapshot.workingDir);
          assertEquals(engine, snapshot.enginePath);
          assertEquals(gtpConfig, snapshot.gtpConfigPath);
          assertEquals(weight, snapshot.activeWeightPath);
        });
  }

  @Test
  void startupRepairDoesNotRewriteTensorRtProfileToCuda() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-tensorrt-repair");
    Path cudaEngine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Path tensorRtEngine =
        touch(
            tempRoot
                .resolve("runtime")
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia-tensorrt")
                .resolve("katago.exe"));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfig = touch(configDir.resolve("analysis.cfg"));
    Path estimateConfig = touch(configDir.resolve("estimate.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          engines.add(engineData("KataGo Bundled", cudaEngine, gtpConfig, weight, false));
          engines.add(engineData("KataGo TensorRT", tensorRtEngine, gtpConfig, weight, true));
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 1);
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(tensorRtEngine)
                  + " analysis -model "
                  + quote(weight)
                  + " -config "
                  + quote(analysisConfig)
                  + " -quit-without-waiting");
          Lizzie.config.uiConfig.put(
              "estimate-command",
              quote(tensorRtEngine)
                  + " gtp -model "
                  + quote(weight)
                  + " -config "
                  + quote(estimateConfig));

          assertFalse(KataGoAutoSetupHelper.repairBrokenBundledCommandsIfNeeded());
          assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());

          ArrayList<EngineData> repairedEngines = Utils.getEngineData();
          assertEquals("KataGo TensorRT", repairedEngines.get(1).name);
          assertTrue(repairedEngines.get(1).isDefault);
          assertTrue(repairedEngines.get(1).commands.contains("windows-x64-nvidia-tensorrt"));
          assertEquals(1, Lizzie.config.uiConfig.optInt("default-engine"));
          assertTrue(
              Lizzie.config
                  .uiConfig
                  .optString("analysis-engine-command")
                  .contains("windows-x64-nvidia-tensorrt"));
        });
  }

  @Test
  void startupRepairLeavesRemoteComputeEngineAlone() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-remote-compute-repair");

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData remote = new EngineData();
          remote.index = 0;
          remote.name = "自建算力 · 127.0.0.1:8765";
          remote.commands = "remote-compute://custom-websocket";
          remote.isDefault = true;
          engines.add(remote);
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 0);

          assertNull(Utils.resolveExistingExecutable(remote.commands));
          assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());

          ArrayList<EngineData> repairedEngines = Utils.getEngineData();
          assertEquals("remote-compute://custom-websocket", repairedEngines.get(0).commands);
          assertTrue(repairedEngines.get(0).isDefault);
          assertEquals(0, Lizzie.config.uiConfig.optInt("default-engine"));
        });
  }

  @Test
  void autoSetupRefreshPreservesUserEngineKomiAndSettings() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-preserve-engine-settings");
    Path engine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(configDir.resolve("estimate.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData autoSetupEngine =
              engineData(
                  KataGoAutoSetupHelper.getAutoSetupEngineName(), engine, gtpConfig, weight, false);
          autoSetupEngine.komi = 6.5F;
          autoSetupEngine.preload = true;
          autoSetupEngine.width = 13;
          autoSetupEngine.height = 13;
          autoSetupEngine.initialCommand = "kata-set-rules chinese";
          engines.add(autoSetupEngine);
          Utils.saveEngineSettings(engines);

          KataGoAutoSetupHelper.applyAutoSetup(KataGoAutoSetupHelper.inspectLocalSetup(), false);

          ArrayList<EngineData> refreshedEngines = Utils.getEngineData();
          EngineData refreshed = refreshedEngines.get(0);
          assertEquals(6.5F, refreshed.komi);
          assertTrue(refreshed.preload);
          assertEquals(13, refreshed.width);
          assertEquals(13, refreshed.height);
          assertEquals("kata-set-rules chinese", refreshed.initialCommand);
          assertFalse(refreshed.isDefault);
        });
  }

  @Test
  void addingDownloadedWeightsKeepsIndependentReusableEngineProfiles() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-independent-weight-engines");
    touch(
        tempRoot
            .resolve("engines")
            .resolve("katago")
            .resolve(detectTestPlatformDir())
            .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(configDir.resolve("estimate.cfg"));
    Path firstWeight = touch(tempRoot.resolve("weights").resolve("model-a.bin.gz"));
    Path secondWeight = touch(tempRoot.resolve("weights").resolve("model-b.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("autoload-default", false);
          Lizzie.config.uiConfig.put("autoload-empty", true);
          Lizzie.config.uiConfig.put("autoload-last", false);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.SetupResult initial =
              KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(firstWeight));
          KataGoAutoSetupHelper.SetupResult first =
              KataGoAutoSetupHelper.addWeightEngineProfile(snapshot.withActiveWeight(firstWeight));
          KataGoAutoSetupHelper.SetupResult second =
              KataGoAutoSetupHelper.addWeightEngineProfile(snapshot.withActiveWeight(secondWeight));
          KataGoAutoSetupHelper.SetupResult repeated =
              KataGoAutoSetupHelper.addWeightEngineProfile(snapshot.withActiveWeight(secondWeight));

          ArrayList<EngineData> engines = Utils.getEngineData();
          assertEquals(initial.engineIndex, first.engineIndex);
          assertFalse(first.createdEngine);
          assertTrue(second.createdEngine);
          assertFalse(repeated.createdEngine);
          assertEquals(second.engineIndex, repeated.engineIndex);
          assertEquals(2, engines.size());
          assertTrue(engines.get(first.engineIndex).name.startsWith("KataGo · "));
          assertTrue(engines.get(first.engineIndex).commands.contains(firstWeight.toString()));
          assertTrue(engines.get(second.engineIndex).commands.contains(secondWeight.toString()));
          assertFalse(engines.get(first.engineIndex).isDefault);
          assertTrue(engines.get(second.engineIndex).isDefault);
          assertEquals(second.engineIndex, Lizzie.config.uiConfig.optInt("default-engine"));
          assertFalse(Lizzie.config.uiConfig.optBoolean("autoload-default"));
          assertTrue(Lizzie.config.uiConfig.optBoolean("autoload-empty"));
          assertFalse(Lizzie.config.uiConfig.optBoolean("autoload-last"));
          assertTrue(
              Lizzie.config
                  .uiConfig
                  .optString("analysis-engine-command")
                  .contains(secondWeight.toString()));

          KataGoAutoSetupHelper.SetupResult selectedAgain =
              KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(firstWeight));
          ArrayList<EngineData> refreshed = Utils.getEngineData();
          assertEquals(first.engineIndex, selectedAgain.engineIndex);
          assertEquals(2, refreshed.size());
          assertTrue(refreshed.get(first.engineIndex).isDefault);
          assertTrue(refreshed.get(second.engineIndex).commands.contains(secondWeight.toString()));
        });
  }

  @Test
  void autoSetupDoesNotOverwriteUserManagedAnalysisCommand() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-user-analysis-command");
    touch(
        tempRoot
            .resolve("engines")
            .resolve("katago")
            .resolve(detectTestPlatformDir())
            .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(configDir.resolve("estimate.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("model.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          String customCommand = "custom-katago analysis -model custom.bin.gz";
          Lizzie.config.analysisEngineCommand = customCommand;
          Lizzie.config.analysisEngineCommandCustomized = true;
          Lizzie.config.uiConfig.put("analysis-engine-command", customCommand);
          Lizzie.config.uiConfig.put("analysis-engine-command-customized", true);

          KataGoAutoSetupHelper.applyAutoSetup(
              KataGoAutoSetupHelper.inspectLocalSetup().withActiveWeight(weight));

          assertEquals(customCommand, Lizzie.config.analysisEngineCommand);
          assertEquals(customCommand, Lizzie.config.uiConfig.optString("analysis-engine-command"));
        });
  }

  private static EngineData engineData(
      String name, Path enginePath, Path configPath, Path weightPath, boolean isDefault) {
    EngineData data = new EngineData();
    data.name = name;
    data.commands =
        quote(enginePath) + " gtp -model " + quote(weightPath) + " -config " + quote(configPath);
    data.preload = false;
    data.komi = 7.5F;
    data.width = 19;
    data.height = 19;
    data.isDefault = isDefault;
    data.useJavaSSH = false;
    data.ip = "";
    data.port = "";
    data.userName = "";
    data.password = "";
    data.useKeyGen = false;
    data.keyGenPath = "";
    data.initialCommand = "";
    return data;
  }

  private static String officialModel(String family, int version) {
    return "kata1-" + family + "c512nbt-s" + version + "-d" + version;
  }

  private static String officialLink(String model) {
    return "<a href=\"https://example.com/" + model + ".bin.gz\">" + model + "</a>";
  }

  private static String officialRow(String model, String releaseDate, String elo) {
    return "<tr><td>"
        + model
        + "</td><td>"
        + releaseDate
        + "</td><td>"
        + elo
        + "</td><td>"
        + officialLink(model)
        + "</td></tr>";
  }

  private static String officialFamily(KataGoAutoSetupHelper.RemoteWeightInfo info) {
    int start = info.modelName.indexOf("-b");
    int end = info.modelName.indexOf('c', start + 2);
    return start >= 0 && end > start ? info.modelName.substring(start + 1, end) : "";
  }

  private static Path touch(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[0]).toAbsolutePath().normalize();
  }

  private static Path touchModel(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[2 * 1024 * 1024]).toAbsolutePath().normalize();
  }

  private static Path writeModel(Path path, byte[] bytes) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, bytes).toAbsolutePath().normalize();
  }

  private static byte[] repeatedBytes(int size, byte value) {
    byte[] bytes = new byte[size];
    java.util.Arrays.fill(bytes, value);
    return bytes;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize().toString() + "\"";
  }

  private static String quoteLiteral(Path path) {
    return "\"" + path.toString() + "\"";
  }

  private static String detectTestPlatformDir() {
    String osName = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
    boolean is64 = arch.contains("64");
    if (osName.contains("win")) {
      return is64 ? "windows-x64" : "windows-x86";
    }
    if (osName.contains("mac") || osName.contains("darwin")) {
      return isArm ? "macos-arm64" : "macos-amd64";
    }
    return is64 ? "linux-x64" : "linux-x86";
  }

  private static String testKataGoBinaryName() {
    return System.getProperty("os.name", "").toLowerCase().contains("win")
        ? "katago.exe"
        : "katago";
  }

  private static void withUserDirAndConfig(Path userDir, ThrowingRunnable action) throws Exception {
    withProcessDirAndConfig(userDir, userDir, action);
  }

  private static void withProcessDirAndConfig(
      Path processDir, Path configDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", processDir.toString());
      Lizzie.config = ConfigTestHelper.createForTests(configDir);
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

  private static void withHumanSlDownloadProperties(
      String url, String sha256, long size, ThrowingRunnable action) throws Exception {
    String previousUrl = System.getProperty("lizzie.humansl.model.url");
    String previousSha = System.getProperty("lizzie.humansl.model.sha256");
    String previousSize = System.getProperty("lizzie.humansl.model.size");
    try {
      System.setProperty("lizzie.humansl.model.url", url);
      System.setProperty("lizzie.humansl.model.sha256", sha256);
      System.setProperty("lizzie.humansl.model.size", Long.toString(size));
      action.run();
    } finally {
      restoreProperty("lizzie.humansl.model.url", previousUrl);
      restoreProperty("lizzie.humansl.model.sha256", previousSha);
      restoreProperty("lizzie.humansl.model.size", previousSize);
    }
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, previousValue);
  }

  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static final class FixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private FixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static FixtureServer start(byte[] bytes) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      server.createContext(
          "/model.bin.gz",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
              body.write(bytes);
            }
          });
      server.setExecutor(executor);
      server.start();
      return new FixtureServer(server, executor);
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin.gz";
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
