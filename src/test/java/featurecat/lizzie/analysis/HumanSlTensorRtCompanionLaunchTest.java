package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import featurecat.lizzie.util.TensorRtCompanionFixture;
import featurecat.lizzie.util.TensorRtCompanionFixture.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AI Coach on a TensorRT package must launch the verified CUDA companion without a zlib DLL. */
public class HumanSlTensorRtCompanionLaunchTest {
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
  void startHandsTheVerifiedCompanionToProcessCreationWithoutZlibDll() throws Exception {
    for (Layout layout : Layout.values()) {
      TensorRtCompanionFixture fixture = TensorRtCompanionFixture.install(layout);
      Path model = Files.writeString(fixture.root.resolve("human model.bin.gz"), "model");
      String command = fixture.humanSlCommand(model);
      AtomicReference<ProcessBuilder> launched = new AtomicReference<>();

      HumanSlAnalysisRunner runner = runner(command, model, launched);

      assertNull(
          KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command),
          layout + ": component checks must agree with the companion launch preflight");
      assertFalse(runner.start(), layout.toString());
      assertNotNull(launched.get(), layout + ": " + runner.getUnavailableReason());
      assertEquals(fixture.companion.toString(), launched.get().command().get(0), layout.toString());
      assertNull(runner.getTensorRtRepairContext());
    }
  }

  @Test
  void missingCompanionRuntimeStopsBeforeProcessCreationWithRuntimeRepair() throws Exception {
    TensorRtCompanionFixture fixture =
        TensorRtCompanionFixture.install(Layout.ONLINE_INSTALL_WITH_SHARED_RUNTIME);
    Files.delete(fixture.runtimeDir.resolve("cudnn64_9.dll"));
    Path model = Files.writeString(fixture.root.resolve("human.bin.gz"), "model");
    AtomicReference<ProcessBuilder> launched = new AtomicReference<>();

    HumanSlAnalysisRunner runner = runner(fixture.humanSlCommand(model), model, launched);

    assertFalse(runner.start());
    assertNull(launched.get(), "No process may be created while the companion runtime is missing.");
    TensorRtRepairContext context = runner.getTensorRtRepairContext();
    assertNotNull(context);
    assertEquals(List.of(TensorRtInstallStatus.MISSING_RUNTIME), context.missingItems);
    assertTrue(context.repairable);
  }

  /** The starter records the final launch and stops it, so no fixture bytes are ever executed. */
  private static HumanSlAnalysisRunner runner(
      String analysisCommand, Path model, AtomicReference<ProcessBuilder> launched) {
    return new HumanSlAnalysisRunner(
        HumanSlAnalysisRunner.buildHumanSlCommand(analysisCommand, model),
        builder -> {
          launched.set(builder);
          throw new IOException("controlled launch stop");
        });
  }
}
