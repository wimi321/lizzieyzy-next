package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineStartupDialogPolicyTest {
  @TempDir Path tempDir;

  @Test
  void primaryEngineFailuresStayInTheAccessibleRepairStatus() {
    assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(true, false));
    assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(true, true));
  }

  @Test
  void secondaryEngineDiagnosticsRemainAvailableOutsideFirstLaunch() {
    assertTrue(Leelaz.shouldOpenInteractiveDiagnostic(false, false));
    assertFalse(Leelaz.shouldOpenInteractiveDiagnostic(false, true));
  }

  @Test
  void backgroundAnalysisPreloadDoesNotAnnounceGeneratedConfig() {
    assertFalse(AnalysisEngine.shouldShowGeneratedConfigNotice(true, true));
    assertFalse(AnalysisEngine.shouldShowGeneratedConfigNotice(false, false));
    assertTrue(AnalysisEngine.shouldShowGeneratedConfigNotice(false, true));
  }

  @Test
  void missingExecutableWeightOrConfigUsesNotReadyRepairState() throws Exception {
    Path executable = tempDir.resolve("katago.exe");
    Path model = tempDir.resolve("model.bin.gz");
    Path config = tempDir.resolve("gtp.cfg");

    assertTrue(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(executable.toString(), "gtp", "-model", model.toString()), false, false));

    Files.writeString(executable, "stub");
    Files.writeString(model, "stub");
    assertTrue(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(
                executable.toString(),
                "gtp",
                "-model",
                model.toString(),
                "-config",
                config.toString()),
            false,
            false));

    Files.writeString(config, "stub");
    assertFalse(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(
                executable.toString(),
                "gtp",
                "-model",
                model.toString(),
                "-config",
                config.toString()),
            false,
            false));
    assertFalse(
        Leelaz.hasMissingLocalStartupAsset(
            List.of(executable.toString(), "gtp"), true, false));
  }
}
