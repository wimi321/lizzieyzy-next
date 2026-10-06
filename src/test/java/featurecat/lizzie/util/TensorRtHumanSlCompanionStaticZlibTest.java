package featurecat.lizzie.util;

import static featurecat.lizzie.util.TensorRtCompanionFixture.COMPANION;
import static featurecat.lizzie.util.TensorRtCompanionFixture.ENGINE_MANIFEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import featurecat.lizzie.util.TensorRtCompanionFixture.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The TensorRT package launches a separate CUDA executable for HumanSL. It shares the TensorRT
 * engine manifest, so its static zlib provenance must be proven through that manifest rather than
 * through a manifest of its own.
 */
public class TensorRtHumanSlCompanionStaticZlibTest {
  private TensorRtCompanionFixture.Isolation isolation;

  @BeforeEach
  void isolateEnvironment() {
    isolation = TensorRtCompanionFixture.isolateEnvironment();
  }

  @AfterEach
  void restoreEnvironment() {
    isolation.close();
  }

  @Test
  void verifiedCompanionNeedsNoZlibDllInEitherPackagedLayout() throws Exception {
    for (Layout layout : Layout.values()) {
      TensorRtCompanionFixture fixture = TensorRtCompanionFixture.install(layout);

      KataGoRuntimeHelper.NvidiaRuntimeStatus main =
          KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.tensorRtEngine, "");
      KataGoRuntimeHelper.NvidiaRuntimeStatus companion =
          KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.companion, "");

      assertTrue(main.ready, layout + " main: " + main.missingDlls);
      assertTrue(companion.ready, layout + " companion: " + companion.missingDlls);
      assertTrue(companion.verifiedStaticZlib, layout.toString());
    }
  }

  @Test
  void companionExemptionFailsClosedWithoutVerifiedTensorRtBinding() throws Exception {
    TensorRtCompanionFixture fixture =
        TensorRtCompanionFixture.install(Layout.ONLINE_INSTALL_WITH_SHARED_RUNTIME);
    Path manifest = fixture.engineDir.resolve(ENGINE_MANIFEST);
    String companionBytes = Files.readString(fixture.companion);
    String mainBytes = Files.readString(fixture.tensorRtEngine);

    Files.writeString(fixture.companion, "modified companion");
    assertRequiresDynamicZlib(fixture.companion);
    Files.writeString(fixture.companion, companionBytes);

    fixture.writeManifest(false);
    assertTrue(KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.tensorRtEngine, "").ready);
    assertRequiresDynamicZlib(fixture.companion);

    Files.writeString(manifest, "HumanSL companion: " + COMPANION + "\n", StandardOpenOption.APPEND);
    assertRequiresDynamicZlib(fixture.companion);

    fixture.writeManifest(true);
    Files.writeString(manifest, "HumanSL companion: " + COMPANION + "\n", StandardOpenOption.APPEND);
    assertRequiresDynamicZlib(fixture.companion);

    fixture.writeManifest(true);
    Files.writeString(fixture.tensorRtEngine, "modified tensorrt engine");
    assertRequiresDynamicZlib(fixture.companion);
    Files.writeString(fixture.tensorRtEngine, mainBytes);

    Files.delete(manifest);
    assertRequiresDynamicZlib(fixture.companion);
    fixture.writeManifest(true);

    Path external = fixture.root.resolve("external").resolve(COMPANION);
    Files.createDirectories(external.getParent());
    Files.copy(fixture.companion, external);
    assertRequiresDynamicZlib(external);

    assertTrue(KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.companion, "").ready);
    Files.delete(fixture.runtimeDir.resolve("cudnn64_9.dll"));
    KataGoRuntimeHelper.NvidiaRuntimeStatus missingCudnn =
        KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.companion, "");
    assertFalse(missingCudnn.ready);
    assertTrue(missingCudnn.missingDlls.contains("cudnn64_9.dll"), missingCudnn.missingDlls.toString());
    assertFalse(
        missingCudnn.missingDlls.stream().anyMatch(value -> value.contains("zlibwapi.dll")),
        "The verified companion keeps its static zlib exemption while reporting real gaps.");
  }

  @Test
  void tensorRtStatusAndRepairAgreeWithTheCompanionPreflight() throws Exception {
    TensorRtCompanionFixture fixture =
        TensorRtCompanionFixture.install(Layout.ONLINE_INSTALL_WITH_SHARED_RUNTIME);
    SetupSnapshot snapshot = fixture.snapshot();
    Path model = Files.writeString(fixture.root.resolve("human.bin.gz"), "model");
    String command = fixture.humanSlCommand(model);

    TensorRtInstallStatus ready = KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
    assertTrue(ready.runtimeReady);
    assertTrue(ready.companionReady);
    assertTrue(ready.installed);
    assertFalse(KataGoRuntimeHelper.canRepairTensorRt(snapshot));
    assertNull(KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command));

    Path cudnn = fixture.runtimeDir.resolve("cudnn64_9.dll");
    Files.delete(cudnn);
    assertRuntimeRepairOffered(fixture, snapshot, command);
    Files.write(cudnn, new byte[0]);

    Files.delete(fixture.runtimeDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"));
    assertRuntimeRepairOffered(fixture, snapshot, command);
  }

  @Test
  void missingRuntimeMessageNamesTheFailingExecutable() throws Exception {
    TensorRtCompanionFixture fixture =
        TensorRtCompanionFixture.install(Layout.ONLINE_INSTALL_WITH_SHARED_RUNTIME);
    Path external = fixture.root.resolve("external").resolve(COMPANION);
    Files.createDirectories(external.getParent());
    Files.copy(fixture.companion, external);

    IOException failure =
        assertThrows(
            IOException.class,
            () -> KataGoRuntimeHelper.ensureBundledRuntimeReady(external, (java.awt.Window) null));

    String message = failure.getMessage();
    assertTrue(message.contains(external.toAbsolutePath().normalize().toString()), message);
  }

  private static void assertRuntimeRepairOffered(
      TensorRtCompanionFixture fixture, SetupSnapshot snapshot, String command) {
    assertFalse(KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.companion, "").ready);
    TensorRtInstallStatus status = KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
    assertFalse(status.runtimeReady);
    assertTrue(status.companionReady);
    assertFalse(status.installed);
    assertTrue(KataGoRuntimeHelper.canRepairTensorRt(snapshot));

    TensorRtRepairContext startup =
        KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
    assertNotNull(startup);
    assertEquals(List.of(TensorRtInstallStatus.MISSING_RUNTIME), startup.missingItems);
    assertTrue(startup.repairable);
  }

  private static void assertRequiresDynamicZlib(Path executable) {
    KataGoRuntimeHelper.NvidiaRuntimeStatus status =
        KataGoRuntimeHelper.inspectNvidiaRuntime(executable, "");
    assertFalse(status.ready, executable.toString());
    assertFalse(status.verifiedStaticZlib, executable.toString());
    assertTrue(
        status.missingDlls.stream().anyMatch(value -> value.contains("zlibwapi.dll")),
        status.missingDlls.toString());
  }
}
