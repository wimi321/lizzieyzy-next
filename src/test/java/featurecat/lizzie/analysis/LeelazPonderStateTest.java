package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class LeelazPonderStateTest {
  @Test
  void enginePlayLineDuringNormalAnalysisDoesNotStopPonder() throws Exception {
    try (TestHarness ignored = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      engine.Pondering();

      invokeParseLine(engine, "play D4");

      assertTrue(engine.isPondering());
    }
  }

  private static void invokeParseLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final boolean previousEngineGame;

    private TestHarness() {
      previousConfig = Lizzie.config;
      previousFrame = Lizzie.frame;
      previousGtpConsole = Lizzie.gtpConsole;
      previousEngineGame = EngineManager.isEngineGame;
    }

    private static TestHarness open() throws Exception {
      TestHarness harness = new TestHarness();
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      EngineManager.isEngineGame = false;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      EngineManager.isEngineGame = previousEngineGame;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
