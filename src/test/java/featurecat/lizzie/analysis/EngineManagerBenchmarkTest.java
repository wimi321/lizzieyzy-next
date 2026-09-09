package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real child processes exercise the same selection/stop boundary used by engine menus. */
@EnabledOnOs(OS.LINUX)
class EngineManagerBenchmarkTest {
  @TempDir Path directory;

  @Test
  void engineGameRejectsBenchmarkBeforeStartingEitherCatalogParticipant() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture benchmark = env.fixture("game benchmark");
      Fixture peer = new Fixture(directory.resolve("game peer"), false);
      env.engines.add(peer.engine);
      java.util.concurrent.atomic.AtomicBoolean nonGtpNotice =
          new java.util.concurrent.atomic.AtomicBoolean();
      EngineManager manager =
          new EngineManager(List.of(benchmark.engine, peer.engine)) {
            @Override
            protected void showBenchmarkGtpUnavailable() {
              nonGtpNotice.set(true);
            }
          };
      Lizzie.setEngineManager(manager);
      var plan = featurecat.lizzie.enginegame.EngineGamePlans.harness(0, 1, false);
      assertFalse(manager.startEngineGame(plan));
      assertTrue(nonGtpNotice.get(), "the user must receive the non-GTP participant reason");
      assertFalse(Files.exists(benchmark.path.resolve("pid")));
      assertFalse(Files.exists(peer.path.resolve("pid")));
      assertSame(benchmark.engine, manager.engineList.get(plan.blackIndex()));
      assertSame(peer.engine, manager.engineList.get(plan.whiteIndex()));
    }
  }

  @Test
  void stoppingQueuedSelectionNeverCreatesProcess() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture target = env.fixture("queued");
      EngineManager manager = env.manager(target.engine);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(manager.switchEngineIfAvailable(0, true));
            manager.killThisEngines();
          });
      SwingUtilities.invokeAndWait(() -> {});
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(Files.exists(target.path.resolve("pid")));
      assertEquals(
          Lizzie.resourceBundle.getString("Benchmark.cancelled"),
          manager.engineSwitchUiSnapshot(true).failureDetail());
    }
  }

  @Test
  void selectedDefaultRunsButUnselectedPreloadDoesNot() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture unused = env.fixture("unused preload");
      Fixture selected = env.fixture("selected default");
      EngineData preload = new EngineData();
      preload.commands = unused.engine.engineCommand;
      preload.name = "Unused";
      preload.preload = true;
      preload.width = preload.height = 19;
      EngineData defaults = new EngineData();
      defaults.commands = selected.engine.engineCommand;
      defaults.name = "Selected";
      defaults.isDefault = true;
      defaults.width = defaults.height = 13;
      EngineManager manager =
          new EngineManager(
              Lizzie.config,
              0,
              true,
              new ArrayList<>(List.of(preload, defaults)),
              command -> {
                Leelaz engine = new NamedEngine(command);
                env.engines.add(engine);
                return engine;
              });
      selected.awaitReady();
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(19, Board.boardWidth);
      assertFalse(Files.exists(unused.path.resolve("pid")));
      manager.forceKillAllEngines();
    }
  }

  @Test
  void modeReservationRejectsToolBeforeCreatingProcess() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture target = env.fixture("reserved");
      EngineManager manager = env.manager(target.engine);
      try (var reservation = target.engine.beginExclusiveGtpLifecycleReservation(new Object())) {
        assertNotNull(reservation);
        assertFalse(manager.switchEngineIfAvailable(0, true));
        assertFalse(Files.exists(target.path.resolve("pid")));
      }
      assertTrue(manager.switchEngineIfAvailable(0, true));
      target.awaitReady();
    }
  }

  @Test
  void selectedBenchmarkPreservesArgumentsAndBoardWithoutProtocolOrReadinessBudget()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture fixture = env.fixture("engine with spaces");
      Leelaz engine = fixture.engine;
      engine.initialCommand = "play B D4";
      engine.oriWidth = 13;
      engine.orikomi = 2.5f;
      EngineManager manager = env.manager(engine);
      BoardHistoryList history = Lizzie.board.getHistory();
      var node = history.getCurrentHistoryNode();
      double komi = history.getGameInfo().getKomi();

      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      engine.togglePonder();
      engine.ponder();
      assertFalse(engine.isPondering(), "a benchmark cannot enter analysis mode");
      engine.sendCommand("name");
      engine.sendCommandNoLeelaz2("play B D4");
      fixture.command("checkpoint");
      fixture.awaitFile("checkpoint");
      assertEquals("", Files.readString(fixture.path.resolve("stdin")));
      assertFalse(engine.isLoaded());
      assertFalse(manager.isEngineSwitchActive(0, true));
      assertSame(history, Lizzie.board.getHistory());
      assertSame(node, history.getCurrentHistoryNode());
      assertEquals(komi, history.getGameInfo().getKomi());
      assertEquals(19, Board.boardWidth);
      await(
          () ->
              env.console.output.toString().contains("raw stdout")
                  && env.console.output.toString().contains("raw stderr"));
      assertTrue(
          Files.readString(fixture.path.resolve("argv"))
              .contains("numSearchThreads=3,logDir=benchmark/value"));

      fixture.command("exit 0");
      BenchmarkExecution.Snapshot result =
          engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
      assertEquals(BenchmarkExecution.State.SUCCEEDED, result.state());
      engine.cancelBenchmark();
      assertEquals(
          BenchmarkExecution.State.SUCCEEDED, engine.benchmarkExecution().snapshot().state());
      assertEquals(0, result.exitCode());
      assertTrue(result.outputTail().contains("final stdout"));
      assertTrue(result.outputTail().contains("final stderr"));
      assertFalse(engine.isLoaded());
      assertFalse(fixture.alive());
    }
  }

  @Test
  void nonzeroAndCreationFailureRetainDiagnosticsWithoutGtpFailureRecovery() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture fixture = env.fixture("failure");
      EngineManager manager = env.manager(fixture.engine);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      fixture.command("exit 7");
      var failed = fixture.engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
      assertEquals(BenchmarkExecution.State.FAILED, failed.state());
      assertEquals(7, failed.exitCode());
      assertTrue(failed.outputTail().contains("final stderr"));
      assertFalse(manager.isEngineSwitchActive(0, true));

      Leelaz missing = new NamedEngine("\"" + directory.resolve("missing/katago") + "\" benchmark");
      manager = env.manager(missing);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      var notStarted = missing.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
      assertEquals(BenchmarkExecution.State.FAILED, notStarted.state());
      assertTrue(notStarted.detail().contains("katago"));
      assertNull(notStarted.exitCode());
    }
  }

  @Test
  void closeCurrentCancelsEvenAfterOutputEofAndExplicitSelectionCreatesFreshInvocation()
      throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture fixture = env.fixture("eof");
      EngineManager manager = env.manager(fixture.engine);
      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      BenchmarkExecution first = fixture.engine.benchmarkExecution();
      fixture.command("eof");
      fixture.awaitFile("eof");
      assertTrue(fixture.alive());
      assertFalse(first.completion().isDone());
      manager.killThisEngines();
      assertEquals(
          BenchmarkExecution.State.CANCELLED, first.completion().get(5, TimeUnit.SECONDS).state());
      assertFalse(fixture.alive());

      fixture.reset();
      assertTrue(manager.switchEngineIfAvailable(0, true));
      fixture.awaitReady();
      BenchmarkExecution second = fixture.engine.benchmarkExecution();
      assertNotSame(first, second);
      assertEquals(BenchmarkExecution.State.CANCELLED, first.snapshot().state());
      manager.forceKillAllEngines();
      assertEquals(
          BenchmarkExecution.State.CANCELLED, second.completion().get(5, TimeUnit.SECONDS).state());
      assertFalse(fixture.alive());
    }
  }

  @Test
  void eachSlotOwnsItsOutputAndReplacingOneDoesNotStopTheOther() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture primary = env.fixture("primary");
      Fixture secondary = env.fixture("secondary");
      Fixture replacement = env.fixture("replacement");
      EngineManager manager = env.manager(primary.engine, secondary.engine, replacement.engine);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      primary.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      secondary.awaitReady();
      BenchmarkExecution oldPrimary = primary.engine.benchmarkExecution();
      assertTrue(manager.switchEngineIfAvailable(2, true));
      replacement.awaitReady();
      assertEquals(
          BenchmarkExecution.State.CANCELLED,
          oldPrimary.completion().get(5, TimeUnit.SECONDS).state());
      assertTrue(secondary.alive());
      assertSame(replacement.engine, Lizzie.leelaz);
      assertSame(secondary.engine, Lizzie.leelaz2);
      assertEquals(2, EngineManager.currentEngineNo);
      assertEquals(1, EngineManager.currentEngineNo2);
      assertEquals("", Files.readString(secondary.path.resolve("stdin")));
      assertTrue(manager.killAllEngines());
      assertEquals(
          BenchmarkExecution.State.CANCELLED,
          secondary.engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS).state());
      assertEquals(
          BenchmarkExecution.State.CANCELLED,
          replacement.engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS).state());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void switchingBackToGtpReapsToolAndRestoresBoard(boolean fastChange) throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture tool = env.fixture("tool");
      Fixture gtp = new Fixture(directory.resolve("benchmark gtp"), false);
      EngineManager manager = env.manager(tool.engine, gtp.engine);
      Lizzie.config.fastChange = fastChange;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      tool.awaitReady();
      BenchmarkExecution old = tool.engine.benchmarkExecution();
      assertTrue(manager.switchEngineIfAvailable(1, true));
      try { await(() -> manager.isEngineSwitchActive(1, true)); }
      catch (AssertionError failure) {
        throw new AssertionError(Files.readString(gtp.path.resolve("stdin"))
            + " failure=" + manager.engineSwitchUiSnapshot(true).failureDetail(), failure);
      }
      assertFalse(tool.alive());
      assertEquals(BenchmarkExecution.State.CANCELLED, old.snapshot().state());
      assertTrue(Files.readString(gtp.path.resolve("stdin")).contains("clear_board"));
      assertSame(gtp.engine, Lizzie.leelaz);
      assertTrue(gtp.engine.isLoaded());
    }
  }

  @Test
  void secondaryGtpRestoreDoesNotMirrorIntoPrimaryTool() throws Exception {
    try (Environment env = new Environment(directory)) {
      Fixture tool = env.fixture("primary tool");
      Fixture gtp = new Fixture(directory.resolve("secondary gtp"), false);
      EngineManager manager = env.manager(tool.engine, gtp.engine);
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertTrue(manager.switchEngineIfAvailable(0, true));
      tool.awaitReady();
      assertTrue(manager.switchEngineIfAvailable(1, false));
      await(() -> manager.isEngineSwitchActive(1, false));
      tool.command("checkpoint");
      tool.awaitFile("checkpoint");
      assertEquals("", Files.readString(tool.path.resolve("stdin")));
      assertTrue(tool.alive());
    }
  }

  private static final String CHILD =
      """
      #!/usr/bin/python3
      import os, sys, pathlib, select, time
      p = pathlib.Path(__file__).parent
      (p/'pid').write_text(str(os.getpid()))
      (p/'argv').write_text(repr(sys.argv))
      (p/'stdin').write_text('')
      if sys.argv[1] == 'gtp':
          for line in sys.stdin:
              with (p/'stdin').open('a') as f: f.write(line)
              words = line.split()
              ident = words.pop(0) if words and words[0].isdigit() else ''
              cmd = words[0] if words else ''
              answer = 'ControlledGTP' if cmd == 'name' else '1.0' if cmd == 'version' else ''
              if cmd == 'list_commands': answer = 'name\\nversion\\nlist_commands\\nkomi\\nboardsize\\nclear_board\\nplay\\nquit'
              print('=' + ident + ' ' + answer + '\\n', flush=True)
              if cmd == 'quit': break
          sys.exit(0)
      print('raw stdout: unrecognized progress', flush=True)
      print('raw stderr: loading', file=sys.stderr, flush=True)
      (p/'ready').touch()
      eof = False
      while True:
          readable, _, _ = select.select([0], [], [], 0.01)
          if readable:
              data = os.read(0, 4096)
              if data:
                  with (p/'stdin').open('ab') as f: f.write(data)
          c = p/'control'
          if not c.exists(): continue
          command = c.read_text()
          if not command: continue
          c.unlink()
          if command == 'checkpoint': (p/'checkpoint').touch()
          if command == 'eof':
              os.close(1)
              os.close(2)
              eof = True
              (p/'eof').touch()
          if command.startswith('exit '):
              if not eof:
                  print('final stdout', flush=True)
                  print('final stderr', file=sys.stderr, flush=True)
              sys.exit(int(command.split()[1]))
      """;

  private static final class Fixture {
    final Path path;
    final Leelaz engine;

    Fixture(Path path) throws Exception {
      this(path, true);
    }

    Fixture(Path path, boolean benchmark) throws Exception {
      this.path = Files.createDirectories(path);
      Path executable = Files.writeString(path.resolve("katago"), CHILD);
      assertTrue(executable.toFile().setExecutable(true));
      engine =
          new NamedEngine(
              "\""
                  + executable
                  + "\" "
                  + (benchmark ? "benchmark" : "gtp")
                  + " -model \"model with spaces.bin.gz\""
                  + " -threads 3 -override-config numSearchThreads=3,logDir=benchmark/value");
    }

    void command(String value) throws Exception {
      Files.writeString(path.resolve("control"), value);
    }

    void awaitFile(String name) throws Exception {
      await(() -> Files.exists(path.resolve(name)));
    }

    void awaitReady() throws Exception {
      awaitFile("ready");
    }

    boolean alive() throws Exception {
      Path pid = path.resolve("pid");
      return Files.exists(pid)
          && ProcessHandle.of(Long.parseLong(Files.readString(pid)))
              .map(ProcessHandle::isAlive)
              .orElse(false);
    }

    void reset() throws Exception {
      for (String name : List.of("ready", "pid", "eof", "control", "checkpoint"))
        Files.deleteIfExists(path.resolve(name));
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "controlled process did not reach the requested boundary");
  }

  private static final class NamedEngine extends Leelaz {
    NamedEngine(String command) throws java.io.IOException {
      super(command);
    }

    @Override
    public String getEngineName(int index) {
      return "Controlled KataGo";
    }

    @Override
    long engineStartupSynchronizationTimeoutMillis() {
      return isBenchmark() ? 1 : 3000;
    }
  }

  private static final class RecordingConsole extends GtpConsolePane {
    StringBuffer output;

    private RecordingConsole() {
      super(null);
    }

    @Override
    public void addLine(String line) {
      output.append(line);
    }
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void resetTitle() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class Environment implements AutoCloseable {
    final Config oldConfig = Lizzie.config;
    final Leelaz oldPrimary = Lizzie.leelaz, oldSecondary = Lizzie.leelaz2;
    final EngineManager oldManager = Lizzie.engineManager;
    final Board oldBoard = Lizzie.board;
    final LizzieFrame oldFrame = Lizzie.frame;
    final Menu oldMenu = LizzieFrame.menu;
    final GtpConsolePane oldConsole = Lizzie.gtpConsole;
    final int oldWidth = Board.boardWidth, oldHeight = Board.boardHeight;
    final int oldIndex = EngineManager.currentEngineNo, oldIndex2 = EngineManager.currentEngineNo2;
    final boolean oldEmpty = EngineManager.isEmpty;
    final Path root;
    final RecordingConsole console;
    final List<Leelaz> engines = new ArrayList<>();

    Environment(Path root) throws Exception {
      this.root = root;
      Lizzie.config = ConfigTestHelper.createForTests(root);
      Lizzie.config.extraMode = ExtraMode.Normal;
      Lizzie.config.fastChange = true;
      Lizzie.config.autoCheckEngineAlive = false;
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = null;
      Lizzie.frame = null;
      LizzieFrame.menu = null;
      Board.boardWidth = Board.boardHeight = 19;
      Zobrist.init();
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      Lizzie.board = board;
      Lizzie.frame = allocate(QuietFrame.class);
      console = allocate(RecordingConsole.class);
      console.output = new StringBuffer();
      Lizzie.gtpConsole = console;
      EngineManager.currentEngineNo = EngineManager.currentEngineNo2 = -1;
      EngineManager.isEmpty = true;
    }

    Fixture fixture(String name) throws Exception {
      Fixture fixture = new Fixture(root.resolve(name));
      engines.add(fixture.engine);
      return fixture;
    }

    EngineManager manager(Leelaz... catalog) {
      for (Leelaz engine : catalog) if (!engines.contains(engine)) engines.add(engine);
      EngineManager manager = new EngineManager(new ArrayList<>(List.of(catalog)));
      Lizzie.setEngineManager(manager);
      return manager;
    }

    @Override
    public void close() throws Exception {
      try {
        for (Leelaz engine : engines) {
          engine.cancelBenchmark();
          if (engine.benchmarkExecution() != null)
            engine.benchmarkExecution().completion().get(5, TimeUnit.SECONDS);
          else if (engine.isStarted()) engine.forceQuit();
        }
        SwingUtilities.invokeAndWait(() -> {});
      } finally {
        Lizzie.config = oldConfig;
        Lizzie.setPrimaryEngine(oldPrimary);
        Lizzie.leelaz2 = oldSecondary;
        Lizzie.setEngineManager(oldManager);
        Lizzie.board = oldBoard;
        Lizzie.frame = oldFrame;
        LizzieFrame.menu = oldMenu;
        Lizzie.gtpConsole = oldConsole;
        Board.boardWidth = oldWidth;
        Board.boardHeight = oldHeight;
        Zobrist.init();
        EngineManager.currentEngineNo = oldIndex;
        EngineManager.currentEngineNo2 = oldIndex2;
        EngineManager.isEmpty = oldEmpty;
      }
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
