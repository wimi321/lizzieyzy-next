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
import java.util.Collection;
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
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000",
              "kata-genmove_analyze B 10"),
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
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void genmoveAnalyzeForReadBoardRestoresPreviousLimitOverridesWhenLimitsAreZero()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      setStringField(engine, "readBoardGmaOriginalMaxTime", "2");
      setStringField(engine, "readBoardGmaOriginalMaxVisits", "800");
      setBooleanField(engine, "readBoardGmaMaxTimeOverridden", true);
      setBooleanField(engine, "readBoardGmaMaxVisitsOverridden", true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("W", 0, 0, false);

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-set-param maxTime 2",
              "kata-set-param maxVisits 800",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void restoreReadBoardGmaSearchLimitsWaitsForOriginalValuesWhenStopHappensEarly()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true);
      engine.restoreReadBoardGmaSearchLimitsIfNeeded();
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");
      invokeProcessCommandResponseLine(engine, "= 2");
      invokeProcessCommandResponseLine(engine, "=");
      invokeProcessCommandResponseLine(engine, "= 800");

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000",
              "kata-genmove_analyze B 10",
              "kata-set-param maxTime 2",
              "kata-set-param maxVisits 800"),
          output.commands());
    }
  }

  @Test
  void newPositiveReadBoardGmaCancelsEarlyStopRestoreBeforeOriginalValueArrives()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 5, 0, true);
      engine.restoreReadBoardGmaSearchLimitsIfNeeded();
      engine.genmoveAnalyzeForReadBoard("W", 6, 0, true);
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");
      invokeProcessCommandResponseLine(engine, "= 2");

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true",
              "kata-set-param maxTime 6",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void restoreReadBoardGmaRuntimeSettingsWaitsForOriginalPonderingValueWhenStopHappensEarly()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, false);
      invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(engine);
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true"),
          output.commands());
    }
  }

  @Test
  void restoreReadBoardGmaRuntimeSettingsRecapturesPonderingForNextSession()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, false);
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");
      invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(engine);

      engine.genmoveAnalyzeForReadBoard("W", 0, 0, false);

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true",
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void readBoardGmaPlayLineRetiresPendingResponseHandler() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", false);
      Lizzie.frame.readBoard = readBoard;

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, true);
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");

      assertEquals(1, pendingResponseHandlerCount(engine));

      invokeParseLine(engine, "play D4");
      invokeProcessCommandResponseLine(engine, "=");

      assertEquals(0, pendingResponseHandlerCount(engine));
      assertFalse(getBooleanField(engine, "isCommandLine"));
    }
  }

  @Test
  void readBoardGmaPlayLineRetiresPendingResponseHandlerAfterLogicalCancel() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
      Lizzie.frame.readBoard = readBoard;

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, true);
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");
      assertEquals(1, pendingResponseHandlerCount(engine));

      readBoard.parseLine("nobothSync");
      invokeParseLine(engine, "play D4");
      invokeProcessCommandResponseLine(engine, "=");

      assertEquals(0, pendingResponseHandlerCount(engine));
      assertFalse(getBooleanField(engine, "isCommandLine"));
    }
  }

  @Test
  void readBoardGmaErrorLineRetiresPendingResponseHandler() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
      Lizzie.frame.readBoard = readBoard;

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, true);
      invokeProcessCommandResponseLine(engine, "= true");
      invokeProcessCommandResponseLine(engine, "=");
      assertEquals(1, pendingResponseHandlerCount(engine));

      invokeParseLine(engine, "? engine failed");

      assertEquals(0, pendingResponseHandlerCount(engine));
      assertFalse(getBooleanField(readBoard, "readBoardGmaPending"));
      assertFalse(getBooleanField(engine, "isCommandLine"));
    }
  }

  private static void invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(Leelaz engine)
      throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("restoreReadBoardGmaRuntimeSettingsIfNeeded");
    method.setAccessible(true);
    method.invoke(engine);
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

  private static void setStringField(Object target, String fieldName, String value)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static boolean getBooleanField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getBoolean(target);
  }

  private static void invokeProcessCommandResponseLine(Leelaz engine, String line)
      throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeParseLine(Leelaz engine, String line) throws Exception {
    java.lang.reflect.Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static int pendingResponseHandlerCount(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    field.setAccessible(true);
    Collection<?> handlers = (Collection<?>) field.get(engine);
    return handlers == null ? 0 : handlers.size();
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
