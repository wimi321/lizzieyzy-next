package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class TensorRtParserRuntimeTest {
  @Test
  void pinnedTransformerTensorRtRequiresItsOnnxParserButCudaCompanionDoesNot() throws Exception {
    try (var isolation = TensorRtCompanionFixture.isolateEnvironment()) {
      for (var layout : TensorRtCompanionFixture.Layout.values()) {
        var fixture = TensorRtCompanionFixture.install(layout);
        var parser = Files.write(fixture.runtimeDir.resolve("nvonnxparser_10.dll"), new byte[0]);
        assertTrue(KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.tensorRtEngine, "").ready);
        Files.delete(parser);

        var missing = KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.tensorRtEngine, "");
        assertFalse(
            missing.ready, "The Windows loader cannot start this TensorRT binary without its parser");
        assertTrue(missing.missingDlls.contains("nvonnxparser_10.dll"), missing.missingDlls.toString());
        assertTrue(
            KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.companion, "").ready,
            "The CUDA companion must not inherit TensorRT-only dependencies");

        Files.write(parser, new byte[0]);
        assertTrue(KataGoRuntimeHelper.inspectNvidiaRuntime(fixture.tensorRtEngine, "").ready);
      }
    }
  }
}
