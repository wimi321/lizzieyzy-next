package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class KataGoRuntimeHelperTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";
  private static final String PATH_SEPARATOR = System.getProperty("path.separator");
  private static final String WINDOWS_OS_NAME = "Windows 11";
  private static final String EMPTY_FILE_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @BeforeEach
  void acceptEmptyCompanionFixture() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(EMPTY_FILE_SHA256);
  }

  @AfterEach
  void restoreProductionCompanionDigest() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(null);
  }

  @Test
  void externalEngineKeepsOriginalDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-external");
    Path enginePath = touch(tempRoot.resolve("external-engine").resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
    ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

    withConfig(
        runtimeWorkDirectory,
        () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

    assertEquals(
        normalize(originalDirectory),
        normalize(processBuilder.directory().toPath()),
        "External engine should keep its directory.");
    assertEquals(
        originalPath,
        processBuilder.environment().get("PATH"),
        "External engine should keep PATH unchanged.");
  }

  @Test
  void bundledOpenclEngineUsesRuntimeDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-bundled-opencl");
    Path enginePath =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-opencl")
                .resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
    ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

    withConfig(
        runtimeWorkDirectory,
        () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

    assertEquals(
        normalize(runtimeWorkDirectory),
        normalize(processBuilder.directory().toPath()),
        "Bundled OpenCL engine should use runtime directory.");
    assertEquals(
        normalize(enginePath.getParent()),
        firstPathEntry(processBuilder.environment().get("PATH")),
        "Bundled OpenCL engine should prepend its engine directory.");
  }

  @Test
  void bundledEngineUnderSpacedUnicodePathKeepsRuntimeStateOutOfEngineDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-spaced-path");
    Path portableRoot = Files.createDirectories(tempRoot.resolve("LizzieYzy Next 测试 portable"));
    Path enginePath =
        touch(
            portableRoot
                .resolve("app")
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(enginePath.getParent());
    Path runtimeWorkDirectory =
        Files.createDirectories(portableRoot.resolve("user-data").resolve("runtime"));
    ProcessBuilder processBuilder =
        createProcessBuilder(originalDirectory, String.join(PATH_SEPARATOR, "alpha", "beta"));

    withConfig(
        runtimeWorkDirectory,
        () -> {
          List<String> launchCommand =
              KataGoRuntimeHelper.prepareBundledLaunchCommand(
                  Arrays.asList(enginePath.toString(), "gtp", "-config", "gtp.cfg"), enginePath);
          KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath);

          assertEquals(
              normalize(runtimeWorkDirectory),
              normalize(processBuilder.directory().toPath()),
              "A portable path containing spaces must still use user-data/runtime.");
          assertEquals(
              normalize(enginePath.getParent()),
              firstPathEntry(processBuilder.environment().get("PATH")));
          int overrideIndex = launchCommand.indexOf("-override-config");
          assertTrue(overrideIndex >= 0);
          String overrides = launchCommand.get(overrideIndex + 1);
          assertTrue(
              overrides.contains(
                  "homeDataDir="
                      + runtimeWorkDirectory.resolve("katago-home").toAbsolutePath().normalize()),
              "KataGo homeDataDir must remain one structured argument even when it has spaces.");
          assertFalse(
              Files.exists(enginePath.getParent().resolve("KataGoData")),
              "The immutable engine directory must not receive cache data.");
        });
  }

  @Test
  void bundledOpenclEngineNeedsFirstTuningUntilCacheExists() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-opencl-tuning");
          Path enginePath =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-opencl")
                      .resolve("katago.exe"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                assertTrue(
                    KataGoRuntimeHelper.needsFirstOpenCLTuning(enginePath),
                    "Bundled OpenCL should get the longer startup budget before tuning exists.");

                Path tuningDir =
                    Files.createDirectories(
                        runtimeWorkDirectory.resolve("katago-home/opencltuning"));
                touch(tuningDir.resolve("tune11_gpu0.txt"));

                assertFalse(
                    KataGoRuntimeHelper.needsFirstOpenCLTuning(enginePath),
                    "Existing OpenCL tuning cache should restore the normal startup timeout.");
              });
        });
  }

  @Test
  void bundledNvidiaEngineDoesNotNeedOpenclTuningBudget() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia-no-opencl-tuning");
          Path enginePath =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia")
                      .resolve("katago.exe"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () ->
                  assertFalse(
                      KataGoRuntimeHelper.needsFirstOpenCLTuning(enginePath),
                      "Bundled NVIDIA engines should not use the OpenCL tuning watchdog budget."));
        });
  }

  @Test
  void bundledTensorRtDetectionAcceptsPackageDirectoryAndBackendMarker() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-detection");
          Path namedEngine =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia-tensorrt")
                      .resolve("katago.exe"));
          Path markedDir =
              Files.createDirectories(
                  tempRoot.resolve("app").resolve("engines").resolve("katago").resolve("windows-x64"));
          Path markedEngine = touch(markedDir.resolve("katago.exe"));
          Files.writeString(
              markedDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");

          assertTrue(KataGoRuntimeHelper.isBundledTensorRtPath(namedEngine));
          assertTrue(KataGoRuntimeHelper.isBundledTensorRtPath(markedEngine));
          assertTrue(
              KataGoRuntimeHelper.isBundledNvidiaCommand(
                  "\""
                      + markedEngine
                      + "\" gtp -model \""
                      + tempRoot.resolve("weight.bin.gz")
                      + "\""));
          assertTrue(
              KataGoRuntimeHelper.isBundledTensorRtCommand(
                  "\""
                      + markedEngine
                      + "\" gtp -model \""
                      + tempRoot.resolve("weight.bin.gz")
                      + "\""));

          Files.writeString(
              markedDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda\n");
          assertFalse(KataGoRuntimeHelper.isBundledTensorRtPath(markedEngine));
          assertTrue(KataGoRuntimeHelper.isBundledNvidiaCommand(markedEngine.toString()));
        });
  }

  @Test
  void humanSlTensorRtLaunchUsesPackagedCudaCompanionAndLightweightProfile() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-humansl-companion");
          Path engineDir =
              Files.createDirectories(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia-tensorrt"));
          Path tensorRtEngine = touch(engineDir.resolve("katago.exe"));
          Path companion =
              touch(engineDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
          writeCurrentTensorRtEngineManifest(engineDir);
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          int originalWidth = Board.boardWidth;
          int originalHeight = Board.boardHeight;
          try {
            Board.boardWidth = 19;
            Board.boardHeight = 19;
            withConfig(
                runtimeWorkDirectory,
                () -> {
                  List<String> command =
                      KataGoRuntimeHelper.prepareBundledLaunchCommand(
                          Arrays.asList(
                              tensorRtEngine.toString(),
                              "analysis",
                              "-config",
                              "analysis.cfg",
                              "-override-config",
                              "nnMaxBatchSize=64,numAnalysisThreads=2"),
                          tensorRtEngine,
                          KataGoRuntimeHelper.LaunchPurpose.HUMAN_SL);

                  assertEquals(normalize(companion).toString(), normalize(Path.of(command.get(0))).toString());
                  String overrides = command.get(command.indexOf("-override-config") + 1);
                  assertTrue(overrides.contains("numAnalysisThreads=1"));
                  assertTrue(overrides.contains("numSearchThreadsPerAnalysisThread=8"));
                  assertTrue(overrides.contains("nnMaxBatchSize=8"));
                  assertTrue(overrides.contains("nnCacheSizePowerOfTwo=20"));
                  assertTrue(overrides.contains("maxBoardXSizeForNNBuffer=19"));
                  assertTrue(overrides.contains("maxBoardYSizeForNNBuffer=19"));
                  assertTrue(overrides.contains("requireMaxBoardSize=true"));
                  assertFalse(KataGoRuntimeHelper.isBundledTensorRtPath(companion));
                });
          } finally {
            Board.boardWidth = originalWidth;
            Board.boardHeight = originalHeight;
          }
        });
  }

  @Test
  void humanSlTensorRtLaunchCanReuseConfiguredBundledCudaEngine() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-humansl-configured-cuda");
          Path tensorRtEngine =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia-tensorrt")
                      .resolve("katago.exe"));
          Path cudaEngine =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia50-cuda")
                      .resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(cudaEngine.getParent());
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Lizzie.config.leelazConfig.put(
                    "engine-settings-list",
                    new JSONArray()
                        .put(new JSONObject().put("command", tensorRtEngine + " gtp"))
                        .put(new JSONObject().put("command", cudaEngine + " gtp")));

                assertEquals(
                    normalize(cudaEngine),
                    normalize(KataGoRuntimeHelper.resolveHumanSlCudaCompanion(tensorRtEngine)));
              });
        });
  }

  @Test
  void humanSlTensorRtLaunchFailsFastWithoutCudaCompanion() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-humansl-no-companion");
          Path tensorRtEngine =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia-tensorrt")
                      .resolve("katago.exe"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                IllegalStateException error =
                    assertThrows(
                        IllegalStateException.class,
                        () ->
                            KataGoRuntimeHelper.prepareBundledLaunchCommand(
                                Arrays.asList(tensorRtEngine.toString(), "analysis"),
                                tensorRtEngine,
                                KataGoRuntimeHelper.LaunchPurpose.HUMAN_SL));

                String message = error.getMessage();
                assertTrue(message.contains("TensorRT"));
                assertTrue(message.contains("CUDA"));
                assertTrue(
                    message.toLowerCase(Locale.ROOT).contains("reinstall")
                        || message.contains("重新安装")
                        || message.contains("重新安裝"));
              });
        });
  }

  @Test
  void cudaAndTensorRtRuntimesRequireNvrtcCompilerAndBuiltins() throws Exception {
    Path legacyDir = Files.createTempDirectory("katago-helper-cudnn8-required");
    Path legacyEngine = touch(legacyDir.resolve("katago.exe"));
    Files.writeString(
        legacyDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "Profile: cuda12.1-cudnn8\n");
    List<List<String>> legacy =
        KataGoRuntimeHelper.requiredRuntimeDllGroups(legacyEngine, "nvidia");
    List<List<String>> standard =
        KataGoRuntimeHelper.requiredRuntimeDllGroups(
            Path.of("engines/katago/windows-x64-nvidia/katago.exe"), "nvidia");
    List<List<String>> rtx50 =
        KataGoRuntimeHelper.requiredRuntimeDllGroups(
            Path.of("engines/katago/windows-x64-nvidia50-cuda/katago.exe"),
            "nvidia50-cuda");
    List<List<String>> tensorRt =
        KataGoRuntimeHelper.requiredRuntimeDllGroups(
            Path.of("engines/katago/windows-x64-nvidia-tensorrt/katago.exe"),
            "nvidia-tensorrt");

    assertTrue(legacy.contains(List.of("nvrtc64_*.dll")));
    assertTrue(legacy.contains(List.of("nvrtc-builtins64_*.dll")));
    assertTrue(standard.contains(List.of("nvrtc64_*.dll")));
    assertTrue(standard.contains(List.of("nvrtc-builtins64_*.dll")));
    assertTrue(rtx50.contains(List.of("nvrtc64_120_0.dll")));
    assertTrue(rtx50.contains(List.of("nvrtc-builtins64_128.dll")));
    assertTrue(tensorRt.contains(List.of("nvrtc64_120_0.dll")));
    assertTrue(tensorRt.contains(List.of("nvrtc-builtins64_128.dll")));
  }

  @Test
  void tensorRtRuntimeUsesPinnedOfficialArchiveSha256() {
    assertEquals(
        "c2758eb60191f01a47b24f54700e5463f577ebe129cd18fe835d0aa9f1e1a16d",
        KataGoRuntimeHelper.TENSORRT_RUNTIME_SHA256);
    assertEquals("12.8.61", KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION);
    assertEquals(
        "e43603b09f8a52d681ceb814c00b655af19da53692ab91671dabbf8071c8f93d",
        KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256);
  }

  @Test
  void bundledNvidiaEnginePrependsRuntimePath() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-bundled-nvidia");
          Path enginePath =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia")
                      .resolve("katago.exe"));
          Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
          ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

          withConfig(
              runtimeWorkDirectory,
              () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

          assertEquals(
              normalize(runtimeWorkDirectory),
              normalize(processBuilder.directory().toPath()),
              "Bundled NVIDIA engine should use runtime directory.");
          assertEquals(
              normalize(runtimeDir),
              firstPathEntry(processBuilder.environment().get("PATH")),
              "Bundled NVIDIA engine should prepend runtime directory first.");
          assertEquals(
              normalize(enginePath.getParent()),
              secondPathEntry(processBuilder.environment().get("PATH")),
              "Bundled NVIDIA engine should keep engine directory after runtime directory.");
        });
  }

  @Test
  void nvidia50MarkerPrependsRuntimePath() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-bundled-nvidia50");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
          ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

          withConfig(
              runtimeWorkDirectory,
              () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

          assertEquals(
              normalize(runtimeDir),
              firstPathEntry(processBuilder.environment().get("PATH")),
              "RTX 50 NVIDIA package should prepend the runtime directory.");
          assertEquals(
              normalize(enginePath.getParent()),
              secondPathEntry(processBuilder.environment().get("PATH")),
              "RTX 50 NVIDIA package should keep the engine directory after runtime.");
        });
  }

  @Test
  void tensorRtLaunchKeepsCudaAndTempCachesInsideRuntimeDirectory() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-cache-env");
          Path enginePath =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia-tensorrt")
                      .resolve("katago.exe"));
          Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
          ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

          withConfig(
              runtimeWorkDirectory,
              () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

          Path expectedCudaCache = runtimeDir.resolve("cache").resolve("cuda");
          Path expectedTempCache = runtimeDir.resolve("cache").resolve("temp");
          assertEquals(
              normalize(expectedCudaCache),
              normalize(Path.of(processBuilder.environment().get("CUDA_CACHE_PATH"))),
              "Bundled TensorRT should keep CUDA cache under the app runtime directory.");
          assertEquals(
              normalize(expectedTempCache),
              normalize(Path.of(processBuilder.environment().get("TEMP"))),
              "Bundled TensorRT should keep temp files under the app runtime directory.");
          assertEquals(
              normalize(expectedTempCache),
              normalize(Path.of(processBuilder.environment().get("TMP"))),
              "Bundled TensorRT should keep temp files under the app runtime directory.");
          assertTrue(Files.isDirectory(expectedCudaCache));
          assertTrue(Files.isDirectory(expectedTempCache));
        });
  }

  @Test
  void tensorRtUnderSpacedPortablePathUsesSeparateRuntimeDirectory() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-separated-tensorrt");
          Path portableRoot =
              Files.createDirectories(tempRoot.resolve("LizzieYzy Next CUDA portable"));
          Path runtimeWorkDirectory =
              Files.createDirectories(portableRoot.resolve("user-data").resolve("runtime"));
          Path engineDir =
              Files.createDirectories(
                  runtimeWorkDirectory
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia-tensorrt"));
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt");
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          touchRequiredCuda12_8Dlls(runtimeDir);
          touch(runtimeDir.resolve("nvinfer_10.dll"));
          touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
          Path originalDirectory = Files.createDirectories(portableRoot.resolve("app"));
          ProcessBuilder processBuilder =
              createProcessBuilder(
                  originalDirectory, String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta")));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath, "");
                KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath);

                assertTrue(
                    status.ready,
                    "TensorRT should be ready when its engine and runtime are stored separately.");
                assertEquals(
                    normalize(runtimeWorkDirectory),
                    normalize(processBuilder.directory().toPath()),
                    "The TensorRT process should keep all mutable state in user-data/runtime.");
                assertEquals(
                    normalize(runtimeDir),
                    firstPathEntry(processBuilder.environment().get("PATH")),
                    "The separately installed NVIDIA runtime must be first on PATH.");
                assertEquals(
                    normalize(engineDir),
                    secondPathEntry(processBuilder.environment().get("PATH")),
                    "The TensorRT engine directory should follow its runtime on PATH.");
                assertFalse(
                    Files.isRegularFile(engineDir.resolve("cudnn64_9.dll")),
                    "Runtime DLLs should not need to be duplicated into the engine directory.");
              });
        });
  }

  @Test
  void standardNvidia117RuntimeRequiresCudnn9() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia117-runtime");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
              "Profile: cuda12.1-cudnn9\n");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCommonCuda12Dlls(engineDir);
          touch(engineDir.resolve("cudnn64_8.dll"));
          touch(engineDir.resolve("libz.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath, "");

                assertTrue(status.applicable);
                assertFalse(status.ready);
                assertTrue(status.missingDlls.contains("cudnn64_9.dll"));
              });
        });
  }

  @Test
  void standardNvidia117RuntimeAcceptsOfficialZDllName() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia117-zdll");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
              "Profile: cuda12.1-cudnn9\n");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCommonCuda12Dlls(engineDir);
          touch(engineDir.resolve("cudnn64_9.dll"));
          touch(engineDir.resolve("z.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath, "");

                assertTrue(status.applicable);
                assertTrue(
                    status.ready, "KataGo 1.17's official z.dll must satisfy the runtime check.");
                assertTrue(status.missingDlls.isEmpty());
              });
        });
  }

  @Test
  void legacyStandardNvidiaRuntimeStillAcceptsCudnn8() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia-legacy-runtime");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
              "Profile: cuda12.1-cudnn8\n");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCommonCuda12Dlls(engineDir);
          touch(engineDir.resolve("cudnn64_8.dll"));
          touch(engineDir.resolve("libz.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath, "");

                assertTrue(status.applicable);
                assertTrue(status.ready);
                assertTrue(status.missingDlls.isEmpty());
              });
        });
  }

  @Test
  void oldStandardNvidiaRuntimeWithoutNvrtcIsNotReady() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia-missing-nvrtc");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
              "Profile: cuda12.1-cudnn9\n");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCuda12CoreWithoutNvrtc(engineDir);
          touch(engineDir.resolve("cudnn64_9.dll"));
          touch(engineDir.resolve("z.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath, "");

                assertFalse(status.ready);
                assertTrue(status.missingDlls.contains("nvrtc64_*.dll"));
                assertTrue(status.missingDlls.contains("nvrtc-builtins64_*.dll"));
              });
        });
  }

  @Test
  void standardNvidiaRuntimeCanUseNvrtcFromExplicitLaunchPath() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia-nvrtc-path");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
              "Profile: cuda12.1-cudnn9\n");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCuda12CoreWithoutNvrtc(engineDir);
          touch(engineDir.resolve("cudnn64_9.dll"));
          touch(engineDir.resolve("z.dll"));
          Path launchPathDir = Files.createDirectories(tempRoot.resolve("launch-path"));
          touch(launchPathDir.resolve("nvrtc64_120_0.dll"));
          touch(launchPathDir.resolve("nvrtc-builtins64_126.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(
                        enginePath, launchPathDir.toString());

                assertTrue(status.ready);
                assertTrue(status.missingDlls.isEmpty());
              });
        });
  }

  @Test
  void rtx50RuntimeWithoutPinnedNvrtcManifestIsNotReady() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-nvrtc-manifest");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(engineDir);
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
              "Profile: cuda12.8-cudnn9\n"
                  + "- CUDA NVRTC: 12.8.60 | fixture | sha256="
                  + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
                  + "\n");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath, "");

                assertFalse(status.ready);
                assertTrue(
                    status.missingDlls.contains("CUDA NVRTC 12.8.61 manifest"),
                    "RTX 50 migration must reject an unpinned NVRTC manifest");
              });
        });
  }

  @Test
  void nvidia50CudaRuntimeAcceptsCudnn9() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-cuda-runtime");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(engineDir);
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath);

                assertTrue(status.applicable, "RTX 50 CUDA package should need NVIDIA runtime.");
                assertTrue(status.ready, "CUDA 12.8/cuDNN 9 runtime should satisfy RTX 50 CUDA.");
                assertEquals(0, status.missingDlls.size());
              });
        });
  }

  @Test
  void nvidia50CudaRuntimeRejectsOldCudnn8OnlyBundle() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-cuda-old-cudnn");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCommonCuda12Dlls(engineDir);
          touch(engineDir.resolve("cudnn64_8.dll"));
          touch(engineDir.resolve("libz.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath);

                assertTrue(status.applicable);
                assertEquals(false, status.ready);
                assertTrue(
                    status.missingDlls.contains("cudnn64_9.dll"),
                    "RTX 50 CUDA package must require cuDNN 9.");
              });
        });
  }

  @Test
  void nvidia50TensorRtRuntimeRequiresTensorRtDlls() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-trt-runtime");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-trt");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(engineDir);
          touch(engineDir.resolve("nvinfer_10.dll"));
          touch(engineDir.resolve("nvinfer_plugin_10.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath);

                assertTrue(status.applicable, "TensorRT package should need NVIDIA runtime.");
                assertTrue(status.ready, "TensorRT runtime DLLs should satisfy the package.");
              });
        });
  }

  @Test
  void tensorRtInstallSpecUsesOfficialKataGoAssetAndWritableRuntimeTarget() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-spec");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.TensorRtInstallSpec spec =
                    KataGoRuntimeHelper.buildTensorRtInstallSpec(snapshot);

                assertTrue(
                    spec.katagoUrl.endsWith("/katago-v1.18.1-trt10.9.0-cuda12.8-windows-x64.zip"));
                assertEquals(
                    "49b7229803b2ccee5205cc9d1f7b1a37790469405324de5e5acaafe7a8a9172a",
                    spec.katagoSha256);
                assertEquals(8_375_820L, spec.katagoSizeBytes);
                assertEquals(6, spec.runtimePackageCount);
                assertTrue(spec.totalDownloadBytes > 3_000_000_000L);
                assertEquals(
                    normalize(
                        runtimeWorkDirectory
                            .resolve("engines")
                            .resolve("katago")
                            .resolve("windows-x64-nvidia-tensorrt")),
                    normalize(spec.targetEngineDir));
                assertEquals(
                    normalize(spec.targetEngineDir.resolve("katago.exe")),
                    normalize(spec.targetEnginePath));
              });
        });
  }

  @Test
  void tensorRtNvidiaMirrorSelectionPrefersFastestUsableHost() {
    KataGoRuntimeHelper.NvidiaMirrorProbeResult cnResult =
        new KataGoRuntimeHelper.NvidiaMirrorProbeResult(
            "developer.download.nvidia.cn", 512_000L, 1200L, null);
    KataGoRuntimeHelper.NvidiaMirrorProbeResult comResult =
        new KataGoRuntimeHelper.NvidiaMirrorProbeResult(
            "developer.download.nvidia.com", 512_000L, 300L, null);

    assertEquals(
        "developer.download.nvidia.com",
        KataGoRuntimeHelper.selectNvidiaDownloadHostFromProbes(cnResult, comResult));
  }

  @Test
  void tensorRtNvidiaMirrorSelectionFallsBackToWorkingHost() {
    KataGoRuntimeHelper.NvidiaMirrorProbeResult cnResult =
        new KataGoRuntimeHelper.NvidiaMirrorProbeResult(
            "developer.download.nvidia.cn", 0L, 6000L, "timeout");
    KataGoRuntimeHelper.NvidiaMirrorProbeResult comResult =
        new KataGoRuntimeHelper.NvidiaMirrorProbeResult(
            "developer.download.nvidia.com", 256_000L, 500L, null);

    assertEquals(
        "developer.download.nvidia.com",
        KataGoRuntimeHelper.selectNvidiaDownloadHostFromProbes(cnResult, comResult));
  }

  @Test
  void tensorRtNvidiaMirrorUrlRewriteOnlyTouchesNvidiaDownloadHosts() {
    assertEquals(
        "https://developer.download.nvidia.cn/compute/cuda/redist/redistrib_12.8.0.json",
        KataGoRuntimeHelper.mirrorNvidiaDownloadUrl(
            "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.8.0.json",
            "developer.download.nvidia.cn"));
    assertEquals(
        "https://example.com/compute/cuda/redist/redistrib_12.8.0.json",
        KataGoRuntimeHelper.mirrorNvidiaDownloadUrl(
            "https://example.com/compute/cuda/redist/redistrib_12.8.0.json",
            "developer.download.nvidia.cn"));
  }

  @Test
  void tensorRtInstallSpecKeepsExistingLegacyTargetCompatible() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-legacy-spec");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path legacyEnginePath =
              touch(
                  runtimeWorkDirectory
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia50-trt")
                      .resolve("katago.exe"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.TensorRtInstallSpec spec =
                    KataGoRuntimeHelper.buildTensorRtInstallSpec(snapshot);

                assertEquals(
                    normalize(legacyEnginePath.getParent()), normalize(spec.targetEngineDir));
                assertEquals(normalize(legacyEnginePath), normalize(spec.targetEnginePath));
              });
        });
  }

  @Test
  void tensorRtInstallCreatesSeparateEngineProfileFromFixture() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-install");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
          Path fixtureZip =
              createTensorRtFixtureZip(tempRoot.resolve("fixture").resolve("katago-trt.zip"));
          withTensorRtFixtureProperties(
              fixtureZip.toUri().toString(),
              sha256(fixtureZip),
              Files.size(fixtureZip),
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        SetupResult result =
                            KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                snapshot, null, new DownloadSession());
                        Path targetDir =
                            runtimeWorkDirectory
                                .resolve("engines")
                                .resolve("katago")
                                .resolve("windows-x64-nvidia-tensorrt");

                        assertEquals("KataGo TensorRT", result.engineName);
                        assertTrue(Files.isRegularFile(targetDir.resolve("katago.exe")));
                        assertTrue(Files.isRegularFile(targetDir.resolve("libz.dll")));
                        assertTrue(
                            Files.isRegularFile(
                                targetDir.resolve(
                                    KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)),
                            "The in-app install must preserve a CUDA HumanSL companion.");
                        assertEquals(
                            "nvidia-tensorrt",
                            Files.readString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"))
                                .trim());
                        assertTrue(
                            Files.readString(
                                    targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"))
                                .contains("KataGo release: v1.18.1"));
                        assertTrue(
                            Files.readString(
                                    targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"))
                                .contains("HumanSL companion SHA-256:"));
                        List<EngineData> engines = Utils.getEngineData();
                        assertTrue(
                            engines.stream()
                                .anyMatch(
                                    engine ->
                                        "KataGo TensorRT".equals(engine.name)
                                            && engine.isDefault
                                            && engine.commands.contains(
                                                "windows-x64-nvidia-tensorrt")));
                        assertFalse(
                            Files.exists(
                                runtimeWorkDirectory
                                    .resolve("nvidia-runtime")
                                    .resolve("downloads")
                                    .resolve("katago-trt.zip")),
                            "Successful TensorRT installs should remove the completed installer archive.");
                      }));
        });
  }

  @Test
  void tensorRtInstallResumesInterruptedDownloadPartFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-resume");
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
    Path fixtureZip =
        createTensorRtFixtureZip(tempRoot.resolve("fixture").resolve("katago-trt.zip"));
    byte[] fixtureBytes = Files.readAllBytes(fixtureZip);
    int firstChunkSize = Math.max(1, fixtureBytes.length / 2);

    try (ResumableFixtureServer server =
        ResumableFixtureServer.start(fixtureBytes, firstChunkSize)) {
      withOsName(
          WINDOWS_OS_NAME,
          () ->
              withTensorRtFixtureProperties(
                  server.url(),
                  sha256(fixtureZip),
                  fixtureBytes.length,
                  () ->
                      withConfig(
                          runtimeWorkDirectory,
                          () -> {
                            Path partialArchive =
                                runtimeWorkDirectory
                                    .resolve("nvidia-runtime")
                                    .resolve("downloads")
                                    .resolve("katago-trt.zip.part");
                            assertThrows(
                                IOException.class,
                                () ->
                                    KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                        snapshot, null, new DownloadSession()));
                            assertTrue(
                                Files.isRegularFile(partialArchive),
                                "Interrupted TensorRT downloads should keep the .part file.");
                            assertEquals(firstChunkSize, Files.size(partialArchive));

                            SetupResult result =
                                KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                    snapshot, null, new DownloadSession());

                            assertEquals("KataGo TensorRT", result.engineName);
                            assertEquals(
                                "bytes=" + firstChunkSize + "-",
                                server.lastRangeHeader(),
                                "The second attempt should resume from the partial byte count.");
                            assertFalse(
                                Files.exists(partialArchive),
                                "Successful resume should promote the .part file into the cache.");
                            assertFalse(
                                Files.exists(partialArchive.resolveSibling("katago-trt.zip")),
                                "Successful TensorRT installs should clean the completed archive after setup.");
                          })));
    }
  }

  @Test
  void manualTensorRtCacheCleanupDeletesStaleArchivesAndParts() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-cache-cleanup");
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

    withConfig(
        runtimeWorkDirectory,
        () -> {
          Path downloads =
              Files.createDirectories(
                  runtimeWorkDirectory.resolve("nvidia-runtime").resolve("downloads"));
          Path archive = Files.write(downloads.resolve("runtime.zip"), new byte[] {1, 2, 3});
          Path partial = Files.write(downloads.resolve("runtime.zip.part"), new byte[] {4, 5});

          assertEquals(5L, KataGoRuntimeHelper.tensorRtDownloadCacheBytes());
          long freedBytes = KataGoRuntimeHelper.cleanupTensorRtDownloadCache();

          assertEquals(5L, freedBytes);
          assertFalse(Files.exists(archive));
          assertFalse(Files.exists(partial));
          assertEquals(0L, KataGoRuntimeHelper.tensorRtDownloadCacheBytes());
        });
  }

  @Test
  void tensorRtInstallRefusesConcurrentInstallerBeforeMutatingProfile() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-lock");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
          Path fixtureZip =
              createTensorRtFixtureZip(tempRoot.resolve("fixture").resolve("katago-trt.zip"));

          withTensorRtFixtureProperties(
              fixtureZip.toUri().toString(),
              sha256(fixtureZip),
              Files.size(fixtureZip),
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        Path runtimeDir =
                            Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
                        Path lockPath = runtimeDir.resolve("tensorrt-install.lock");
                        try (FileChannel lockChannel =
                                FileChannel.open(
                                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                            FileLock ignored = lockChannel.lock()) {
                          IOException error =
                              assertThrows(
                                  IOException.class,
                                  () ->
                                      KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                          snapshot, null, new DownloadSession()));
                          String errorMessage = error.getMessage();

                          assertTrue(
                              errorMessage.contains("TensorRT")
                                  && (errorMessage.toLowerCase(Locale.ROOT).contains("running")
                                      || errorMessage.contains("运行")),
                              "Concurrent TensorRT installs should report one quiet running-task message.");
                          assertFalse(
                              Utils.getEngineData().stream()
                                  .anyMatch(engine -> "KataGo TensorRT".equals(engine.name)));
                        }
                      }));
        });
  }

  @Test
  void applyInstalledTensorRtSwitchesProfileWithoutRedownloading() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-apply-installed");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                Path runtimeDir = runtimeWorkDirectory.resolve("nvidia-runtime");
                touch(targetDir.resolve("katago.exe"));
                touch(targetDir.resolve("libz.dll"));
                touchRequiredCuda12_8Dlls(runtimeDir);
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifest(targetDir);

                KataGoRuntimeHelper.NvidiaRuntimeStatus runtimeStatus =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(
                        targetDir.resolve("katago.exe"), "");
                assertTrue(
                    runtimeStatus.ready,
                    "TensorRT runtime should be accepted when launch PATH dirs satisfy dependencies.");
                KataGoRuntimeHelper.TensorRtInstallStatus installStatus =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
                assertTrue(installStatus.installed);
                assertFalse(installStatus.active);
                assertTrue(
                    KataGoRuntimeHelper.canInstallTensorRt(snapshot),
                    "Installed but inactive TensorRT should leave the button actionable.");

                SetupResult result = KataGoRuntimeHelper.applyInstalledTensorRt(snapshot);

                assertEquals("KataGo TensorRT", result.engineName);
                assertEquals(
                    normalize(targetDir.resolve("katago.exe")),
                    normalize(result.snapshot.enginePath));
                KataGoRuntimeHelper.TensorRtInstallStatus activeStatus =
                    KataGoRuntimeHelper.inspectTensorRtInstall(result.snapshot);
                assertTrue(activeStatus.installed);
                assertTrue(activeStatus.active);
                assertFalse(
                    KataGoRuntimeHelper.canInstallTensorRt(result.snapshot),
                    "The TensorRT button should be disabled only after TensorRT is active.");
                KataGoRuntimeHelper.TensorRtInstallStatus refreshedStatus =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
                assertTrue(
                    refreshedStatus.active,
                    "TensorRT should stay active when the dialog refresh finds the original CUDA package first.");
                assertFalse(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
                assertTrue(
                    Utils.getEngineData().stream()
                        .anyMatch(
                            engine ->
                                "KataGo TensorRT".equals(engine.name)
                                    && engine.isDefault
                                    && engine.commands.contains("windows-x64-nvidia-tensorrt")));
              });
        });
  }

  @Test
  void currentTensorRtInstallWithoutCompanionRepairsFromPinnedUnifiedCudaSource()
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-companion-repair");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                Path targetEngine = touch(targetDir.resolve("katago.exe"));
                Path runtimeDir = runtimeWorkDirectory.resolve("nvidia-runtime");
                touchRequiredCuda12_8Dlls(runtimeDir);
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"),
                    "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifestWithoutCompanion(targetDir);

                assertFalse(
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot).installed,
                    "A current TensorRT binary without any usable HumanSL route is incomplete.");

                SetupResult result =
                    KataGoRuntimeHelper.downloadAndInstallTensorRt(
                        snapshot, null, new DownloadSession());

                Path repaired =
                    targetDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME);
                assertTrue(Files.isRegularFile(repaired));
                assertEquals(EMPTY_FILE_SHA256, sha256(repaired));
                assertTrue(
                    Files.readString(
                            targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"))
                        .contains("HumanSL companion SHA-256: " + EMPTY_FILE_SHA256));
                assertEquals(normalize(targetEngine), normalize(result.snapshot.enginePath));
                assertTrue(KataGoRuntimeHelper.inspectTensorRtInstall(result.snapshot).active);
              });
        });
  }

  @Test
  void legacyCudnn8EngineRemainsAnExternalFallbackAndIsNeverCopiedIntoTensorRt()
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-legacy-fallback");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createLegacyNvidiaSnapshot(tempRoot);
          Files.writeString(snapshot.enginePath, "legacy cuDNN 8 fixture");

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                touch(targetDir.resolve("katago.exe"));
                Path runtimeDir = runtimeWorkDirectory.resolve("nvidia-runtime");
                touchRequiredCuda12_8Dlls(runtimeDir);
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"),
                    "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifestWithoutCompanion(targetDir);
                Lizzie.config.leelazConfig.put(
                    "engine-settings-list",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("command", '"' + snapshot.enginePath.toString() + '"' + " gtp")));

                SetupResult result =
                    KataGoRuntimeHelper.downloadAndInstallTensorRt(
                        snapshot, null, new DownloadSession());

                assertFalse(
                    Files.exists(
                        targetDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)),
                    "A cuDNN 8 executable must never be copied as the TensorRT CUDA companion.");
                assertEquals(
                    normalize(snapshot.enginePath),
                    normalize(
                        KataGoRuntimeHelper.resolveHumanSlCudaCompanion(
                            result.snapshot.enginePath)));
                assertTrue(KataGoRuntimeHelper.inspectTensorRtInstall(result.snapshot).active);
              });
        });
  }

  @Test
  void unknownNvidia50ExecutableCannotBecomeTensorRtCompanion() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-unknown-companion");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
          Files.writeString(snapshot.enginePath, "unknown ABI fixture");

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                touch(targetDir.resolve("katago.exe"));
                Path runtimeDir = runtimeWorkDirectory.resolve("nvidia-runtime");
                touchRequiredCuda12_8Dlls(runtimeDir);
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"),
                    "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifestWithoutCompanion(targetDir);

                IOException failure =
                    assertThrows(
                        IOException.class,
                        () ->
                            KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                snapshot, null, new DownloadSession()));

                assertFalse(failure.getMessage().trim().isEmpty());
                assertFalse(
                    Files.exists(
                        targetDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
              });
        });
  }

  @Test
  void outdatedTensorRtEngineUpgradesWithoutRedownloadingRuntime() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-engine-upgrade");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
          Path targetDir =
              runtimeWorkDirectory
                  .resolve("engines")
                  .resolve("katago")
                  .resolve("windows-x64-nvidia-tensorrt");
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          touch(targetDir.resolve("katago.exe"));
          Files.writeString(
              targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
              "KataGo release: v1.17.1\n"
                  + "Asset SHA-256: "
                  + "b5de0178194cf728c12994cf0ace8a105597e864e0d42d7c6b4e0a1e9ea7a943\n");
          Files.writeString(
              targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
          touchRequiredCuda12_8Dlls(runtimeDir);
          touch(runtimeDir.resolve("nvinfer_10.dll"));
          touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
          Path runtimeSentinel = touch(runtimeDir.resolve("existing-runtime-sentinel.txt"));
          Path fixtureZip =
              createTensorRtFixtureZip(tempRoot.resolve("fixture").resolve("katago-trt.zip"));
          AtomicReference<Long> lastTotalBytes = new AtomicReference<Long>(-1L);

          withTensorRtFixtureProperties(
              fixtureZip.toUri().toString(),
              sha256(fixtureZip),
              Files.size(fixtureZip),
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        KataGoRuntimeHelper.TensorRtInstallStatus before =
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);

                        assertTrue(before.downloaded);
                        assertFalse(before.installed);
                        assertEquals(Files.size(fixtureZip), before.downloadBytes);
                        assertThrows(
                            IOException.class,
                            () -> KataGoRuntimeHelper.applyInstalledTensorRt(snapshot));

                        SetupResult result =
                            KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                snapshot,
                                (status, downloaded, total) -> lastTotalBytes.set(total),
                                new DownloadSession());

                        assertEquals("KataGo TensorRT", result.engineName);
                        assertEquals(Files.size(fixtureZip), lastTotalBytes.get());
                        assertTrue(Files.isRegularFile(runtimeSentinel));
                        assertTrue(
                            Files.readString(
                                    targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"))
                                .contains("KataGo release: v1.18.1"));
                        assertTrue(
                            KataGoRuntimeHelper.inspectTensorRtInstall(result.snapshot).active);
                      }));
        });
  }

  @Test
  void tensorRtStatusSeparatesDownloadedFromConfigured() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-status");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                touch(targetDir.resolve("katago.exe"));
                touch(targetDir.resolve("libz.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifest(targetDir);

                KataGoRuntimeHelper.TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);

                assertTrue(status.downloaded);
                assertFalse(status.installed, "Missing TensorRT runtime should not be ready.");
                assertFalse(status.active);
                assertTrue(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
              });
        });
  }

  @Test
  void oldTensorRtInstallWithoutNvrtcRequiresRuntimeMigration() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-missing-nvrtc");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                Path runtimeDir =
                    Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
                touch(targetDir.resolve("katago.exe"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifest(targetDir);
                touchCuda12CoreWithoutNvrtc(runtimeDir);
                touch(runtimeDir.resolve("cudnn64_9.dll"));
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                touch(runtimeDir.resolve("z.dll"));
                Files.writeString(
                    runtimeDir.resolve("manifest.txt"),
                    "CUDA NVRTC: 12.8.61\nfixture\nSHA-256: "
                        + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
                        + "\n");

                KataGoRuntimeHelper.NvidiaRuntimeStatus runtimeStatus =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(
                        targetDir.resolve("katago.exe"), "");
                KataGoRuntimeHelper.TensorRtInstallStatus installStatus =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);

                assertFalse(runtimeStatus.ready);
                assertTrue(runtimeStatus.missingDlls.contains("nvrtc64_120_0.dll"));
                assertTrue(runtimeStatus.missingDlls.contains("nvrtc-builtins64_128.dll"));
                assertTrue(installStatus.downloaded);
                assertFalse(installStatus.installed);
                assertTrue(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
              });
        });
  }

  @Test
  void tensorRtSplitPackageAppRootIsDetectedAsDownloadedAndActive() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-split-active");
          Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
          Path workingDir = Files.createDirectories(appRoot.resolve("user-data"));
          Path runtimeWorkDirectory = Files.createDirectories(workingDir.resolve("runtime"));
          Path targetDir =
              appRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia-tensorrt");
          Path enginePath = touch(targetDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(targetDir);
          touch(targetDir.resolve("nvinfer_10.dll"));
          touch(targetDir.resolve("nvinfer_plugin_10.dll"));
          Files.writeString(
              targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
          writeCurrentTensorRtEngineManifest(targetDir);
          Path configDir =
              Files.createDirectories(
                  appRoot.resolve("engines").resolve("katago").resolve("configs"));
          Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
          Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
          Path weightPath = touch(appRoot.resolve("weights").resolve("default.bin.gz"));
          SetupSnapshot snapshot =
              setupSnapshot(
                  workingDir,
                  appRoot,
                  enginePath,
                  gtpConfigPath,
                  analysisConfigPath,
                  weightPath);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);

                assertTrue(
                    status.downloaded, "Preinstalled TensorRT package should be downloaded.");
                assertTrue(status.installed, "Preinstalled TensorRT package should be ready.");
                assertTrue(
                    status.active, "Running from the TensorRT split package should be active.");
                assertEquals(normalize(enginePath), normalize(status.enginePath));
                assertFalse(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
              });
        });
  }

  @Test
  void tensorRtSplitPackageAppRootCanBeEnabledFromCudaSnapshot() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-split-enable");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
          Path targetDir =
              snapshot
                  .appRoot
                  .resolve("engines")
                  .resolve("katago")
                  .resolve("windows-x64-nvidia-tensorrt");
          Path tensorRtEnginePath = touch(targetDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(targetDir);
          touch(targetDir.resolve("nvinfer_10.dll"));
          touch(targetDir.resolve("nvinfer_plugin_10.dll"));
          Files.writeString(
              targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
          writeCurrentTensorRtEngineManifest(targetDir);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);

                assertTrue(status.downloaded);
                assertTrue(status.installed);
                assertFalse(status.active);
                assertTrue(KataGoRuntimeHelper.canInstallTensorRt(snapshot));

                SetupResult result = KataGoRuntimeHelper.applyInstalledTensorRt(snapshot);

                assertEquals("KataGo TensorRT", result.engineName);
                assertEquals(normalize(tensorRtEnginePath), normalize(result.snapshot.enginePath));
                KataGoRuntimeHelper.TensorRtInstallStatus activeStatus =
                    KataGoRuntimeHelper.inspectTensorRtInstall(result.snapshot);
                assertTrue(activeStatus.downloaded);
                assertTrue(activeStatus.installed);
                assertTrue(activeStatus.active);
              });
        });
  }

  @Test
  void switchBackToCudaKeepsTensorRtDownloadedButNotConfigured() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-back-to-cuda");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path targetDir =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt");
                Path runtimeDir = runtimeWorkDirectory.resolve("nvidia-runtime");
                touch(targetDir.resolve("katago.exe"));
                touch(targetDir.resolve("libz.dll"));
                touchRequiredCuda12_8Dlls(runtimeDir);
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
                writeCurrentTensorRtEngineManifest(targetDir);

                SetupResult tensorRtResult = KataGoRuntimeHelper.applyInstalledTensorRt(snapshot);
                assertEquals("KataGo TensorRT", tensorRtResult.engineName);
                assertTrue(KataGoRuntimeHelper.canSwitchBackToCuda(snapshot));

                SetupResult cudaResult = KataGoRuntimeHelper.applyBundledCudaProfile(snapshot);

                assertEquals("KataGo Auto Setup", cudaResult.engineName);
                assertEquals(
                    normalize(snapshot.enginePath), normalize(cudaResult.snapshot.enginePath));
                KataGoRuntimeHelper.TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
                assertTrue(status.downloaded);
                assertTrue(status.installed);
                assertFalse(status.active);
                assertFalse(KataGoRuntimeHelper.canSwitchBackToCuda(snapshot));
                assertTrue(
                    Utils.getEngineData().stream()
                        .anyMatch(
                            engine ->
                                "KataGo Auto Setup".equals(engine.name)
                                    && engine.isDefault
                                    && engine.commands.contains("windows-x64-nvidia")));
              });
        });
  }

  @Test
  void cancelledTensorRtInstallLeavesEngineProfileUnchanged() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-cancel");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createUnifiedNvidiaSnapshot(tempRoot);
          DownloadSession session = new DownloadSession();
          session.cancel();

          withTensorRtFixtureProperties(
              "http://127.0.0.1:9/never.zip",
              "",
              1L,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        assertThrows(
                            DownloadCancelledException.class,
                            () ->
                                KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                    snapshot, null, session));
                        assertFalse(
                            Files.exists(
                                runtimeWorkDirectory
                                    .resolve("engines")
                                    .resolve("katago")
                                    .resolve("windows-x64-nvidia-tensorrt")));
                        assertFalse(
                            Utils.getEngineData().stream()
                                .anyMatch(engine -> "KataGo TensorRT".equals(engine.name)));
                      }));
        });
  }

  @Test
  void smartOptimizeUsesBoundedOfficialBenchmarkArguments() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-benchmark-command");
    Path enginePath = touch(tempRoot.resolve("external-engine").resolve("katago"));
    Path gtpConfigPath = touch(tempRoot.resolve("configs").resolve("gtp.cfg"));
    Path analysisConfigPath = touch(tempRoot.resolve("configs").resolve("analysis.cfg"));
    Path weightPath = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    SetupSnapshot snapshot =
        setupSnapshot(
            tempRoot,
            tempRoot,
            enginePath,
            gtpConfigPath,
            analysisConfigPath,
            weightPath);

    withConfig(
        runtimeWorkDirectory,
        () -> {
          Lizzie.config.maxGameThinkingTimeSeconds = 12;
          List<String> command = KataGoRuntimeHelper.buildBenchmarkCommand(snapshot);

          assertEquals(normalize(enginePath).toString(), command.get(0));
          assertEquals("benchmark", command.get(1));
          assertTrue(command.contains("-s"), "Smart Optimize should keep KataGo official tuning.");
          assertOptionValue(command, "-n", "6");
          assertOptionValue(command, "-v", "800");
          assertOptionValue(command, "-time", "12");
        });
  }

  @Test
  void bundledLaunchCommandAddsHomeDataDirAndPvLengthOverride() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-bundled-command");
    Path enginePath =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

    withConfig(
        runtimeWorkDirectory,
        () -> {
          Lizzie.config.limitBranchLength = 32;
          List<String> command =
              KataGoRuntimeHelper.prepareBundledLaunchCommand(
                  Arrays.asList(enginePath.toString(), "gtp", "-config", "gtp.cfg"), enginePath);

          assertTrue(command.contains("-override-config"));
          String overrides = command.get(command.indexOf("-override-config") + 1);
          assertTrue(overrides.contains("homeDataDir="));
          assertTrue(overrides.contains("analysisPVLen=32"));
        });
  }

  @Test
  void legacyOpenClTuningCacheIsQuarantinedBeforeFreshFp16Tuning() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-opencl-tuning-generation");
          Path enginePath = createOpenClEngine(tempRoot);
          Path modelPath = touch(tempRoot.resolve("weights").resolve("current.bin.gz"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path homeDataDir = runtimeWorkDirectory.resolve("katago-home");
          Path legacyTuning =
              Files.createDirectories(homeDataDir.resolve("opencltuning"))
                  .resolve("tune11_gpuNVIDIA_x19_y19_c512_mv15.txt");
          Files.writeString(legacyTuning, "unsafe concurrent tuning");
          String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
          try {
            System.setProperty("lizzie.opencl.nvidiaDriverVersion", "560.76");
            withConfig(
                runtimeWorkDirectory,
                () -> {
                  List<String> command =
                      KataGoRuntimeHelper.prepareBundledLaunchCommand(
                          Arrays.asList(
                              enginePath.toString(),
                              "gtp",
                              "-model",
                              modelPath.toString(),
                              "-config",
                              "gtp.cfg",
                              "-override-config",
                              "numSearchThreads=2"),
                          enginePath);

                  String overrides = command.get(command.indexOf("-override-config") + 1);
                  assertTrue(
                      overrides.contains(
                          "homeDataDir=" + homeDataDir.toAbsolutePath().normalize()));
                  assertTrue(overrides.contains("numSearchThreads=2"));
                  assertFalse(overrides.contains("openclUseFP16=false"));
                  assertFalse(
                      KataGoRuntimeHelper.isOpenClFp32CompatibilityActive(command, enginePath));
                  assertFalse(Files.exists(legacyTuning));
                  Path quarantine = homeDataDir.resolve("opencltuning-legacy");
                  assertEquals(
                      "unsafe concurrent tuning",
                      Files.readString(quarantine.resolve(legacyTuning.getFileName())));
                  assertEquals(
                      "serialized-launch-v1",
                      Files.readString(homeDataDir.resolve("lizzie-opencl-tuning-generation.txt")));
                  assertTrue(KataGoRuntimeHelper.needsFirstOpenCLTuning(enginePath));

                  Path freshTuning =
                      Files.createDirectories(homeDataDir.resolve("opencltuning"))
                          .resolve("fresh.txt");
                  Files.writeString(freshTuning, "fresh serialized tuning");
                  KataGoRuntimeHelper.prepareBundledLaunchCommand(
                      Arrays.asList(
                          enginePath.toString(),
                          "gtp",
                          "-model",
                          modelPath.toString(),
                          "-config",
                          "gtp.cfg"),
                      enginePath);
                  assertEquals("fresh serialized tuning", Files.readString(freshTuning));
                });
          } finally {
            restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
          }
        });
  }

  @Test
  void currentNvidiaOpenClDriverKeepsNormalFp16PathWithoutFailureMarker() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-opencl-current-driver");
          Path enginePath = createOpenClEngine(tempRoot);
          Path modelPath = touch(tempRoot.resolve("weights").resolve("current.bin.gz"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
          try {
            System.setProperty("lizzie.opencl.nvidiaDriverVersion", "610.74");
            withConfig(
                runtimeWorkDirectory,
                () -> {
                  List<String> command =
                      KataGoRuntimeHelper.prepareBundledLaunchCommand(
                          Arrays.asList(
                              enginePath.toString(),
                              "gtp",
                              "-model",
                              modelPath.toString(),
                              "-config",
                              "gtp.cfg"),
                          enginePath);

                  String overrides = command.get(command.indexOf("-override-config") + 1);
                  assertTrue(
                      overrides.contains(
                          "homeDataDir="
                              + runtimeWorkDirectory
                                  .resolve("katago-home")
                                  .toAbsolutePath()
                                  .normalize()));
                  assertFalse(overrides.contains("openclUseFP16=false"));
                  assertFalse(
                      KataGoRuntimeHelper.isOpenClFp32CompatibilityActive(command, enginePath));
                });
          } finally {
            restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
          }
        });
  }

  @Test
  void explicitOpenClFp32OverrideUsesItsOwnTuningCache() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-opencl-explicit-fp32");
          Path enginePath = createOpenClEngine(tempRoot);
          Path modelPath = touch(tempRoot.resolve("weights").resolve("current.bin.gz"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          withConfig(
              runtimeWorkDirectory,
              () -> {
                List<String> command =
                    KataGoRuntimeHelper.prepareBundledLaunchCommand(
                        Arrays.asList(
                            enginePath.toString(),
                            "gtp",
                            "-model",
                            modelPath.toString(),
                            "-config",
                            "gtp.cfg",
                            "-override-config",
                            "homeDataDir=stale-fp16-cache,openclUseFP16=false"),
                        enginePath);

                String overrides = command.get(command.indexOf("-override-config") + 1);
                assertTrue(overrides.contains("openclUseFP16=false"));
                assertFalse(overrides.contains("homeDataDir=stale-fp16-cache"));
                assertTrue(
                    overrides.contains(
                        "homeDataDir="
                            + runtimeWorkDirectory
                                .resolve("katago-home-opencl-fp32")
                                .toAbsolutePath()
                                .normalize()));
                assertTrue(
                    KataGoRuntimeHelper.isOpenClFp32CompatibilityActive(command, enginePath));
              });
        });
  }

  @Test
  void nativeOpenClFastFailIsRememberedOnlyForMatchingDriverEngineAndModel() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-opencl-learned-fallback");
          Path enginePath = createOpenClEngine(tempRoot);
          Path modelPath = touch(tempRoot.resolve("weights").resolve("current.bin.gz"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          List<String> originalCommand =
              Arrays.asList(
                  enginePath.toString(),
                  "gtp",
                  "-model",
                  modelPath.toString(),
                  "-config",
                  "gtp.cfg");
          String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
          try {
            System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
            withConfig(
                runtimeWorkDirectory,
                () -> {
                  assertFalse(
                      KataGoRuntimeHelper.shouldRecoverOpenClNativeExit(
                          originalCommand, enginePath, 1, false));
                  assertTrue(
                      KataGoRuntimeHelper.shouldRecoverOpenClNativeExit(
                          originalCommand, enginePath, (int) 0xC0000409L, false));
                  assertFalse(
                      KataGoRuntimeHelper.shouldRecoverOpenClNativeExit(
                          originalCommand, enginePath, (int) 0xC0000409L, true));
                  assertTrue(
                      KataGoRuntimeHelper.rememberOpenClFp32Compatibility(
                          originalCommand, enginePath));

                  List<String> recovered =
                      KataGoRuntimeHelper.prepareBundledLaunchCommand(originalCommand, enginePath);
                  assertTrue(
                      KataGoRuntimeHelper.isOpenClFp32CompatibilityActive(recovered, enginePath));

                  Files.write(modelPath, new byte[] {1, 2, 3});
                  List<String> changedModel =
                      KataGoRuntimeHelper.prepareBundledLaunchCommand(originalCommand, enginePath);
                  assertFalse(
                      KataGoRuntimeHelper.isOpenClFp32CompatibilityActive(
                          changedModel, enginePath));
                });
          } finally {
            restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
          }
        });
  }

  @Test
  void katagoAnalysisCommandReceivesPvLengthOverride() throws Exception {
    Path runtimeWorkDirectory = Files.createTempDirectory("katago-helper-pvlen");
    withConfig(
        runtimeWorkDirectory,
        () -> {
          Lizzie.config.limitBranchLength = 28;
          String command =
              KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
                  "katago analysis -model model.bin.gz -config analysis.cfg", 100, false);

          assertTrue(command.contains("analysisPVLen=28"));
        });
  }

  @Test
  void cudaCompatibilityProbeCacheIsBoundToDriverEngineModelAndConfig() throws Exception {
    Path root = Files.createTempDirectory("katago-cuda-compatibility-signature");
    Path engine = Files.writeString(root.resolve("katago.exe"), "engine-v1");
    Path model = Files.writeString(root.resolve("default.bin.gz"), "model-v1");
    Path config = Files.writeString(root.resolve("gtp.cfg"), "config-v1");
    Path marker = root.resolve("cache/compatibility.txt");

    String original =
        KataGoRuntimeHelper.buildCudaCompatibilityProbeSignature(
            engine, model, config, "560.76");
    assertFalse(KataGoRuntimeHelper.hasMatchingCudaCompatibilityProbe(marker, original));

    KataGoRuntimeHelper.rememberCudaCompatibilityProbe(marker, original);
    assertTrue(KataGoRuntimeHelper.hasMatchingCudaCompatibilityProbe(marker, original));

    String changedDriver =
        KataGoRuntimeHelper.buildCudaCompatibilityProbeSignature(
            engine, model, config, "566.14");
    assertFalse(original.equals(changedDriver));
    assertFalse(KataGoRuntimeHelper.hasMatchingCudaCompatibilityProbe(marker, changedDriver));

    Files.writeString(model, "model-v2-with-different-size");
    String changedModel =
        KataGoRuntimeHelper.buildCudaCompatibilityProbeSignature(
            engine, model, config, "560.76");
    assertFalse(original.equals(changedModel));
    assertFalse(KataGoRuntimeHelper.hasMatchingCudaCompatibilityProbe(marker, changedModel));
  }

  @Test
  void cudaCompatibilityProbeUsesTheExactLaunchModelAndConfig() throws Exception {
    Path root = Files.createTempDirectory("katago-cuda-probe-command-inputs");
    Path engine = Files.writeString(root.resolve("katago.exe"), "engine");
    Path model = Files.writeString(root.resolve("custom-model.bin.gz"), "model");
    Path config = Files.writeString(root.resolve("custom-gtp.cfg"), "config");
    List<String> command =
        List.of(
            engine.toString(),
            "gtp",
            "-model",
            model.toString(),
            "-config",
            config.toString());

    KataGoRuntimeHelper.CudaCompatibilityProbeInputs inputs =
        KataGoRuntimeHelper.resolveCudaCompatibilityProbeInputs(engine, command);

    assertEquals(normalize(model), inputs.modelPath);
    assertEquals(normalize(config), inputs.configPath);
  }

  @Test
  void cudaCompatibilityProbeUsesTheSmallestKataGo118LegalInference() throws Exception {
    Path root = Files.createTempDirectory("katago-cuda-probe-command");
    Path engine = Files.writeString(root.resolve("katago.exe"), "engine");
    Path model = Files.writeString(root.resolve("model.bin.gz"), "model");
    Path config =
        Files.writeString(
            root.resolve("analysis.cfg"), "numSearchThreadsPerAnalysisThread = 16\n");
    KataGoRuntimeHelper.CudaCompatibilityProbeInputs inputs =
        KataGoRuntimeHelper.resolveCudaCompatibilityProbeInputs(
            engine,
            List.of(
                engine.toString(),
                "gtp",
                "-model",
                model.toString(),
                "-config",
                config.toString()));

    List<String> command =
        KataGoRuntimeHelper.buildCudaCompatibilityProbeCommand(engine, inputs);

    assertEquals("2", command.get(command.indexOf("-v") + 1));
    assertEquals("1", command.get(command.indexOf("-n") + 1));
    assertEquals("1", command.get(command.indexOf("-t") + 1));
    assertTrue(command.contains("-no-server-thread-test"));
    assertTrue(command.contains("-no-half-batch-size-test"));
    assertTrue(
        command.get(command.indexOf("-override-config") + 1).contains("numSearchThreads=1"),
        "KataGo benchmark requires numSearchThreads even when the source is an analysis config.");
  }

  @Test
  void nonKataGoAnalysisCommandKeepsOriginalText() throws Exception {
    Path runtimeWorkDirectory = Files.createTempDirectory("katago-helper-nonkatago");
    withConfig(
        runtimeWorkDirectory,
        () ->
            assertEquals(
                "leelaz --gtp",
                KataGoRuntimeHelper.optimizeAnalysisEngineCommand("leelaz --gtp", 100, false)));
  }

  @Test
  void benchmarkHeartbeatSmoothsLateSilentProgress() {
    int smoothed = KataGoRuntimeHelper.smoothSilentBenchmarkProgress(880, 12000L);

    assertTrue(smoothed >= 940, "Silent official benchmark output should still feel alive.");
    assertTrue(smoothed <= 970, "Heartbeat should leave room for the real final summary.");
  }

  @Test
  void benchmarkHeartbeatCarriesSyntheticProgressPastEightyEightPercent() {
    int firstSynthetic =
        KataGoRuntimeHelper.estimateSyntheticBenchmarkPermille(300_000L, 260_000L, 80);
    int secondSynthetic =
        KataGoRuntimeHelper.estimateSyntheticBenchmarkPermille(301_000L, 261_000L, firstSynthetic);
    int thirdSynthetic =
        KataGoRuntimeHelper.estimateSyntheticBenchmarkPermille(302_000L, 262_000L, secondSynthetic);

    assertEquals(880, firstSynthetic, "Five-minute fallback should reach the finalizing phase.");
    assertTrue(
        secondSynthetic >= 970,
        "The first heartbeat after 88% should keep advancing instead of staying stuck.");
    assertTrue(thirdSynthetic >= 985, "Long finalization should advance toward 99%.");
    assertTrue(thirdSynthetic < 1000, "Only the real benchmark completion may show 100%.");
  }

  @Test
  void benchmarkPrePositionHeartbeatDoesNotJumpAheadOfRealPositions() {
    assertEquals(
        90,
        KataGoRuntimeHelper.estimatePrePositionBenchmarkPermille(300_000L, 90),
        "Before KataGo reports position progress, the heartbeat should stay at the loading cap.");
    assertEquals(
        90,
        KataGoRuntimeHelper.estimatePrePositionBenchmarkPermille(30_000L, 80),
        "The heartbeat may reach the loading cap, but must not fake search progress.");
  }

  @Test
  void benchmarkProgressAdvancesByCompletedPositions() {
    KataGoRuntimeHelper.BenchmarkProgressTracker tracker =
        new KataGoRuntimeHelper.BenchmarkProgressTracker();

    assertEquals(30, tracker.update("Loading model and initializing"));
    assertEquals(80, tracker.update("Possible numbers of threads to test: 1 2 4 8"));

    int first = tracker.update("numSearchThreads = 4: 1/6 positions");
    int second = tracker.update("numSearchThreads = 4: 2/6 positions");
    int third = tracker.update("numSearchThreads = 4: 3/6 positions");
    int fourth = tracker.update("numSearchThreads = 4: 4/6 positions");
    int duplicate = tracker.update("numSearchThreads = 4: 4/6 positions");

    assertTrue(tracker.hasObservedPositionProgress());
    assertTrue(first > 90, "The first completed position should move past the loading cap.");
    assertTrue(fourth < 300, "Thread 4 progress should not jump near completion.");
    assertTrue(
        Math.abs((second - first) - (third - second)) <= 1,
        "Each completed position should add a stable amount of progress.");
    assertTrue(
        Math.abs((third - second) - (fourth - third)) <= 1,
        "Progress should advance by completed benchmark positions, not by elapsed time.");
    assertEquals(fourth, duplicate, "Repeating the same KataGo status line must not add progress.");
  }

  @Test
  void fixedThreadBenchmarkProgressUsesItsSingleExpectedThreadTest() {
    KataGoRuntimeHelper.BenchmarkProgressTracker tracker =
        new KataGoRuntimeHelper.BenchmarkProgressTracker(1);

    int completed = tracker.update("numSearchThreads = 6: 1/1 positions");

    assertTrue(
        completed >= 950,
        "A completed fixed-thread cell should nearly complete its segment instead of assuming 12 tests.");
  }

  @Test
  void katago118ExtraTuningStagesKeepProgressMovingAfterThreadSearch() {
    KataGoRuntimeHelper.BenchmarkProgressTracker tracker =
        new KataGoRuntimeHelper.BenchmarkProgressTracker();

    tracker.update("Possible numbers of threads to test: 1 2 4 6 8 10");
    tracker.update("numSearchThreads = 10: 6/6 positions");
    int summary = tracker.update("Ordered summary of results:");
    int extraStart =
        tracker.update(
            "Running additional tests of a few other settings at numSearchThreads = 10.");
    int baselineStart =
        tracker.update("Re-measuring the current recommendation as a baseline:");
    int baselineHalf = tracker.update("numSearchThreads = 10: 3/6 positions");
    int serverStart = tracker.update("Testing 2 NN server threads per GPU.");
    int serverDone = tracker.update("numSearchThreads = 10: 6/6 positions");
    int batchStart = tracker.update("Testing a max batch size of 5, half the search threads:");
    int batchDone = tracker.update("numSearchThreads = 10: 6/6 positions");
    int recommendation =
        tracker.update(
            "ADDITIONAL RECOMMENDATION: a smaller batch size measured faster. "
                + "To use this, set nnMaxBatchSize = 5 in your config.");

    assertEquals(795, summary);
    assertTrue(extraStart > summary);
    assertTrue(baselineStart >= extraStart);
    assertTrue(baselineHalf > baselineStart);
    assertTrue(serverStart > baselineHalf);
    assertTrue(serverDone > serverStart);
    assertTrue(batchStart > serverDone);
    assertTrue(batchDone > batchStart);
    assertEquals(990, recommendation);
  }

  @Test
  void appleSiliconStartupBenchmarkRespectsUserSwitch() throws Exception {
    withOsNameAndArch(
        "Mac OS X",
        "aarch64",
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-apple-switch");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createAppleSiliconSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Lizzie.config.enableStartupBenchmark = false;
                assertFalse(
                    KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot),
                    "Apple Silicon auto benchmark must respect the startup benchmark switch.");

                Lizzie.config.enableStartupBenchmark = true;
                assertTrue(
                    KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot),
                    "Fresh Apple Silicon setup should still offer the first auto benchmark.");
              });
        });
  }

  @Test
  void dismissedStartupBenchmarkDoesNotReappearForSameEngineAndWeight() throws Exception {
    withOsNameAndArch(
        "Mac OS X",
        "aarch64",
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-apple-dismiss");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createAppleSiliconSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Lizzie.config.enableStartupBenchmark = true;
                assertTrue(KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot));

                KataGoRuntimeHelper.rememberStartupBenchmarkDismissal(snapshot);

                assertTrue(KataGoRuntimeHelper.isStartupBenchmarkDismissed(snapshot));
                assertFalse(
                    KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot),
                    "Closing the auto benchmark notice should not nag again for the same setup.");
              });
        });
  }

  private static ProcessBuilder createProcessBuilder(Path directory, String pathValue) {
    ProcessBuilder processBuilder = new ProcessBuilder("echo");
    processBuilder.directory(directory.toFile());
    processBuilder.environment().put("PATH", pathValue);
    return processBuilder;
  }

  private static void withConfig(Path runtimeWorkDirectory, ThrowingRunnable action)
      throws Exception {
    Config previousConfig = Lizzie.config;
    String previousUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", runtimeWorkDirectory.toString());
      Lizzie.config = createTestConfig(runtimeWorkDirectory);
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

  @Test
  void wholeGameAnalysisUsesParallelPositionProfile() throws Exception {
    Path runtimeWorkDirectory = Files.createTempDirectory("katago-helper-whole-game");
    withConfig(
        runtimeWorkDirectory,
        () -> {
          String command =
              KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
                  "katago analysis -model model.bin.gz -config analysis.cfg", 500, false, true);

          assertTrue(command.contains("numAnalysisThreads="));
          assertTrue(command.contains("numSearchThreadsPerAnalysisThread="));
          assertTrue(command.contains("analysisPVLen="));
        });
  }

  @Test
  void wholeGameAnalysisRespectsExplicitThreadOverrides() throws Exception {
    Path runtimeWorkDirectory = Files.createTempDirectory("katago-helper-whole-game-override");
    withConfig(
        runtimeWorkDirectory,
        () -> {
          String command =
              KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
                  "katago analysis -model model.bin.gz -config analysis.cfg "
                      + "-override-config numAnalysisThreads=3,numSearchThreadsPerAnalysisThread=4",
                  500,
                  false,
                  true);

          assertEquals(1, occurrences(command, "numAnalysisThreads=3"));
          assertEquals(1, occurrences(command, "numSearchThreadsPerAnalysisThread=4"));
        });
  }

  private static Config createTestConfig(Path runtimeWorkDirectory) {
    Config config = ConfigTestHelper.createForTests(runtimeWorkDirectory);
    config.config = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.uiConfig = new JSONObject();
    config.config.put("leelaz", config.leelazConfig);
    config.config.put("ui", config.uiConfig);
    return config;
  }

  private static void withOsName(String osName, ThrowingRunnable action) throws Exception {
    String previousOsName = System.getProperty(OS_NAME_PROPERTY);
    try {
      System.setProperty(OS_NAME_PROPERTY, osName);
      action.run();
    } finally {
      restoreOsName(previousOsName);
    }
  }

  private static void withOsNameAndArch(String osName, String osArch, ThrowingRunnable action)
      throws Exception {
    String previousOsName = System.getProperty(OS_NAME_PROPERTY);
    String previousOsArch = System.getProperty(OS_ARCH_PROPERTY);
    try {
      System.setProperty(OS_NAME_PROPERTY, osName);
      System.setProperty(OS_ARCH_PROPERTY, osArch);
      action.run();
    } finally {
      restoreOsName(previousOsName);
      restoreProperty(OS_ARCH_PROPERTY, previousOsArch);
    }
  }

  private static void restoreOsName(String previousOsName) {
    if (previousOsName == null) {
      System.clearProperty(OS_NAME_PROPERTY);
      return;
    }
    System.setProperty(OS_NAME_PROPERTY, previousOsName);
  }

  private static Path firstPathEntry(String pathValue) {
    return Path.of(pathValue.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))[0])
        .toAbsolutePath()
        .normalize();
  }

  private static Path secondPathEntry(String pathValue) {
    return Path.of(pathValue.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))[1])
        .toAbsolutePath()
        .normalize();
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static Path createOpenClEngine(Path tempRoot) throws IOException {
    Path engineDir =
        Files.createDirectories(
            tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
    return touch(engineDir.resolve("katago.exe"));
  }

  private static void touchCuda12CoreWithoutNvrtc(Path directory) throws IOException {
    touch(directory.resolve("cudart64_12.dll"));
    touch(directory.resolve("cublas64_12.dll"));
    touch(directory.resolve("cublasLt64_12.dll"));
    touch(directory.resolve("nvJitLink64_12.dll"));
  }

  private static void touchCommonCuda12Dlls(Path directory) throws IOException {
    touchCuda12CoreWithoutNvrtc(directory);
    touch(directory.resolve("nvrtc64_120_0.dll"));
    touch(directory.resolve("nvrtc-builtins64_128.dll"));
  }

  private static void touchRequiredCuda12_8Dlls(Path directory) throws IOException {
    touchCommonCuda12Dlls(directory);
    touch(directory.resolve("cudnn64_9.dll"));
    touch(directory.resolve("z.dll"));
    Files.writeString(
        directory.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "Profile: cuda12.8-cudnn9\n"
            + "- CUDA NVRTC: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION
            + " | fixture | sha256="
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
            + "\n");
  }

  private static void touchRequiredLegacyCudaDlls(Path directory) throws IOException {
    touchCuda12CoreWithoutNvrtc(directory);
    touch(directory.resolve("cudnn64_8.dll"));
    touch(directory.resolve("nvrtc64_120_0.dll"));
    touch(directory.resolve("nvrtc-builtins64_121.dll"));
    touch(directory.resolve("z.dll"));
  }

  private static void writeCurrentTensorRtEngineManifest(Path directory) throws IOException {
    touch(directory.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
    writeCurrentTensorRtEngineManifestWithoutCompanion(directory);
    Files.writeString(
        directory.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
        "HumanSL companion: katago-human-sl-cuda.exe\n"
            + "HumanSL companion SHA-256: "
            + EMPTY_FILE_SHA256
            + "\n",
        StandardOpenOption.APPEND);
  }

  private static void writeCurrentTensorRtEngineManifestWithoutCompanion(Path directory)
      throws IOException {
    Files.writeString(
        directory.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
        "KataGo release: v1.18.1\n"
            + "Asset: katago-v1.18.1-trt10.9.0-cuda12.8-windows-x64.zip\n"
            + "Asset SHA-256: "
            + "49b7229803b2ccee5205cc9d1f7b1a37790469405324de5e5acaafe7a8a9172a\n");
  }

  private static SetupSnapshot createUnifiedNvidiaSnapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia"));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath);
  }

  private static SetupSnapshot createLegacyNvidiaSnapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia"));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia");
    touchRequiredLegacyCudaDlls(engineDir);
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath);
  }

  private static SetupSnapshot createAppleSiliconSnapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("macos-arm64"));
    Path enginePath = touch(engineDir.resolve("katago"));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath);
  }

  private static Path createTensorRtFixtureZip(Path zipPath) throws IOException {
    Files.createDirectories(zipPath.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      writeZipEntry(output, "katago.exe", "fake-katago");
      writeZipEntry(output, "libz.dll", "fake-libz");
      writeZipEntry(output, "cacert.pem", "fake-cert");
      writeZipEntry(output, "default_gtp.cfg", "ignored");
    }
    return zipPath;
  }

  private static void writeZipEntry(ZipOutputStream output, String name, String content)
      throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    output.closeEntry();
  }

  private static final class ResumableFixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastRangeHeader = new AtomicReference<>("");

    private ResumableFixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static ResumableFixtureServer start(byte[] bytes, int firstChunkSize)
        throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      ResumableFixtureServer fixture = new ResumableFixtureServer(server, executor);
      server.createContext(
          "/katago-trt.zip", exchange -> fixture.handle(exchange, bytes, firstChunkSize));
      server.setExecutor(executor);
      server.start();
      return fixture;
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/katago-trt.zip";
    }

    private String lastRangeHeader() {
      return lastRangeHeader.get();
    }

    private void handle(HttpExchange exchange, byte[] bytes, int firstChunkSize)
        throws IOException {
      int requestNumber = requests.incrementAndGet();
      String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
      if (rangeHeader != null) {
        lastRangeHeader.set(rangeHeader);
      }
      if (requestNumber == 1) {
        exchange.sendResponseHeaders(200, firstChunkSize);
        try (OutputStream body = exchange.getResponseBody()) {
          body.write(bytes, 0, firstChunkSize);
        } catch (IOException ignored) {
          // The client should keep this short .part file and resume it on the next attempt.
        }
        return;
      }

      int start = parseRangeStart(rangeHeader);
      if (start < 0 || start >= bytes.length) {
        exchange.sendResponseHeaders(416, -1);
        exchange.close();
        return;
      }
      exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
      exchange
          .getResponseHeaders()
          .set("Content-Range", "bytes " + start + "-" + (bytes.length - 1) + "/" + bytes.length);
      exchange.sendResponseHeaders(206, bytes.length - start);
      try (OutputStream body = exchange.getResponseBody()) {
        body.write(bytes, start, bytes.length - start);
      }
    }

    private static int parseRangeStart(String rangeHeader) {
      if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
        return 0;
      }
      int dash = rangeHeader.indexOf('-');
      String start = dash > 0 ? rangeHeader.substring("bytes=".length(), dash) : "";
      try {
        return Integer.parseInt(start);
      } catch (NumberFormatException e) {
        return -1;
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static void withTensorRtFixtureProperties(
      String url, String sha256, long size, ThrowingRunnable action) throws Exception {
    String previousUrl = System.getProperty("lizzie.tensorrt.katago.url");
    String previousSha = System.getProperty("lizzie.tensorrt.katago.sha256");
    String previousSize = System.getProperty("lizzie.tensorrt.katago.size");
    String previousSkip = System.getProperty("lizzie.tensorrt.skipRuntimePackagesForTests");
    try {
      System.setProperty("lizzie.tensorrt.katago.url", url);
      System.setProperty("lizzie.tensorrt.katago.sha256", sha256);
      System.setProperty("lizzie.tensorrt.katago.size", Long.toString(size));
      System.setProperty("lizzie.tensorrt.skipRuntimePackagesForTests", "true");
      action.run();
    } finally {
      restoreProperty("lizzie.tensorrt.katago.url", previousUrl);
      restoreProperty("lizzie.tensorrt.katago.sha256", previousSha);
      restoreProperty("lizzie.tensorrt.katago.size", previousSize);
      restoreProperty("lizzie.tensorrt.skipRuntimePackagesForTests", previousSkip);
    }
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, previousValue);
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] bytes = Files.readAllBytes(path);
    byte[] hash = digest.digest(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path weightPath,
      List<Path> weightCandidates)
      throws Exception {
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath,
        weightCandidates);
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path weightPath)
      throws Exception {
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath,
        Arrays.asList(weightPath));
  }

  private static void assertOptionValue(List<String> command, String option, String expectedValue) {
    int index = command.indexOf(option);
    assertTrue(index >= 0, "Expected benchmark option " + option);
    assertTrue(index + 1 < command.size(), "Expected a value after benchmark option " + option);
    assertEquals(expectedValue, command.get(index + 1));
  }

  private static int occurrences(String text, String value) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(value, index)) >= 0) {
      count++;
      index += value.length();
    }
    return count;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
