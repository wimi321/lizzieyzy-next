package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackingAnalysisControllerTest {
  @Test
  void unknownFocusCapabilityRejectsUserRequestWithoutStartingAnAnalysis() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      engine.started = true;
      engine.isKatago = true;
      engine.commandLists.addAll(List.of("stop", "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      setField(engine, "outputStream", new BufferedOutputStream(output));
      Lizzie.leelaz = engine;
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.board = null;
      TrackingAnalysisController controller = new TrackingAnalysisController();
      TrackingAnalysisController.Context context = new TrackingAnalysisController.Context(
          this, output, 19, 19, "stones", true, "chinese", 7.5,
          engine, engine.trackingStreamIncarnation(),
          new TrackingAnalysisController.Parameters(10, 100), null);
      assertEquals(TrackingAnalysisController.AddResult.LEASE_UNAVAILABLE,
          controller.addPoint("D4", context));
      assertEquals("", output.toString(StandardCharsets.UTF_8));
      assertTrue(controller.snapshot().selectedPoints().isEmpty());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
    }
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
