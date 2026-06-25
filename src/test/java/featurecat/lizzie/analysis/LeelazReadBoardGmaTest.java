package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.awt.Window;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeelazReadBoardGmaTest {
  @Test
  void supportsReadBoardGmaRequiresKatagoAndRequiredCommands() throws Exception {
    Leelaz engine = new Leelaz("");
    setBooleanField(engine, "endGetCommandList", true);
    engine.commandLists.add("kata-genmove_analyze");
    engine.commandLists.add("kata-get-param");
    engine.commandLists.add("kata-set-param");

    assertFalse(engine.supportsReadBoardGma(), "non-KataGo engine must not pass GMA gate.");

    engine.isKatago = true;
    assertTrue(engine.supportsReadBoardGma());

    engine.commandLists.remove("kata-set-param");
    assertFalse(
        engine.supportsReadBoardGma(), "GMA gate must require runtime ponder param support.");
  }

  @Test
  void genmoveAnalyzeForReadBoardMapsPositiveLimitsAndPonderSetting() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true);

      assertEquals(
          List.of(
              "kata-set-param ponderingEnabled true",
              "kata-genmove_analyze B 10 maxTime 5 maxVisits 1000"),
          output.commands());
      assertTrue(engine.isThinking);
    }
  }

  @Test
  void genmoveAnalyzeForReadBoardOmitsZeroLimitsAndDisablesPonder() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("W", 0, 0, false);

      assertEquals(
          List.of(
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  private static void setOutputStream(Leelaz engine, OutputStream stream) throws Exception {
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, Leelaz.createCommandOutputStream(stream));
  }

  private static void setBooleanField(Object target, String fieldName, boolean value)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.setBoolean(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static Config minimalConfig() throws Exception {
    Config config = allocate(Config.class);
    config.extraMode = ExtraMode.Normal;
    config.analyzeUpdateIntervalCentisec = 10;
    config.analyzeUpdateIntervalCentisecSSH = 10;
    return config;
  }

  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final Menu previousMenu;
    private final boolean previousEngineGameFlag;

    private Harness(
        Config previousConfig,
        LizzieFrame previousFrame,
        GtpConsolePane previousGtpConsole,
        Leelaz previousLeelaz,
        Leelaz previousLeelaz2,
        Menu previousMenu,
        boolean previousEngineGameFlag) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousGtpConsole = previousGtpConsole;
      this.previousLeelaz = previousLeelaz;
      this.previousLeelaz2 = previousLeelaz2;
      this.previousMenu = previousMenu;
      this.previousEngineGameFlag = previousEngineGameFlag;
    }

    private static Harness open() throws Exception {
      Harness harness =
          new Harness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.gtpConsole,
              Lizzie.leelaz,
              Lizzie.leelaz2,
              LizzieFrame.menu,
              EngineManager.isEngineGame);
      Lizzie.config = minimalConfig();
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = allocate(Menu.class);
      EngineManager.isEngineGame = false;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
      LizzieFrame.menu = previousMenu;
      EngineManager.isEngineGame = previousEngineGameFlag;
    }
  }

  private static final class RecordingOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    @Override
    public synchronized void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public synchronized void flush() {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (!command.isEmpty()) {
        commands.add(command);
      }
    }

    private synchronized List<String> commands() {
      return new ArrayList<>(commands);
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }

    @Override
    public void refresh() {}
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
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}
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
