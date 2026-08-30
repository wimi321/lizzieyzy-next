package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.web.WebBoardManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class LizzieShutdownTest {
  @Test
  void forceKillAllowsAnAlreadyMissingPrimaryEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      assertDoesNotThrow(() -> Lizzie.engineManager.forceKillAllEngines());
      assertEquals(1, harness.engine.forceQuitCount);
    }
  }

  @Test
  void autosaveRuntimeFailureStillPersistsConfigKillsEnginesAndExits() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.autoSaveFailure = new IllegalStateException("autosave failed");
      harness.config.autoSaveOnExit = true;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(1, harness.frame.autoSaveCalls);
      assertTrue(harness.config.persistCalled, "UI persist must run after autosave failure");
      assertTrue(harness.config.saveCalled, "config save must run after autosave failure");
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  @Test
  void closingWithoutExitAutosaveStillPersistsConfigKillsEnginesAndExits() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.config.autoSaveOnExit = false;
      AtomicInteger exitCode = new AtomicInteger(-1);

      Lizzie.shutdown(exitCode::set);

      assertEquals(0, harness.frame.autoSaveCalls);
      assertTrue(harness.config.persistCalled);
      assertTrue(harness.config.saveCalled);
      assertEquals(1, harness.engine.forceQuitCount);
      assertEquals(0, exitCode.get());
    }
  }

  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final EngineManager previousEngineManager;
    private final Leelaz previousEngine;
    private final WebBoardManager previousWebBoardManager;
    private final RecordingConfig config;
    private final RecordingFrame frame;
    private final RecordingLeelaz engine;

    private Harness(
        Config previousConfig,
        LizzieFrame previousFrame,
        EngineManager previousEngineManager,
        Leelaz previousEngine,
        WebBoardManager previousWebBoardManager,
        RecordingConfig config,
        RecordingFrame frame,
        RecordingLeelaz engine) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousEngineManager = previousEngineManager;
      this.previousEngine = previousEngine;
      this.previousWebBoardManager = previousWebBoardManager;
      this.config = config;
      this.frame = frame;
      this.engine = engine;
    }

    private static Harness open() throws Exception {
      RecordingConfig config = allocate(RecordingConfig.class);
      config.autoSaveOnExit = true;
      config.uiConfig = new JSONObject();
      RecordingFrame frame = allocate(RecordingFrame.class);
      RecordingLeelaz engine = new RecordingLeelaz();
      EngineManager engineManager = allocate(EngineManager.class);
      engineManager.engineList = new ArrayList<>();
      engineManager.engineList.add(engine);
      Harness harness =
          new Harness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.engineManager,
              Lizzie.leelaz,
              Lizzie.webBoardManager,
              config,
              frame,
              engine);
      Lizzie.config = config;
      Lizzie.frame = frame;
      Lizzie.engineManager = engineManager;
      Lizzie.leelaz = null;
      Lizzie.webBoardManager = null;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousEngineManager;
      Lizzie.leelaz = previousEngine;
      Lizzie.webBoardManager = previousWebBoardManager;
    }
  }

  private static final class RecordingConfig extends Config {
    private RecordingConfig() throws IOException {}

    boolean persistCalled;
    boolean saveCalled;

    @Override
    public void persist() {
      persistCalled = true;
    }

    @Override
    public void save() {
      saveCalled = true;
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    int autoSaveCalls;
    RuntimeException autoSaveFailure;

    @Override
    public void saveAutoGame(int index) {
      autoSaveCalls++;
      if (autoSaveFailure != null) {
        throw autoSaveFailure;
      }
    }

    @Override
    public void closeContributeEngine() {}

    @Override
    public void shutdownClockHelper() {}
  }

  private static final class RecordingLeelaz extends Leelaz {
    int forceQuitCount;

    private RecordingLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public void forceQuit() {
      forceQuitCount++;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
