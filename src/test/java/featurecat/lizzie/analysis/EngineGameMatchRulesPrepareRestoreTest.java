package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.Acceptance;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameBatchSpecFactory;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.GameActivity;
import featurecat.lizzie.enginegame.GameOutcome;
import featurecat.lizzie.enginegame.MatchRulesSnapshot;
import featurecat.lizzie.enginegame.MatchRulesTexts;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import featurecat.lizzie.enginegame.StartFailure;
import featurecat.lizzie.enginegame.StartObserver;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineGameMatchRulesPrepareRestoreTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("black-cmd", "");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("white-cmd", "");
  private static final KataGoRules CHINESE = KataGoRules.parse("chinese").orElseThrow();
  private static final KataGoRules JAPANESE = KataGoRules.parse("japanese").orElseThrow();
  private static final KataGoRules TROMP = KataGoRules.parse("tromp-taylor").orElseThrow();

  private EngineManager previousManager;
  private Leelaz previousPrimary;
  private Config previousConfig;
  private LizzieFrame previousFrame;
  private BottomToolbar previousToolbar;
  private Menu previousMenu;
  private JFontMenu previousEngineMenu;
  private Board previousBoard;
  private WinrateGraph previousWinrateGraph;
  private GtpConsolePane previousGtpConsole;
  private boolean previousEmpty;
  private int previousEngineNo;

  private StatefulMatchRulesLeelaz black;
  private StatefulMatchRulesLeelaz white;
  private InlineEngineManager manager;
  private RecordingObserver observer;

  @BeforeEach
  void installFixture() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
    previousManager = Lizzie.engineManager;
    previousPrimary = Lizzie.leelaz;
    previousConfig = Lizzie.config;
    previousFrame = Lizzie.frame;
    previousToolbar = LizzieFrame.toolbar;
    previousMenu = LizzieFrame.menu;
    previousEngineMenu = Menu.engineMenu;
    previousBoard = Lizzie.board;
    previousWinrateGraph = LizzieFrame.winrateGraph;
    previousGtpConsole = Lizzie.gtpConsole;
    previousEmpty = EngineManager.isEmpty;
    previousEngineNo = EngineManager.currentEngineNo;

    EngineManager.resetEngineGameTransactionStateForTest();
    Lizzie.engineGame.resetForTest();
    Config config = allocate(Config.class);
    config.uiConfig = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.newEngineGameHandicap = 0;
    config.newEngineGameKomi = 7.5;
    config.pkAdvanceTimeSettings = false;
    config.enginePkPonder = false;
    config.extraMode = ExtraMode.Normal;
    Lizzie.config = config;
    black = new StatefulMatchRulesLeelaz(JAPANESE);
    white = new StatefulMatchRulesLeelaz(TROMP);
    black.bindLiveRuntime("black-cmd");
    white.bindLiveRuntime("white-cmd");
    Lizzie.setPrimaryEngine(black);
    Lizzie.frame = allocate(SilentFrame.class);
    LizzieFrame.toolbar = allocate(SilentToolbar.class);
    LizzieFrame.menu = allocate(SilentMenu.class);
    Menu.engineMenu = new JFontMenu();
    LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
    Board board = allocate(SilentBoard.class);
    board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
    Lizzie.board = board;
    Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
    manager = new InlineEngineManager(List.of(black, white));
    Lizzie.engineManager = manager;
    EngineManager.isEmpty = false;
    EngineManager.currentEngineNo = 0;
    observer = new RecordingObserver();
    Lizzie.engineGame.replaceChromeForTest(transition -> {});
    Lizzie.engineGame.installMatchRulesConsentForTest(decision -> true);
  }

  @AfterEach
  void restoreFixture() throws Exception {
    try {
      if (Lizzie.engineGame != null) {
        Lizzie.engineGame.stop();
      }
      manager.awaitStartupWorkers();
    } finally {
      Lizzie.engineGame.replaceChromeForTest(null);
      Lizzie.engineGame.installMatchRulesConsentForTest(null);
      Lizzie.engineGame.resetForTest();
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.engineManager = previousManager;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
      Menu.engineMenu = previousEngineMenu;
      Lizzie.board = previousBoard;
      LizzieFrame.winrateGraph = previousWinrateGraph;
      Lizzie.gtpConsole = previousGtpConsole;
    }
    SwingUtilities.invokeAndWait(() -> {});
  }

  @Test
  void readWriteEnginesUnifyToTargetBeforeFirstSearchAndRestoreOriginals() {
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(CHINESE), observer);
    assertInstanceOf(Acceptance.Accepted.class, acceptance, () -> String.valueOf(acceptance));
    awaitPlaying();
    assertEquals(0, black.searchesBeforeTarget.get());
    assertEquals(0, white.searchesBeforeTarget.get());
    assertTrue(black.effective().semanticallyEquals(CHINESE));
    assertTrue(white.effective().semanticallyEquals(CHINESE));
    assertTrue(black.searches.get() + white.searches.get() > 0);
    MatchRulesSnapshot snapshot = Lizzie.engineGame.matchRulesSnapshot();
    assertNotNull(snapshot);
    assertTrue(snapshot.confirmed());
    ResourceBundle bundle = Lizzie.resourceBundle;
    assertEquals(
        bundle.getString("LizzieFrame.currentRules.chinese"), snapshot.mainSummary(bundle));

    Lizzie.engineGame.stop();
    awaitRestored();
    assertTrue(black.effective().semanticallyEquals(JAPANESE));
    assertTrue(white.effective().semanticallyEquals(TROMP));
  }

  @Test
  void readOnlyMismatchRejectsWithoutOverlay() {
    black.readOnly(JAPANESE);
    white.readOnly(CHINESE);
    Acceptance acceptance = Lizzie.engineGame.accept(genmoveSpec(CHINESE), observer);
    assertInstanceOf(Acceptance.Accepted.class, acceptance, () -> String.valueOf(acceptance));
    awaitFailedStart();
    assertEquals(0, black.searches.get());
    assertEquals(0, white.searches.get());
    assertTrue(black.effective().semanticallyEquals(JAPANESE));
    assertTrue(white.effective().semanticallyEquals(CHINESE));
    assertTrue(black.applies.get() == 0);
    MatchRulesSnapshot snapshot = Lizzie.engineGame.matchRulesSnapshot();
    assertEquals(MatchRulesSnapshot.Phase.FAILED, snapshot.phase());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
    assertEquals(
        snapshot.mainSummary(Lizzie.resourceBundle),
        EngineGamePresentation.matchRulesCaption(
            Lizzie.engineGame.current(),
            snapshot,
            Lizzie.board.getHistory().getGameInfo(),
            Lizzie.resourceBundle));
  }

  @Test
  void writeOnlyKeepsOwnRulesAndRequiresConsent() {
    black.writeOnly(JAPANESE);
    white.readWrite(TROMP);
    AtomicInteger prompts = new AtomicInteger();
    Lizzie.engineGame.installMatchRulesConsentForTest(
        decision -> {
          prompts.incrementAndGet();
          ResourceBundle bundle = Lizzie.resourceBundle;
          String message = MatchRulesTexts.consentMessage(decision, bundle);
          assertTrue(message.contains(bundle.getString("MatchRules.black")));
          assertTrue(message.contains(bundle.getString("MatchRules.unconfirmed")));
          assertFalse(message.contains("QUERY_UNSUPPORTED"));
          return true;
        });
    assertInstanceOf(
        Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(CHINESE), observer));
    awaitPlaying();
    assertEquals(1, prompts.get());
    assertTrue(black.effective().semanticallyEquals(JAPANESE));
    assertEquals(0, black.applies.get());
    assertTrue(white.effective().semanticallyEquals(CHINESE));
    assertTrue(Lizzie.engineGame.matchRulesSnapshot().unverified());
  }

  @Test
  void refusedUnverifiedConsentRestoresAndDoesNotSearch() {
    black.writeOnly(JAPANESE);
    white.writeOnly(TROMP);
    Lizzie.engineGame.installMatchRulesConsentForTest(decision -> false);
    assertInstanceOf(
        Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(CHINESE), observer));
    awaitFailedStart();
    assertEquals(0, black.searches.get() + white.searches.get());
    assertTrue(black.effective().semanticallyEquals(JAPANESE));
    assertTrue(white.effective().semanticallyEquals(TROMP));
    assertInstanceOf(StartFailure.MatchRulesFailed.class, observer.failures.get(0));
    MatchRulesSnapshot snapshot = Lizzie.engineGame.matchRulesSnapshot();
    assertEquals(MatchRulesSnapshot.Phase.FAILED, snapshot.phase());
    assertNull(Lizzie.board.getHistory().getGameInfo().engineGameRecord());
    assertInstanceOf(EngineGameSnapshot.Idle.class, Lizzie.engineGame.current());
  }

  @Test
  void supportedQueryFailureRejectsAndDoesNotBecomeUnverified() {
    black.failNextQuery();
    white.readWrite(TROMP);
    assertInstanceOf(
        Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(CHINESE), observer));
    awaitFailedStart();
    assertEquals(0, black.searches.get() + white.searches.get());
    assertEquals(0, black.applies.get());
    assertEquals(0, white.applies.get());
    MatchRulesSnapshot snapshot = Lizzie.engineGame.matchRulesSnapshot();
    assertEquals(MatchRulesSnapshot.Phase.FAILED, snapshot.phase());
    assertFalse(snapshot.unverified());
  }

  @Test
  void partialSetFailureRestoresTheModifiedEngine() {
    white.rejectNextSet();
    assertInstanceOf(
        Acceptance.Accepted.class, Lizzie.engineGame.accept(genmoveSpec(CHINESE), observer));
    awaitFailedStart();
    assertEquals(0, black.searches.get() + white.searches.get());
    awaitRestored();
    assertTrue(black.effective().semanticallyEquals(JAPANESE));
    assertTrue(white.effective().semanticallyEquals(TROMP));
  }

  @Test
  void matchingConsentIsReusedAcrossColorExchange() throws Exception {
    black.writeOnly(JAPANESE);
    white.writeOnly(TROMP);
    AtomicInteger prompts = new AtomicInteger();
    Lizzie.engineGame.installMatchRulesConsentForTest(
        decision -> {
          prompts.incrementAndGet();
          assertEquals(2, decision.unverified().size());
          return true;
        });
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(FIRST)
                .second(SECOND)
                .genmove(true)
                .timeLimitEnabled(true)
                .firstTimeSeconds(2)
                .secondTimeSeconds(2)
                .batch(true)
                .batchLimit(2)
                .exchangeColors(true)
                .matchRules(CHINESE)
                .autosave(false)
                .build());
    assertInstanceOf(Acceptance.Accepted.class, Lizzie.engineGame.accept(spec, observer));
    awaitPlaying();
    Path outputRoot = Path.of("EngineGames");
    boolean outputRootExisted = Files.exists(outputRoot);
    Path batchOutput = outputRoot.resolve(Lizzie.engineGame.outputBatchName());
    assertFalse(Files.exists(batchOutput), "test must not claim existing game output");
    try {
      assertEquals(1, prompts.get());
      Lizzie.engineGame.complete(
          new GameOutcome.DoublePass(),
          Lizzie.engineGame.transaction().lifecycle().ownerToken(),
          0);
      assertTrue(Lizzie.engineGame.onOwnerRetired());
      awaitPlaying();
      assertEquals(1, prompts.get());
      Lizzie.engineGame.stop();
      awaitRestored();
    } finally {
      if (Files.exists(batchOutput)) {
        List<Path> generatedPaths;
        try (var paths = Files.walk(batchOutput)) {
          generatedPaths = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : generatedPaths) {
          Files.delete(path);
        }
      }
      if (!outputRootExisted && Files.isDirectory(outputRoot)) {
        boolean empty;
        try (var remaining = Files.newDirectoryStream(outputRoot)) {
          empty = !remaining.iterator().hasNext();
        }
        if (empty) {
          Files.delete(outputRoot);
        }
      }
    }
  }

  @Test
  void analysisReadWriteAlsoUnifiesBeforeFirstSearch() {
    assertInstanceOf(
        Acceptance.Accepted.class, Lizzie.engineGame.accept(analysisSpec(CHINESE), observer));
    awaitPlaying();
    assertEquals(0, black.searchesBeforeTarget.get());
    assertEquals(0, white.searchesBeforeTarget.get());
    assertTrue(black.effective().semanticallyEquals(CHINESE));
    assertTrue(white.effective().semanticallyEquals(CHINESE));
  }

  private static EngineGameBatchSpec genmoveSpec(KataGoRules rules) {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .genmove(true)
            .timeLimitEnabled(true)
            .firstTimeSeconds(2)
            .secondTimeSeconds(2)
            .matchRules(rules)
            .autosave(false)
            .build());
  }

  private static EngineGameBatchSpec analysisSpec(KataGoRules rules) {
    return EngineGameBatchSpecFactory.from(
        EngineGameParsedStart.builder()
            .first(FIRST)
            .second(SECOND)
            .visitLimitEnabled(true)
            .firstVisits(10)
            .secondVisits(10)
            .matchRules(rules)
            .autosave(false)
            .build());
  }

  private void awaitPlaying() {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < deadline) {
      if (Lizzie.engineGame.current() instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.Playing) {
        return;
      }
      if (!observer.failures.isEmpty()) {
        throw new AssertionError("start failed: " + observer.failures);
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    throw new AssertionError("not playing: " + Lizzie.engineGame.current());
  }

  private void awaitFailedStart() {
    try {
      manager.awaitStartupWorkers();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
    assertFalse(observer.failures.isEmpty(), () -> "did not fail: " + Lizzie.engineGame.current());
  }

  private void awaitRestored() {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < deadline) {
      if (black.effective().semanticallyEquals(JAPANESE)
          && white.effective().semanticallyEquals(TROMP)) {
        return;
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    throw new AssertionError(
        "not restored black=" + black.effective() + " white=" + white.effective());
  }

  private static final class RecordingObserver implements StartObserver {
    private int playing;
    private final List<StartFailure> failures = new ArrayList<>();

    @Override
    public void playing() {
      playing++;
    }

    @Override
    public void startFailed(StartFailure failure) {
      failures.add(failure);
    }
  }

  private static final class InlineEngineManager extends EngineManager {
    private final List<Thread> startupWorkers = new CopyOnWriteArrayList<>();

    private void awaitStartupWorkers() throws InterruptedException {
      for (Thread worker : startupWorkers) {
        worker.join(TimeUnit.SECONDS.toMillis(8));
        assertFalse(worker.isAlive(), () -> "startup worker did not finish: " + worker.getName());
      }
    }

    private InlineEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {}

    @Override
    protected void dispatchEngineGameUi(Runnable update) {
      update.run();
    }

    @Override
    protected Thread createEngineGameWorker(Runnable task, String name) {
      if (name != null && name.startsWith("engine-game-match-rules-restore")) {
        return new Thread(task, name) {
          @Override
          public synchronized void start() {
            run();
          }
        };
      }
      Thread worker = super.createEngineGameWorker(task, name);
      startupWorkers.add(worker);
      return worker;
    }

    @Override
    protected Thread createEngineGameRetirementContinuationWorker(Runnable task, String name) {
      return new Thread(task, name) {
        @Override
        public synchronized void start() {
          // Tests drive onOwnerRetired explicitly after restore.
        }
      };
    }
  }

  private static final class StatefulMatchRulesLeelaz extends Leelaz {
    private final AtomicReference<KataGoRules> effective;
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger searchesBeforeTarget = new AtomicInteger();
    private final AtomicInteger applies = new AtomicInteger();
    private final AtomicBoolean failQuery = new AtomicBoolean();
    private final AtomicBoolean rejectSet = new AtomicBoolean();

    private StatefulMatchRulesLeelaz(KataGoRules initial) throws Exception {
      super("");
      this.effective = new AtomicReference<>(initial);
    }

    private void bindLiveRuntime(String command) {
      ExactSnapshotRestoreProtocolFixture.install(
          this, ignored -> ExactSnapshotRestoreProtocolFixture.Response.success());
      started = true;
      isLoaded = true;
      isCheckingName = false;
      isKatago = true;
      firstLoad = false;
      width = 19;
      height = 19;
      setEngineCommand(command);
      oriEnginename = command;
      currentEnginename = command;
      readWrite(effective.get());
      installMatchRulesTestHook(
          new MatchRulesTestHook() {
            @Override
            public void query(Leelaz engine) {
              if (failQuery.getAndSet(false)) {
                engine.failEngineRulesForTest(
                    EngineRulesResult.Status.QUERY_FAILED, EngineRulesResult.Reason.QUERY_REJECTED);
                return;
              }
              engine.confirmEngineRulesForTest(effective.get());
            }

            @Override
            public void apply(Leelaz engine, KataGoRules requested) {
              applies.incrementAndGet();
              if (rejectSet.getAndSet(false)) {
                engine.failEngineRulesForTest(
                    EngineRulesResult.Status.SET_FAILED, EngineRulesResult.Reason.SET_REJECTED);
                return;
              }
              effective.set(requested);
              engine.confirmEngineRulesForTest(requested);
            }
          });
    }

    private KataGoRules effective() {
      return effective.get();
    }

    private void readWrite(KataGoRules rules) {
      effective.set(rules);
      commandLists.clear();
      commandLists.add("kata-set-rules");
      commandLists.add("kata-get-rules");
    }

    private void readOnly(KataGoRules rules) {
      effective.set(rules);
      commandLists.clear();
      commandLists.add("kata-get-rules");
    }

    private void writeOnly(KataGoRules rules) {
      effective.set(rules);
      commandLists.clear();
      commandLists.add("kata-set-rules");
    }

    private void failNextQuery() {
      failQuery.set(true);
    }

    private void rejectNextSet() {
      rejectSet.set(true);
    }

    private void recordSearch() {
      searches.incrementAndGet();
      KataGoRules current = effective.get();
      if (current == null || !current.semanticallyEquals(CHINESE)) {
        searchesBeforeTarget.incrementAndGet();
      }
    }

    @Override
    boolean genmoveForPk(String color, EngineManager.EngineGameOwnerTransaction transaction) {
      recordSearch();
      return true;
    }

    @Override
    public void ponder() {
      recordSearch();
      cmdNumber++;
    }

    @Override
    public void ponder(boolean addPlayer, boolean blackToPlay) {
      ponder();
    }

    @Override
    public void notPondering() {}

    @Override
    public void clearBestMoves() {}

    @Override
    public String getEngineName(int index) {
      return oriEngineCommand == null || oriEngineCommand.isEmpty() ? "engine" : oriEngineCommand;
    }

    @Override
    public void nameCmd() {}

    @Override
    public void nameCmdfornoponder() {}

    @Override
    public void clearWithoutPonder() {}
  }

  private static final class SilentFrame extends LizzieFrame {
    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public boolean isInputRoutingInitialized() {
      return true;
    }

    @Override
    public void addInput(boolean shouldAdd) {}

    @Override
    public void removeInput(boolean shouldRemove) {}

    @Override
    public void setResult(String result) {}

    @Override
    public void resetTitle() {}

    @Override
    public void restoreWRN(boolean isGenmove) {}

    @Override
    public void refresh() {}

    @Override
    public void setPlayers(String whiteName, String blackName) {}

    @Override
    public void updateTitle() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void clearWRNforGame(boolean isGenmove) {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void showUnsupportedWebSocketAdvancedClock() {}
  }

  private static final class SilentToolbar extends BottomToolbar {
    @Override
    public void enableDisabelForEngineGame(boolean enable) {}
  }

  private static final class SilentMenu extends Menu {
    @Override
    public void changeicon(int index) {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}

    @Override
    public void setBtnRankMark() {}

    @Override
    public void updateMenuStatusForEngine() {}
  }

  private static final class SilentBoard extends Board {
    @Override
    public void clear(boolean isEngineGame) {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((java.awt.Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }
}
