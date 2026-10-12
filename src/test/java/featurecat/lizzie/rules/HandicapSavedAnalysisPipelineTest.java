package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Import-to-display protocol coverage. Uses Leelaz's real queue, asynchronous ACK reader, import
 * coordinator and ordinary info routing; no external engine or native window is launched.
 */
@Isolated("Uses shared Lizzie state and observes uncaught EDT failures")
class HandicapSavedAnalysisPipelineTest {
  @TempDir Path tempDirectory;

  @ParameterizedTest
  @CsvSource({"file, true", "file, false", "string, true", "string, false"})
  void synchronizedHandicapImportProtectsSavedAnalysisUntilDeeperRootInfo(
      String entrypoint, boolean readKomi) throws Exception {
    ResourceBundle previousResources = Lizzie.resourceBundle;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Leelaz previousSecondary = Lizzie.leelaz2;
    EngineManager previousManager = Lizzie.engineManager;
    int previousEngineNo = EngineManager.currentEngineNo;
    Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    List<Throwable> edtFailures = new CopyOnWriteArrayList<>();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> {
          if (thread.getName().startsWith("AWT-EventQueue")) edtFailures.add(failure);
          if (previousHandler != null) previousHandler.uncaughtException(thread, failure);
          else failure.printStackTrace();
        });
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(5)) {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings");
      Lizzie.config.readKomi = readKomi;
      Lizzie.config.enableLizzieCache = true;
      Lizzie.config.loadSgfLast = false;
      Lizzie.config.autoQuickAnalyzeOnLoad = false;
      Lizzie.leelaz2 = null;
      Lizzie.engineManager = null;
      EngineManager.currentEngineNo = 0;
      TestFrame frame = RulesLayerTestHarness.allocate(TestFrame.class);
      frame.loadResumes = new CopyOnWriteArrayList<>();
      Lizzie.frame = frame;
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      QuietMenu menu = RulesLayerTestHarness.allocate(QuietMenu.class);
      menu.txtKomi = new JTextField();
      LizzieFrame.menu = menu;
      LizzieFrame.toolbar = RulesLayerTestHarness.allocate(BottomToolbar.class);
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      engine.isLoaded = true;
      engine.komi = 7.5f;
      engine.orikomi = 7.5f;
      setField(Leelaz.class, engine, "bestMovesEnginename", "b11");
      setField(Leelaz.class, engine, "started", true);
      engine.commandLists.addAll(List.of("stop", "kata-analyze", "loadsgf"));
      setField(Leelaz.class, engine, "endGetCommandList", true);
      Lizzie.setPrimaryEngine(engine);
      EngineManager.isEmpty = false;
      List<String> restoredSnapshots = new CopyOnWriteArrayList<>();
      var transport =
          ExactSnapshotRestoreProtocolFixture.installAsync(
              engine,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  restoredSnapshots.add(
                      Files.readString(Path.of(command.substring("loadsgf ".length()))));
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              });
      try {
        String sgf =
            "(;SZ[5]HA[2]AB[aa][ee]PL[W]KM[0.5]"
                + "LZOP[b11 40 10 2 1 0 rootVisits=10\n"
                + "move C3 visits 10 winrate 6000 scoreMean 2 pv C3]"
                + ";W[bb];B[cc]LZ[b11 30 100 3 1 0 rootVisits=100\n"
                + "move D4 visits 50 winrate 7000 scoreMean 3 pv D4 C4])";
        CountDownLatch pausedAfterImport = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(
            () -> {
              try {
                if (entrypoint.equals("file")) {
                  Path input = tempDirectory.resolve("同步让子分析.SGF");
                  Files.writeString(input, sgf);
                  assertTrue(SGFParser.load(input.toString(), false, true));
                  // File finalization starts the real coordinator on the next EDT turn.
                  // This pause is queued after it and before any completion callback.
                  SwingUtilities.invokeLater(() -> pauseAutomaticResume(frame, pausedAfterImport));
                } else {
                  assertTrue(SGFParser.loadFromString(sgf, true));
                  pauseAutomaticResume(frame, pausedAfterImport);
                }
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
            });
        assertTrue(pausedAfterImport.await(5, TimeUnit.SECONDS));
        await(
            () -> !frame.loadResumes.isEmpty()
                && frame.loadResumes.stream().allMatch(resume -> resume.getCount() == 0),
            "the actual post-load resume continuation did not settle");
        awaitEngineIdle(engine, transport);
        assertTrue(LizzieFrame.canGoAfterload);
        assertTrue(frame.isUserAnalysisPaused());
        assertEquals(0.5, engine.komi, 0.0001);
        assertEquals(7.5, engine.orikomi, 0.0001);
        assertEquals(0.5, env.board().getHistory().getGameInfo().getKomi(), 0.0001);
        assertTrue(transport.commands().contains("komi 0.5"));
        assertFalse(restoredSnapshots.isEmpty(), "setup must use an ACK-backed loadsgf snapshot");
        BoardData root = env.board().getHistory().getStart().getData();
        BoardHistoryList restoredRoot = SGFParser.parseSgf(restoredSnapshots.get(0), true);
        assertEquals(0.5, restoredRoot.getGameInfo().getKomi(), 0.0001);
        assertArrayEquals(root.stones, restoredRoot.getStart().getData().stones);
        assertEquals(root.blackToPlay, restoredRoot.getStart().getData().blackToPlay);
        assertEquals(10, root.getPlayouts());
        assertFalse(root.isChanged, "position/komi synchronization must preserve imported analysis");

        navigateForward(env.board(), engine, transport);
        navigateForward(env.board(), engine, transport);
        BoardHistoryNode savedNode = env.current();
        BoardData saved = savedNode.getData();
        assertSavedPayload(saved, 100, 70, 3, "D4", List.of("D4", "C4"));
        SwingUtilities.invokeAndWait(() -> assertTrue(env.board().previousMove(false)));
        awaitHistoryRestores();
        awaitEngineIdle(engine, transport);
        navigateForward(env.board(), engine, transport);
        assertSame(savedNode, env.current());
        assertSavedPayload(saved, 100, 70, 3, "D4", List.of("D4", "C4"));

        engine.sendCommand("kata-analyze W 10");
        awaitEngineIdle(engine, transport);
        assertTrue(transport.commands().contains("kata-analyze W 10"));
        for (int visits : new int[] {99, 100, 101}) {
          // Child visits can exceed root work due to transpositions. They must not bypass cache.
          dispatchInfo(
              engine,
              transport,
              "info move C4 visits 50000 winrate 0.25 scoreLead -5 prior 0.2 pv C4 D3"
                  + " rootInfo visits " + visits + " winrate 0.9 scoreLead 50");
          assertEquals(visits, engine.getBestMovesPlayouts());
          boolean deeper = visits > 100;
          assertSavedPayload(
              saved,
              deeper ? visits : 100,
              deeper ? 25 : 70,
              deeper ? -5 : 3,
              deeper ? "C4" : "D4",
              deeper ? List.of("C4", "D3") : List.of("D4", "C4"));
        }
        AtomicReference<String> written = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
            () -> {
              try {
                written.set(SGFParser.saveToString(false));
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
            });
        BoardHistoryList reopened = SGFParser.parseSgf(written.get(), true);
        assertEquals(0.5, reopened.getGameInfo().getKomi(), 0.0001);
        assertEquals(10, reopened.getStart().getData().getPlayouts());
        assertSavedPayload(
            reopened.getStart().next().orElseThrow().next().orElseThrow().getData(),
            101, 25, -5, "C4", List.of("C4", "D3"));
      } finally {
        // Cancel/await this coordinator before restoring globals. A queue marker alone is not
        // sufficient because its completion posts another EDT action and a post-load thread.
        try {
          closeImportCoordinator(frame);
        } finally {
          try {
            awaitHistoryRestores();
            engine.sendCommand("stop");
            awaitEngineIdle(engine, transport);
          } finally {
            try {
              transport.close();
            } finally {
              SwingUtilities.invokeAndWait(() -> {});
            }
          }
        }
      }
      assertTrue(edtFailures.isEmpty(), () -> "uncaught EDT failures: " + edtFailures);
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            LizzieFrame.toolbar = previousToolbar;
            Lizzie.resourceBundle = previousResources;
            Lizzie.leelaz2 = previousSecondary;
            Lizzie.engineManager = previousManager;
            EngineManager.currentEngineNo = previousEngineNo;
          });
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
  }

  private static void pauseAutomaticResume(TestFrame frame, CountDownLatch paused) {
    try {
      // Imports deliberately clear the previous game's pause intent. Set the pause after that
      // transition, as when the user pauses while the new position is being synchronized.
      setField(LizzieFrame.class, frame, "userAnalysisPaused", true);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    } finally {
      paused.countDown();
    }
  }

  private static void assertSavedPayload(
      BoardData data, int visits, double winrate, double score, String coordinate, List<String> pv) {
    assertEquals(visits, data.getPlayouts());
    assertEquals(visits, data.rootVisits);
    assertEquals(winrate, data.winrate, 0.0001);
    assertEquals(score, data.scoreMean, 0.0001);
    assertEquals(coordinate, data.bestMoves.get(0).coordinate);
    assertEquals(pv, data.bestMoves.get(0).variation);
  }

  private static void navigateForward(
      Board board, Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport transport)
      throws Exception {
    SwingUtilities.invokeAndWait(() -> assertTrue(board.nextMove(false)));
    awaitHistoryRestores();
    awaitEngineIdle(engine, transport);
  }

  private static void awaitHistoryRestores() throws Exception {
    ExecutorService executor = (ExecutorService) getField(Board.class, null, "HISTORY_RESTORE_EXECUTOR");
    executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static void awaitEngineIdle(
      Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport transport) throws Exception {
    Method queueMethod = Leelaz.class.getDeclaredMethod("commandQueue");
    queueMethod.setAccessible(true);
    Collection<?> queue = (Collection<?>) queueMethod.invoke(engine);
    AtomicBoolean dispatchScheduled =
        (AtomicBoolean) getField(Leelaz.class, engine, "eventDispatchCommandScheduled");
    Field sending = Leelaz.class.getDeclaredField("normalCommandSendInProgress");
    sending.setAccessible(true);
    await(
        () -> {
          synchronized (queue) {
            try {
              return queue.isEmpty()
                  && !sending.getBoolean(engine)
                  && !dispatchScheduled.get()
                  && engine.isResponseUpToDate();
            } catch (IllegalAccessException failure) {
              throw new AssertionError(failure);
            }
          }
        },
        "queued GTP work did not finish with actual ACKs");
    transport.awaitResponses();
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static void dispatchInfo(
      Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport transport, String line)
      throws Exception {
    Method dispatch = Leelaz.class.getDeclaredMethod("dispatchReaderLineForTest", String.class);
    dispatch.setAccessible(true);
    ExecutorService reader =
        (ExecutorService) getField(transport.getClass(), transport, "responseReader");
    reader
        .submit(
            () -> {
              try {
                dispatch.invoke(engine, line);
              } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
              }
            })
        .get(5, TimeUnit.SECONDS);
    transport.awaitResponses();
  }

  private static void closeImportCoordinator(TestFrame frame) throws Exception {
    AtomicReference<ExecutorService> worker = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            Object coordinator = getField(LizzieFrame.class, frame, "kifuEngineSyncCoordinator");
            if (coordinator != null) {
              worker.set((ExecutorService) getField(coordinator.getClass(), coordinator, "executor"));
            }
          } catch (Exception failure) {
            throw new AssertionError(failure);
          } finally {
            frame.shutdownKifuEngineSyncCoordinator();
          }
        });
    if (worker.get() != null) {
      assertTrue(worker.get().awaitTermination(5, TimeUnit.SECONDS), "import worker did not terminate");
    }
    SwingUtilities.invokeAndWait(() -> {});
    for (CountDownLatch resume : frame.loadResumes) {
      assertTrue(resume.await(5, TimeUnit.SECONDS), "post-load continuation outlived its fixture");
    }
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> {});
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), message);
  }

  private static Object getField(Class<?> type, Object target, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Class<?> type, Object target, String name, Object value)
      throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class QuietMenu extends Menu {
    @Override
    public void toggleEngineMenuStatus(boolean pondering, boolean thinking) {}
  }

  private static final class TestFrame extends LizzieFrame {
    private List<CountDownLatch> loadResumes;

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      CountDownLatch completion = new CountDownLatch(1);
      loadResumes.add(completion);
      super.scheduleResumeAnalysisAfterLoad(
          delayMillis,
          () -> {
            try {
              action.run();
            } finally {
              completion.countDown();
            }
          });
    }

    @Override
    protected int showImportedRulesFailurePrompt(String message) {
      throw new AssertionError("unexpected imported-rules failure: " + message);
    }

    @Override public void refresh() {}
    @Override public void refresh(int mode) {}
    @Override public void onMainEnginePonder() {}
    @Override public void setPlayers(String white, String black) {}
    @Override public void resetTitle() {}
    @Override public void tryToResetByoTime() {}
    @Override public void requestProblemListRefresh() {}
    @Override public void refreshProblemListSnapshot() {}
    @Override public void clearKataEstimate() {}
    @Override public void requestAnalysisRefresh() {}
    @Override public void requestAnalysisTitleUpdate() {}
  }
}
