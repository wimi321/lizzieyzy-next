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
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class KataGoRuntimeHelperTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";
  private static final String PATH_SEPARATOR = System.getProperty("path.separator");
  private static final String WINDOWS_OS_NAME = "Windows 11";

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
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.TensorRtInstallSpec spec =
                    KataGoRuntimeHelper.buildTensorRtInstallSpec(snapshot);

                assertTrue(
                    spec.katagoUrl.endsWith("/katago-v1.16.4-trt10.9.0-cuda12.8-windows-x64.zip"));
                assertEquals(
                    "1dea0b507c6331c9a7cf4f0ed2eeee5384b880d60f1db7fe876506daee55830f",
                    spec.katagoSha256);
                assertEquals(5, spec.runtimePackageCount);
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
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);

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
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);
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
                        assertEquals(
                            "nvidia-tensorrt",
                            Files.readString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"))
                                .trim());
                        List<EngineData> engines = Utils.getEngineData();
                        assertTrue(
                            engines.stream()
                                .anyMatch(
                                    engine ->
                                        "KataGo TensorRT".equals(engine.name)
                                            && engine.isDefault
                                            && engine.commands.contains(
                                                "windows-x64-nvidia-tensorrt")));
                      }));
        });
  }

  @Test
  void tensorRtInstallResumesInterruptedDownloadPartFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-resume");
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);
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
                          })));
    }
  }

  @Test
  void tensorRtInstallRefusesConcurrentInstallerBeforeMutatingProfile() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-lock");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);
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
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);

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
                touchCommonCuda12Dlls(runtimeDir);
                touch(runtimeDir.resolve("cudnn64_9.dll"));
                touch(runtimeDir.resolve("nvinfer_10.dll"));
                touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
                Files.writeString(
                    targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");

                KataGoRuntimeHelper.NvidiaRuntimeStatus runtimeStatus =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(targetDir.resolve("katago.exe"));
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
  void tensorRtStatusSeparatesDownloadedFromConfigured() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-status");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);

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
  void switchBackToCudaKeepsTensorRtDownloadedButNotConfigured() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-back-to-cuda");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);

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
                                    && engine.commands.contains("windows-x64-nvidia50-cuda")));
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
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);
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
    Path estimateConfigPath = touch(tempRoot.resolve("configs").resolve("estimate.cfg"));
    Path weightPath = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    SetupSnapshot snapshot =
        setupSnapshot(
            tempRoot,
            tempRoot,
            enginePath,
            gtpConfigPath,
            analysisConfigPath,
            estimateConfigPath,
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

  private static void touchCommonCuda12Dlls(Path directory) throws IOException {
    touch(directory.resolve("cudart64_12.dll"));
    touch(directory.resolve("cublas64_12.dll"));
    touch(directory.resolve("cublasLt64_12.dll"));
    touch(directory.resolve("nvJitLink64_12.dll"));
  }

  private static void touchRequiredCuda12_8Dlls(Path directory) throws IOException {
    touchCommonCuda12Dlls(directory);
    touch(directory.resolve("cudnn64_9.dll"));
    touch(directory.resolve("libz.dll"));
  }

  private static SetupSnapshot createNvidia50Snapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia50-cuda"));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path estimateConfigPath = touch(configDir.resolve("estimate.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
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
    Path estimateConfigPath = touch(configDir.resolve("estimate.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
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
      Path estimateConfigPath,
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
            Path.class,
            List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        weightPath,
        weightCandidates);
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path estimateConfigPath,
      Path weightPath)
      throws Exception {
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        weightPath,
        Arrays.asList(weightPath));
  }

  private static void assertOptionValue(List<String> command, String option, String expectedValue) {
    int index = command.indexOf(option);
    assertTrue(index >= 0, "Expected benchmark option " + option);
    assertTrue(index + 1 < command.size(), "Expected a value after benchmark option " + option);
    assertEquals(expectedValue, command.get(index + 1));
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
