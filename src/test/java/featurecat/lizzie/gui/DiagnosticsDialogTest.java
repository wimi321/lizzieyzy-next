package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.logging.TraceScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsDialogTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void dialogShowsHealthAndAppliesDiagnosticsOnApply() {
    LoggingRuntime runtime = start();
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true);
    assertTrue(dialog.healthText().contains(runtime.logsDirectory().toString()));
    assertTrue(dialog.healthText().contains("persistenceEnabled=true"));
    assertTrue(dialog.estimateText().contains("MB"));
    assertFalse(dialog.cancelButton().isVisible());
    assertFalse(dialog.fullLogsEnabledBox().isSelected());
    dialog.diagnosticsEnabledBox().doClick();
    assertTrue(runtime.settings().diagnosticsEnabled());
    dialog.applyCurrentPlan();
    assertFalse(runtime.settings().diagnosticsEnabled());
  }

  @Test
  void applyFailureRestoresUiFromRuntime() {
    LoggingRuntime runtime = start();
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), new ArrayList<>(), () -> true);
    assertTrue(runtime.settings().diagnosticsEnabled());
    dialog.diagnosticsEnabledBox().setSelected(false);
    try {
      runtime.applySettings(
          runtime.settings().withDiagnosticsEnabled(false),
          settings -> {
            throw new java.io.IOException("disk full");
          });
    } catch (RuntimeException ignored) {
    }
    dialog.refreshFromRuntime();
    assertTrue(dialog.diagnosticsEnabledBox().isSelected());
    assertTrue(runtime.settings().diagnosticsEnabled());
  }

  @Test
  void fullTraceRequiresConfirmationAndRefreshesTitle() {
    LoggingRuntime runtime = start();
    AtomicInteger titles = new AtomicInteger();
    AtomicBoolean confirm = new AtomicBoolean(false);
    DiagnosticsDialog dialog = dialog(runtime, titles, new ArrayList<>(), confirm::get);
    dialog.fullLogsEnabledBox().setSelected(true);
    dialog.applyCurrentPlan();
    assertFalse(runtime.fullTraceActive());
    assertEquals(0, titles.get());
    confirm.set(true);
    dialog.fullLogsEnabledBox().setSelected(true);
    dialog.applyCurrentPlan();
    assertTrue(runtime.fullTraceActive());
    assertEquals(1, titles.get());
    assertTrue(dialog.fullLogsEnabledBox().isSelected());
    assertTrue(dialog.durationText().contains("s"));
    DiagnosticsDialog reopened = dialog(runtime, titles, new ArrayList<>(), () -> true);
    assertTrue(reopened.fullLogsEnabledBox().isSelected());
    assertTrue(reopened.durationText().contains("s"));
    dialog.fullLogsEnabledBox().setSelected(false);
    dialog.applyCurrentPlan();
    assertFalse(runtime.fullTraceActive());
    assertEquals(2, titles.get());
  }

  @Test
  void openFolderAndExportUseWorkDirectoryDiagnostics() throws Exception {
    LoggingRuntime runtime = start();
    List<Path> opened = new ArrayList<>();
    DiagnosticsDialog dialog = dialog(runtime, new AtomicInteger(), opened, () -> true);
    assertTrue(dialog.currentRequest().rawScopes().isEmpty());
    dialog.startFullTraceFromUi();
    assertTrue(dialog.confirmBody().contains("Engine/GTP"));
    assertTrue(dialog.currentRequest().rawScopes().contains(TraceScope.ENGINE_GTP));
    dialog.scopeEngineBox().setSelected(false);
    assertTrue(dialog.currentRequest().rawScopes().contains(TraceScope.ENGINE_GTP));
    Path zip = dialog.exportSynchronously();
    assertTrue(Files.isRegularFile(zip));
    assertTrue(zip.getParent().endsWith("diagnostics"));
    assertTrue(opened.contains(zip.getParent()));
    runtime.stopFullTrace();
    dialog.refreshFromRuntime();
    assertFalse(dialog.fullLogsEnabledBox().isSelected());
    assertTrue(dialog.currentRequest().rawScopes().isEmpty());
  }

  private DiagnosticsDialog dialog(
      LoggingRuntime runtime,
      AtomicInteger titles,
      List<Path> opened,
      java.util.function.BooleanSupplier confirm) {
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir));
    return new DiagnosticsDialog(
        runtime, null, exporter, titles::incrementAndGet, confirm, opened::add);
  }

  private LoggingRuntime start() {
    LoggingRuntime.resetForTests();
    return LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
  }
}
