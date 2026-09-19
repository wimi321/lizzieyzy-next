package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.EngineFailedMessage.DiagnosticActionResult;
import featurecat.lizzie.gui.KataGoAutoSetupDialog;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtFailureKind;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TensorRtRepairEntryLifecycleTest {
  private static final Path TARGET =
      Path.of("engines", "katago", "windows-x64-nvidia-tensorrt", "katago.exe");

  @Test
  void preloadNeverOpensAnalysisSettingsAfterDiagnostic() {
    assertFalse(
        AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(
            true, DiagnosticActionResult.none()));
    assertFalse(
        AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(
            true, DiagnosticActionResult.ofRepairChoice(true)));
    assertFalse(AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(true, null));
  }

  @Test
  void ordinaryOrDismissedFailureStillOpensAnalysisSettings() {
    assertTrue(
        AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(
            false, DiagnosticActionResult.none()));
    assertTrue(
        AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(
            false, DiagnosticActionResult.ofRepairChoice(false)));
    assertTrue(
        AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(
            false, DiagnosticActionResult.of(null)));
    assertTrue(AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(false, null));
  }

  @Test
  void directedRepairClickSkipsAnalysisSettings() {
    DiagnosticActionResult opened = DiagnosticActionResult.ofRepairChoice(true);
    assertTrue(opened.directedRepairOpened);
    assertFalse(AnalysisEngine.shouldOpenAnalysisSettingsAfterDiagnostic(false, opened));
  }

  @Test
  void repairableStartupDiagnosticDoesNotBlockEngineSwitchRollback() {
    TensorRtRepairContext repairable = repairable("katago.exe gtp");
    TensorRtRepairContext diagnosticOnly =
        TensorRtRepairContext.of(
            TARGET,
            repairable.originalCommand,
            repairable.failureKind,
            repairable.missingItems,
            false,
            repairable.displayMessage);

    assertFalse(Leelaz.startupFailureDiagnosticIsModal(repairable));
    assertTrue(Leelaz.startupFailureDiagnosticIsModal(diagnosticOnly));
    assertTrue(Leelaz.startupFailureDiagnosticIsModal(null));
  }


  @Test
  void consumeRequiresIdentityAndPreservesReplacement() throws Exception {
    Leelaz engine = new Leelaz("");
    TensorRtRepairContext pending = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(pending);

    assertTrue(engine.consumePendingTensorRtRepairContext(pending));
    assertNull(engine.pendingTensorRtRepairContext());

    engine.storePendingTensorRtRepairContext(pending);
    TensorRtRepairContext copy =
        TensorRtRepairContext.of(
            Path.of("engines/katago/windows-x64-nvidia-tensorrt/katago.exe"),
            "katago.exe gtp",
            pending.failureKind,
            pending.missingItems,
            pending.repairable,
            pending.displayMessage);
    assertFalse(engine.consumePendingTensorRtRepairContext(copy));
    assertSame(pending, engine.pendingTensorRtRepairContext());

    TensorRtRepairContext replacement = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(replacement);
    assertFalse(Leelaz.consumePendingIfDirectedTransfer(engine, true, pending));
    assertSame(replacement, engine.pendingTensorRtRepairContext());
  }

  @Test
  void foreignContextsAreNotCleared() throws Exception {
    Leelaz engine = new Leelaz("");
    TensorRtRepairContext pending = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(pending);

    assertFalse(engine.consumePendingTensorRtRepairContext(repairable("other.exe gtp")));
    assertSame(pending, engine.pendingTensorRtRepairContext());

    TensorRtRepairContext otherTarget =
        TensorRtRepairContext.of(
            Path.of("other", "katago.exe"),
            pending.originalCommand,
            pending.failureKind,
            pending.missingItems,
            pending.repairable,
            pending.displayMessage);
    assertFalse(engine.consumePendingTensorRtRepairContext(otherTarget));
    assertSame(pending, engine.pendingTensorRtRepairContext());

    assertFalse(engine.consumePendingTensorRtRepairContext(null));
    assertSame(pending, engine.pendingTensorRtRepairContext());
    assertFalse(Leelaz.consumePendingIfDirectedTransfer(null, true, pending));
    assertSame(pending, engine.pendingTensorRtRepairContext());
  }

  @Test
  void directedTransferIsSingleUseAndLaterStatusOpenIsOrdinary() throws Exception {
    Leelaz engine = new Leelaz("");
    TensorRtRepairContext pending = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(pending);

    KataGoAutoSetupDialog.OpenRequest first =
        KataGoAutoSetupDialog.openRequestForEngineStartupStatus(
            true, engine.pendingTensorRtRepairContext());
    assertTrue(first.directed);
    assertSame(pending, first.context);
    assertTrue(Leelaz.consumePendingIfDirectedTransfer(engine, first.directed, first.context));
    assertNull(engine.pendingTensorRtRepairContext());

    KataGoAutoSetupDialog.OpenRequest later =
        KataGoAutoSetupDialog.openRequestForEngineStartupStatus(
            true, engine.pendingTensorRtRepairContext());
    assertFalse(later.directed);
    assertNull(later.context);
  }

  @Test
  void dismissingWithoutOpeningSetupLeavesPendingForStatus() throws Exception {
    Leelaz engine = new Leelaz("");
    TensorRtRepairContext pending = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(pending);

    assertFalse(Leelaz.consumePendingIfDirectedTransfer(engine, false, pending));
    assertSame(pending, engine.pendingTensorRtRepairContext());

    KataGoAutoSetupDialog.OpenRequest status =
        KataGoAutoSetupDialog.openRequestForEngineStartupStatus(
            true, engine.pendingTensorRtRepairContext());
    assertTrue(status.directed);
    assertSame(pending, status.context);
  }

  @Test
  void unrepairableOpenRequestDoesNotConsumePending() throws Exception {
    Leelaz engine = new Leelaz("");
    TensorRtRepairContext pending = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(pending);

    KataGoAutoSetupDialog.OpenRequest request =
        KataGoAutoSetupDialog.openRequestForRepair(
            TensorRtRepairContext.of(
                TARGET,
                pending.originalCommand,
                TensorRtFailureKind.MISSING_RUNTIME,
                List.of(TensorRtInstallStatus.MISSING_RUNTIME),
                false,
                "custom TensorRT is diagnostic only"));
    assertFalse(request.directed);
    assertFalse(Leelaz.consumePendingIfDirectedTransfer(engine, request.directed, request.context));
    assertSame(pending, engine.pendingTensorRtRepairContext());
  }

  @Test
  void staleDirectedTransferRejectedByDialogDoesNotConsumePending() throws Exception {
    Leelaz engine = new Leelaz("");
    TensorRtRepairContext pending = repairable("katago.exe gtp");
    engine.storePendingTensorRtRepairContext(pending);

    KataGoAutoSetupDialog.OpenRequest request =
        KataGoAutoSetupDialog.openRequestForRepair(pending);
    assertTrue(request.directed);
    assertFalse(KataGoRuntimeHelper.isValidDirectedTensorRtTarget(request.context));

    boolean directedTransferAccepted =
        KataGoAutoSetupDialog.directedTransferAccepted(request.context, null);
    assertFalse(
        Leelaz.consumePendingIfDirectedTransfer(
            engine, directedTransferAccepted, request.context));
    assertSame(pending, engine.pendingTensorRtRepairContext());
  }

  private static TensorRtRepairContext repairable(String command) {
    return TensorRtRepairContext.of(
        TARGET,
        command,
        TensorRtFailureKind.MISSING_RUNTIME,
        List.of(TensorRtInstallStatus.MISSING_RUNTIME),
        true,
        "display");
  }
}
