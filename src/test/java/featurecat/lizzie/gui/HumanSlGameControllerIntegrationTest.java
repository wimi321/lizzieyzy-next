package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingSession;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class HumanSlGameControllerIntegrationTest {
  private static final int BOARD_SIZE = 3;

  @Test
  void passShortcutUsesCoachControllerAndSchedulesTheAiTurn() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller = env.startCoach(runner);
      Input input = new Input();
      JPanel source = new JPanel();

      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyPressed(source, KeyEvent.VK_P)));

      assertTrue(runner.awaitRequest(), "the coach pass must schedule HumanSL immediately");
      assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
      assertTrue(controller.isAiThinking());
      assertSame(controller, Lizzie.frame.humanSlGame);

      controller.abort();
      assertTrue(controller.isFinished());
    }
  }

  @Test
  void passShortcutDuringTheAiTurnDoesNotChangeTheBoardOrScheduleAnotherRequest() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller =
          env.startCoach(runner, HumanSlTrainingConfig.PlayerColor.WHITE);
      Input input = new Input();
      JPanel source = new JPanel();

      assertTrue(runner.awaitRequest(), "the opening HumanSL turn must already be in flight");
      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyPressed(source, KeyEvent.VK_P)));

      assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
      assertEquals(1, runner.requestCount());
      assertTrue(controller.isAiThinking());
      assertSame(controller, Lizzie.frame.humanSlGame);

      controller.abort();
      assertTrue(controller.isFinished());
    }
  }

  @Test
  void staleAiResponseEndsCoachWithoutChangingTheNavigatedPosition() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller = env.startCoach(runner);
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();

      controller.humanPass();
      assertTrue(runner.awaitRequest());
      assertEquals(1, root.numberOfChildren());

      assertTrue(Lizzie.board.getHistory().previous().isPresent());
      assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
      runner.releaseResponse();

      assertTrue(awaitCondition(controller::isFinished, Duration.ofSeconds(2)));
      SwingUtilities.invokeAndWait(() -> {});
      assertNull(Lizzie.frame.humanSlGame);
      assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertEquals(
          1,
          root.numberOfChildren(),
          "the stale HumanSL pass must not become a variation on the navigated node");
    }
  }

  @Test
  void unifiedAiModeStopEndsCoachAndReportsThatItStoppedAnActiveMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      CoachFrame frame = (CoachFrame) Lizzie.frame;

      assertTrue(frame.stopAiPlayingAndPolicy());

      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
      assertFalse(frame.isPlayingAgainstLeelaz);
      assertFalse(frame.isAnaPlayingAgainstLeelaz);
    }
  }

  @Test
  void analysisPausedDuringPreparationIsRestoredWhenCoachEnds() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      TrackingLeelaz engine = new TrackingLeelaz();
      Lizzie.leelaz = engine;
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());

      controller.start(true);
      assertFalse(engine.pondering);

      controller.abort();

      assertTrue(engine.pondering);
      assertEquals(1, engine.resumeCount.get());
    }
  }

  @Test
  void newGameTransitionEndsCoachBeforeOpeningTheNextMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new ArrayList<String>();
      frame.newGameDialog = allocate(CancelledNewGameDialog.class);
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.leelaz = new Leelaz("");

      frame.startNewGame();

      assertEquals(List.of("stop-old-mode", "end-coach", "new-game-dialog"), frame.events);
      assertTrue(
          controller.isFinished(), "cancelling the new-game dialog must not revive AI Coach");
      assertNull(frame.humanSlGame);
    }
  }

  @Test
  void analyzeGameTransitionEndsCoachEvenWhenTheDialogIsCancelled() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new ArrayList<String>();
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.leelaz.noAnalyze = false;

      frame.startAnalyzeGameDialogReserved();

      assertEquals(List.of("stop-old-mode", "end-coach", "analyze-game-dialog"), frame.events);
      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
    }
  }

  @Test
  void continuePlayingTransitionEndsCoachBeforeStartingTheEngineMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new ArrayList<String>();
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.config.genmoveGameNoTime = true;

      frame.continueAiPlayingReserved(true, false, true, false);

      assertEquals(List.of("stop-old-mode", "end-coach"), frame.events);
      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
      assertTrue(frame.isPlayingAgainstLeelaz);
    }
  }

  @Test
  void engineGameTransitionEndsCoachBeforeOpeningTheNextMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new ArrayList<String>();
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      EngineManager.isEngineGame = false;

      frame.startEngineGameDialogReserved();

      assertEquals(List.of("end-coach", "engine-game-dialog"), frame.events);
      assertTrue(
          controller.isFinished(), "closing the engine-game dialog must not revive AI Coach");
      assertNull(frame.humanSlGame);
    }
  }

  private static HumanSlTrainingConfig coachConfig(HumanSlTrainingConfig.PlayerColor playerColor) {
    return HumanSlTrainingConfig.builder()
        .playerColor(playerColor)
        .fromCurrentPosition(true)
        .moveTimeSeconds(2)
        .build();
  }

  private static KeyEvent keyPressed(JPanel source, int keyCode) {
    return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED);
  }

  private static boolean awaitCondition(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(10L);
    }
    return condition.getAsBoolean();
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }

  private static final class BlockingHumanSlRunner extends HumanSlAnalysisRunner {
    private final CountDownLatch requestStarted = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();

    private BlockingHumanSlRunner() {
      super("katago analysis", Path.of("human.bin"));
    }

    @Override
    public Optional<String> bestHumanMove(
        BoardHistoryNode positionNode,
        String profile,
        int maxVisits,
        int rootSymmetries,
        Duration timeout) {
      requestCount.incrementAndGet();
      requestStarted.countDown();
      try {
        if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
          return Optional.empty();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
      return Optional.of("pass");
    }

    @Override
    public void cancelActiveRequests() {
      releaseResponse.countDown();
    }

    @Override
    public void close() {
      releaseResponse.countDown();
    }

    private boolean awaitRequest() throws InterruptedException {
      return requestStarted.await(2, TimeUnit.SECONDS);
    }

    private void releaseResponse() {
      releaseResponse.countDown();
    }

    private int requestCount() {
      return requestCount.get();
    }
  }

  private static class CoachFrame extends LizzieFrame {
    @Override
    public void clearKataEstimate() {}

    @Override
    public void showHumanSlTrainingBar(HumanSlGameController controller) {}

    @Override
    public void hideHumanSlTrainingBar(HumanSlGameController controller) {}

    @Override
    public void hideHumanSlCorrection(HumanSlGameController controller) {}

    @Override
    public void updateHumanSlTrainingBar() {}

    @Override
    public void setMainPanelFocus() {}

    @Override
    public void refresh() {}

    @Override
    public void updateTitle() {}
  }

  private static final class CoachBoard extends Board {
    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingLeelaz extends Leelaz {
    private final AtomicInteger resumeCount = new AtomicInteger();
    private boolean pondering;

    private TrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void ponder() {
      pondering = true;
      resumeCount.incrementAndGet();
    }
  }

  private static final class ModeTransitionFrame extends CoachFrame {
    private List<String> events;
    private CancelledNewGameDialog newGameDialog;

    @Override
    public void endHumanSlGameIfActive() {
      events.add("end-coach");
      super.endHumanSlGameIfActive();
    }

    @Override
    public boolean stopAiPlayingAndPolicy() {
      events.add("stop-old-mode");
      boolean wasHumanSlGame = humanSlGame != null && !humanSlGame.isFinished();
      endHumanSlGameIfActive();
      return wasHumanSlGame;
    }

    @Override
    protected NewGameDialog createNewGameDialog() {
      events.add("new-game-dialog");
      return newGameDialog;
    }

    @Override
    protected void showEngineGameDialogAfterModeTransition() {
      events.add("engine-game-dialog");
    }

    @Override
    protected void showAnalyzeGameDialogAfterModeTransition(boolean wasPondering) {
      events.add("analyze-game-dialog");
    }
  }

  private static final class SilentBottomToolbar extends BottomToolbar {
    private SilentBottomToolbar() {}

    @Override
    public void setChkShowBlack(boolean show) {}

    @Override
    public void setChkShowWhite(boolean show) {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void setChkShowBlack(boolean show) {}

    @Override
    public void setChkShowWhite(boolean show) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}
  }

  private static final class CancelledNewGameDialog extends NewGameDialog {
    private CancelledNewGameDialog() {
      super((Window) null);
    }

    @Override
    public void setVisible(boolean visible) {}

    @Override
    public boolean playerIsBlack() {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return true;
    }

    @Override
    public void dispose() {}
  }

  private static final class CoachEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousEngine;
    private final boolean previousEngineGame;
    private final boolean previousPreEngineGame;
    private final boolean previousEngineEmpty;
    private final BottomToolbar previousToolbar;
    private final Menu previousMenu;

    private CoachEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousEngine,
        boolean previousEngineGame,
        boolean previousPreEngineGame,
        boolean previousEngineEmpty,
        BottomToolbar previousToolbar,
        Menu previousMenu) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousEngine = previousEngine;
      this.previousEngineGame = previousEngineGame;
      this.previousPreEngineGame = previousPreEngineGame;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousToolbar = previousToolbar;
      this.previousMenu = previousMenu;
    }

    private static CoachEnvironment open() throws Exception {
      CoachEnvironment environment =
          new CoachEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.leelaz,
              EngineManager.isEngineGame,
              EngineManager.isPreEngineGame,
              EngineManager.isEmpty,
              LizzieFrame.toolbar,
              LizzieFrame.menu);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Config config = allocate(Config.class);
      config.playSound = false;
      config.newMoveNumberInBranch = false;
      Lizzie.config = config;
      Lizzie.frame = allocate(CoachFrame.class);
      Lizzie.leelaz = allocate(Leelaz.class);
      LizzieFrame.toolbar = allocate(SilentBottomToolbar.class);
      LizzieFrame.menu = allocate(SilentMenu.class);
      Board board = allocate(CoachBoard.class);
      board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.board = board;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      EngineManager.isEmpty = false;
      return environment;
    }

    private HumanSlGameController startCoach(BlockingHumanSlRunner runner) {
      return startCoach(runner, HumanSlTrainingConfig.PlayerColor.BLACK);
    }

    private HumanSlGameController startCoach(
        BlockingHumanSlRunner runner, HumanSlTrainingConfig.PlayerColor playerColor) {
      HumanSlGameController controller =
          new HumanSlGameController(runner, coachConfig(playerColor), new HumanSlTrainingSession());
      controller.start();
      assertFalse(controller.isFinished());
      return controller;
    }

    @Override
    public void close() {
      HumanSlGameController active = Lizzie.frame == null ? null : Lizzie.frame.humanSlGame;
      if (active != null && !active.isFinished()) {
        active.abort();
      }
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
      EngineManager.isEmpty = previousEngineEmpty;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
    }
  }
}
