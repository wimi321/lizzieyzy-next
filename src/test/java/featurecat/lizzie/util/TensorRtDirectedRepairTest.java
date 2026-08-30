package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.KataGoAutoSetupDialog;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtFailureKind;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairSession;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRuntimeException;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtTargetInvalidException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TensorRtDirectedRepairTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String WINDOWS_OS_NAME = "Windows 11";
  private static final String EMPTY_FILE_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String DIRECTML_ANALYSIS = "directml-analysis";

  @BeforeEach
  void acceptEmptyCompanionFixture() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(EMPTY_FILE_SHA256);
    System.setProperty("lizzie.tensorrt.runtimeSearchPath", "");
  }

  @AfterEach
  void restoreProductionCompanionDigest() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(null);
    KataGoRuntimeHelper.setTensorRtDirectoryMoveForTests(null);
    KataGoRuntimeHelper.setTensorRtBeforeTargetMutationForTests(null);
    System.clearProperty("lizzie.tensorrt.runtimeSearchPath");
  }

  @Test
  void missingNvrtcAnalysisEngineStartupProducesStructuredContext() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-nvrtc");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String command = analysisCommand(enginePath);
                TensorRtRuntimeException failure =
                    assertThrows(
                        TensorRtRuntimeException.class,
                        () -> startAnalysisEngineReadinessCheck(command));

                TensorRtRepairContext context = failure.context;
                assertEquals(enginePath.toRealPath(), context.failedExecutable);
                assertEquals(command, context.originalCommand);
                assertEquals(TensorRtFailureKind.MISSING_RUNTIME, context.failureKind);
                assertEquals(List.of(TensorRtInstallStatus.MISSING_RUNTIME), context.missingItems);
                assertTrue(context.repairable);
                assertFalse(
                    context.displayMessage.isBlank(),
                    "Human-readable text is for display only and must stay populated.");
                assertSame(context, failure.context);
                assertTrue(KataGoRuntimeHelper.offersTensorRtRepairAction(failure));
              });
        });
  }

  @Test
  void missingCompanionAnalysisEngineStartupProducesStructuredContext() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-companion");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, false);
          installReadyNvidiaRuntime(runtimeWorkDirectory);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String command = analysisCommand(enginePath);
                TensorRtRuntimeException failure =
                    assertThrows(
                        TensorRtRuntimeException.class,
                        () -> startAnalysisEngineReadinessCheck(command));

                TensorRtRepairContext context = failure.context;
                assertEquals(enginePath.toRealPath(), context.failedExecutable);
                assertEquals(command, context.originalCommand);
                assertEquals(TensorRtFailureKind.MISSING_COMPANION, context.failureKind);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_COMPANION), context.missingItems);
                assertTrue(context.repairable);
                assertTrue(KataGoRuntimeHelper.offersTensorRtRepairAction(failure));
              });
        });
  }

  @Test
  void onlyRepairableManagedTensorRtFailuresOfferRepairAction() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-eligibility");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path managed = installManagedTensorRtEngine(runtimeWorkDirectory, true);
          Path cudaEngine =
              installManagedCudaEngine(runtimeWorkDirectory.resolve("cuda-engine-root"));
          Path custom =
              installCustomTensorRtEngine(tempRoot.resolve("custom").resolve("tensorrt-owner"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtRepairContext managedContext =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        managed, analysisCommand(managed));
                assertTrue(managedContext.repairable);
                assertTrue(
                    KataGoRuntimeHelper.offersTensorRtRepairAction(
                        new TensorRtRuntimeException(managedContext)));

                assertFalse(
                    KataGoRuntimeHelper.offersTensorRtRepairAction(new IOException("boom")));
                assertFalse(KataGoRuntimeHelper.offersTensorRtRepairAction(null));

                assertNull(
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        cudaEngine, analysisCommand(cudaEngine)));
                IOException cudaFailure =
                    assertThrows(
                        IOException.class,
                        () -> startAnalysisEngineReadinessCheck(analysisCommand(cudaEngine)));
                assertFalse(cudaFailure instanceof TensorRtRuntimeException);
                assertFalse(KataGoRuntimeHelper.offersTensorRtRepairAction(cudaFailure));

                TensorRtRepairContext customContext =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        custom, analysisCommand(custom));
                assertFalse(customContext.repairable);
                assertFalse(
                    KataGoRuntimeHelper.offersTensorRtRepairAction(
                        new TensorRtRuntimeException(customContext)));
                assertFalse(EngineFailedMessage.shouldOfferTensorRtRepair(customContext));
                assertTrue(EngineFailedMessage.shouldOfferTensorRtRepair(managedContext));
                assertFalse(EngineFailedMessage.shouldOfferTensorRtRepair(null));
              });
        });
  }

  @Test
  void directedRepairPinsFailedTargetAndLeavesProfilesUnchanged() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-inplace");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          Path failedEngine =
              installManagedTensorRtEngine(runtimeWorkDirectory, "windows-x64-nvidia50-trt", false);
          Path decoyDefault =
              installManagedTensorRtEngine(
                  runtimeWorkDirectory, "windows-x64-nvidia-tensorrt", false);
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
                        TensorRtRepairContext context =
                            KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                                failedEngine, analysisCommand(failedEngine));
                        assertEquals(failedEngine.toRealPath(), context.failedExecutable);

                        TensorRtInstallStatus defaultInspect =
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
                        assertEquals(
                            decoyDefault.toAbsolutePath().normalize(), defaultInspect.enginePath);

                        TensorRtInstallStatus directedInspect =
                            KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, null, context);
                        assertEquals(failedEngine.toRealPath(), directedInspect.enginePath);
                        assertTrue(directedInspect.repairable);
                        assertFalse(directedInspect.runtimeReady);
                        assertFalse(directedInspect.companionReady);

                        TensorRtInstallStatus repaired =
                            KataGoRuntimeHelper.repairTensorRtComponents(
                                snapshot, null, new DownloadSession(), context);

                        assertEquals(failedEngine.toRealPath(), repaired.enginePath);
                        assertTrue(repaired.runtimeReady);
                        assertTrue(repaired.companionReady);
                        assertTrue(repaired.enginePresent);
                        assertTrue(repaired.engineCurrent);
                        assertTrue(repaired.installed);
                        assertFalse(repaired.profileActive);
                        assertEquals(profilesBefore, profileFingerprint());
                        assertTrue(
                            Files.isRegularFile(
                                failedEngine
                                    .getParent()
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
                        assertFalse(
                            Files.isRegularFile(
                                decoyDefault
                                    .getParent()
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)),
                            "ordinary default destination must not be mutated in directed mode");
                        assertTrue(
                            repaired.detailText.contains(failedEngine.getFileName().toString())
                                || repaired.enginePath.toString().contains("nvidia50-trt"));
                      }));
        });
  }

  @Test
  void staleMovedOrEscapedTargetsFailClosedAndClearDirectedMode() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-failclosed");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true);
          Path outside = Files.createDirectories(tempRoot.resolve("outside"));
          Path escapedEngine = touch(outside.resolve("katago.exe"));
          Files.writeString(
              outside.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtRepairContext context =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        enginePath, analysisCommand(enginePath));
                TensorRtRepairSession session = new TensorRtRepairSession();
                session.apply(context);
                assertTrue(session.isDirected());

                Files.delete(enginePath);
                assertThrows(
                    TensorRtTargetInvalidException.class,
                    () -> KataGoRuntimeHelper.requireValidDirectedTensorRtTarget(context));
                assertThrows(
                    TensorRtTargetInvalidException.class,
                    () ->
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            createDirectMlSnapshot(tempRoot),
                            null,
                            new DownloadSession(),
                            context));
                assertTrue(session.clearIfTargetInvalid());
                assertFalse(session.isDirected());
                assertNull(session.context());

                Path movedEngine = installManagedTensorRtEngine(runtimeWorkDirectory, true);
                TensorRtRepairContext movedContext =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        movedEngine, analysisCommand(movedEngine));
                Path relocated =
                    Files.createDirectories(tempRoot.resolve("relocated")).resolve("katago.exe");
                Files.move(movedEngine, relocated);
                session.apply(movedContext);
                assertThrows(
                    TensorRtTargetInvalidException.class,
                    () -> KataGoRuntimeHelper.requireValidDirectedTensorRtTarget(movedContext));
                assertTrue(session.clearIfTargetInvalid());

                Path traversal =
                    runtimeWorkDirectory
                        .resolve("engines")
                        .resolve("katago")
                        .resolve("windows-x64-nvidia-tensorrt")
                        .resolve("..")
                        .resolve("..")
                        .resolve("..")
                        .resolve("..")
                        .resolve("outside")
                        .resolve("katago.exe");
                TensorRtRepairContext traversalContext =
                    TensorRtRepairContext.of(
                        traversal,
                        analysisCommand(traversal),
                        TensorRtFailureKind.MISSING_RUNTIME,
                        List.of(TensorRtInstallStatus.MISSING_RUNTIME),
                        true,
                        "display");
                assertThrows(
                    TensorRtTargetInvalidException.class,
                    () -> KataGoRuntimeHelper.requireValidDirectedTensorRtTarget(traversalContext));
              });
        });
  }

  @Test
  void symlinkedTargetsFailClosedAndClearDirectedMode() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-symlink");
          assumeTrue(
              supportsSymbolicLinks(tempRoot),
              "symbolic links require Windows Developer Mode or elevated privileges");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path escapedEngine = touch(tempRoot.resolve("outside").resolve("katago.exe"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Path managedDir =
                    Files.createDirectories(
                        runtimeWorkDirectory
                            .resolve("engines")
                            .resolve("katago")
                            .resolve("windows-x64-nvidia-tensorrt"));
                Path symlinkEngine = managedDir.resolve("katago.exe");
                Files.createSymbolicLink(symlinkEngine, escapedEngine.toAbsolutePath());
                TensorRtRepairContext symlinkContext =
                    TensorRtRepairContext.of(
                        symlinkEngine,
                        analysisCommand(symlinkEngine),
                        TensorRtFailureKind.MISSING_RUNTIME,
                        List.of(TensorRtInstallStatus.MISSING_RUNTIME),
                        true,
                        "display");
                assertThrows(
                    TensorRtTargetInvalidException.class,
                    () -> KataGoRuntimeHelper.requireValidDirectedTensorRtTarget(symlinkContext));
                TensorRtRepairSession session = new TensorRtRepairSession();
                session.apply(symlinkContext);
                assertTrue(session.clearIfTargetInvalid());
                assertFalse(session.isDirected());
              });
        });
  }

  @Test
  void staleDirectedRepairClickDoesNotFallBackToAnotherManagedDirectory() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-stale-click");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          Path failedEngine =
              installManagedTensorRtEngine(runtimeWorkDirectory, "windows-x64-nvidia50-trt", false);
          Path decoyDefault =
              installManagedTensorRtEngine(
                  runtimeWorkDirectory, "windows-x64-nvidia-tensorrt", false);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtRepairContext context =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        failedEngine, analysisCommand(failedEngine));
                TensorRtRepairSession session = new TensorRtRepairSession();
                session.apply(context);
                Files.delete(failedEngine);

                assertTrue(session.clearIfTargetInvalid());
                assertFalse(session.isDirected());

                TensorRtInstallStatus ordinary =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
                assertEquals(decoyDefault.toAbsolutePath().normalize(), ordinary.enginePath);
                assertTrue(ordinary.repairable);
                assertTrue(KataGoRuntimeHelper.canRepairTensorRt(snapshot, null));

                assertFalse(
                    KataGoRuntimeHelper.shouldStartTensorRtComponentRepair(context),
                    "stale directed Repair click must not fall through to ordinary discovery");
                assertTrue(
                    KataGoRuntimeHelper.shouldStartTensorRtComponentRepair(null),
                    "ordinary no-context repair remains available after leaving directed mode");
                assertThrows(
                    TensorRtTargetInvalidException.class,
                    () ->
                        KataGoRuntimeHelper.repairTensorRtComponents(
                            snapshot, null, new DownloadSession(), context));
                assertFalse(
                    Files.isRegularFile(
                        decoyDefault
                            .getParent()
                            .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
              });
        });
  }

  @Test
  void directedTargetInvalidatedAtMutationBoundaryDoesNotMutateDefaultOrOutside()
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-post-download");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          Path failedEngine =
              installManagedTensorRtEngine(runtimeWorkDirectory, "windows-x64-nvidia50-trt", false);
          Path decoyDefault =
              installManagedTensorRtEngine(
                  runtimeWorkDirectory, "windows-x64-nvidia-tensorrt", false);
          Path outside = Files.createDirectories(tempRoot.resolve("outside"));
          Path relocatedEngine = outside.resolve("relocated-katago.exe");
          RepairFixtures fixtures = createRepairFixtures(tempRoot);
          AtomicBoolean invalidatedAtMutationBoundary = new AtomicBoolean();

          withRepairFixtures(
              fixtures,
              true,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        TensorRtRepairContext context =
                            KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                                failedEngine, analysisCommand(failedEngine));
                        KataGoRuntimeHelper.requireValidDirectedTensorRtTarget(context);
                        String decoyBefore = Files.readString(decoyDefault);

                        KataGoRuntimeHelper.setTensorRtBeforeTargetMutationForTests(
                            () -> {
                              if (invalidatedAtMutationBoundary.compareAndSet(false, true)) {
                                Files.move(failedEngine, relocatedEngine);
                              }
                            });

                        assertThrows(
                            TensorRtTargetInvalidException.class,
                            () ->
                                KataGoRuntimeHelper.repairTensorRtComponents(
                                    snapshot, null, new DownloadSession(), context));

                        assertTrue(invalidatedAtMutationBoundary.get());
                        assertTrue(Files.isRegularFile(relocatedEngine));
                        assertEquals("", Files.readString(relocatedEngine));
                        assertFalse(Files.isRegularFile(failedEngine));
                        assertFalse(
                            Files.isRegularFile(
                                failedEngine
                                    .getParent()
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
                        assertEquals(decoyBefore, Files.readString(decoyDefault));
                        assertFalse(
                            Files.isRegularFile(
                                decoyDefault
                                    .getParent()
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)),
                            "ordinary default destination must not be mutated on stale directed context");
                      }));
        });
  }

  @Test
  void repairSessionClearsContextAndDoesNotPersist() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-session");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                seedDirectMlProfiles(createDirectMlSnapshot(tempRoot));
                String profilesBefore = profileFingerprint();
                String configBefore = Lizzie.config.config.toString();
                TensorRtRepairContext context =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        enginePath, analysisCommand(enginePath));

                TensorRtRepairSession session = new TensorRtRepairSession();
                session.apply(context);
                assertSame(context, session.context());
                assertTrue(session.isDirected());
                assertEquals(3, session.setupSectionIndex());

                session.clearAfterSuccessfulRepair();
                assertFalse(session.isDirected());
                session.apply(context);
                session.clearAfterClose();
                assertFalse(session.isDirected());
                session.apply(context);
                session.clearAfterChooseOtherEngine();
                assertFalse(session.isDirected());

                assertEquals(profilesBefore, profileFingerprint());
                assertEquals(configBefore, Lizzie.config.config.toString());
                assertFalse(Lizzie.config.uiConfig.toString().contains("tensorRtRepair"));
                assertFalse(Lizzie.config.leelazConfig.toString().contains("failedExecutable"));

                TensorRtRepairSession reopened = new TensorRtRepairSession();
                assertFalse(reopened.isDirected());
                assertNull(reopened.context());
                assertEquals(0, reopened.setupSectionIndex());
              });
        });
  }

  @Test
  void repairActionRoutesSameContextToAccelerationSection() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-directed-route");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtRepairContext context =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        enginePath, analysisCommand(enginePath));
                KataGoAutoSetupDialog.OpenRequest repair =
                    KataGoAutoSetupDialog.openRequestForRepair(context);
                assertSame(context, repair.context);
                assertEquals(3, repair.sectionIndex);
                assertTrue(repair.directed);

                KataGoAutoSetupDialog.OpenRequest menu = KataGoAutoSetupDialog.openRequestForMenu();
                assertNull(menu.context);
                assertEquals(0, menu.sectionIndex);
                assertFalse(menu.directed);

                assertTrue(EngineFailedMessage.shouldOfferTensorRtRepair(context));
                assertEquals(
                    Lizzie.resourceBundle.getString("EngineFailedMessage.openTensorRtRepair"),
                    EngineFailedMessage.tensorRtRepairActionLabel());
                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                  EngineFailedMessage dialog =
                      new EngineFailedMessage(
                          List.of(enginePath.toString(), "analysis"),
                          analysisCommand(enginePath),
                          context.displayMessage,
                          false,
                          false,
                          false,
                          context);
                  assertTrue(dialog.offersTensorRtRepair());
                  assertSame(context, dialog.repairContext());
                  assertEquals(
                      EngineFailedMessage.tensorRtRepairActionLabel(),
                      dialog.tensorRtRepairButton().getText());

                  EngineFailedMessage ordinary =
                      new EngineFailedMessage(
                          List.of("engine.exe"),
                          "engine.exe gtp",
                          "ordinary startup error",
                          false,
                          false,
                          false);
                  assertFalse(ordinary.offersTensorRtRepair());
                  assertNull(ordinary.repairContext());
                  assertNull(ordinary.tensorRtRepairButton());
                }
                assertFalse(EngineFailedMessage.shouldOfferTensorRtRepair(null));
              });
        });
  }

  @Test
  void primaryAndSecondaryGtpMissingRuntimeProduceSameContextAsAnalysisEngine() throws Exception {
    assumeTrue(GraphicsEnvironment.isHeadless());
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-gtp-nvrtc");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String analysis = analysisCommand(enginePath);
                TensorRtRuntimeException analysisFailure =
                    assertThrows(
                        TensorRtRuntimeException.class,
                        () -> startAnalysisEngineReadinessCheck(analysis));
                TensorRtRepairContext expected = analysisFailure.context;

                String gtp = gtpCommand(enginePath);
                Lizzie.config.leelazConfig.put(
                    "engine-settings-list",
                    new JSONArray()
                        .put(new JSONObject().put("name", "Primary TRT").put("command", gtp))
                        .put(new JSONObject().put("name", "Secondary TRT").put("command", gtp)));
                Leelaz previousPrimary = Lizzie.leelaz;
                Leelaz previousSecondary = Lizzie.leelaz2;
                boolean previousFirstLaunch = forceFirstLaunchSession(true);
                try {
                  Leelaz primary = new Leelaz(gtp);
                  Lizzie.setPrimaryEngine(primary);
                  primary.startEngine(0);

                  TensorRtRepairContext primaryContext = pendingTensorRtRepairContext(primary);
                  assertEquals(expected.failedExecutable, primaryContext.failedExecutable);
                  assertEquals(gtp, primaryContext.originalCommand);
                  assertEquals(expected.failureKind, primaryContext.failureKind);
                  assertEquals(expected.missingItems, primaryContext.missingItems);
                  assertEquals(expected.repairable, primaryContext.repairable);
                  assertTrue(primaryContext.repairable);
                  assertTrue(primary.isDownWithError);
                  assertFalse(primary.started);
                  assertFalse(primary.isLoaded);

                  Leelaz decoyPrimary = new Leelaz("");
                  Leelaz secondary = new Leelaz(gtp);
                  Lizzie.setPrimaryEngine(decoyPrimary);
                  Lizzie.leelaz2 = secondary;
                  secondary.startEngine(1);

                  TensorRtRepairContext secondaryContext = pendingTensorRtRepairContext(secondary);
                  assertEquals(expected.failedExecutable, secondaryContext.failedExecutable);
                  assertEquals(gtp, secondaryContext.originalCommand);
                  assertEquals(expected.failureKind, secondaryContext.failureKind);
                  assertEquals(expected.missingItems, secondaryContext.missingItems);
                  assertEquals(expected.repairable, secondaryContext.repairable);
                  assertTrue(secondary.isDownWithError);
                  assertFalse(secondary.started);
                  assertFalse(secondary.isLoaded);
                } finally {
                  forceFirstLaunchSession(previousFirstLaunch);
                  Lizzie.setPrimaryEngine(previousPrimary);
                  Lizzie.leelaz2 = previousSecondary;
                  Lizzie.engineStartupStatus.ready();
                }
              });
        });
  }

  @Test
  void gtpRepairableFailuresOpenTheSameDialogAndSetupEntryAsAnalysisEngine() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-gtp-dialog");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String gtp = gtpCommand(enginePath);
                TensorRtRepairContext context =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(enginePath, gtp);
                assertTrue(context.repairable);
                assertTrue(EngineFailedMessage.shouldOfferTensorRtRepair(context));
                assertTrue(Leelaz.shouldOpenInteractiveDiagnostic(true, true, context));
                assertTrue(Leelaz.shouldOpenInteractiveDiagnostic(true, false, context));
                assertTrue(Leelaz.shouldOpenInteractiveDiagnostic(false, true, context));
                assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(true, true, null));
                assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(true, false, null));
                assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(false, true, null));
                assertTrue(Leelaz.shouldOpenInteractiveDiagnostic(false, false, null));
                assertFalse(
                    Leelaz.shouldOpenInteractiveDiagnostic(
                        true, true, unrepairableContext(enginePath)));

                KataGoAutoSetupDialog.OpenRequest repair =
                    KataGoAutoSetupDialog.openRequestForRepair(context);
                assertSame(context, repair.context);
                assertEquals(3, repair.sectionIndex);
                assertTrue(repair.directed);

                KataGoAutoSetupDialog.OpenRequest status =
                    KataGoAutoSetupDialog.openRequestForEngineStartupStatus(true, context);
                assertSame(context, status.context);
                assertEquals(3, status.sectionIndex);
                assertTrue(status.directed);
                assertFalse(
                    KataGoAutoSetupDialog.openRequestForEngineStartupStatus(false, context)
                        .directed);
                assertFalse(
                    KataGoAutoSetupDialog.openRequestForEngineStartupStatus(true, null).directed);

                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                  EngineFailedMessage dialog =
                      new EngineFailedMessage(
                          List.of(enginePath.toString(), "gtp"),
                          gtp,
                          context.displayMessage,
                          true,
                          true,
                          false,
                          context);
                  assertTrue(dialog.offersTensorRtRepair());
                  assertSame(context, dialog.repairContext());
                }

                seedEngineSettings("Primary TRT", gtp);
                String profilesBefore = profileFingerprint();
                assumeTrue(GraphicsEnvironment.isHeadless());
                Leelaz previousPrimary = Lizzie.leelaz;
                boolean previousFirstLaunch = forceFirstLaunchSession(true);
                try {
                  Leelaz primary = new Leelaz(gtp);
                  Lizzie.setPrimaryEngine(primary);
                  primary.startEngine(0);
                  TensorRtRepairContext pending = primary.pendingTensorRtRepairContext();
                  assertEquals(context.failedExecutable, pending.failedExecutable);
                  assertEquals(gtp, pending.originalCommand);
                  assertTrue(pending.repairable);
                  KataGoAutoSetupDialog.OpenRequest fromStatus =
                      KataGoAutoSetupDialog.openRequestForEngineStartupStatus(true, pending);
                  assertSame(pending, fromStatus.context);
                  assertTrue(fromStatus.directed);
                  assertEquals(profilesBefore, profileFingerprint());
                  assertTrue(primary.isDownWithError);
                  assertFalse(primary.started);
                  assertFalse(primary.isLoaded);
                } finally {
                  forceFirstLaunchSession(previousFirstLaunch);
                  Lizzie.setPrimaryEngine(previousPrimary);
                  Lizzie.engineStartupStatus.ready();
                }
              });
        });
  }

  @Test
  void customDirectMlOpenClOrdinaryContributeAndRemoteGtpDoNotAutoRepair() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-gtp-negative");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path custom = installCustomTensorRtEngine(tempRoot.resolve("custom").resolve("owner"));
          Path cuda = installManagedCudaEngine(runtimeWorkDirectory.resolve("cuda-engine-root"));
          Path directMl = installManagedDirectMlEngine(runtimeWorkDirectory);
          Path openCl = installManagedOpenClEngine(runtimeWorkDirectory);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtRepairContext customContext =
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(custom, gtpCommand(custom));
                assertFalse(customContext.repairable);
                assertFalse(EngineFailedMessage.shouldOfferTensorRtRepair(customContext));
                assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(true, true, customContext));
                assertFalse(KataGoAutoSetupDialog.openRequestForRepair(customContext).directed);
                assertFalse(
                    KataGoAutoSetupDialog.openRequestForEngineStartupStatus(true, customContext)
                        .directed);

                assertNull(
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(cuda, gtpCommand(cuda)));
                assertNull(
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(
                        directMl, gtpCommand(directMl)));
                assertNull(
                    KataGoRuntimeHelper.inspectTensorRtStartupFailure(openCl, gtpCommand(openCl)));

                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                  EngineFailedMessage contributeStyle =
                      new EngineFailedMessage(
                          List.of("contribute.exe"),
                          "contribute.exe",
                          "ordinary contribute failure",
                          false,
                          false,
                          true);
                  assertFalse(contributeStyle.offersTensorRtRepair());
                  assertNull(contributeStyle.repairContext());

                  EngineFailedMessage ordinary =
                      new EngineFailedMessage(
                          List.of("engine.exe"),
                          "engine.exe gtp",
                          "ordinary process failure",
                          true,
                          true,
                          false);
                  assertFalse(ordinary.offersTensorRtRepair());
                  assertNull(ordinary.tensorRtRepairButton());
                }

                String analysisBefore = String.valueOf(Lizzie.config.analysisEngineCommand);
                int defaultBefore = Lizzie.config.uiConfig.optInt("default-engine", -1);
                assumeTrue(GraphicsEnvironment.isHeadless());
                Leelaz previousPrimary = Lizzie.leelaz;
                boolean previousFirstLaunch = forceFirstLaunchSession(true);
                try {
                  assertGtpDoesNotOfferRepair(gtpCommand(custom), 0);
                  assertGtpDoesNotOfferRepair(gtpCommand(cuda), 0);
                  assertGtpDoesNotOfferRepair(gtpCommand(directMl), 0);
                  assertGtpDoesNotOfferRepair(gtpCommand(openCl), 0);
                  assertGtpDoesNotOfferRepair("missing-ordinary-engine.exe gtp", 0);
                  try {
                    assertGtpDoesNotOfferRepair(
                        featurecat.lizzie.analysis.remote.RemoteComputeConfig.COMMAND_CUSTOM_WS, 0);
                  } catch (Exception ignored) {
                    Leelaz remote =
                        new Leelaz(
                            featurecat.lizzie.analysis.remote.RemoteComputeConfig
                                .COMMAND_CUSTOM_WS);
                    assertNull(remote.pendingTensorRtRepairContext());
                  }
                  assertEquals(analysisBefore, String.valueOf(Lizzie.config.analysisEngineCommand));
                  assertEquals(defaultBefore, Lizzie.config.uiConfig.optInt("default-engine", -1));
                  assertFalse(Lizzie.config.uiConfig.toString().contains("tensorRtRepair"));
                  assertFalse(Lizzie.config.leelazConfig.toString().contains("failedExecutable"));
                } finally {
                  forceFirstLaunchSession(previousFirstLaunch);
                  Lizzie.setPrimaryEngine(previousPrimary);
                  Lizzie.engineStartupStatus.ready();
                }
              });
        });
  }

  private static TensorRtRepairContext unrepairableContext(Path enginePath) {
    return TensorRtRepairContext.of(
        enginePath,
        gtpCommand(enginePath),
        TensorRtFailureKind.MISSING_RUNTIME,
        List.of(TensorRtInstallStatus.MISSING_RUNTIME),
        false,
        "custom TensorRT is diagnostic only");
  }

  private static void seedEngineSettings(String name, String command) {
    Lizzie.config.leelazConfig.put(
        "engine-settings-list",
        new JSONArray().put(new JSONObject().put("name", name).put("command", command)));
  }

  private static void assertGtpDoesNotOfferRepair(String command, int index) throws Exception {
    seedEngineSettings("No Repair", command);
    Leelaz engine = new Leelaz(command);
    Lizzie.setPrimaryEngine(engine);
    try {
      engine.startEngine(index);
    } catch (Exception ignored) {
    }
    TensorRtRepairContext pending = engine.pendingTensorRtRepairContext();
    assertFalse(EngineFailedMessage.shouldOfferTensorRtRepair(pending));
    assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(true, true, pending));
    assertFalse(KataGoAutoSetupDialog.openRequestForEngineStartupStatus(true, pending).directed);
    assertFalse(engine.started);
    assertFalse(engine.isLoaded);
  }

  private static Path installManagedDirectMlEngine(Path runtimeWorkDirectory) throws IOException {
    Path targetDir =
        Files.createDirectories(
            runtimeWorkDirectory
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-directml"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "directml\n");
    return enginePath.toAbsolutePath().normalize();
  }

  private static Path installManagedOpenClEngine(Path runtimeWorkDirectory) throws IOException {
    Path targetDir =
        Files.createDirectories(
            runtimeWorkDirectory
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-opencl"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "opencl\n");
    return enginePath.toAbsolutePath().normalize();
  }

  private static void startAnalysisEngineReadinessCheck(String engineCommand) throws IOException {
    List<String> commands = Utils.splitCommand(engineCommand);
    Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(commands);
    KataGoRuntimeHelper.ensureBundledRuntimeReady(engineExecutable, commands, engineCommand, null);
  }

  private static String gtpCommand(Path enginePath) {
    return enginePath.toAbsolutePath().normalize() + " gtp -model dummy.bin.gz";
  }

  private static TensorRtRepairContext pendingTensorRtRepairContext(Leelaz engine) {
    return engine.pendingTensorRtRepairContext();
  }

  private static boolean forceFirstLaunchSession(boolean value) throws Exception {
    Field field = Lizzie.class.getDeclaredField("firstLaunchSession");
    field.setAccessible(true);
    boolean previous = field.getBoolean(null);
    field.setBoolean(null, value);
    return previous;
  }

  private static String analysisCommand(Path enginePath) {
    return enginePath.toAbsolutePath().normalize() + " analysis -model dummy.bin.gz";
  }

  private static Path installManagedTensorRtEngine(Path runtimeWorkDirectory, boolean companion)
      throws IOException {
    return installManagedTensorRtEngine(
        runtimeWorkDirectory, "windows-x64-nvidia-tensorrt", companion);
  }

  private static Path installManagedTensorRtEngine(
      Path runtimeWorkDirectory, String engineDirName, boolean companion) throws IOException {
    Path targetDir =
        Files.createDirectories(
            runtimeWorkDirectory.resolve("engines").resolve("katago").resolve(engineDirName));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    touch(targetDir.resolve("libz.dll"));
    Files.writeString(
        targetDir.resolve("lizzieyzy-next-engine-backend.txt"),
        engineDirName.contains("nvidia50") ? "nvidia50-trt\n" : "nvidia-tensorrt\n");
    Files.writeString(
        targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
        "KataGo release: v1.18.1\n"
            + "Asset: katago-v1.18.1-trt10.9.0-cuda12.8-windows-x64.zip\n"
            + "Asset SHA-256: "
            + "49b7229803b2ccee5205cc9d1f7b1a37790469405324de5e5acaafe7a8a9172a\n");
    if (companion) {
      touch(targetDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
      Files.writeString(
          targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
          "HumanSL companion: katago-human-sl-cuda.exe\n"
              + "HumanSL companion SHA-256: "
              + EMPTY_FILE_SHA256
              + "\n",
          StandardOpenOption.APPEND);
    }
    return enginePath.toAbsolutePath().normalize();
  }

  private static Path installManagedCudaEngine(Path runtimeWorkDirectory) throws IOException {
    Path targetDir =
        Files.createDirectories(
            runtimeWorkDirectory
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia\n");
    return enginePath.toAbsolutePath().normalize();
  }

  private static Path installCustomTensorRtEngine(Path ownerRoot) throws IOException {
    Path targetDir =
        Files.createDirectories(
            ownerRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia-tensorrt"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
    return enginePath.toAbsolutePath().normalize();
  }

  private static void installReadyNvidiaRuntime(Path runtimeWorkDirectory) throws IOException {
    Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
    String[] dlls = {
      "cudart64_12.dll",
      "cublas64_12.dll",
      "cublasLt64_12.dll",
      "cudnn64_9.dll",
      "nvJitLink64_12.dll",
      "nvrtc64_120_0.dll",
      "nvrtc-builtins64_128.dll",
      "nvinfer_10.dll",
      "nvinfer_plugin_10.dll",
      "z.dll"
    };
    for (String dll : dlls) {
      touch(runtimeDir.resolve(dll));
    }
    Files.writeString(
        runtimeDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "CUDA NVRTC: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION
            + "\nfixture\nSHA-256: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
            + "\n");
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
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class, Path.class, Path.class, Path.class, Path.class, Path.class, List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath,
        Arrays.asList(weightPath));
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
      }
      action.run();
    } finally {
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

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(Files.readAllBytes(path));
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static boolean supportsSymbolicLinks(Path directory) throws IOException {
    Path target = touch(directory.resolve("symlink-capability-target"));
    Path link = directory.resolve("symlink-capability-link");
    try {
      Files.createSymbolicLink(link, target.toAbsolutePath());
      return Files.isSymbolicLink(link);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      return false;
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(target);
    }
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
}
