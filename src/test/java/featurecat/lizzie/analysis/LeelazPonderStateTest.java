package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.awt.Window;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  @Test
  void kataNameStartupCommandsDoNotHoldEngineMonitorWhileCommandQueueIsBlocked()
      throws Exception {
    try (TestHarness ignored = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.isCheckingName = true;
      engine.isCheckingVersion = true;
      setOutputStream(engine, new BufferedOutputStream(new ByteArrayOutputStream()));
      Object commandQueue = commandQueue(engine);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      Future<?> parseFuture = null;
      try {
        synchronized (commandQueue) {
          CountDownLatch parseStarted = new CountDownLatch(1);
          parseFuture =
              executor.submit(
                  () -> {
                    parseStarted.countDown();
                    invokeParseLineUnchecked(engine, "= KataGo");
                  });
          assertTrue(parseStarted.await(1, TimeUnit.SECONDS));
          Thread.sleep(200);

          CountDownLatch engineLockAcquired = new CountDownLatch(1);
          Future<?> lockFuture =
              executor.submit(
                  () -> {
                    synchronized (engine) {
                      engineLockAcquired.countDown();
                    }
                  });

          assertTrue(
              engineLockAcquired.await(1, TimeUnit.SECONDS),
              "KataGo startup command initialization must not wait for the command queue while holding the engine monitor");
          assertFalse(parseFuture.isDone());
          lockFuture.get(1, TimeUnit.SECONDS);
        }
        parseFuture.get(1, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
  }

  private static void invokeParseLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeParseLineUnchecked(Leelaz engine, String line) {
    try {
      invokeParseLine(engine, line);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static ArrayDeque<?> commandQueue(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("cmdQueue");
    field.setAccessible(true);
    return (ArrayDeque<?>) field.get(engine);
  }

  private static void setOutputStream(Leelaz engine, BufferedOutputStream outputStream)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(engine, outputStream);
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

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void changeEngineIcon2(int index, int mode) {}
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Menu previousMenu;
    private final boolean previousEngineGame;

    private TestHarness() {
      previousConfig = Lizzie.config;
      previousFrame = Lizzie.frame;
      previousGtpConsole = Lizzie.gtpConsole;
      previousLeelaz = Lizzie.leelaz;
      previousMenu = LizzieFrame.menu;
      previousEngineGame = EngineManager.isEngineGame;
    }

    private static TestHarness open() throws Exception {
      TestHarness harness = new TestHarness();
      Lizzie.config = allocate(Config.class);
      Lizzie.config.extraMode = ExtraMode.Normal;
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      LizzieFrame.menu = allocate(SilentMenu.class);
      EngineManager.isEngineGame = false;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      LizzieFrame.menu = previousMenu;
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
