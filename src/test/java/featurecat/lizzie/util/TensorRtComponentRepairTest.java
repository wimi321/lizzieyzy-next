package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtActivationException;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.NvidiaGpuDetector.DetectionResult;
import featurecat.lizzie.util.NvidiaGpuDetector.GpuInfo;
import featurecat.lizzie.util.NvidiaGpuDetector.TensorRtRecommendation;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TensorRtComponentRepairTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String WINDOWS_OS_NAME = "Windows 11";
  private static final String EMPTY_FILE_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String FIXTURE_ENGINE_SHA256 =
      "aebec349fba1dcd086e336d96538d0d010f15bfc9667d6096ea08f928c732bdb";
  private static final String RTX_3060_ALLOWED_TEXT =
      "Optional: RTX 30 series and earlier may try TensorRT.";
  private static final String NOT_RECOMMENDED_TEXT =
      "Use CUDA: RTX 40/50 series run the unified CUDA package by default.";
  private static final String UNKNOWN_TEXT = "Could not confirm Compute Capability.";
  private static final String DIRECTML_ANALYSIS = "directml-analysis";

  @BeforeEach
  void acceptEmptyCompanionFixture() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(EMPTY_FILE_SHA256);
    KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(EMPTY_FILE_SHA256);
    System.setProperty("lizzie.tensorrt.runtimeSearchPath", "");
  }

  @AfterEach
  void restoreProductionCompanionDigest() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(null);
    KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(null);
    KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(null);
    KataGoRuntimeHelper.setTensorRtDirectoryCopyForTests(null);
    System.clearProperty("lizzie.tensorrt.runtimeSearchPath");
  }

  @Test
  void directMlSnapshotCanRepairRegardlessOfGpuAdvice() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-directml-gate");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                assertTrue(KataGoRuntimeHelper.canRepairTensorRt(snapshot));
                assertFalse(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
                assertFalse(KataGoRuntimeHelper.canActivateTensorRt(snapshot));

                TensorRtInstallStatus pending =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, null);
                TensorRtInstallStatus notRecommended =
                    KataGoRuntimeHelper.inspectTensorRtInstall(
                        snapshot,
                        detection(
                            new GpuInfo("NVIDIA GeForce RTX 4090", 8, 9, "570.65", 24576L, "test"),
                            TensorRtRecommendation.NOT_RECOMMENDED,
                            NOT_RECOMMENDED_TEXT));
                TensorRtInstallStatus unknown =
                    KataGoRuntimeHelper.inspectTensorRtInstall(
                        snapshot, detection(null, TensorRtRecommendation.UNKNOWN, UNKNOWN_TEXT));

                assertTrue(pending.repairable);
                assertTrue(notRecommended.repairable);
                assertTrue(unknown.repairable);
                assertTrue(KataGoRuntimeHelper.canRepairTensorRt(snapshot));
                assertEquals(NOT_RECOMMENDED_TEXT, notRecommended.gpuRecommendationText);
                assertEquals(UNKNOWN_TEXT, unknown.gpuRecommendationText);
                assertFalse(pending.gpuRecommendationText.isBlank());
              });
        });
  }

  @Test
  void missingRuntimeEngineAndCompanionRepairToReadyWithoutChangingProfiles() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-all-missing");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        String profilesBefore = profileFingerprint();

                        TensorRtInstallStatus before =
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, rtx3060Allowed());
                        assertTrue(before.repairable);
                        assertFalse(before.runtimeReady);
                        assertFalse(before.companionReady);
                        assertFalse(before.enginePresent);
                        assertFalse(before.activatable);

                        TensorRtInstallStatus repaired =
                            KataGoRuntimeHelper.repairTensorRtComponents(
                                snapshot, null, new DownloadSession());

                        assertTrue(repaired.runtimeReady);
                        assertTrue(repaired.companionReady);
                        assertTrue(repaired.enginePresent);
                        assertTrue(repaired.engineCurrent);
                        assertTrue(repaired.installed);
                        assertTrue(repaired.activatable);
                        assertFalse(repaired.profileActive);
                        assertFalse(repaired.active);
                        assertEquals(profilesBefore, profileFingerprint());
                        assertTrue(
                            Files.isRegularFile(
                                tensorRtEngineDir(runtimeWorkDirectory)
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
                        assertEquals(
                            EMPTY_FILE_SHA256,
                            sha256(
                                tensorRtEngineDir(runtimeWorkDirectory)
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
                      }));
        });
  }

  @Test
  void companionDigestMismatchFailsClosedAndValidCompanionIsReused() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-companion-digest");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);
          Path wrongCompanionZip =
              writeZip(
                  tempRoot.resolve("fixture").resolve("windows-nvidia-wrong.zip"),
                  "katago.exe",
                  "not-the-pinned-companion");

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        System.setProperty(
                            "lizzie.tensorrt.companion.url",
                            wrongCompanionZip.toUri().toString());
                        System.setProperty(
                            "lizzie.tensorrt.companion.sha256", sha256(wrongCompanionZip));
                        System.setProperty(
                            "lizzie.tensorrt.companion.size",
                            Long.toString(Files.size(wrongCompanionZip)));

                        assertThrows(
                            IOException.class,
                            () ->
                                KataGoRuntimeHelper.repairTensorRtComponents(
                                    snapshot, null, new DownloadSession()));
                        Path companion =
                            tensorRtEngineDir(runtimeWorkDirectory)
                                .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME);
                        assertFalse(
                            Files.isRegularFile(companion),
                            "Unverified companion executable must not be installed.");
                        assertFalse(
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot).companionReady);
                      }));

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession());
                        try (CountingFixtureServer server =
                            CountingFixtureServer.start(Files.readAllBytes(fixtures.companionZip))) {
                          System.setProperty("lizzie.tensorrt.companion.url", server.url());
                          System.setProperty(
                              "lizzie.tensorrt.companion.sha256", fixtures.companionSha256);
                          System.setProperty(
                              "lizzie.tensorrt.companion.size",
                              Long.toString(fixtures.companionSize));

                          TensorRtInstallStatus reused =
                              KataGoRuntimeHelper.repairTensorRtComponents(
                                  snapshot, null, new DownloadSession());
                          assertTrue(reused.companionReady);
                          assertEquals(0, server.requests());
                        }
                      }));
        });
  }

  @Test
  void repairFailuresAndCancelLeaveProfilesAndDirectMlSetupUnchanged() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-no-profile");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);
          DownloadSession cancelled = new DownloadSession();
          cancelled.cancel();

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        String profilesBefore = profileFingerprint();

                        assertThrows(
                            DownloadCancelledException.class,
                            () ->
                                KataGoRuntimeHelper.repairTensorRtComponents(
                                    snapshot, null, cancelled));
                        assertEquals(profilesBefore, profileFingerprint());

                        System.setProperty(
                            "lizzie.tensorrt.katago.url", "http://127.0.0.1:9/never.zip");
                        assertThrows(
                            IOException.class,
                            () ->
                                KataGoRuntimeHelper.repairTensorRtComponents(
                                    snapshot, null, new DownloadSession()));
                        assertEquals(profilesBefore, profileFingerprint());

                        System.setProperty(
                            "lizzie.tensorrt.katago.url", fixtures.engineZip.toUri().toString());
                        System.setProperty("lizzie.tensorrt.katago.sha256", EMPTY_FILE_SHA256);
                        assertThrows(
                            IOException.class,
                            () ->
                                KataGoRuntimeHelper.repairTensorRtComponents(
                                    snapshot, null, new DownloadSession()));
                        assertEquals(profilesBefore, profileFingerprint());
                        assertFalse(
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot).engineCurrent);
                      }));
        });
  }

  @Test
  void partialRuntimeStaysIncompleteUntilALaterRepairAndCompletedFilesAreReused()
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-partial-runtime");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              false,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        TensorRtInstallStatus first =
                            KataGoRuntimeHelper.repairTensorRtComponents(
                                snapshot, null, new DownloadSession());
                        assertTrue(first.engineCurrent);
                        assertTrue(first.companionReady);
                        assertFalse(first.runtimeReady);
                        assertTrue(first.repairable);
                        assertFalse(first.installed);

                        System.setProperty(
                            "lizzie.tensorrt.runtime.fixture.url",
                            fixtures.runtimeZip.toUri().toString());
                        System.setProperty(
                            "lizzie.tensorrt.runtime.fixture.sha256", fixtures.runtimeSha256);
                        System.setProperty(
                            "lizzie.tensorrt.runtime.fixture.size",
                            Long.toString(fixtures.runtimeSize));

                        TensorRtInstallStatus second =
                            KataGoRuntimeHelper.repairTensorRtComponents(
                                snapshot, null, new DownloadSession());
                        assertTrue(second.runtimeReady);
                        assertTrue(second.companionReady);
                        assertTrue(second.engineCurrent);
                        assertTrue(second.installed);
                        assertFalse(second.profileActive);
                      }));
        });
  }

  @Test
  void concurrentRepairIsRejectedAndFailedReplacementKeepsLastUsableEngine() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-lock-restore");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession());
                        Path engine =
                            tensorRtEngineDir(runtimeWorkDirectory).resolve("katago.exe");
                        String engineBefore = Files.readString(engine);

                        Path lockPath =
                            runtimeWorkDirectory
                                .resolve("nvidia-runtime")
                                .resolve("tensorrt-install.lock");
                        try (FileChannel lockChannel =
                                FileChannel.open(
                                    lockPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                            var ignored = lockChannel.lock()) {
                          IOException locked =
                              assertThrows(
                                  IOException.class,
                                  () ->
                                      KataGoRuntimeHelper.repairTensorRtComponents(
                                          snapshot, null, new DownloadSession()));
                          assertTrue(
                              locked.getMessage().toLowerCase(Locale.ROOT).contains("running")
                                  || locked.getMessage().contains("运行"));
                        }

                        Path emptyEngineZip =
                            writeZip(
                                tempRoot.resolve("fixture").resolve("katago-trt-empty.zip"),
                                "readme.txt",
                                "no-engine");
                        System.setProperty(
                            "lizzie.tensorrt.katago.url", emptyEngineZip.toUri().toString());
                        System.setProperty(
                            "lizzie.tensorrt.katago.sha256", sha256(emptyEngineZip));
                        System.setProperty(
                            "lizzie.tensorrt.katago.size",
                            Long.toString(Files.size(emptyEngineZip)));
                        Files.writeString(
                            tensorRtEngineDir(runtimeWorkDirectory)
                                .resolve("lizzieyzy-next-katago-engine-manifest.txt"),
                            "KataGo release: v1.0.0\nAsset SHA-256: " + EMPTY_FILE_SHA256 + "\n");

                        assertThrows(
                            IOException.class,
                            () ->
                                KataGoRuntimeHelper.repairTensorRtComponents(
                                    snapshot, null, new DownloadSession()));
                        assertEquals(engineBefore, Files.readString(engine));
                        assertTrue(Files.isRegularFile(engine));
                      }));
        });
  }

  @Test
  void failedStagingPromotionAndRestoreMoveFallsBackToLastKnownGoodCopy() throws Exception {
    assertLastKnownGoodSurvivesFailedPromotion(false);
  }

  @Test
  void completedRestoreMoveThatThrowsKeepsLastKnownGoodTarget() throws Exception {
    assertLastKnownGoodSurvivesFailedPromotion(true);
  }

  private void assertLastKnownGoodSurvivesFailedPromotion(boolean failAfterRestoreMove)
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-backup-restore");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession());
                        Path engineDir = tensorRtEngineDir(runtimeWorkDirectory);
                        Path engine = engineDir.resolve("katago.exe");
                        Files.writeString(engine, "last-known-good-engine");
                        Files.writeString(
                            engineDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
                            "KataGo release: v1.0.0\nAsset SHA-256: " + EMPTY_FILE_SHA256 + "\n");

                        KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(
                            (source, target) -> {
                              String sourceName = source.getFileName().toString();
                              if (sourceName.contains(".installing-")) {
                                throw new IOException("injected TensorRT promotion failure");
                              }
                              if (sourceName.contains(".backup-")) {
                                if (failAfterRestoreMove) {
                                  Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                                }
                                throw new IOException("injected TensorRT restore failure");
                              }
                              Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                            });
                        try {
                          assertThrows(
                              IOException.class,
                              () ->
                                  KataGoRuntimeHelper.repairTensorRtComponents(
                                      snapshot, null, new DownloadSession()));
                        } finally {
                          KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(null);
                        }

                        Path recoveredBackup = null;
                        boolean leftoverStaging = false;
                        String prefix = engineDir.getFileName().toString();
                        try (DirectoryStream<Path> children =
                            Files.newDirectoryStream(engineDir.getParent())) {
                          for (Path child : children) {
                            String name = child.getFileName().toString();
                            if (name.startsWith(prefix + ".installing-")) {
                              leftoverStaging = true;
                            }
                            Path backupEngine = child.resolve("katago.exe");
                            if (name.startsWith(prefix + ".backup-")
                                && Files.isDirectory(child)
                                && Files.isRegularFile(backupEngine)
                                && "last-known-good-engine"
                                    .equals(Files.readString(backupEngine))) {
                              recoveredBackup = child;
                            }
                          }
                        }
                        assertFalse(leftoverStaging, "staging cleanup must still run");
                        assertTrue(
                            Files.isRegularFile(engine),
                            "restore must keep the configured engine path usable");
                        assertEquals("last-known-good-engine", Files.readString(engine));
                        if (failAfterRestoreMove) {
                          assertTrue(
                              recoveredBackup == null,
                              "a completed restore move should consume the backup directory");
                        } else {
                          assertTrue(
                              recoveredBackup != null && Files.isDirectory(recoveredBackup),
                              "fallback restore must retain the last-known-good backup");
                          assertEquals(
                              "last-known-good-engine",
                              Files.readString(recoveredBackup.resolve("katago.exe")));
                        }
                      }));
        });
  }

  @Test
  void failedRestoreMoveAndCopyFallbackKeepsLastKnownGoodBackupAndPartialTarget()
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-restore-copy-double-fail");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession());
                        Path engineDir = tensorRtEngineDir(runtimeWorkDirectory);
                        Path engine = engineDir.resolve("katago.exe");
                        Files.writeString(engine, "last-known-good-engine");
                        Files.writeString(
                            engineDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
                            "KataGo release: v1.0.0\nAsset SHA-256: " + EMPTY_FILE_SHA256 + "\n");

                        KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(
                            (source, target) -> {
                              String sourceName = source.getFileName().toString();
                              if (sourceName.contains(".installing-")) {
                                throw new IOException("injected TensorRT promotion failure");
                              }
                              if (sourceName.contains(".backup-")) {
                                throw new IOException("injected TensorRT restore failure");
                              }
                              Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                            });
                        KataGoRuntimeHelper.setTensorRtDirectoryCopyForTests(
                            (source, target) -> {
                              Files.createDirectories(target);
                              Files.copy(
                                  source.resolve("katago.exe"),
                                  target.resolve("katago.exe"),
                                  StandardCopyOption.REPLACE_EXISTING);
                              Files.writeString(
                                  target.resolve("partial-restore.marker"), "copied-then-failed");
                              throw new IOException("injected TensorRT copy fallback failure");
                            });
                        IOException failure;
                        try {
                          failure =
                              assertThrows(
                                  IOException.class,
                                  () ->
                                      KataGoRuntimeHelper.repairTensorRtComponents(
                                          snapshot, null, new DownloadSession()));
                        } finally {
                          KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(null);
                          KataGoRuntimeHelper.setTensorRtDirectoryCopyForTests(null);
                        }

                        assertEquals(
                            "injected TensorRT promotion failure", failure.getMessage());
                        boolean sawRestore = false;
                        boolean sawCopy = false;
                        for (Throwable suppressed : failure.getSuppressed()) {
                          String message = suppressed.getMessage();
                          if ("injected TensorRT restore failure".equals(message)) {
                            sawRestore = true;
                          }
                          if ("injected TensorRT copy fallback failure".equals(message)) {
                            sawCopy = true;
                          }
                        }
                        assertTrue(
                            sawRestore,
                            "restore-move failure must be suppressed on the install error");
                        assertTrue(
                            sawCopy,
                            "copy-fallback failure must be suppressed on the install error");

                        Path recoveredBackup = null;
                        boolean leftoverStaging = false;
                        String prefix = engineDir.getFileName().toString();
                        try (DirectoryStream<Path> children =
                            Files.newDirectoryStream(engineDir.getParent())) {
                          for (Path child : children) {
                            String name = child.getFileName().toString();
                            if (name.startsWith(prefix + ".installing-")) {
                              leftoverStaging = true;
                            }
                            Path backupEngine = child.resolve("katago.exe");
                            if (name.startsWith(prefix + ".backup-")
                                && Files.isDirectory(child)
                                && Files.isRegularFile(backupEngine)
                                && "last-known-good-engine"
                                    .equals(Files.readString(backupEngine))) {
                              recoveredBackup = child;
                            }
                          }
                        }
                        assertFalse(leftoverStaging, "staging cleanup must still run");
                        assertTrue(
                            recoveredBackup != null && Files.isDirectory(recoveredBackup),
                            "double-failure restore must retain the last-known-good backup");
                        assertEquals(
                            "last-known-good-engine",
                            Files.readString(recoveredBackup.resolve("katago.exe")));
                        assertTrue(
                            Files.exists(engineDir),
                            "copy failure must not recursively delete the configured target");
                        assertTrue(
                            Files.isRegularFile(engineDir.resolve("partial-restore.marker")),
                            "partial copy contents must remain after copy fallback failure");
                        assertEquals(
                            "copied-then-failed",
                            Files.readString(engineDir.resolve("partial-restore.marker")));
                        assertTrue(Files.isRegularFile(engine));
                        assertEquals("last-known-good-engine", Files.readString(engine));
                      }));
        });
  }

  @Test
  void completedInitialBackupMoveThatThrowsRestoresLastKnownGoodTarget() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-initial-backup");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession());
                        Path engineDir = tensorRtEngineDir(runtimeWorkDirectory);
                        Path engine = engineDir.resolve("katago.exe");
                        Files.writeString(engine, "last-known-good-engine");
                        Files.writeString(
                            engineDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
                            "KataGo release: v1.0.0\nAsset SHA-256: "
                                + EMPTY_FILE_SHA256
                                + "\n");

                        KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(
                            (source, target) -> {
                              Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                              if (source.equals(engineDir)) {
                                throw new IOException(
                                    "injected completed TensorRT initial backup move failure");
                              }
                            });
                        IOException failure;
                        try {
                          failure =
                              assertThrows(
                                  IOException.class,
                                  () ->
                                      KataGoRuntimeHelper.repairTensorRtComponents(
                                          snapshot, null, new DownloadSession()));
                        } finally {
                          KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(null);
                        }

                        assertEquals(
                            "injected completed TensorRT initial backup move failure",
                            failure.getMessage());
                        assertTrue(
                            Files.isRegularFile(engine),
                            "failed initial backup move must restore the configured engine path");
                        assertEquals("last-known-good-engine", Files.readString(engine));
                        String prefix = engineDir.getFileName().toString();
                        try (DirectoryStream<Path> children =
                            Files.newDirectoryStream(engineDir.getParent())) {
                          for (Path child : children) {
                            assertFalse(
                                child.getFileName().toString().startsWith(prefix + ".installing-"),
                                "staging cleanup must still run");
                          }
                        }
                      }));
        });
  }

  @Test
  void onlyEnableTensorRtWritesTheProfileAndMissingItemsBlockActivation() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-activate");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        String profilesBefore = profileFingerprint();

                        TensorRtActivationException missing =
                            assertThrows(
                                TensorRtActivationException.class,
                                () -> KataGoRuntimeHelper.applyInstalledTensorRt(snapshot));
                        assertTrue(
                            missing.missingItems.contains(TensorRtInstallStatus.MISSING_RUNTIME));
                        assertTrue(
                            missing.missingItems.contains(TensorRtInstallStatus.MISSING_ENGINE));
                        assertTrue(
                            missing.missingItems.contains(TensorRtInstallStatus.MISSING_COMPANION));
                        assertFalse(KataGoRuntimeHelper.canActivateTensorRt(snapshot));
                        assertEquals(profilesBefore, profileFingerprint());

                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession());
                        assertEquals(profilesBefore, profileFingerprint());
                        assertTrue(KataGoRuntimeHelper.canActivateTensorRt(snapshot));

                        SetupSnapshot missingWeight =
                            setupSnapshot(
                                snapshot.workingDir,
                                snapshot.appRoot,
                                snapshot.enginePath,
                                snapshot.gtpConfigPath,
                                snapshot.analysisConfigPath,
                                snapshot.workingDir.resolve("weights").resolve("absent.bin.gz"));
                        TensorRtActivationException noWeight =
                            assertThrows(
                                TensorRtActivationException.class,
                                () -> KataGoRuntimeHelper.applyInstalledTensorRt(missingWeight));
                        assertEquals(
                            List.of(TensorRtInstallStatus.MISSING_WEIGHT), noWeight.missingItems);
                        assertEquals(profilesBefore, profileFingerprint());

                        KataGoRuntimeHelper.applyInstalledTensorRt(snapshot);
                        assertTrue(
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot).profileActive);
                        assertFalse(KataGoRuntimeHelper.canActivateTensorRt(snapshot));
                        assertTrue(profileFingerprint().contains("KataGo TensorRT"));
                      }));
        });
  }

  @Test
  void companionDownloadSizeIsCountedOnceOnProductionRepairPlan() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-repair-companion-size");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          RepairFixtures fixtures = createRepairFixtures(tempRoot);
          String previousKatagoUrl = System.getProperty("lizzie.tensorrt.katago.url");
          String previousKatagoSha = System.getProperty("lizzie.tensorrt.katago.sha256");
          String previousKatagoSize = System.getProperty("lizzie.tensorrt.katago.size");
          String previousSkip = System.getProperty("lizzie.tensorrt.skipRuntimePackagesForTests");
          String previousCompanionUrl = System.getProperty("lizzie.tensorrt.companion.url");
          String previousCompanionSha = System.getProperty("lizzie.tensorrt.companion.sha256");
          String previousCompanionSize = System.getProperty("lizzie.tensorrt.companion.size");
          String previousRuntimeUrl = System.getProperty("lizzie.tensorrt.runtime.fixture.url");
          try {
            KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(FIXTURE_ENGINE_SHA256);
            System.setProperty("lizzie.tensorrt.katago.url", fixtures.engineZip.toUri().toString());
            System.setProperty("lizzie.tensorrt.katago.sha256", fixtures.engineSha256);
            System.setProperty("lizzie.tensorrt.katago.size", Long.toString(fixtures.engineSize));
            System.clearProperty("lizzie.tensorrt.skipRuntimePackagesForTests");
            System.setProperty(
                "lizzie.tensorrt.companion.url", fixtures.companionZip.toUri().toString());
            System.setProperty("lizzie.tensorrt.companion.sha256", fixtures.companionSha256);
            System.setProperty(
                "lizzie.tensorrt.companion.size", Long.toString(fixtures.companionSize));
            System.clearProperty("lizzie.tensorrt.runtime.fixture.url");

            withConfig(
                runtimeWorkDirectory,
                () -> {
                  seedDirectMlProfiles(snapshot);
                  KataGoRuntimeHelper.TensorRtInstallSpec spec =
                      KataGoRuntimeHelper.buildTensorRtInstallSpec(snapshot);
                  assertTrue(spec.companionDownloadNeeded);
                  assertTrue(spec.companionSizeBytes > 0L);

                  AtomicLong firstTotal = new AtomicLong(-1L);
                  DownloadSession session = new DownloadSession();
                  assertThrows(
                      DownloadCancelledException.class,
                      () ->
                          KataGoRuntimeHelper.repairTensorRtComponents(
                              snapshot,
                              (status, downloaded, total) -> {
                                if (firstTotal.compareAndSet(-1L, total)) {
                                  session.cancel();
                                }
                              },
                              session));

                  assertEquals(
                      spec.totalDownloadBytes,
                      firstTotal.get(),
                      "companion size must be counted once in the live repair progress total");
                  assertEquals(
                      spec.totalDownloadBytes,
                      KataGoRuntimeHelper.inspectTensorRtInstall(snapshot).downloadBytes);
                });
          } finally {
            KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(EMPTY_FILE_SHA256);
            restoreProperty("lizzie.tensorrt.katago.url", previousKatagoUrl);
            restoreProperty("lizzie.tensorrt.katago.sha256", previousKatagoSha);
            restoreProperty("lizzie.tensorrt.katago.size", previousKatagoSize);
            restoreProperty("lizzie.tensorrt.skipRuntimePackagesForTests", previousSkip);
            restoreProperty("lizzie.tensorrt.companion.url", previousCompanionUrl);
            restoreProperty("lizzie.tensorrt.companion.sha256", previousCompanionSha);
            restoreProperty("lizzie.tensorrt.companion.size", previousCompanionSize);
            restoreProperty("lizzie.tensorrt.runtime.fixture.url", previousRuntimeUrl);
          }
        });
  }

  private static DetectionResult rtx3060Allowed() {
    return detection(
        new GpuInfo("NVIDIA GeForce RTX 3060", 8, 6, "570.65", 12288L, "test"),
        TensorRtRecommendation.ALLOWED,
        RTX_3060_ALLOWED_TEXT);
  }

  private static DetectionResult detection(
      GpuInfo gpu, TensorRtRecommendation recommendation, String detailText) {
    try {
      Constructor<DetectionResult> constructor =
          DetectionResult.class.getDeclaredConstructor(
              boolean.class,
              List.class,
              GpuInfo.class,
              TensorRtRecommendation.class,
              String.class,
              String.class);
      constructor.setAccessible(true);
      List<GpuInfo> gpus = gpu == null ? List.of() : List.of(gpu);
      return constructor.newInstance(
          gpu != null, gpus, gpu, recommendation, detailText, detailText);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SetupSnapshot createDirectMlSnapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("windows-x64-directml"));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "directml");
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir, appRoot, enginePath, gtpConfigPath, analysisConfigPath, weightPath);
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path weightPath)
      throws Exception {
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class, Path.class, Path.class, Path.class, Path.class, Path.class, List.class);
    constructor.setAccessible(true);
    List<Path> weights = weightPath == null ? List.of() : Arrays.asList(weightPath);
    return constructor.newInstance(
        workingDir, appRoot, enginePath, gtpConfigPath, analysisConfigPath, weightPath, weights);
  }

  private static Path tensorRtEngineDir(Path runtimeWorkDirectory) {
    return runtimeWorkDirectory
        .resolve("engines")
        .resolve("katago")
        .resolve("windows-x64-nvidia-tensorrt");
  }

  private static void seedDirectMlProfiles(SetupSnapshot snapshot) {
    Lizzie.config.leelazConfig.put(
        "engine-settings-list",
        new JSONArray()
            .put(
                new JSONObject()
                    .put("name", "DirectML")
                    .put("command", snapshot.enginePath + " gtp")
                    .put("preload", false)));
    Lizzie.config.uiConfig.put("default-engine", 0);
    Lizzie.config.uiConfig.put("autoload-default", true);
    Lizzie.config.uiConfig.put("autoload-empty", false);
    Lizzie.config.uiConfig.put("autoload-last", false);
    Lizzie.config.uiConfig.put("analysis-engine-command", DIRECTML_ANALYSIS);
    Lizzie.config.analysisEngineCommand = DIRECTML_ANALYSIS;
    Lizzie.config.analysisEngineCommandCustomized = true;
  }

  private static String profileFingerprint() {
    return Lizzie.config.leelazConfig.toString()
        + "|"
        + Lizzie.config.uiConfig.optInt("default-engine", -1)
        + "|"
        + Lizzie.config.uiConfig.optBoolean("autoload-default")
        + "|"
        + Lizzie.config.uiConfig.optString("analysis-engine-command")
        + "|"
        + String.valueOf(Lizzie.config.analysisEngineCommand);
  }

  private static RepairFixtures createRepairFixtures(Path tempRoot) throws Exception {
    Path fixtureDir = Files.createDirectories(tempRoot.resolve("fixture"));
    Path engineZip =
        writeZip(
            fixtureDir.resolve("katago-trt.zip"),
            "katago.exe",
            "fake-katago",
            "libz.dll",
            "fake-libz");
    Path companionZip = writeZip(fixtureDir.resolve("windows-nvidia.zip"), "katago.exe", "");
    Path runtimeZip = writeRuntimeFixtureZip(fixtureDir.resolve("nvidia-runtime.zip"));
    return new RepairFixtures(
        engineZip,
        sha256(engineZip),
        Files.size(engineZip),
        companionZip,
        sha256(companionZip),
        Files.size(companionZip),
        runtimeZip,
        sha256(runtimeZip),
        Files.size(runtimeZip));
  }

  private static Path writeRuntimeFixtureZip(Path zipPath) throws IOException {
    String[] dlls = {
      "cudart64_12.dll",
      "cublas64_12.dll",
      "cublasLt64_12.dll",
      "cudnn64_9.dll",
      "nvJitLink64_12.dll",
      "nvrtc64_120_0.dll",
      "nvrtc-builtins64_128.dll",
      "nvinfer_10.dll",
      "nvonnxparser_10.dll",
      "nvinfer_plugin_10.dll",
      "z.dll"
    };
    Files.createDirectories(zipPath.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (String dll : dlls) {
        output.putNextEntry(new ZipEntry(dll));
        output.closeEntry();
      }
    }
    return zipPath;
  }

  private static Path writeZip(Path zipPath, String... namesAndContents) throws IOException {
    Files.createDirectories(zipPath.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (int index = 0; index < namesAndContents.length; index += 2) {
        output.putNextEntry(new ZipEntry(namesAndContents[index]));
        output.write(namesAndContents[index + 1].getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return zipPath;
  }

  private static void withRepairFixtures(
      RepairFixtures fixtures, boolean includeRuntime, ThrowingRunnable action) throws Exception {
    String previousKatagoUrl = System.getProperty("lizzie.tensorrt.katago.url");
    String previousKatagoSha = System.getProperty("lizzie.tensorrt.katago.sha256");
    String previousKatagoSize = System.getProperty("lizzie.tensorrt.katago.size");
    String previousSkip = System.getProperty("lizzie.tensorrt.skipRuntimePackagesForTests");
    String previousCompanionUrl = System.getProperty("lizzie.tensorrt.companion.url");
    String previousCompanionSha = System.getProperty("lizzie.tensorrt.companion.sha256");
    String previousCompanionSize = System.getProperty("lizzie.tensorrt.companion.size");
    String previousRuntimeUrl = System.getProperty("lizzie.tensorrt.runtime.fixture.url");
    String previousRuntimeSha = System.getProperty("lizzie.tensorrt.runtime.fixture.sha256");
    String previousRuntimeSize = System.getProperty("lizzie.tensorrt.runtime.fixture.size");
    try {
      KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(FIXTURE_ENGINE_SHA256);
      System.setProperty("lizzie.tensorrt.katago.url", fixtures.engineZip.toUri().toString());
      System.setProperty("lizzie.tensorrt.katago.sha256", fixtures.engineSha256);
      System.setProperty("lizzie.tensorrt.katago.size", Long.toString(fixtures.engineSize));
      System.setProperty("lizzie.tensorrt.skipRuntimePackagesForTests", "true");
      System.setProperty("lizzie.tensorrt.companion.url", fixtures.companionZip.toUri().toString());
      System.setProperty("lizzie.tensorrt.companion.sha256", fixtures.companionSha256);
      System.setProperty("lizzie.tensorrt.companion.size", Long.toString(fixtures.companionSize));
      if (includeRuntime) {
        System.setProperty(
            "lizzie.tensorrt.runtime.fixture.url", fixtures.runtimeZip.toUri().toString());
        System.setProperty("lizzie.tensorrt.runtime.fixture.sha256", fixtures.runtimeSha256);
        System.setProperty(
            "lizzie.tensorrt.runtime.fixture.size", Long.toString(fixtures.runtimeSize));
      } else {
        System.clearProperty("lizzie.tensorrt.runtime.fixture.url");
        System.clearProperty("lizzie.tensorrt.runtime.fixture.sha256");
        System.clearProperty("lizzie.tensorrt.runtime.fixture.size");
      }
      action.run();
    } finally {
      KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(EMPTY_FILE_SHA256);
      restoreProperty("lizzie.tensorrt.katago.url", previousKatagoUrl);
      restoreProperty("lizzie.tensorrt.katago.sha256", previousKatagoSha);
      restoreProperty("lizzie.tensorrt.katago.size", previousKatagoSize);
      restoreProperty("lizzie.tensorrt.skipRuntimePackagesForTests", previousSkip);
      restoreProperty("lizzie.tensorrt.companion.url", previousCompanionUrl);
      restoreProperty("lizzie.tensorrt.companion.sha256", previousCompanionSha);
      restoreProperty("lizzie.tensorrt.companion.size", previousCompanionSize);
      restoreProperty("lizzie.tensorrt.runtime.fixture.url", previousRuntimeUrl);
      restoreProperty("lizzie.tensorrt.runtime.fixture.sha256", previousRuntimeSha);
      restoreProperty("lizzie.tensorrt.runtime.fixture.size", previousRuntimeSize);
    }
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, previousValue);
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(Files.readAllBytes(path));
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
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
      if (previousOsName == null) {
        System.clearProperty(OS_NAME_PROPERTY);
      } else {
        System.setProperty(OS_NAME_PROPERTY, previousOsName);
      }
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class RepairFixtures {
    final Path engineZip;
    final String engineSha256;
    final long engineSize;
    final Path companionZip;
    final String companionSha256;
    final long companionSize;
    final Path runtimeZip;
    final String runtimeSha256;
    final long runtimeSize;

    private RepairFixtures(
        Path engineZip,
        String engineSha256,
        long engineSize,
        Path companionZip,
        String companionSha256,
        long companionSize,
        Path runtimeZip,
        String runtimeSha256,
        long runtimeSize) {
      this.engineZip = engineZip;
      this.engineSha256 = engineSha256;
      this.engineSize = engineSize;
      this.companionZip = companionZip;
      this.companionSha256 = companionSha256;
      this.companionSize = companionSize;
      this.runtimeZip = runtimeZip;
      this.runtimeSha256 = runtimeSha256;
      this.runtimeSize = runtimeSize;
    }
  }

  private static final class CountingFixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();

    private CountingFixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static CountingFixtureServer start(byte[] bytes) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      CountingFixtureServer fixture = new CountingFixtureServer(server, executor);
      server.createContext(
          "/windows-nvidia.zip",
          exchange -> {
            fixture.requests.incrementAndGet();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
              body.write(bytes);
            }
          });
      server.setExecutor(executor);
      server.start();
      return fixture;
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/windows-nvidia.zip";
    }

    private int requests() {
      return requests.get();
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
