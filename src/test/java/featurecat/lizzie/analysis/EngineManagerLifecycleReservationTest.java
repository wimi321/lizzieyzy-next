package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineManagerLifecycleReservationTest {
  private JFontMenu previousEngineMenu;

  @BeforeEach
  void installHeadlessEngineMenu() {
    previousEngineMenu = Menu.engineMenu;
    if (Menu.engineMenu == null) {
      Menu.engineMenu = new SilentJFontMenu();
    }
  }

  @AfterEach
  void restoreHeadlessEngineMenu() {
    Menu.engineMenu = previousEngineMenu;
  }


  @Test
  void setupModeRejectsForegroundEngineSwitchBeforeLifecyclePreparation() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    SetupGuardEngineManager manager = new SetupGuardEngineManager(List.of(current, target));
    try {
      Lizzie.board = preparedRestoreBoard();
      Lizzie.board.setSetupMode(true);
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);

      assertEquals(1, manager.setupModeBlockCount);
      assertSame(current, Lizzie.leelaz);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void switchReservesCurrentAndFrozenTargetWithoutSeparateMirrorReservation() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz future = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz mirror = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, future));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      current.started = true;
      current.isLoaded = true;
      future.started = true;
      future.isLoaded = true;
      mirror.started = true;
      mirror.isLoaded = true;
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = mirror;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);

      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(future.hasExclusiveGtpWorkInProgress());
      assertFalse(mirror.hasExclusiveGtpWorkInProgress());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void secondarySwitchReservesPreviousSecondaryAndTargetWithoutReservingPrimaryMirror()
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingSwitchLeelaz primary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz currentSecondary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(primary, currentSecondary, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      primary.started = true;
      primary.isLoaded = true;
      currentSecondary.started = true;
      currentSecondary.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = currentSecondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      manager.switchEngine(2, false);

      assertFalse(primary.hasExclusiveGtpWorkInProgress());
      assertTrue(currentSecondary.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  @Test
  void switchExecutionUsesFrozenEnginesWhenCatalogSlotsChangeAfterReservation() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz replacementCurrent = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz replacementTarget = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(new ArrayList<>(List.of(current, target)));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      current.onLifecycleReservation =
          () -> {
            manager.engineList.set(0, replacementCurrent);
            manager.engineList.set(1, replacementTarget);
          };

      manager.switchEngine(1, true);

      assertSame(target, Lizzie.leelaz);
      assertSame(target, manager.synchronizationEngine);
      assertTrue(current.commands.contains("name"));
      assertFalse(replacementCurrent.commands.contains("name"));
      assertTrue(replacementTarget.commands.isEmpty());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void switchReleaseFenceUsesTheSameCatalogInstanceAsExecution() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    Menu previousMenu = LizzieFrame.menu;
    BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    EngineManager previousManager = Lizzie.engineManager;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz firstTarget = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz laterTarget = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(
            new TargetChangingList(current, firstTarget, laterTarget));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Menu.engineMenu = new SilentJFontMenu();
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      Lizzie.engineManager = manager;
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      Lizzie.board = preparedRestoreBoard();
      current.started = true;
      current.isLoaded = true;
      firstTarget.started = true;
      firstTarget.isLoaded = true;
      laterTarget.started = true;
      laterTarget.isLoaded = true;
      setLeelazField(current, "engineStateUnrestored", true);
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      assertTrue(manager.switchEngineIfAvailable(1, true));

      RecordingSwitchLeelaz executedTarget = (RecordingSwitchLeelaz) manager.synchronizationEngine;
      assertSame(executedTarget, Lizzie.leelaz);
      manager.synchronization.run();
      manager.afterSync.run();
      assertEquals(1, executedTarget.boardSynchronizationConfirmations);
      assertFalse(
          executedTarget.hasExclusiveGtpLifecycleTransitionForTest(),
          "the convergent route releases reservations before the stable frame/fence handoff");
      executedTarget.completeBoardSynchronization();
      assertFalse(executedTarget.hasExclusiveGtpWorkInProgress());
      assertEquals(
          0,
          executedTarget == firstTarget
              ? laterTarget.boardSynchronizationConfirmations
              : firstTarget.boardSynchronizationConfirmations);
    } finally {
      firstTarget.completeBoardSynchronization();
      laterTarget.completeBoardSynchronization();
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      Menu.engineMenu = previousEngineMenu;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      Lizzie.engineManager = previousManager;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void switchEmptyPreparationCannotReenterExactRestoreAfterLifecycleEffects() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    Menu previousMenu = LizzieFrame.menu;
    BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    EngineManager previousManager = Lizzie.engineManager;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    PreparedRestoreBoard board = fallbackRestoreBoard();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Menu.engineMenu = new SilentJFontMenu();
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      Lizzie.engineManager = manager;
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      Lizzie.board = board;
      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);
      board.getHistory().getStart().getData().stones[Board.getIndex(3, 3)] = Stone.BLACK;
      manager.synchronization.run();

      assertTrue(board.rootRestoreReceived);
      assertFalse(board.genericRestoreReceived);
      assertFalse(board.preparedRestoreReceived);
      assertTrue(target.loadedSgf.isEmpty());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.leelaz = previousPrimary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      Menu.engineMenu = previousEngineMenu;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      Lizzie.engineManager = previousManager;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void sameSlotDoubleEngineSelectionIsRejectedBeforeLifecyclePreparation() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    Leelaz current = new Leelaz("");
    Leelaz secondary = new Leelaz("");
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, secondary));
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.board = preparedRestoreBoard();
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      assertFalse(manager.switchEngineIfAvailable(1, true));

      assertEquals(0, manager.switchCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  @Test
  void updateEnginesSameSizeFreezesExactRestoreBeforeReplacementStarts() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19);
    try {
      state.install();
      state.previousForegroundEngine.onForceQuit =
          () -> {
            BoardHistoryList history = state.board.getHistory();
            history.getStart().getData().stones[Board.getIndex(3, 3)] = Stone.EMPTY;
            history.getGameInfo().setKomiNoMenu(7.5);
            history.add(moveNode(0, 1, Stone.BLACK, false, 2));
          };
      state.manager.updateEngines();
      Leelaz preparedTarget = state.manager.engineList.get(0);
      Leelaz.EngineModeReservation competingReservation =
          preparedTarget.beginEngineModeReservation();
      if (competingReservation != null) {
        competingReservation.close();
      }
      assertNull(competingReservation);
      assertTrue(state.previousForegroundEngine.hasExclusiveGtpWorkInProgress());
      state.releaseStartup();

      String commands = waitForLog(state.commandLog, "play W Q4", 2000L);
      assertEquals(1, countCommands(commands, "loadsgf "));
      assertTrue(commands.contains("AB[dd]"));
      assertTrue(commands.contains("KM[6.5]"));
      assertTrue(commands.contains("play W Q4"));
      assertFalse(commands.contains("play B A18"));
      assertEquals(19, Board.boardWidth);
      assertEquals(19, Board.boardHeight);
      awaitLifecycleTransitionReleased(preparedTarget);
      assertFalse(preparedTarget.hasExclusiveGtpLifecycleTransitionForTest());
      assertNull(preparedTarget.beginEngineModeReservation());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
          preparedTarget.previewForegroundAnalysisLeaseAvailability());
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      state.releaseBoardFence();
      awaitEngineStartupReady();
      Leelaz.EngineModeReservation afterFence = preparedTarget.beginEngineModeReservation();
      assertNotNull(afterFence);
      afterFence.close();
      assertFalse(state.previousForegroundEngine.hasExclusiveGtpWorkInProgress());
    } finally {
      state.restore();
    }
  }

  @Test
  void updateEnginesConvergesToNavigatedBoardWhileReplacementReadinessDelayed() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19);
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      // Production Board navigation remains available while the replacement readiness is gated.
      assertTrue(state.board.previousMove(false));
      state.releaseStartup();

      String commands = waitForCommandCount(state.commandLog, "loadsgf ", 2, 2000L);
      assertTrue(state.board.nextMove(false));
      state.releaseCatchUp();
      commands = waitForCommandCount(state.commandLog, "loadsgf ", 3, 2000L);
      assertEquals(3, countCommands(commands, "loadsgf "));
      assertEquals(1, state.board.getHistory().getData().moveNumber);
      assertEquals(Stone.WHITE, state.board.getHistory().getData().lastMoveColor);
      awaitLifecycleTransitionReleased(replacement);
      assertFalse(replacement.hasExclusiveGtpLifecycleTransitionForTest());
      assertNull(replacement.beginEngineModeReservation());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
          replacement.previewForegroundAnalysisLeaseAvailability());
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      state.releaseBoardFence();
      awaitEngineStartupReady();
      Leelaz.EngineModeReservation afterFence = replacement.beginEngineModeReservation();
      assertNotNull(afterFence);
      afterFence.close();
      assertFalse(state.previousForegroundEngine.hasExclusiveGtpWorkInProgress());
    } finally {
      state.restore();
    }
  }

  @Test
  void updateEnginesRestoreFailureLeavesReplacementUnavailableAndReleasesReservation()
      throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19);
    try {
      state.install();
      state.failLoadSgf();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      state.releaseStartup();

      waitForLog(state.commandLog, "loadsgf ", 2000L);
      awaitEngineUnavailable(replacement);
      awaitReservationReleased(replacement);
      assertFalse(replacement.isLoaded());
      assertFalse(replacement.hasUnrestoredReadBoardGmaState());
      assertEquals(1, countCommands(Files.readString(state.commandLog), "loadsgf "));
      Leelaz.EngineModeReservation recovery = replacement.beginEngineModeReservation();
      assertNotNull(recovery);
      recovery.close();
    } finally {
      state.restore();
    }
  }

  @Test
  void updateEnginesFinalFenceFailureQuarantinesReplacementAndReleasesCompletionGate()
      throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19);
    try {
      state.install();
      state.failFence();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      state.releaseStartup();

      waitForCommandCount(state.commandLog, "name", 2, 2000L);
      awaitLifecycleTransitionReleased(replacement);
      assertFalse(replacement.hasExclusiveGtpLifecycleTransitionForTest());
      assertNull(replacement.beginEngineModeReservation());
      state.releaseBoardFence();
      awaitEngineUnavailable(replacement);
      assertFalse(replacement.isLoaded());
      assertFalse(replacement.hasUnrestoredReadBoardGmaState());
      assertFalse(
          Lizzie.engineStartupStatus.snapshot().state == EngineStartupStatus.State.READY);
      Leelaz.EngineModeReservation recovery = replacement.beginEngineModeReservation();
      assertNotNull(recovery);
      recovery.close();
    } finally {
      state.restore();
    }
  }

  @Test
  void updateEnginesDifferentSizeSkipsFrozenExactRestoreAndClearsBoard() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(13, 19);
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      state.releaseStartup();

      waitForLog(state.commandLog, "list_commands", 2000L);
      awaitLifecycleTransitionReleased(replacement);
      state.releaseBoardFence();
      awaitEngineStartupReady();
      assertEquals(0, countCommands(Files.readString(state.commandLog), "loadsgf "));
      assertEquals(1, state.board.clearCount);
      // The frozen root replay converges through one catch-up root replay of the cleared board.
      assertEquals(2, state.board.rootRestoreCount);
      assertEquals(0, state.board.rootMoves.size());
      assertEquals(0, state.board.getHistory().getData().moveNumber);
      assertTrue(
          java.util.Arrays.stream(state.board.getHistory().getData().stones)
              .allMatch(stone -> stone == Stone.EMPTY));
      assertEquals(13, Board.boardWidth);
      assertEquals(19, Board.boardHeight);
    } finally {
      state.restore();
    }
  }
  @Test
  void updateEnginesConvergesBothReplacementEnginesBeforeReady() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19, true);
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      Leelaz mirror = state.manager.engineList.get(1);
      // Navigate while both replacement engines' readiness is gated.
      assertTrue(state.board.previousMove(false));
      state.releaseStartup();

      // The frozen round (2 loadsgf commands, one per captured engine) restores the
      // pre-navigation frame; the frame recheck rejects it and starts a catch-up round whose
      // loadsgf responses are gated on the catch-up gate.
      waitForCommandCount(state.commandLog, "loadsgf ", 4, 10_000L);
      // Navigate again while both engines are blocked in the catch-up round.
      assertTrue(state.board.nextMove(false));
      state.releaseCatchUp();
      waitForCommandCount(state.commandLog, "loadsgf ", 6, 10_000L);
      assertEquals(1, state.board.getHistory().getData().moveNumber);
      assertEquals(Stone.WHITE, state.board.getHistory().getData().lastMoveColor);

      // Both captured replacement engines converge to the final Board position before Ready/fence
      // completion: every round restores the static root, and later catch-up rounds replay the
      // Board's final white tail to both engines.
      waitForCommandCount(state.commandLog, "name", 4, 10_000L);
      String commands = Files.readString(state.commandLog);
      assertEquals(6, countCommands(commands, "loadsgf "));
      List<String> restores = sgfLines(commands);
      assertEquals(6, restores.size());
      assertTrue(restores.get(0).contains("AB[dd]"), "target frozen round must restore the root");
      assertTrue(restores.get(1).contains("AB[dd]"), "mirror frozen round must restore the root");
      assertTrue(
          restores.stream().allMatch(sgf -> sgf.contains("KM[6.5]")),
          "navigation must preserve the Board's captured komi on every replacement route");
      assertEquals(4, countCommands(commands, "play W Q4"));

      awaitLifecycleTransitionReleased(replacement);
      awaitLifecycleTransitionReleased(mirror);
      assertFalse(replacement.hasExclusiveGtpLifecycleTransitionForTest());
      assertFalse(mirror.hasExclusiveGtpLifecycleTransitionForTest());
      // The final fence is pending: Ready and the completion gate have not settled yet.
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertNull(replacement.beginEngineModeReservation());
      assertNull(mirror.beginEngineModeReservation());
      state.releaseBoardFence();
      awaitEngineStartupReady();
      assertTrue(replacement.isLoaded());
      assertTrue(mirror.isLoaded());
      Leelaz.EngineModeReservation targetAfterFence = replacement.beginEngineModeReservation();
      assertNotNull(targetAfterFence);
      targetAfterFence.close();
      Leelaz.EngineModeReservation mirrorAfterFence = mirror.beginEngineModeReservation();
      assertNotNull(mirrorAfterFence);
      mirrorAfterFence.close();
    } finally {
      state.restore();
    }
  }

  @Test
  void updateEnginesMirrorStartIOExceptionRetiresStartedTargetAndFailsClosed() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19, true, true);
    boolean previousFirstLaunchSession = forceFirstLaunchSession(true);
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      Leelaz mirror = state.manager.engineList.get(1);

      // The target started its fake engine process before the frozen mirror's startEngine threw
      // IOException; the replacement must retire every endpoint that actually started.
      awaitEngineUnavailable(replacement);
      awaitEngineUnavailable(mirror);
      assertFalse(replacement.isLoaded());
      assertFalse(mirror.isLoaded());
      assertFalse(replacement.isStarted(), "the started target must be retired, not leaked");
      assertFalse(mirror.isStarted());
      assertFalse(replacement.hasExclusiveGtpWorkInProgress());
      assertFalse(mirror.hasExclusiveGtpWorkInProgress());
      awaitLifecycleTransitionReleased(replacement);
      awaitLifecycleTransitionReleased(mirror);
      assertFalse(replacement.hasExclusiveGtpLifecycleTransitionForTest());
      assertFalse(mirror.hasExclusiveGtpLifecycleTransitionForTest());
      assertFalse(replacement.hasUnrestoredReadBoardGmaState());
      assertFalse(mirror.hasUnrestoredReadBoardGmaState());
      // The existing synchronization failure path keeps the replacement out of Ready/ponder.
      assertFalse(
          Lizzie.engineStartupStatus.snapshot().state == EngineStartupStatus.State.READY);
      // Lifecycle/completion ownership is released for a fresh admission.
      Leelaz.EngineModeReservation targetRecovery = replacement.beginEngineModeReservation();
      assertNotNull(targetRecovery);
      targetRecovery.close();
      Leelaz.EngineModeReservation mirrorRecovery = mirror.beginEngineModeReservation();
      assertNotNull(mirrorRecovery);
      mirrorRecovery.close();
    } finally {
      state.restore();
      forceFirstLaunchSession(previousFirstLaunchSession);
    }
  }

  @Test
  void updateEnginesRestoreSettlesInFlightReplacementBeforeRestoringGlobals() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19);
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);

      // The first name response is deliberately gated, so teardown begins while replacement startup
      // still owns the lifecycle transition.
      assertTrue(replacement.hasExclusiveGtpLifecycleTransitionForTest());
      state.restore();

      Process replacementProcess = (Process) getLeelazField(replacement, "process");
      ScheduledExecutorService stdoutExecutor =
          (ScheduledExecutorService) getLeelazField(replacement, "executor");
      ScheduledExecutorService stderrExecutor =
          (ScheduledExecutorService) getLeelazField(replacement, "executorErr");
      assertTrue(state.cleanupLifecycleSettled);
      assertTrue(state.cleanupProcessesStopped);
      assertTrue(state.cleanupExecutorsStopped);
      assertEquals(2, state.capturedReaderExecutors.size());
      assertTrue(
          state.capturedReaderExecutors.stream().allMatch(ScheduledExecutorService::isShutdown));
      assertTrue(
          state.capturedReaderExecutors.stream().allMatch(ScheduledExecutorService::isTerminated));
      assertFalse(replacement.hasExclusiveGtpLifecycleTransitionForTest());
      assertTrue(replacementProcess == null || !replacementProcess.isAlive());
      assertNotNull(stdoutExecutor);
      assertNotNull(stderrExecutor);
      assertTrue(stdoutExecutor.isShutdown());
      assertTrue(stderrExecutor.isShutdown());
      assertTrue(stdoutExecutor.isTerminated());
      assertTrue(stderrExecutor.isTerminated());
      assertSame(state.previousConfig, Lizzie.config);
      assertSame(state.previousMenu, LizzieFrame.menu);
    } finally {
      state.restore();
    }
  }

  @Test
  void testOnlyProcessFallbackCannotRescueProductionCleanupResult() throws Exception {
    FallbackCleanupLeelaz engine = new FallbackCleanupLeelaz();
    FallbackProcess process = new FallbackProcess();
    setLeelazField(engine, "process", process);

    assertFalse(UpdateEnginesState.stopReplacementProcesses(List.of(engine), 1L));
    assertEquals(1, process.forcibleDestroyCount.get());
    assertFalse(process.isAlive());
  }

  @Test
  void testOnlyExecutorFallbackCannotRescueProductionCleanupResult() throws Exception {
    ScheduledExecutorService executor = runningReaderExecutor();
    try {
      assertFalse(UpdateEnginesState.awaitCapturedReaderExecutorsStopped(List.of(executor), 1L));
      assertTrue(executor.isShutdown());
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void capturedReaderExecutorsShareOneProductionTerminationDeadline() {
    BudgetConsumingExecutor first = new BudgetConsumingExecutor(80L);
    BudgetConsumingExecutor second = new BudgetConsumingExecutor(80L);
    try {
      assertFalse(
          UpdateEnginesState.awaitCapturedReaderExecutorsStopped(List.of(first, second), 120L));
      assertEquals(0, first.fallbackShutdownCount.get());
      assertEquals(1, second.fallbackShutdownCount.get());
      assertTrue(first.isTerminated());
      assertTrue(second.isTerminated());
    } finally {
      first.shutdownNow();
      second.shutdownNow();
    }
  }

  @Test
  void readerExecutorShutdownFromItsOwnWorkerDoesNotAwaitItself() throws Exception {
    ScheduledExecutorService stdoutExecutor = Executors.newSingleThreadScheduledExecutor();
    ScheduledExecutorService stderrExecutor = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch shutdownReturned = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    long[] shutdownElapsedNanos = new long[1];
    Method shutdownReaderExecutors =
        Leelaz.class.getDeclaredMethod(
            "shutdownReaderExecutors",
            ScheduledExecutorService.class,
            ScheduledExecutorService.class);
    shutdownReaderExecutors.setAccessible(true);
    try {
      stdoutExecutor.execute(
          () -> {
            try {
              long startedAt = System.nanoTime();
              shutdownReaderExecutors.invoke(null, stdoutExecutor, stderrExecutor);
              shutdownElapsedNanos[0] = System.nanoTime() - startedAt;
              assertFalse(Thread.currentThread().isInterrupted());
            } catch (Throwable invocationFailure) {
              failure.set(invocationFailure);
            } finally {
              shutdownReturned.countDown();
            }
          });

      assertTrue(shutdownReturned.await(3, TimeUnit.SECONDS));
      assertTrue(stdoutExecutor.awaitTermination(3, TimeUnit.SECONDS));
      assertTrue(stderrExecutor.awaitTermination(3, TimeUnit.SECONDS));
      assertNull(failure.get());
      assertTrue(shutdownElapsedNanos[0] < TimeUnit.MILLISECONDS.toNanos(500L));
      assertTrue(stdoutExecutor.isTerminated());
      assertTrue(stderrExecutor.isTerminated());
    } finally {
      stdoutExecutor.shutdownNow();
      stderrExecutor.shutdownNow();
    }
  }

  @Test
  void javaSshExitPathsCloseExactSessionAndBothReaderExecutors() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      for (String exitPath : List.of("normal", "force", "shutdown", "terminal")) {
        QuietExitLeelaz engine = new QuietExitLeelaz();
        RecordingSshController ssh = new RecordingSshController(engine);
        ScheduledExecutorService stdout = runningReaderExecutor();
        ScheduledExecutorService stderr = runningReaderExecutor();
        Object binding = installJavaSshReaderBinding(engine, ssh, stdout, stderr);
        Lizzie.leelaz = engine;

        switch (exitPath) {
          case "normal" -> engine.normalQuit();
          case "force" -> engine.forceQuit();
          case "shutdown" -> engine.shutdown();
          case "terminal" -> invokeShutdownReaderTransport(engine, binding);
          default -> throw new AssertionError(exitPath);
        }

        assertEquals(1, ssh.closeCount.get(), exitPath);
        assertTrue(stdout.isShutdown(), exitPath);
        assertTrue(stderr.isShutdown(), exitPath);
        assertTrue(stdout.awaitTermination(3, TimeUnit.SECONDS), exitPath);
        assertTrue(stderr.awaitTermination(3, TimeUnit.SECONDS), exitPath);
        assertEquals(
            !exitPath.equals("terminal"),
            (boolean) getField(binding, "normalExitRequested"),
            exitPath);
      }
    } finally {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      LizzieFrame.menu = previousMenu;
    }
  }

  @Test
  void readerShutdownBeforeInstallSkipsBothSubmissionsWithoutRejection() throws Exception {
    QuietExitLeelaz engine = new QuietExitLeelaz();
    RecordingSshController ssh = new RecordingSshController(engine);
    Object binding = installJavaSshReaderBinding(engine, ssh, null, null);
    engine.shutdown();
    RecordingSubmissionExecutor stdout = new RecordingSubmissionExecutor(false);
    RecordingSubmissionExecutor stderr = new RecordingSubmissionExecutor(false);
    try {
      assertFalse(invokeStartReaderExecutors(engine, binding, stdout, stderr));
      assertEquals(0, stdout.submissionCount.get());
      assertEquals(0, stderr.submissionCount.get());
      assertTrue(stdout.isShutdown());
      assertTrue(stderr.isShutdown());
    } finally {
      stdout.shutdownNow();
      stderr.shutdownNow();
    }
  }

  @Test
  void readerInstallAndShutdownSerializeAcrossBothTaskSubmissions() throws Exception {
    QuietExitLeelaz engine = new QuietExitLeelaz();
    RecordingSshController ssh = new RecordingSshController(engine);
    Object binding = installJavaSshReaderBinding(engine, ssh, null, null);
    RecordingSubmissionExecutor stdout = new RecordingSubmissionExecutor(true);
    RecordingSubmissionExecutor stderr = new RecordingSubmissionExecutor(false);
    AtomicReference<Throwable> installFailure = new AtomicReference<>();
    AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
    Thread install =
        new Thread(
            () -> {
              try {
                assertTrue(invokeStartReaderExecutors(engine, binding, stdout, stderr));
              } catch (Throwable failure) {
                installFailure.set(failure);
              }
            });
    Thread shutdown =
        new Thread(
            () -> {
              try {
                engine.shutdown();
              } catch (Throwable failure) {
                shutdownFailure.set(failure);
              }
            });
    try {
      install.start();
      assertTrue(stdout.submissionEntered.await(3, TimeUnit.SECONDS));
      shutdown.start();
      assertTrue(awaitThreadState(shutdown, Thread.State.BLOCKED, 3_000L));
      assertFalse(stdout.isShutdown());
      assertFalse(stderr.isShutdown());
      stdout.allowSubmission.countDown();
      install.join(3_000L);
      shutdown.join(3_000L);

      assertFalse(install.isAlive());
      assertFalse(shutdown.isAlive());
      assertNull(installFailure.get());
      assertNull(shutdownFailure.get());
      assertEquals(1, stdout.submissionCount.get());
      assertEquals(1, stderr.submissionCount.get());
      assertEquals(1, ssh.closeCount.get());
      assertTrue(stdout.awaitTermination(3, TimeUnit.SECONDS));
      assertTrue(stderr.awaitTermination(3, TimeUnit.SECONDS));
    } finally {
      stdout.allowSubmission.countDown();
      stdout.shutdownNow();
      stderr.shutdownNow();
      install.join(3_000L);
      shutdown.join(3_000L);
    }
  }

  @Test
  void staleBindingNormalQuitUsesCapturedOutputAndCannotOverwriteReplacementState()
      throws Exception {
    assertStaleBindingExitDoesNotAffectReplacement(true);
  }

  @Test
  void staleBindingForceQuitDoesNotWriteOrOverwriteReplacementState() throws Exception {
    assertStaleBindingExitDoesNotAffectReplacement(false);
  }

  private static void assertStaleBindingExitDoesNotAffectReplacement(boolean normalQuit)
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    BlockingPonderExitLeelaz engine = new BlockingPonderExitLeelaz();
    RecordingSshController retiredSsh = new RecordingSshController(engine);
    RecordingSshController replacementSsh = new RecordingSshController(engine);
    ScheduledExecutorService retiredStdout = runningReaderExecutor();
    ScheduledExecutorService retiredStderr = runningReaderExecutor();
    ScheduledExecutorService replacementStdout = runningReaderExecutor();
    ScheduledExecutorService replacementStderr = runningReaderExecutor();
    ByteArrayOutputStream retiredBytes = new ByteArrayOutputStream();
    BufferedOutputStream retiredOutput = new BufferedOutputStream(retiredBytes);
    ByteArrayOutputStream replacementBytes = new ByteArrayOutputStream();
    BufferedOutputStream replacementOutput = new BufferedOutputStream(replacementBytes);
    installJavaSshReaderBinding(engine, retiredSsh, retiredStdout, retiredStderr, retiredOutput);
    Object replacementBinding =
        newJavaSshReaderBinding(
            replacementSsh, replacementStdout, replacementStderr, replacementOutput, 2L);
    AtomicReference<Throwable> exitFailure = new AtomicReference<>();
    Thread exit =
        new Thread(
            () -> {
              try {
                if (normalQuit) {
                  engine.normalQuit();
                } else {
                  engine.forceQuit();
                }
              } catch (Throwable failure) {
                exitFailure.set(failure);
              }
            });
    try {
      Lizzie.leelaz = engine;
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = false;
      exit.start();
      assertTrue(engine.stopPonderEntered.await(3, TimeUnit.SECONDS));
      setLeelazField(engine, "readerStreamBinding", replacementBinding);
      setLeelazField(engine, "javaSSH", replacementSsh);
      setLeelazField(engine, "executor", replacementStdout);
      setLeelazField(engine, "executorErr", replacementStderr);
      setLeelazField(engine, "outputStream", replacementOutput);
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = false;
      engine.allowStopPonder.countDown();
      exit.join(3_000L);

      assertFalse(exit.isAlive());
      assertNull(exitFailure.get());
      assertEquals(1, retiredSsh.closeCount.get());
      assertEquals(0, replacementSsh.closeCount.get());
      assertTrue(retiredStdout.awaitTermination(3, TimeUnit.SECONDS));
      assertTrue(retiredStderr.awaitTermination(3, TimeUnit.SECONDS));
      assertFalse(replacementStdout.isShutdown());
      assertFalse(replacementStderr.isShutdown());
      assertSame(replacementOutput, getLeelazField(engine, "outputStream"));
      assertEquals(normalQuit ? "quit\n" : "", retiredBytes.toString(StandardCharsets.UTF_8));
      assertEquals("", replacementBytes.toString(StandardCharsets.UTF_8));
      assertTrue(engine.started);
      assertTrue(engine.isLoaded);
      assertFalse(engine.isNormalEnd);
    } finally {
      engine.allowStopPonder.countDown();
      exit.join(3_000L);
      retiredStdout.shutdownNow();
      retiredStderr.shutdownNow();
      replacementStdout.shutdownNow();
      replacementStderr.shutdownNow();
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      LizzieFrame.menu = previousMenu;
    }
  }

  @Test
  void staleOrTerminatedBindingCannotInstallOrPublishReaderExecutors() throws Exception {
    QuietExitLeelaz engine = new QuietExitLeelaz();
    RecordingSshController retiredSsh = new RecordingSshController(engine);
    Object staleBinding = installJavaSshReaderBinding(engine, retiredSsh, null, null);
    ScheduledExecutorService replacementStdout = runningReaderExecutor();
    ScheduledExecutorService replacementStderr = runningReaderExecutor();
    Object replacementBinding =
        newJavaSshReaderBinding(
            new RecordingSshController(engine), replacementStdout, replacementStderr, 2L);
    RecordingSubmissionExecutor staleStdout = new RecordingSubmissionExecutor(false);
    RecordingSubmissionExecutor staleStderr = new RecordingSubmissionExecutor(false);
    RecordingSubmissionExecutor terminatedStdout = new RecordingSubmissionExecutor(false);
    RecordingSubmissionExecutor terminatedStderr = new RecordingSubmissionExecutor(false);
    try {
      setLeelazField(engine, "readerStreamBinding", replacementBinding);
      setLeelazField(engine, "executor", replacementStdout);
      setLeelazField(engine, "executorErr", replacementStderr);

      assertFalse(invokeStartReaderExecutors(engine, staleBinding, staleStdout, staleStderr));
      assertEquals(0, staleStdout.submissionCount.get());
      assertEquals(0, staleStderr.submissionCount.get());
      assertSame(replacementStdout, getLeelazField(engine, "executor"));
      assertSame(replacementStderr, getLeelazField(engine, "executorErr"));

      setField(replacementBinding, "terminated", true);
      assertFalse(
          invokeStartReaderExecutors(
              engine, replacementBinding, terminatedStdout, terminatedStderr));
      assertEquals(0, terminatedStdout.submissionCount.get());
      assertEquals(0, terminatedStderr.submissionCount.get());
      assertSame(replacementStdout, getLeelazField(engine, "executor"));
      assertSame(replacementStderr, getLeelazField(engine, "executorErr"));
      assertTrue(staleStdout.isShutdown());
      assertTrue(staleStderr.isShutdown());
      assertTrue(terminatedStdout.isShutdown());
      assertTrue(terminatedStderr.isShutdown());
    } finally {
      staleStdout.shutdownNow();
      staleStderr.shutdownNow();
      terminatedStdout.shutdownNow();
      terminatedStderr.shutdownNow();
      replacementStdout.shutdownNow();
      replacementStderr.shutdownNow();
    }
  }

  @Test
  void externalExitAndTerminalCleanupCloseEachExactTransportOnlyOnce() throws Exception {
    for (boolean remote : List.of(false, true)) {
      QuietExitLeelaz engine = new QuietExitLeelaz();
      RecordingSshController ssh = remote ? null : new RecordingSshController(engine, true);
      RecordingTransport transport = remote ? new RecordingTransport(true) : null;
      ScheduledExecutorService stdout = runningReaderExecutor();
      ScheduledExecutorService stderr = runningReaderExecutor();
      Object binding =
          remote
              ? installRemoteReaderBinding(engine, transport, stdout, stderr)
              : installJavaSshReaderBinding(engine, ssh, stdout, stderr);
      CountDownLatch closeEntered = remote ? transport.closeEntered : ssh.closeEntered;
      CountDownLatch allowClose = remote ? transport.allowClose : ssh.allowClose;
      AtomicInteger closeCount = remote ? transport.closeCount : ssh.closeCount;
      AtomicReference<Throwable> externalFailure = new AtomicReference<>();
      AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
      Thread external =
          new Thread(
              () -> {
                try {
                  engine.shutdown();
                } catch (Throwable failure) {
                  externalFailure.set(failure);
                }
              });
      Thread terminal =
          new Thread(
              () -> {
                try {
                  invokeShutdownReaderTransport(engine, binding);
                } catch (Throwable failure) {
                  terminalFailure.set(failure);
                }
              });
      try {
        external.start();
        assertTrue(closeEntered.await(3, TimeUnit.SECONDS), "remote=" + remote);
        terminal.start();
        terminal.join(3_000L);
        assertFalse(terminal.isAlive(), "remote=" + remote);
        assertEquals(1, closeCount.get(), "remote=" + remote);
        allowClose.countDown();
        external.join(3_000L);
        assertFalse(external.isAlive(), "remote=" + remote);
        assertNull(externalFailure.get(), "remote=" + remote);
        assertNull(terminalFailure.get(), "remote=" + remote);
        assertEquals(1, closeCount.get(), "remote=" + remote);
        assertTrue(stdout.awaitTermination(3, TimeUnit.SECONDS), "remote=" + remote);
        assertTrue(stderr.awaitTermination(3, TimeUnit.SECONDS), "remote=" + remote);
      } finally {
        allowClose.countDown();
        external.join(3_000L);
        terminal.join(3_000L);
        stdout.shutdownNow();
        stderr.shutdownNow();
      }
    }
  }

  @Test
  void localGracefulCloseCanBeEscalatedByForceQuit() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    QuietExitLeelaz engine = new QuietExitLeelaz();
    FallbackProcess process = new FallbackProcess();
    try {
      Lizzie.leelaz = engine;
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      setLeelazField(engine, "process", process);
      Method currentBinding = Leelaz.class.getDeclaredMethod("currentReaderStreamBinding");
      currentBinding.setAccessible(true);
      Object binding = currentBinding.invoke(engine);

      invokeShutdownReaderTransport(engine, binding);
      assertTrue(process.isAlive(), "the controlled process ignores graceful destroy");
      assertEquals(0, process.forcibleDestroyCount.get());

      engine.forceQuit();

      assertFalse(process.isAlive());
      assertEquals(1, process.forcibleDestroyCount.get());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      LizzieFrame.menu = previousMenu;
    }
  }

  @Test
  void foregroundEngineSwitchPreservesSnapshotGameKomiBeforeTargetCommands() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);

      BoardData snapshot = BoardData.empty(19, 19);
      snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
      BoardHistoryList history = new BoardHistoryList(snapshot);
      Stone[] afterMove = snapshot.stones.clone();
      afterMove[Board.getIndex(15, 15)] = Stone.WHITE;
      history.add(
          BoardData.move(
              afterMove,
              new int[] {15, 15},
              Stone.WHITE,
              true,
              new Zobrist(),
              1,
              new int[19 * 19],
              0,
              0,
              50,
              0));
      history.getGameInfo().setKomiNoMenu(6.5);
      RecordingSwitchBoard board = allocate(RecordingSwitchBoard.class);
      board.startStonelist = new ArrayList<>();
      board.setHistory(history);
      Lizzie.board = board;

      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      target.komi = 7.5f;
      target.orikomi = 7.5f;
      current.onLifecycleReservation =
          () -> {
            history.getStart().getData().stones[Board.getIndex(3, 3)] = Stone.EMPTY;
            history.getData().lastMove = java.util.Optional.of(new int[] {0, 0});
            history.getGameInfo().setKomiNoMenu(7.5);
          };
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);

      assertEquals(6.5, history.getGameInfo().getKomi());
      assertEquals(6.5f, target.komi);
      assertTrue(target.commands.contains("komi 6.5"));
      assertNotNull(manager.synchronization);
      assertThrows(PreparedRestoreObserved.class, manager.synchronization::run);
      assertTrue(board.preparedRestoreReceived);
      assertTrue(target.loadedSgf.contains("KM[6.5]"));
      assertTrue(target.loadedSgf.contains("AB[dd]"));
      assertTrue(target.commands.contains("play W Q4"));
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void foregroundEngineSwitchFreezesOrdinaryKomiDecisionBeforeReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    PreparedRestoreBoard board = fallbackRestoreBoard();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = board;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      board.getHistory().getGameInfo().setKomiNoMenu(6.5);
      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      target.width = 19;
      target.height = 19;
      target.oriWidth = 19;
      target.oriHeight = 19;
      target.orikomi = 7.5f;
      target.komi = 6.5f;
      current.onLifecycleReservation = board.getHistory().getGameInfo()::changeKomi;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);

      assertEquals(7.5f, target.komi);
      assertTrue(target.commands.contains("komi 7.5"));
      assertFalse(target.commands.contains("komi 6.5"));
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
    }
  }

  @Test
  void pkStartCapturesPreparedRestoreBeforePreRestoreCommands() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    BoardHistoryList history = board.getHistory();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      engine.width = 19;
      engine.height = 19;
      engine.komi = 7.5f;
      engine.orikomi = 7.5f;
      engine.mutateOnFirstCommand = () -> mutateHistory(history);
      engine.onLifecycleReservation = () -> history.getGameInfo().setKomiNoMenu(7.5);

      new EngineManager(List.of(engine)).startEngineForPk(0);

      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(board.preparedRestoreReceived);
      assertFalse(board.genericRestoreReceived);
      assertTrue(board.engineGameInitialization);
      assertTrue(engine.loadedSgf.contains("AB[dd]"));
      assertTrue(engine.loadedSgf.contains("KM[6.5]"));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkRestartCatchesUpNavigationBeforeFinalFenceAndAnalysis() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    EngineGameInfo previousEngineGameInfo = EngineManager.engineGameInfo;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard(2);
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.engineGameInfo = new EngineGameInfo();
      EngineManager.engineGameInfo.isGenmove = false;
      engine.isLoaded = true;
      engine.blockRestore = true;
      engine.deferBoardSynchronizationCompletion = true;
      new EngineManager(List.of(engine)).restartEngineForPk(0);

      assertTrue(engine.restoreEntered.await(2, TimeUnit.SECONDS));
      assertTrue(board.nextMove(false));
      engine.allowRestore.countDown();
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (engine.restoreCount < 2 && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertTrue(engine.restoreCount >= 2, "navigation must trigger a PK catch-up restore");
      assertEquals(0, engine.ponderCount, "analysis waits for the final response fence");
      long fenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (engine.pendingBoardSynchronizationCompletion == null
          && System.nanoTime() < fenceDeadline) {
        Thread.sleep(10L);
      }
      assertNotNull(engine.pendingBoardSynchronizationCompletion);
      engine.pendingBoardSynchronizationCompletion.run();
      assertEquals(1, engine.ponderCount, "PK analysis starts after the final fence");
    } finally {
      engine.allowRestore.countDown();
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.engineGameInfo = previousEngineGameInfo;
    }
  }

  @Test
  void pkStartCatchesUpNavigationDuringFinalFenceBeforePublishingCompletion()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard(2);
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;
      engine.width = 19;
      engine.height = 19;
      engine.deferBoardSynchronizationCompletion = true;
      EngineManager manager = new EngineManager(List.of(engine));

      EngineManager.PkEngineSynchronization completion =
          manager.startEngineForPkSynchronization(0);

      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(engine.isLoaded(), "engine readiness precedes lifecycle convergence");
      assertFalse(completion.isComplete(), "PK workflow must remain gated on the final fence");
      long firstFenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (engine.pendingBoardSynchronizationCompletion == null
          && System.nanoTime() < firstFenceDeadline) {
        Thread.sleep(10L);
      }
      Runnable firstFence = engine.pendingBoardSynchronizationCompletion;
      assertNotNull(firstFence);

      assertTrue(board.nextMove(false));
      firstFence.run();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((engine.restoreCount < 2
              || engine.pendingBoardSynchronizationCompletion == firstFence)
          && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertTrue(engine.restoreCount >= 2, "fence-time navigation must trigger catch-up");
      assertFalse(completion.isComplete(), "completion waits for the catch-up response fence");

      Runnable catchUpFence = engine.pendingBoardSynchronizationCompletion;
      assertNotNull(catchUpFence);
      assertTrue(catchUpFence != firstFence);
      catchUpFence.run();
      assertTrue(completion.await());
      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation, "completion publishes only after endpoint claims are released");
      reservation.close();
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkStartFailureLeavesPreGameOnlyAfterBothOwnersSettle() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    try {
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = true;
      EngineManager manager = new EngineManager(List.of());
      EngineManager.PkEngineSynchronization black =
          manager.startEngineForPkSynchronization(-1);
      EngineManager.PkEngineSynchronization white =
          manager.startEngineForPkSynchronization(-1);

      assertFalse(manager.finishPkEngineSynchronizations(black, white));

      assertFalse(EngineManager.isPreEngineGame);
      assertFalse(EngineManager.isEngineGame);
      assertTrue(black.isComplete());
      assertTrue(white.isComplete());
    } finally {
      Lizzie.frame = previousFrame;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
    }
  }

  @Test
  void pkStartSynchronousFailureStillSettlesBothOwnersAndLeavesPreGame()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Config previousConfig = Lizzie.config;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    PkRestoreLeelaz failing = new PkRestoreLeelaz();
    PkRestoreLeelaz healthy = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = failing;
      Lizzie.board = board;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = true;
      failing.started = true;
      failing.isLoaded = true;
      failing.width = 19;
      failing.height = 19;
      failing.mutateOnFirstCommand =
          () -> {
            throw new IllegalStateException("controlled synchronous PK start failure");
          };
      healthy.started = true;
      healthy.isLoaded = true;
      healthy.width = 19;
      healthy.height = 19;
      EngineManager manager = new EngineManager(List.of(failing, healthy));

      EngineManager.PkEngineSynchronization black =
          manager.startEngineForPkSynchronization(0);
      EngineManager.PkEngineSynchronization white =
          manager.startEngineForPkSynchronization(1);

      assertFalse(manager.finishPkEngineSynchronizations(black, white));
      assertTrue(black.isComplete());
      assertTrue(white.isComplete());
      assertFalse(EngineManager.isPreEngineGame);
      assertFalse(EngineManager.isEngineGame);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
    }
  }

  @Test
  void pkStartClearsTheFrozenTargetWhenCatalogChangesAfterReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz target = new PkRestoreLeelaz();
    PkRestoreLeelaz replacement = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    EngineManager manager = new EngineManager(new ArrayList<>(List.of(target)));
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = target;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      target.started = true;
      target.isLoaded = true;
      target.width = 19;
      target.height = 19;
      target.onLifecycleReservation = () -> manager.engineList.set(0, replacement);

      manager.startEngineForPk(0);

      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(1, target.clearWithoutPonderCount);
      assertEquals(0, replacement.clearWithoutPonderCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkStartCompletionClaimExcludesCapturedMirrorWithoutRoundReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousMirror = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PkRestoreLeelaz capturedMirror = new PkRestoreLeelaz();
    PkRestoreLeelaz laterMirror = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.leelaz2 = capturedMirror;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;
      engine.width = 19;
      engine.height = 19;
      engine.resolvedMirrors = List.of(capturedMirror, laterMirror);
      engine.blockRestore = true;

      new EngineManager(List.of(engine)).startEngineForPk(0);

      assertTrue(engine.restoreEntered.await(2, TimeUnit.SECONDS));
      assertFalse(capturedMirror.hasExclusiveGtpLifecycleTransitionForTest());
      assertNull(capturedMirror.beginExclusiveGtpLifecycleReservation());
      Leelaz.ExclusiveGtpLifecycleReservation unrelatedMirrorReservation =
          laterMirror.beginExclusiveGtpLifecycleReservation();
      assertNotNull(unrelatedMirrorReservation);
      unrelatedMirrorReservation.close();
      engine.allowRestore.countDown();
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      awaitReservationReleased(engine);
      awaitReservationReleased(capturedMirror);
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(capturedMirror.hasExclusiveGtpWorkInProgress());
    } finally {
      engine.allowRestore.countDown();
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkStartFallbackUsesCapturedBoardRouteWhenAsyncRestoreRuns() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard capturedBoard = fallbackRestoreBoard();
    PreparedRestoreBoard liveBoard = preparedRestoreBoard();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = capturedBoard;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = false;
      engine.width = 19;
      engine.height = 19;

      new EngineManager(List.of(engine)).startEngineForPk(0);

      Lizzie.board = liveBoard;
      engine.isLoaded = true;
      assertTrue(capturedBoard.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertFalse(capturedBoard.genericRestoreReceived);
      assertFalse(liveBoard.genericRestoreReceived);
      assertFalse(liveBoard.preparedRestoreReceived);
      assertTrue(capturedBoard.rootRestoreReceived);
    } finally {
      engine.isLoaded = true;
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkRestartCapturesPreparedRestoreBeforeEngineStart() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    BoardHistoryList history = board.getHistory();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.isKatago = true;
      engine.width = 19;
      engine.height = 19;
      engine.komi = 7.5f;
      engine.orikomi = 7.5f;
      engine.mutateOnStart = () -> mutateHistory(history);
      engine.onLifecycleReservation = () -> history.getGameInfo().setKomiNoMenu(7.5);
      engine.readyAfterStart = false;

      new EngineManager(List.of(engine)).restartEngineForPk(0);

      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      engine.isLoaded = true;
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(board.preparedRestoreReceived);
      assertFalse(board.genericRestoreReceived);
      assertTrue(engine.loadedSgf.contains("AB[dd]"));
      assertTrue(engine.loadedSgf.contains("KM[6.5]"));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkRestartUsesFrozenTargetWhenCatalogSlotChangesAfterReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz target = new PkRestoreLeelaz();
    PkRestoreLeelaz replacement = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    EngineManager manager = new EngineManager(new ArrayList<>(List.of(target)));
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = target;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      target.width = 19;
      target.height = 19;
      target.onLifecycleReservation = () -> manager.engineList.set(0, replacement);

      manager.restartEngineForPk(0);

      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      assertFalse(replacement.startCompleted.await(100, TimeUnit.MILLISECONDS));
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(board.preparedRestoreReceived);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkStartRestoreFailureReleasesReservationWithoutChangingEngineStatePolicy() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;
      engine.width = 19;
      engine.height = 19;
      engine.failRestore = true;

      new EngineManager(List.of(engine)).startEngineForPk(0);

      assertTrue(engine.restoreFailure.await(2, TimeUnit.SECONDS));
      awaitEngineUnavailable(engine);
      assertFalse(engine.isLoaded());
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      Leelaz.EngineModeReservation ordinaryReservation = engine.beginEngineModeReservation();
      assertNotNull(ordinaryReservation);
      ordinaryReservation.close();
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkRestartRestoreFailureReleasesReservationWithoutChangingEngineStatePolicy()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.width = 19;
      engine.height = 19;
      engine.failRestore = true;

      new EngineManager(List.of(engine)).restartEngineForPk(0);

      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(engine.restoreFailure.await(2, TimeUnit.SECONDS));
      awaitEngineUnavailable(engine);
      assertFalse(engine.isLoaded());
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      Leelaz.EngineModeReservation ordinaryReservation = engine.beginEngineModeReservation();
      assertNotNull(ordinaryReservation);
      ordinaryReservation.close();
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkRestartHoldsReservationWhileBlockedRestoreSettlesAsFailure() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousMirror = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PkRestoreLeelaz mirror = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.leelaz2 = mirror;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.width = 19;
      engine.height = 19;
      engine.failRestore = true;
      engine.blockRestore = true;
      engine.resolvedMirrors = List.of(mirror);

      new EngineManager(List.of(engine)).restartEngineForPk(0);

      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      engine.isLoaded = true;
      assertTrue(engine.restoreEntered.await(2, TimeUnit.SECONDS));
      assertNull(engine.beginExclusiveGtpLifecycleReservation());
      assertFalse(mirror.hasExclusiveGtpLifecycleTransitionForTest());
      assertNull(mirror.beginExclusiveGtpLifecycleReservation());
      engine.allowRestore.countDown();
      assertTrue(engine.restoreFailure.await(2, TimeUnit.SECONDS));
      awaitEngineUnavailable(engine);
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      Leelaz.EngineModeReservation ordinaryReservation = engine.beginEngineModeReservation();
      assertNotNull(ordinaryReservation);
      ordinaryReservation.close();
    } finally {
      engine.allowRestore.countDown();
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void pkStartAdmissionConflictUsesLeaseUiInsteadOfLeakingException() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PkRestoreLeelaz engine = new PkRestoreLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    Leelaz.EngineModeReservation reservation = null;
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;
      reservation = engine.beginEngineModeReservation();
      assertNotNull(reservation);

      LeaseConflictEngineManager manager = new LeaseConflictEngineManager(List.of(engine));
      assertDoesNotThrow(() -> manager.startEngineForPk(0));
      assertEquals(1, manager.leaseConflictCount);
      assertFalse(board.restoreCompleted.await(50, TimeUnit.MILLISECONDS));
    } finally {
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void engineSwitchBindsTargetAndKomiToOneHistoryInstance() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);

      BoardHistoryList capturedHistory = historyWithStone(3, 3, 6.5);
      capturedHistory.add(moveNode(15, 15, Stone.WHITE, true, 1));
      BoardHistoryList replacementHistory = historyWithStone(0, 0, 7.5);
      replacementHistory.add(moveNode(0, 1, Stone.WHITE, true, 1));
      HistorySwapBoard board = allocate(HistorySwapBoard.class);
      board.firstHistory = capturedHistory;
      board.secondHistory = replacementHistory;
      board.startStonelist = new ArrayList<>();
      Lizzie.board = board;

      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);

      assertNotNull(manager.synchronization);
      assertThrows(PreparedRestoreObserved.class, manager.synchronization::run);
      assertTrue(target.loadedSgf.contains("AB[dd]"));
      assertTrue(target.loadedSgf.contains("KM[6.5]"));
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  private static BoardHistoryList historyWithStone(int x, int y, double komi) {
    BoardData snapshot = BoardData.empty(19, 19);
    snapshot.stones[Board.getIndex(x, y)] = Stone.BLACK;
    BoardHistoryList history = new BoardHistoryList(snapshot);
    history.getGameInfo().setKomiNoMenu(komi);
    return history;
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = new Stone[19 * 19];
    java.util.Arrays.fill(stones, Stone.EMPTY);
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[19 * 19],
        0,
        0,
        50,
        0);
  }

  private static PreparedRestoreBoard preparedRestoreBoard() throws Exception {
    return preparedRestoreBoard(0);
  }

  private static PreparedRestoreBoard preparedRestoreBoard(int moveCount) throws Exception {
    BoardData snapshot = BoardData.empty(19, 19);
    snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
    BoardHistoryList history = new BoardHistoryList(snapshot);
    history.getGameInfo().setKomiNoMenu(6.5);
    for (int move = 1; move <= moveCount; move++) {
      Stone color = move % 2 == 1 ? Stone.BLACK : Stone.WHITE;
      history.add(moveNode(3 + move, 3, color, color != Stone.BLACK, move));
    }
    history.toStart();
    PreparedRestoreBoard board = allocate(PreparedRestoreBoard.class);
    board.restoreCompleted = new CountDownLatch(1);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }


  private static PreparedRestoreBoard fallbackRestoreBoard() throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(19, 19));
    PreparedRestoreBoard board = allocate(PreparedRestoreBoard.class);
    board.restoreCompleted = new CountDownLatch(1);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static void mutateHistory(BoardHistoryList history) {
    history.getStart().getData().stones[Board.getIndex(3, 3)] = Stone.EMPTY;
    history.getGameInfo().setKomiNoMenu(7.5);
  }

  @Test
  void mainSwitchRejectsUnrelatedSecondaryLifecycleBeforeMirrorRestore() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousMirror = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, target));
    AtomicReference<Leelaz.EngineModeReservation> secondaryReservation = new AtomicReference<>();
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);

      BoardData snapshot = BoardData.empty(19, 19);
      snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
      BoardHistoryList history = new BoardHistoryList(snapshot);
      Stone[] afterMove = snapshot.stones.clone();
      afterMove[Board.getIndex(15, 15)] = Stone.WHITE;
      history.add(
          BoardData.move(
              afterMove,
              new int[] {15, 15},
              Stone.WHITE,
              true,
              new Zobrist(),
              1,
              new int[19 * 19],
              0,
              0,
              50,
              0));
      RecordingSwitchBoard board = allocate(RecordingSwitchBoard.class);
      board.startStonelist = new ArrayList<>();
      board.setHistory(history);
      Lizzie.board = board;

      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      secondary.started = true;
      secondary.isLoaded = true;
      setLeelazField(
          secondary, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      current.onLifecycleReservation =
          () -> secondaryReservation.set(secondary.beginEngineModeReservation());

      manager.switchEngine(1, true);

      assertNotNull(manager.synchronization);
      assertThrows(IllegalStateException.class, manager.synchronization::run);
      assertNotNull(secondaryReservation.get());
      assertTrue(secondary.hasExclusiveGtpWorkInProgress());
      assertTrue(target.loadedSgf.isEmpty());
      assertTrue(secondary.loadedSgf.isEmpty());
      assertTrue(target.commands.stream().noneMatch(command -> command.startsWith("play ")));
      assertTrue(secondary.commands.stream().noneMatch(command -> command.startsWith("play ")));
      secondaryReservation.get().close();
      secondaryReservation.set(null);
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      if (secondaryReservation.get() != null) {
        secondaryReservation.get().close();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void mainSwitchRestoresFrozenMirrorAndTargetWithoutCompetingLifecycle() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousMirror = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);

      BoardData snapshot = BoardData.empty(19, 19);
      snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
      BoardHistoryList history = new BoardHistoryList(snapshot);
      Stone[] afterMove = snapshot.stones.clone();
      afterMove[Board.getIndex(15, 15)] = Stone.WHITE;
      history.add(
          BoardData.move(
              afterMove,
              new int[] {15, 15},
              Stone.WHITE,
              true,
              new Zobrist(),
              1,
              new int[19 * 19],
              0,
              0,
              50,
              0));
      RecordingSwitchBoard board = allocate(RecordingSwitchBoard.class);
      board.startStonelist = new ArrayList<>();
      board.setHistory(history);
      Lizzie.board = board;

      current.started = true;
      current.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      secondary.started = true;
      secondary.isLoaded = true;
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);

      assertNotNull(manager.synchronization);
      assertThrows(PreparedRestoreObserved.class, manager.synchronization::run);
      assertTrue(target.loadedSgf.contains("AB[dd]"));
      assertTrue(secondary.loadedSgf.contains("AB[dd]"));
      assertTrue(target.commands.stream().anyMatch(command -> command.startsWith("play ")));
      assertTrue(secondary.commands.stream().anyMatch(command -> command.startsWith("play ")));
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void configurationSwitchRejectsPreExistingMirrorReservationBeforeAnySwitchWork()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousMirror = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    Leelaz current = new Leelaz("");
    Leelaz target = new Leelaz("");
    Leelaz mirror = new Leelaz("");
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    Leelaz.EngineModeReservation mirrorReservation = null;
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;

      BoardData snapshot = BoardData.empty(19, 19);
      snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.setHistory(new BoardHistoryList(snapshot));
      Lizzie.board = board;

      Lizzie.leelaz = current;
      Lizzie.leelaz2 = mirror;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      mirrorReservation = mirror.beginEngineModeReservation();
      assertNotNull(mirrorReservation);

      assertFalse(manager.switchEngineIfAvailable(1, true));

      assertEquals(0, manager.switchCount);
      assertEquals(0, manager.conflictCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertTrue(mirror.hasExclusiveGtpWorkInProgress());
    } finally {
      if (mirrorReservation != null) {
        mirrorReservation.close();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void killAllEnginesClaimsActiveTrackingAndRunsImmediatelyOnce() throws Exception {
    assertDestructiveKillClaimsActiveTracking(true);
  }

  @Test
  void killThisEngineClaimsActiveTrackingAndRunsImmediatelyOnce() throws Exception {
    assertDestructiveKillClaimsActiveTracking(false);
  }

  @Test
  void activeRestartResumesPonderAfterFinalBoardFenceAndRetiresTrackingOnRebind()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    CountingRestartGateFrame frame = allocate(CountingRestartGateFrame.class);
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(engine));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(output));
    setCapabilityDiscoveryComplete(engine, true);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.Pondering();
      Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
      engine.emitPonderCommand = true;
      engine.Pondering();

      manager.reStartEngine(0);

      assertEquals(1, engine.shutdownCount);
      assertEquals(1, manager.switchCount);
      assertEquals(1, frame.beginCount);
      assertFalse(tracking.lease().isOwned());
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
      assertNotNull(manager.afterSync);
      assertFalse(engine.isStarted());
      assertFalse(engine.isLoaded());
      engine.started = true;
      engine.isLoaded = true;
      engine.Pondering();
      manager.afterSync.run();
      assertNotNull(engine.confirmation);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
      assertEquals(0, engine.ponderCount);
      setLeelazField(engine, "currentCmdNum", 15);
      setLeelazField(engine, "cmdNumber", 16);
      engine.confirmation.run();
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertEquals(1, engine.ponderCount);
      assertTrue(engine.ponderWhileLifecycleHeld);
      assertEquals(17, getLeelazField(engine, "cmdNumber"));
      assertTrue(
          engine.isResponseUpToDate(),
          "post-fence ponder must accept the first analysis info without another board action");
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void activeRestartReleasesLifecycleAfterBoardFenceFailure() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    CountingRestartGateFrame frame = allocate(CountingRestartGateFrame.class);
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(engine));
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    setCapabilityDiscoveryComplete(engine, true);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      activateTracking(engine);

      manager.reStartEngine(0);

      assertEquals(1, engine.shutdownCount);
      assertEquals(1, frame.beginCount);
      assertNotNull(manager.afterSync);
      assertFalse(engine.isStarted());
      assertFalse(engine.isLoaded());
      engine.started = true;
      engine.isLoaded = true;
      engine.Pondering();
      manager.afterSync.run();
      assertNotNull(engine.rejection);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());

      engine.rejection.accept("controlled board fence failure");

      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isLoaded());
      assertEquals(0, engine.ponderCount);
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertEquals(1, manager.failureCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void unresponsiveRemoteAnalysisRestartsAndRestoresThroughExistingLifecycle() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.useRemoteCompute = true;
    engine.started = true;
    engine.processDead = true;
    engine.Pondering();
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.restartUnresponsiveRemoteEngine(engine, 0);

      assertTrue(engine.restartCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(1, engine.restartCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void disconnectedRemoteSessionRestartsEvenWhenOrdinaryPonderIsNotActive() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.useRemoteCompute = true;
    engine.started = true;
    engine.processDead = true;
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.restartUnresponsiveRemoteEngine(engine, 0);

      assertTrue(engine.restartCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(1, engine.restartCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticJavaSshRestartDoesNotClearQuarantinedGmaState() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.useJavaSSH = true;
    engine.isLoaded = true;
    engine.canCheckAlive = true;
    engine.javaSSHClosed = true;
    setEngineStateUnrestored(engine, true);
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);

      assertEquals(0, engine.restartCount);
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticProcessRestartDoesNotRaceAnActiveGmaReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.started = true;
    engine.canCheckAlive = true;
    engine.processDead = true;
    Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
    setReadBoardGmaReservation(engine, reservation);
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);

      assertEquals(0, engine.restartCount);
    } finally {
      setReadBoardGmaReservation(engine, null);
      reservation.close();
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticProcessRestartLosesTheRaceWhenGmaReservesBeforeRestartDispatch() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.started = true;
    engine.canCheckAlive = true;
    engine.processDead = true;
    engine.blockProcessDeadCheck = true;
    EngineManager manager = new EngineManager(List.of(engine));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread checkThread =
        new Thread(
            () -> {
              try {
                invokeCheckEngineAlive(manager);
              } catch (Throwable ex) {
                failure.set(ex);
              }
            });
    Leelaz.EngineModeReservation reservation = null;
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;
      checkThread.start();
      assertTrue(engine.processDeadCheckEntered.await(1, TimeUnit.SECONDS));
      reservation = engine.beginEngineModeReservation();
      assertNotNull(reservation);
      setReadBoardGmaReservation(engine, reservation);

      engine.releaseProcessDeadCheck.countDown();
      checkThread.join(1000L);

      assertFalse(checkThread.isAlive());
      assertEquals(null, failure.get());
      assertEquals(0, engine.restartCount);
    } finally {
      engine.releaseProcessDeadCheck.countDown();
      checkThread.join(1000L);
      setReadBoardGmaReservation(engine, null);
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void remoteAutomaticRestartHandsItsReservationToTheBoardRestore() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.started = true;
    engine.canCheckAlive = true;
    engine.processDead = true;
    engine.useRemoteCompute = true;
    engine.blockSecondProcessDeadCheck = true;
    EngineManager manager = new EngineManager(List.of(engine));
    Leelaz.EngineModeReservation competingReservation = null;
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);
      assertTrue(engine.secondProcessDeadCheckEntered.await(1, TimeUnit.SECONDS));
      competingReservation = engine.beginEngineModeReservation();
      boolean competingReservationAcquired = competingReservation != null;
      if (competingReservation != null) {
        competingReservation.close();
        competingReservation = null;
      }
      engine.releaseSecondProcessDeadCheck.countDown();
      assertTrue(engine.restartCompleted.await(1, TimeUnit.SECONDS));

      assertFalse(competingReservationAcquired);
      assertEquals(1, engine.restartCount);
      awaitReservationReleased(engine);
      Leelaz.EngineModeReservation afterRestore = engine.beginEngineModeReservation();
      assertNotNull(afterRestore);
      afterRestore.close();
    } finally {
      engine.releaseSecondProcessDeadCheck.countDown();
      if (competingReservation != null) {
        competingReservation.close();
      }
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void switchKeepsCurrentAndTargetReservedUntilBoardSynchronizationCompletes() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = new Leelaz("");
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);

      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());
      assertNotNull(manager.afterSync);

      Thread synchronizationThread = new Thread(manager.afterSync);
      synchronizationThread.start();
      synchronizationThread.join();

      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void retainedSwitchKeepsOldTrackingQueueGatedUntilFinalFence() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    TrackingKillLeelaz current = new TrackingKillLeelaz();
    TrackingRestartActionLeelaz target = new TrackingRestartActionLeelaz();
    LifecycleFrame frame = allocate(LifecycleFrame.class);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    setLeelazField(current, "outputStream", new BufferedOutputStream(output));
    setCapabilityDiscoveryComplete(current, true);
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = current;
      Lizzie.config = allocate(Config.class);
      activateTracking(current);

      manager.switchEngine(1, true);
      target.started = true;
      target.isLoaded = true;
      target.Pondering();
      Lizzie.leelaz = target;

      assertEquals(1, manager.switchCount);
      assertNotNull(manager.afterSync);
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));
      current.sendCommand("stop");
      manager.afterSync.run();
      assertNotNull(target.confirmation);
      target.confirmation.run();
      assertEquals(0, target.ponderCount, "regular switch must preserve its existing ponder path");
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));

      assertTrue(dispatchExclusiveLine(current, ""));
      assertTrue(dispatchExclusiveLine(current, "=800000002"));
      assertTrue(dispatchExclusiveLine(current, ""));
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("stop\nstop\n"));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void configurationSwitchReportsReservationConflictWithoutGenericPopup() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LifecycleConflictLeelaz current = new LifecycleConflictLeelaz();
    Leelaz target = new Leelaz("");
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.leelaz = current;

      assertFalse(manager.switchEngineIfAvailable(1, true));
      assertEquals(0, manager.conflictCount);
      assertEquals(0, manager.switchCount);
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void retainedNewGameReservationCanBeReusedForTheSameForegroundSwitch() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    Leelaz current = new Leelaz("");
    Leelaz target = new Leelaz("");
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
    Leelaz.EngineModeReservation retainedReservation = null;
    try {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.board = preparedRestoreBoard();
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      retainedReservation = current.beginEngineModeReservation();
      assertNotNull(retainedReservation);

      assertTrue(manager.switchEngineIfAvailable(1, true, retainedReservation));

      assertEquals(1, manager.switchCount);
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());
      manager.afterSync.run();
      assertTrue(
          current.hasExclusiveGtpWorkInProgress(),
          "the retained new-game reservation must remain active until its dialog flow exits");
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      if (retainedReservation != null) {
        retainedReservation.close();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void retainedReservationFromAnotherEngineCannotBypassSwitchExclusion() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = new Leelaz("");
    Leelaz unrelated = new Leelaz("");
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
    Leelaz.EngineModeReservation unrelatedReservation = unrelated.beginEngineModeReservation();
    try {
      Lizzie.leelaz = current;

      assertFalse(manager.switchEngineIfAvailable(1, true, unrelatedReservation));

      assertEquals(1, manager.conflictCount);
      assertEquals(0, manager.switchCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      if (unrelatedReservation != null) {
        unrelatedReservation.close();
      }
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void switchReservesDistinctTargetBeforeTouchingCurrentOwner() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    List<String> reservationOrder = new java.util.ArrayList<>();
    OrderedLifecycleLeelaz current = new OrderedLifecycleLeelaz("current", reservationOrder, false);
    OrderedLifecycleLeelaz target = new OrderedLifecycleLeelaz("target", reservationOrder, true);
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.leelaz = current;

      assertFalse(manager.switchEngineIfAvailable(1, true));

      assertEquals(List.of("target"), reservationOrder);
      assertEquals(0, current.reservationAttempts);
      assertEquals(0, manager.switchCount);
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void recoverySwitchWaitsForTargetBoardSynchronizationFenceBeforeReleasingReservations()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    setEngineStateUnrestored(current, true);
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());

      manager.afterSync.run();

      assertNotNull(target.confirmation);
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());
      target.confirmation.run();
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void failedRecoverySwitchFenceLeavesTargetUnavailableAndReleasesReservations() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    setEngineStateUnrestored(current, true);
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      manager.afterSync.run();
      target.rejection.accept("controlled fence failure");

      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertFalse(target.isLoaded());
      assertFalse(target.hasUnrestoredReadBoardGmaState());
      Leelaz.EngineModeReservation recovery = target.beginEngineModeReservation();
      assertNotNull(recovery);
      recovery.close();
      assertEquals(1, manager.failureCount);
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void selectingTheSameQuarantinedEngineDoesNotPretendToRecoverIt() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    FenceTrackingLeelaz current = new FenceTrackingLeelaz();
    current.started = true;
    current.isLoaded = true;
    setEngineStateUnrestored(current, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current), current);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(0, true);
      manager.afterSync.run();

      assertEquals(null, current.confirmation);
      assertTrue(current.hasUnrestoredReadBoardGmaState());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void switchingToAQuarantinedTargetDoesNotPretendToRestoreItsRuntimeState() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    setEngineStateUnrestored(target, true);
    setCapabilityDiscoveryComplete(target, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      manager.afterSync.run();

      assertEquals(null, target.confirmation);
      assertTrue(target.hasUnrestoredReadBoardGmaState());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void explicitlyRestartingAQuarantinedTargetClearsItOnlyAfterTheBoardFence() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    Leelaz current = new Leelaz("");
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    setEngineStateUnrestored(target, true);
    setCapabilityDiscoveryComplete(target, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;

      manager.reStartEngine(1);
      manager.afterSync.run();
      assertNotNull(target.confirmation);
      assertTrue(target.hasUnrestoredReadBoardGmaState());

      target.confirmation.run();

      assertFalse(target.hasUnrestoredReadBoardGmaState());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
    }
  }

  @Test
  void ordinaryForegroundActivationStartsAnalysisForInitialAndReopenedEngine() throws Exception {
    assertForegroundActivationStartsAnalysis(false);
    assertForegroundActivationStartsAnalysis(true);
  }

  private void assertForegroundActivationStartsAnalysis(boolean reopenCurrentEngine)
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    Menu previousMenu = LizzieFrame.menu;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    EngineManager previousManager = Lizzie.engineManager;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz target = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz noEngineSentinel =
        reopenCurrentEngine ? target : new RecordingSwitchLeelaz();
    DeferredBoardSynchronizationEngineManager manager =
        new DeferredBoardSynchronizationEngineManager(List.of(target));
    SilentSwitchFrame frame = allocate(SilentSwitchFrame.class);
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      config.notStartPondering = false;
      Lizzie.config = config;
      Lizzie.frame = frame;
      LizzieFrame.menu = allocate(CountingRestartMenu.class);
      Menu.engineMenu = new SilentJFontMenu();
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      Lizzie.board = fallbackRestoreBoard();
      Lizzie.engineManager = manager;
      noEngineSentinel.started = true;
      noEngineSentinel.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      target.isKatago = true;
      target.width = 19;
      target.height = 19;
      target.oriWidth = 19;
      target.oriHeight = 19;
      target.orikomi = 7.5f;
      Lizzie.leelaz = noEngineSentinel;
      if (reopenCurrentEngine) {
        EngineManager.isEmpty = false;
        EngineManager.currentEngineNo = 0;
        assertTrue(manager.killAllEngines());
        assertTrue(EngineManager.isEmpty);
        target.started = true;
        target.isLoaded = true;
      } else {
        EngineManager.isEmpty = true;
        EngineManager.currentEngineNo = -1;
      }
      Lizzie.engineStartupStatus.checking("engine.starting", "using existing cache");

      manager.switchEngine(0, true);
      target.isCheckingName = false;
      assertNotNull(manager.synchronization);
      manager.synchronization.run();
      manager.afterSync.run();
      SwingUtilities.invokeAndWait(() -> {});

      assertSame(target, Lizzie.leelaz);
      assertEquals(1, target.ponderCount);
      assertTrue(target.isPondering());
      assertTrue(target.isResponseUpToDate());
      assertEquals(1, target.responseFreshenedAfterPonderCount);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(1, frame.reSetLocCount);
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      if (manager.afterSync != null) {
        manager.afterSync.run();
      }
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.leelaz = previousPrimary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      LizzieFrame.menu = previousMenu;
      Menu.engineMenu = previousEngineMenu;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      Lizzie.engineManager = previousManager;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void inactiveExplicitRestartWaitsForBoardFenceBeforeInitializationAndRelease() throws Exception {
    assertExplicitRestartWaitsForBoardFence(true);
  }

  @Test
  void pausedExplicitRestartWaitsForBoardFenceAndPublishesTerminalState() throws Exception {
    assertExplicitRestartWaitsForBoardFence(false);
  }

  @Test
  void secondaryActiveExplicitRestartSettlesAfterOwnerBoardFence() throws Exception {
    assertSecondaryExplicitRestartSettlesAfterOwnerBoardFence(true);
  }

  @Test
  void secondaryPausedExplicitRestartStaysPausedAfterOwnerBoardFence() throws Exception {
    assertSecondaryExplicitRestartSettlesAfterOwnerBoardFence(false);
  }

  private void assertSecondaryExplicitRestartSettlesAfterOwnerBoardFence(boolean resumePonder)
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    TrackingRestartActionLeelaz primary = new TrackingRestartActionLeelaz();
    TrackingRestartActionLeelaz secondary = new TrackingRestartActionLeelaz();
    DeferredSecondaryRestartEngineManager manager =
        new DeferredSecondaryRestartEngineManager(List.of(primary, secondary), secondary);
    List<String> terminalOrder = new ArrayList<>();
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.board = preparedRestoreBoard();
      primary.started = true;
      primary.isLoaded = true;
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.Pondering();
      if (resumePonder) {
        primary.Pondering();
      } else {
        primary.notPondering();
      }
      primary.onPonder = () -> terminalOrder.add("ponder");
      secondary.onSecondaryTerminal = () -> terminalOrder.add("terminal");
      secondary.onResponseWatermark = () -> terminalOrder.add("watermark");
      setLeelazField(secondary, "currentCmdNum", 15);
      setLeelazField(secondary, "cmdNumber", 17);
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      Lizzie.engineStartupStatus.checking("primary.still.starting", "controlled");

      manager.reStartEngine2();
      assertEquals(1, secondary.shutdownCount);
      assertNotNull(manager.afterSync);
      assertTrue(secondary.hasExclusiveGtpWorkInProgress());

      manager.afterSync.run();

      assertNotNull(
          secondary.confirmation,
          "secondary explicit restart must wait for its owner board synchronization fence");
      assertTrue(secondary.hasExclusiveGtpWorkInProgress());
      assertEquals(0, secondary.secondaryTerminalCount);
      assertEquals(0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertFalse(secondary.isResponseUpToDate());
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);

      Runnable confirmation = secondary.confirmation;
      secondary.confirmation = null;
      confirmation.run();

      assertNotNull(
          primary.confirmation,
          "secondary restart must also wait for the captured primary mirror fence");
      assertEquals(0, secondary.secondaryTerminalCount);
      assertTrue(secondary.hasExclusiveGtpWorkInProgress());
      Runnable mirrorConfirmation = primary.confirmation;
      primary.confirmation = null;
      mirrorConfirmation.run();

      assertEquals(1, secondary.secondaryTerminalCount);
      assertTrue(secondary.secondaryTerminalWhileLifecycleHeld);
      assertTrue(secondary.responseWatermarkWhileLifecycleHeld);
      assertTrue(secondary.canRestoreDymPda);
      assertFalse(secondary.isPondering());
      assertEquals(resumePonder ? 1 : 0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertEquals(
          resumePonder
              ? List.of("terminal", "ponder", "watermark")
              : List.of("terminal", "watermark"),
          terminalOrder);
      assertTrue(secondary.isResponseUpToDate());
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
    } finally {
      if (secondary.confirmation != null) {
        Runnable confirmation = secondary.confirmation;
        secondary.confirmation = null;
        confirmation.run();
      } else if (manager.afterSync != null && secondary.secondaryTerminalCount == 0) {
        manager.afterSync.run();
        if (secondary.confirmation != null) {
          Runnable confirmation = secondary.confirmation;
          secondary.confirmation = null;
          confirmation.run();
        }
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void secondaryExplicitRestartFenceFailureRetiresLifecycleFailClosed() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    TrackingRestartActionLeelaz primary = new TrackingRestartActionLeelaz();
    TrackingRestartActionLeelaz secondary = new TrackingRestartActionLeelaz();
    DeferredSecondaryRestartEngineManager manager =
        new DeferredSecondaryRestartEngineManager(List.of(primary, secondary), secondary);
    boolean settled = false;
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.board = preparedRestoreBoard();
      primary.started = true;
      primary.isLoaded = true;
      primary.notPondering();
      secondary.started = true;
      secondary.isLoaded = true;
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      Lizzie.engineStartupStatus.ready();

      manager.reStartEngine2();
      manager.afterSync.run();

      assertNotNull(secondary.rejection);
      assertTrue(secondary.hasExclusiveGtpWorkInProgress());
      secondary.rejection.accept("controlled secondary board fence failure");
      settled = true;

      assertFalse(secondary.isLoaded());
      assertEquals(0, secondary.secondaryTerminalCount);
      assertEquals(0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertEquals(1, manager.failureCount);
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
    } finally {
      if (!settled && manager.afterSync != null) {
        manager.afterSync.run();
        if (secondary.rejection != null) {
          secondary.rejection.accept("controlled secondary board fence cleanup");
        }
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void secondaryExplicitRestartBoardFenceTimeoutRetiresLifecycleFailClosed() throws Exception {
    assertSecondaryExplicitRestartBoardFenceFailClosed(false);
  }

  @Test
  void secondaryExplicitRestartBoardFenceSendFailureRetiresLifecycleFailClosed() throws Exception {
    assertSecondaryExplicitRestartBoardFenceFailClosed(true);
  }

  private void assertSecondaryExplicitRestartBoardFenceFailClosed(boolean failOnSend)
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    TrackingRestartActionLeelaz primary = new TrackingRestartActionLeelaz();
    TrackingRestartActionLeelaz secondary = new TrackingRestartActionLeelaz();
    DeferredSecondaryRestartEngineManager manager =
        new DeferredSecondaryRestartEngineManager(List.of(primary, secondary), secondary);
    GatedCommandOutputStream gatedOutput = new GatedCommandOutputStream(failOnSend);
    Thread fenceThread = null;
    boolean fenceSettled = false;
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.board = preparedRestoreBoard();
      primary.started = true;
      primary.isLoaded = true;
      primary.notPondering();
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.useRealBoardSynchronizationFence = true;
      secondary.boardSynchronizationTimeoutMillis = 100L;
      setLeelazField(secondary, "currentCmdNum", 15);
      setLeelazField(secondary, "cmdNumber", 17);
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      Lizzie.engineStartupStatus.ready();

      manager.reStartEngine2();
      assertEquals(1, secondary.shutdownCount);
      assertNotNull(manager.afterSync);
      setLeelazField(secondary, "outputStream", new BufferedOutputStream(gatedOutput));

      fenceThread = new Thread(() -> manager.afterSync.run(), "secondary-restart-board-fence");
      fenceThread.start();
      assertTrue(gatedOutput.writeEntered.await(2, TimeUnit.SECONDS));

      int fenceResponseCommandId = pendingFenceResponseCommandId(secondary);
      assertEquals(0, manager.failureCount);
      assertFalse(
          primary.hasExclusiveGtpWorkInProgress(),
          "secondary restart must not reserve the primary lifecycle");
      assertTrue(secondary.hasExclusiveGtpWorkInProgress());
      assertTrue(secondary.isLoaded());
      assertEquals(0, secondary.secondaryTerminalCount);
      assertEquals(0, secondary.initializationCount);
      assertEquals(0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertFalse(secondary.responseWatermarkWhileLifecycleHeld);
      assertEquals(1, pendingResponseHandlerCount(secondary));
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);

      gatedOutput.releaseWrite();
      fenceThread.join(2000);
      assertFalse(fenceThread.isAlive());
      fenceThread = null;
      assertTrue(manager.fenceFailureSettled.await(2, TimeUnit.SECONDS));
      fenceSettled = true;

      assertFalse(secondary.isLoaded());
      assertEquals(0, secondary.secondaryTerminalCount);
      assertEquals(0, secondary.initializationCount);
      assertEquals(0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertFalse(secondary.responseWatermarkWhileLifecycleHeld);
      assertFalse(secondary.isResponseUpToDate());
      assertEquals(1, manager.failureCount);
      assertFalse(primary.hasExclusiveGtpWorkInProgress());
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, pendingResponseHandlerCount(secondary));

      processCommandResponse(secondary, "=" + fenceResponseCommandId + " name");

      assertEquals(1, manager.failureCount);
      assertEquals(0, secondary.secondaryTerminalCount);
      assertEquals(0, secondary.initializationCount);
      assertEquals(0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertFalse(secondary.isLoaded());
      assertFalse(primary.hasExclusiveGtpWorkInProgress());
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, pendingResponseHandlerCount(secondary));
    } finally {
      gatedOutput.releaseWrite();
      if (fenceThread != null) {
        fenceThread.join(2000);
      }
      if (!fenceSettled && gatedOutput.writeEntered.getCount() == 0) {
        try {
          manager.fenceFailureSettled.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Lizzie.engineStartupStatus.ready();
    }
  }

  private void assertExplicitRestartWaitsForBoardFence(boolean resumePonder) throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    CountingRestartGateFrame frame = allocate(CountingRestartGateFrame.class);
    CountingRestartMenu menu = allocate(CountingRestartMenu.class);
    BottomToolbar toolbar = allocate(SilentSwitchToolbar.class);
    PreparedRestoreBoard board = preparedRestoreBoard();
    Config config = allocate(Config.class);
    config.fastChange = true;
    config.extraMode = ExtraMode.Normal;
    engine.started = true;
    engine.isLoaded = true;
    if (resumePonder) {
      engine.Pondering();
    } else {
      engine.notPondering();
    }
    RecoverySwitchEngineManager manager = new RecoverySwitchEngineManager(List.of(engine), engine);
    boolean synchronizationRan = false;
    try {
      Lizzie.config = config;
      Lizzie.board = board;
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      LizzieFrame.menu = menu;
      LizzieFrame.toolbar = toolbar;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      Lizzie.engineStartupStatus.checking("engine.starting", "using existing cache");
      engine.invokeRealInitialization = true;

      manager.reStartEngine(0);
      manager.afterSync.run();
      synchronizationRan = true;

      assertNotNull(engine.confirmation);
      assertTrue(engine.isLoaded);
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, engine.initializationCount);
      assertEquals(0, engine.ponderCount);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());

      engine.confirmation.run();
      SwingUtilities.invokeAndWait(() -> {});

      assertTrue(engine.isLoaded);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(1, engine.initializationCount);
      assertEquals(resumePonder, engine.resumePonderIntent);
      assertEquals(resumePonder ? 1 : 0, engine.ponderCount);
      assertEquals(resumePonder, engine.isPondering());
      if (resumePonder) {
        assertTrue(engine.ponderWhileLifecycleHeld);
      }
      assertTrue(engine.isResponseUpToDate());
      assertEquals(1, frame.reSetLocCount);
      assertEquals(1, menu.updateCount);
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
      if (!synchronizationRan && manager.afterSync != null) {
        manager.afterSync.run();
      }
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void restartSynchronizationPropagatesReceiptIntoTheFinalBoardFence() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    ReceiptAwareFenceLeelaz engine = new ReceiptAwareFenceLeelaz();
    engine.started = true;
    engine.isLoaded = true;
    setCapabilityDiscoveryComplete(engine, true);
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    Lizzie.leelaz = engine;
    Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
    try {
      activateTracking(engine);
      reservation = engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
      rebindReader(engine);
      ReceiptSynchronizationEngineManager manager =
          new ReceiptSynchronizationEngineManager(List.of(engine));
      manager.synchronize(engine, () -> engine.confirmBoardSynchronization(() -> {}, detail -> {}));

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertTrue(engine.receiptSeenByBoardFence);
    } finally {
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void restartSynchronizationFailureRetiresReceiptWithoutCreatingGmaQuarantine() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    engine.started = true;
    engine.isLoaded = true;
    setCapabilityDiscoveryComplete(engine, true);
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    Lizzie.leelaz = engine;
    Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
    try {
      activateTracking(engine);
      reservation = engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
      rebindReader(engine);
      ReceiptSynchronizationEngineManager manager =
          new ReceiptSynchronizationEngineManager(List.of(engine));
      Leelaz.ExclusiveGtpLifecycleReservation heldReservation = reservation;

      manager.synchronize(
          engine,
          () -> {
            throw new IllegalStateException("controlled board restore failure");
          },
          heldReservation::close);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertFalse(engine.isLoaded());
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void restartReceiptIsDetachedFromTheReaderBindingWhenLifecycleEnds() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz engine = new TrackingRestartActionLeelaz();
    setCapabilityDiscoveryComplete(engine, true);
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    Lizzie.leelaz = engine;
    try {
      activateTracking(engine);
      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
      rebindReader(engine);
      Object binding = getLeelazField(engine, "readerStreamBinding");
      assertNotNull(getField(binding, "restartBootstrapReceipt"));

      reservation.close();

      assertEquals(null, getField(binding, "restartBootstrapReceipt"));
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void restartGateFailureReleasesLifecycleBeforeDestructiveWork() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    setCapabilityDiscoveryComplete(engine, true);
    GateFailureEngineManager manager = new GateFailureEngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      EngineManager.isEmpty = false;
      Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
      Lizzie.frame = allocate(FailingRestartGateFrame.class);

      manager.reStartEngine(0);

      assertEquals(0, engine.shutdownCount);
      assertEquals(1, manager.failureCount);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, tracking.lease().disposition());
      assertFalse((boolean) getLeelazField(engine, "exclusiveGtpLifecycleTransition"));
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertTrue(dispatchExclusiveLine(engine, "=800000002"));
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertFalse(tracking.lease().isOwned());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
    }
  }

  @Test
  void secondaryRestartConflictDoesNotShutDownSecondaryEngine() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    int previousSecondEngineNo = EngineManager.currentEngineNo2;
    LifecycleConflictLeelaz current = new LifecycleConflictLeelaz();
    TrackingShutdownLeelaz secondary = new TrackingShutdownLeelaz();
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, secondary));
    try {
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = secondary;
      EngineManager.currentEngineNo2 = 1;

      manager.reStartEngine2();

      assertEquals(1, manager.conflictCount);
      assertEquals(0, secondary.shutdownCount);
      assertEquals(0, manager.switchCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousSecondEngine;
      EngineManager.currentEngineNo2 = previousSecondEngineNo;
    }
  }

  @Test
  void primaryRestartKeepsIndexZeroAfterSecondaryExplicitRestart() throws Exception {
    RestartIndexLeelaz primary = new RestartIndexLeelaz("same-command");
    RestartIndexLeelaz secondary = new RestartIndexLeelaz("same-command");
    try (RestartIndexTestEnvironment environment =
        new RestartIndexTestEnvironment(List.of(primary, secondary), 0, 1)) {
      environment.manager.reStartEngine2();
      assertEquals(1, secondary.shutdownCount);
      assertEquals(0, EngineManager.currentEngineNo);
      environment.completeDeferredSwitch();

      environment.manager.reStartEngine();

      assertEquals(1, primary.shutdownCount);
      assertEquals(0, primary.startIndex);
      assertEquals(0, EngineManager.currentEngineNo);
    }
  }

  @Test
  void primaryRestartRejectsTheSameEngineIndex() throws Exception {
    RestartIndexLeelaz shared = new RestartIndexLeelaz("shared");
    RestartIndexLeelaz unrelated = new RestartIndexLeelaz("unrelated");
    try (RestartIndexTestEnvironment environment =
        new RestartIndexTestEnvironment(List.of(shared, unrelated), 0, 0)) {
      // This path stops before any frame interaction. Avoid showing a real modal dialog from the
      // Unsafe-allocated test frame, which is not a fully initialized AWT Window on Windows.
      LizzieFrame testFrame = Lizzie.frame;
      Lizzie.frame = null;
      try {
        environment.manager.reStartEngine();
      } finally {
        Lizzie.frame = testFrame;
      }

      assertEquals(0, shared.shutdownCount);
      assertEquals(0, unrelated.shutdownCount);
    }
  }

  @Test
  void primaryRestartUsesItsCurrentNonZeroIndex() throws Exception {
    RestartIndexLeelaz unused = new RestartIndexLeelaz("unused");
    RestartIndexLeelaz secondary = new RestartIndexLeelaz("secondary");
    RestartIndexLeelaz primary = new RestartIndexLeelaz("primary");
    try (RestartIndexTestEnvironment environment =
        new RestartIndexTestEnvironment(List.of(unused, secondary, primary), 2, 1)) {
      environment.manager.reStartEngine();

      assertEquals(1, primary.shutdownCount);
      assertEquals(2, primary.startIndex);
      assertEquals(0, secondary.shutdownCount);
    }
  }

  @Test
  void secondaryRestartAfterCloseDoesNotUseInvalidEngineIndex() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    int previousSecondEngineNo = EngineManager.currentEngineNo2;
    Leelaz current = new Leelaz("");
    TrackingShutdownLeelaz secondary = new TrackingShutdownLeelaz();
    EngineManager manager = new EngineManager(List.of(current, secondary));
    try {
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = secondary;
      EngineManager.currentEngineNo2 = 1;

      manager.killThisEngines2();
      manager.reStartEngine2();

      assertEquals(-1, EngineManager.currentEngineNo2);
      assertEquals(1, secondary.shutdownCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousSecondEngine;
      EngineManager.currentEngineNo2 = previousSecondEngineNo;
    }
  }

  @Test
  void failedTargetReadinessReleasesBothSwitchReservationsWithoutSynchronization()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = unavailableStartedEngine();
    target.isDownWithError = true;
    ReadinessFailureEngineManager manager =
        new ReadinessFailureEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(target, Lizzie.leelaz);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertFalse(target.isLoaded());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void targetReadinessTimeoutReleasesBothSwitchReservations() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = unavailableStartedEngine();
    ReadinessFailureEngineManager manager =
        new ReadinessFailureEngineManager(List.of(current, target), target, 10L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void switchWaitsForPublishedNameCheckAndBoardSynchronizationBeforeCompleting() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(500L);
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      target.isLoaded = true;
      target.allowFirstLoadedRead.countDown();

      assertTrue(target.secondLoadedReadEntered.await(1, TimeUnit.SECONDS));
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());

      target.isCheckingName = false;
      target.allowSecondLoadedRead.countDown();
      assertTrue(manager.synchronizationStarted.await(1, TimeUnit.SECONDS));
      assertEquals(1L, manager.completed.getCount());
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());

      manager.allowSynchronizationToComplete.countDown();
      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.synchronizationCount);
      assertEquals(0, manager.failureCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void publishedAbnormalExitFailsWithoutWaitingForTheStartupTimeout() throws Exception {
    assertPublishedTerminalStateFailsImmediately(
        target -> target.isDownWithError = true, "abnormal exit");
  }

  @Test
  void publishedNormalExitFailsWithoutWaitingForTheStartupTimeout() throws Exception {
    assertPublishedTerminalStateFailsImmediately(
        target -> target.isNormalEnd = true, "normal exit");
  }

  @Test
  void publishedStoppedStateFailsWithoutWaitingForTheStartupTimeout() throws Exception {
    assertPublishedTerminalStateFailsImmediately(target -> target.started = false, "stopped");
  }

  @Test
  void tuningStateExtendsTheOrdinaryStartupDeadline() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(1000L);
    target.isTuning = true;
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 10L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      target.allowFirstLoadedRead.countDown();
      assertTrue(target.secondLoadedReadEntered.await(1, TimeUnit.SECONDS));
      assertFalse(manager.completed.await(50, TimeUnit.MILLISECONDS));
      target.allowSecondLoadedRead.countDown();
      assertTrue(target.thirdLoadedReadEntered.await(1, TimeUnit.SECONDS));

      target.isLoaded = true;
      target.isCheckingName = false;
      target.allowThirdLoadedRead.countDown();
      assertTrue(manager.synchronizationStarted.await(1, TimeUnit.SECONDS));
      manager.allowSynchronizationToComplete.countDown();
      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.synchronizationCount);
      assertEquals(0, manager.failureCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void tuningTimeoutReleasesBothSwitchReservations() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(10L);
    target.isTuning = true;
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;
      target.releaseLoadedReads();

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(target, Lizzie.leelaz);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(target.isLoaded());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  private static void assertPublishedTerminalStateFailsImmediately(
      Consumer<ControlledReadinessLeelaz> publishTerminalState, String stateDescription)
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(500L);
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 5000L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      publishTerminalState.accept(target);
      target.allowFirstLoadedRead.countDown();

      assertTrue(
          manager.completed.await(500, TimeUnit.MILLISECONDS),
          stateDescription + " should fail before the five-second startup timeout");
      target.releaseLoadedReads();
      assertEquals(target, Lizzie.leelaz);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(target.isLoaded());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  private static ControlledReadinessLeelaz unavailableControlledEngine(long tuningTimeoutMillis)
      throws Exception {
    ControlledReadinessLeelaz engine = new ControlledReadinessLeelaz(tuningTimeoutMillis);
    engine.started = true;
    engine.isLoaded = false;
    engine.isCheckingName = true;
    return engine;
  }

  private static Leelaz unavailableStartedEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = false;
    engine.isCheckingName = true;
    return engine;
  }

  private static void assertDestructiveKillClaimsActiveTracking(boolean killAll) throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingKillLeelaz engine = new TrackingKillLeelaz();
    LifecycleFrame frame = allocate(LifecycleFrame.class);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(output));
    setCapabilityDiscoveryComplete(engine, true);
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = engine;
      Menu.engineMenu = new JFontMenu();
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
      EngineManager manager = new EngineManager(List.of(engine));

      if (killAll) {
        assertTrue(manager.killAllEngines());
      } else {
        manager.killThisEngines();
      }

      assertEquals(1, engine.forceQuitCount);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, tracking.lease().disposition());
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));
      assertEquals(1, frame.trackingInvalidationCount);
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertTrue(dispatchExclusiveLine(engine, "=800000002"));
      assertTrue(dispatchExclusiveLine(engine, ""));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Menu.engineMenu = previousEngineMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static void setLeelazField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static Object getLeelazField(Leelaz engine, String name)
      throws ReflectiveOperationException {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static Object getField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static ScheduledExecutorService runningReaderExecutor() throws Exception {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
    return executor;
  }

  private static Object installJavaSshReaderBinding(
      Leelaz engine,
      SSHController ssh,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr)
      throws Exception {
    return installJavaSshReaderBinding(engine, ssh, stdout, stderr, null);
  }

  private static Object installJavaSshReaderBinding(
      Leelaz engine,
      SSHController ssh,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr,
      BufferedOutputStream output)
      throws Exception {
    engine.useJavaSSH = true;
    setLeelazField(engine, "javaSSH", ssh);
    setLeelazField(engine, "outputStream", output);
    Method currentBinding = Leelaz.class.getDeclaredMethod("currentReaderStreamBinding");
    currentBinding.setAccessible(true);
    Object binding = currentBinding.invoke(engine);
    setField(binding, "stdoutExecutor", stdout);
    setField(binding, "stderrExecutor", stderr);
    setLeelazField(engine, "executor", stdout);
    setLeelazField(engine, "executorErr", stderr);
    return binding;
  }

  private static Object installRemoteReaderBinding(
      Leelaz engine,
      EngineTransport transport,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr)
      throws Exception {
    engine.useRemoteCompute = true;
    setLeelazField(engine, "remoteTransport", transport);
    Method currentBinding = Leelaz.class.getDeclaredMethod("currentReaderStreamBinding");
    currentBinding.setAccessible(true);
    Object binding = currentBinding.invoke(engine);
    setField(binding, "stdoutExecutor", stdout);
    setField(binding, "stderrExecutor", stderr);
    setLeelazField(engine, "executor", stdout);
    setLeelazField(engine, "executorErr", stderr);
    return binding;
  }

  private static Object newJavaSshReaderBinding(
      SSHController ssh,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr,
      long incarnation)
      throws Exception {
    return newJavaSshReaderBinding(ssh, stdout, stderr, null, incarnation);
  }

  private static Object newJavaSshReaderBinding(
      SSHController ssh,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr,
      BufferedOutputStream output,
      long incarnation)
      throws Exception {
    Class<?> bindingType = Class.forName(Leelaz.class.getName() + "$ReaderStreamBinding");
    java.lang.reflect.Constructor<?> constructor = bindingType.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object binding = constructor.newInstance(null, null, output, null, null, ssh, incarnation);
    setField(binding, "stdoutExecutor", stdout);
    setField(binding, "stderrExecutor", stderr);
    return binding;
  }

  private static boolean invokeStartReaderExecutors(
      Leelaz engine,
      Object binding,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr)
      throws Exception {
    Method start =
        Leelaz.class.getDeclaredMethod(
            "startReaderExecutors",
            binding.getClass(),
            ScheduledExecutorService.class,
            ScheduledExecutorService.class);
    start.setAccessible(true);
    return (boolean) start.invoke(engine, binding, stdout, stderr);
  }

  private static void invokeShutdownReaderTransport(Leelaz engine, Object binding)
      throws Exception {
    Method shutdown = Leelaz.class.getDeclaredMethod("shutdownReaderTransport", binding.getClass());
    shutdown.setAccessible(true);
    shutdown.invoke(engine, binding);
  }

  private static boolean awaitThreadState(Thread thread, Thread.State state, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == state) return true;
      Thread.sleep(5L);
    }
    return thread.getState() == state;
  }

  private static boolean hasRestartBootstrapReceiptContext(Leelaz engine) {
    try {
      @SuppressWarnings("unchecked")
      ThreadLocal<Object> context =
          (ThreadLocal<Object>) getLeelazField(engine, "restartBootstrapReceiptContext");
      return context.get() != null;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void setEngineStateUnrestored(Leelaz engine, boolean value) throws Exception {
    Field field = Leelaz.class.getDeclaredField("engineStateUnrestored");
    field.setAccessible(true);
    field.setBoolean(engine, value);
  }

  private static boolean forceFirstLaunchSession(boolean value) throws Exception {
    Field field = Lizzie.class.getDeclaredField("firstLaunchSession");
    field.setAccessible(true);
    boolean previous = field.getBoolean(null);
    field.setBoolean(null, value);
    return previous;
  }

  private static void setCapabilityDiscoveryComplete(Leelaz engine, boolean value)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("endGetCommandList");
    field.setAccessible(true);
    field.setBoolean(engine, value);
  }

  private static Leelaz.TrackingStreamLeaseAcquisition activateTracking(Leelaz engine)
      throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking =
        engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
    processCommandResponse(engine, "=800000000");
    assertTrue(dispatchExclusiveLine(engine, ""));
    assertTrue(tracking.lease().send("kata-analyze B 10"));
    return tracking;
  }

  private static void rebindReader(Leelaz engine) {
    try {
      Method method =
          Leelaz.class.getDeclaredMethod(
              "initializeStreams",
              java.io.InputStream.class,
              java.io.OutputStream.class,
              java.io.InputStream.class);
      method.setAccessible(true);
      method.invoke(
          engine,
          new java.io.ByteArrayInputStream(new byte[0]),
          new ByteArrayOutputStream(),
          new java.io.ByteArrayInputStream(new byte[0]));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void setReadBoardGmaReservation(
      Leelaz engine, Leelaz.EngineModeReservation reservation) throws Exception {
    Field field = Leelaz.class.getDeclaredField("readBoardGmaReservation");
    field.setAccessible(true);
    field.set(engine, reservation);
  }

  private static int pendingResponseHandlerCount(Leelaz engine) throws Exception {
    Object handlers = getLeelazField(engine, "pendingResponseHandlers");
    synchronized (handlers) {
      return ((java.util.Collection<?>) handlers).size();
    }
  }

  private static int pendingFenceResponseCommandId(Leelaz engine) throws Exception {
    Object handlers = getLeelazField(engine, "pendingResponseHandlers");
    synchronized (handlers) {
      java.util.ArrayDeque<?> pending = (java.util.ArrayDeque<?>) handlers;
      assertEquals(1, pending.size());
      return (Integer) getField(pending.peekFirst(), "responseCommandId");
    }
  }

  private static void awaitEngineUnavailable(Leelaz engine) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline
        && (engine.isLoaded() || engine.hasExclusiveGtpWorkInProgress())) {
      Thread.sleep(10L);
    }
  }

  private static void awaitReservationReleased(Leelaz engine) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && engine.hasExclusiveGtpWorkInProgress()) {
      Thread.sleep(10L);
    }
    assertFalse(engine.hasExclusiveGtpWorkInProgress());
  }

  /**
   * Waits for the narrow lifecycle round transition to be released at the stable restore frame.
   * The broad completion claim can still reject unrelated engine-mode owners until the final fence
   * settles, so callers must keep broad-busy assertions until fence settlement.
   */
  private static void awaitLifecycleTransitionReleased(Leelaz engine) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && engine.hasExclusiveGtpLifecycleTransitionForTest()) {
      Thread.sleep(10L);
    }
    assertFalse(engine.hasExclusiveGtpLifecycleTransitionForTest());
  }

  private static void awaitEngineStartupReady() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline
        && Lizzie.engineStartupStatus.snapshot().state != EngineStartupStatus.State.READY) {
      Thread.sleep(10L);
    }
    assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
  }

  private static String waitForLog(Path log, String marker, long timeoutMillis) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    String content = "";
    while (System.currentTimeMillis() < deadline) {
      content = readLog(log);
      if (content.contains(marker)) {
        return content;
      }
      Thread.sleep(10L);
    }
    assertTrue(content.contains(marker), "timed out waiting for engine log marker: " + marker);
    return content;
  }

  private static String waitForCommandCount(
      Path log, String command, int expectedCount, long timeoutMillis) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    String content = "";
    while (System.currentTimeMillis() < deadline) {
      content = readLog(log);
      if (countCommands(content, command) >= expectedCount) {
        return content;
      }
      Thread.sleep(10L);
    }
    assertTrue(
        countCommands(content, command) >= expectedCount,
        "timed out waiting for engine command count: "
            + command
            + " x"
            + expectedCount
            + " actual="
            + countCommands(content, command)
            + " log=\n"
            + content);
    return content;
  }

  private static String readLog(Path log) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
    while (true) {
      try {
        return Files.readString(log);
      } catch (IOException ex) {
        if (System.nanoTime() >= deadline) {
          return "";
        }
        Thread.sleep(10L);
      }
    }
  }

  private static int countCommands(String log, String command) {
    int count = 0;
    for (String line : log.split("\\R")) {
      if (line.contains(command)) {
        count++;
      }
    }
    return count;
  }

  private static List<String> sgfLines(String log) {
    List<String> lines = new ArrayList<>();
    for (String line : log.split("\\R")) {
      if (line.startsWith("SGF:")) {
        lines.add(line);
      }
    }
    return lines;
  }

  private static void invokeCheckEngineAlive(EngineManager manager) throws Exception {
    Method method = EngineManager.class.getDeclaredMethod("checkEngineAlive");
    method.setAccessible(true);
    method.invoke(manager);
  }

  private static final class DeferredSwitchEngineManager extends EngineManager {
    private Runnable afterSync;
    private int conflictCount;
    private int switchCount;
    private int failureCount;

    private DeferredSwitchEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void switchEngineInternal(
        int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
      switchCount++;
      this.afterSync = afterSync;
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      conflictCount++;
    }

    @Override
    protected void showSameEngineSelection() {}

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class DeferredSecondaryRestartEngineManager extends EngineManager {
    private final Leelaz target;
    private final CountDownLatch fenceFailureSettled = new CountDownLatch(1);
    private Runnable afterSync;
    private int failureCount;

    private DeferredSecondaryRestartEngineManager(List<Leelaz> engines, Leelaz target) {
      super(engines);
      this.target = target;
    }

    @Override
    protected void switchEngineInternal(
        int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
      Lizzie.leelaz2 = target;
      target.started = true;
      target.isLoaded = true;
      this.afterSync = afterSync;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
      fenceFailureSettled.countDown();
    }
  }

  private static final class GatedCommandOutputStream extends java.io.OutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);
    private final boolean failOnRelease;
    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    private GatedCommandOutputStream(boolean failOnRelease) {
      this.failOnRelease = failOnRelease;
    }

    @Override
    public void write(int value) throws java.io.IOException {
      awaitWriteEntry();
      if (failOnRelease) {
        throw new java.io.IOException("controlled board fence send failure");
      }
      sink.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
      awaitWriteEntry();
      if (failOnRelease) {
        throw new java.io.IOException("controlled board fence send failure");
      }
      sink.write(bytes, offset, length);
    }

    private void awaitWriteEntry() {
      writeEntered.countDown();
      try {
        releaseWrite.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled board fence stream interrupted", interrupted);
      }
    }

    private void releaseWrite() {
      releaseWrite.countDown();
    }
  }

  private static final class LifecycleConflictLeelaz extends Leelaz {
    private LifecycleConflictLeelaz() throws Exception {
      super("");
    }

    @Override
    public synchronized ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      return null;
    }
  }

  private static final class TrackingKillLeelaz extends Leelaz {
    private int forceQuitCount;

    private TrackingKillLeelaz() throws Exception {
      super("");
      started = true;
      isLoaded = true;
      isKatago = true;
      commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    }

    @Override
    public void forceQuit() {
      forceQuitCount++;
    }
  }

  private static final class TrackingRestartActionLeelaz extends Leelaz {
    private int shutdownCount;
    private int ponderCount;
    private boolean ponderWhileLifecycleHeld;
    private Runnable confirmation;
    private Consumer<String> rejection;
    private boolean emitPonderCommand;
    private boolean invokeRealInitialization;
    private int initializationCount;
    private boolean resumePonderIntent;
    private int secondaryTerminalCount;
    private boolean secondaryTerminalWhileLifecycleHeld;
    private boolean responseWatermarkWhileLifecycleHeld;
    private Runnable onPonder;
    private Runnable onSecondaryTerminal;
    private Runnable onResponseWatermark;
    private boolean useRealBoardSynchronizationFence;
    private long boardSynchronizationTimeoutMillis = -1L;

    private TrackingRestartActionLeelaz() throws Exception {
      super("");
      started = true;
      isLoaded = true;
      isKatago = true;
      commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    }

    @Override
    public void shutdown() {
      shutdownCount++;
      rebindReader(this);
    }

    @Override
    public void ponder() {
      ponderWhileLifecycleHeld = hasExclusiveGtpWorkInProgress();
      ponderCount++;
      if (onPonder != null) {
        onPonder.run();
      }
      if (emitPonderCommand) {
        cmdNumber++;
      }
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      if (useRealBoardSynchronizationFence) {
        super.confirmBoardSynchronization(onSuccess, onFailure);
      } else {
        confirmation = onSuccess;
        rejection = onFailure;
      }
    }

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return boardSynchronizationTimeoutMillis > 0
          ? boardSynchronizationTimeoutMillis
          : super.readBoardGmaRestoreResponseTimeoutMillis();
    }

    @Override
    void completeSecondaryExplicitRestartBoardSynchronization() {
      secondaryTerminalCount++;
      secondaryTerminalWhileLifecycleHeld = hasExclusiveGtpWorkInProgress();
      if (onSecondaryTerminal != null) {
        onSecondaryTerminal.run();
      }
      super.completeSecondaryExplicitRestartBoardSynchronization();
    }

    @Override
    public void setResponseUpToDate() {
      responseWatermarkWhileLifecycleHeld = hasExclusiveGtpWorkInProgress();
      if (onResponseWatermark != null) {
        onResponseWatermark.run();
      }
      super.setResponseUpToDate();
    }

    @Override
    void initializeAfterExplicitRestartBoardSynchronization(boolean resumePonder) {
      initializationCount++;
      resumePonderIntent = resumePonder;
      if (invokeRealInitialization) {
        super.initializeAfterExplicitRestartBoardSynchronization(resumePonder);
      } else {
        if (resumePonder) {
          ponder();
        }
        setResponseUpToDate();
      }
    }
  }

  private static final class LifecycleFrame extends LizzieFrame {
    private int trackingInvalidationCount;

    @Override
    public void invalidateTrackingAnalysis() {
      trackingInvalidationCount++;
    }

    @Override
    public void refresh() {}
  }

  private static final class SilentSwitchFrame extends LizzieFrame {
    private int reSetLocCount;

    @Override
    public void reSetLoc() {
      reSetLocCount++;
    }

    @Override
    public void invalidateTrackingAnalysis() {}

    @Override
    public void addInput(boolean shouldAdd) {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void refresh() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void setPdaAndWrn(double pda, double wrn) {}
  }

  private static final class SilentJFontMenu extends JFontMenu {
    @Override
    public void setText(String text) {}
  }

  private static final class QuietExitLeelaz extends Leelaz {
    private QuietExitLeelaz() throws Exception {
      super("");
    }

    @Override
    public void leela0110StopPonder() {}
  }

  private static final class FallbackCleanupLeelaz extends Leelaz {
    private FallbackCleanupLeelaz() throws Exception {
      super("");
    }

    @Override
    public void forceQuit() {}
  }

  private static final class FallbackProcess extends Process {
    private final AtomicInteger forcibleDestroyCount = new AtomicInteger();
    private volatile boolean alive = true;

    @Override
    public java.io.OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      alive = false;
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return !alive;
    }

    @Override
    public int exitValue() {
      if (alive) {
        throw new IllegalThreadStateException("controlled process still alive");
      }
      return 0;
    }

    @Override
    public void destroy() {}

    @Override
    public Process destroyForcibly() {
      forcibleDestroyCount.incrementAndGet();
      alive = false;
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }
  }

  private static final class BlockingPonderExitLeelaz extends Leelaz {
    private final CountDownLatch stopPonderEntered = new CountDownLatch(1);
    private final CountDownLatch allowStopPonder = new CountDownLatch(1);

    private BlockingPonderExitLeelaz() throws Exception {
      super("");
    }

    @Override
    public void leela0110StopPonder() {
      stopPonderEntered.countDown();
      try {
        if (!allowStopPonder.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release controlled stop-ponder");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }

  private static final class RecordingSshController extends SSHController {
    private final boolean blockClose;
    private final AtomicInteger closeCount = new AtomicInteger();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch allowClose = new CountDownLatch(1);

    private RecordingSshController(Leelaz owner) {
      this(owner, false);
    }

    private RecordingSshController(Leelaz owner, boolean blockClose) {
      super(owner, "127.0.0.1", "22");
      this.blockClose = blockClose;
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
      closeEntered.countDown();
      if (!blockClose) return;
      try {
        if (!allowClose.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release controlled SSH close");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }

  private static final class RecordingTransport implements EngineTransport {
    private final boolean blockClose;
    private final AtomicInteger closeCount = new AtomicInteger();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch allowClose = new CountDownLatch(1);

    private RecordingTransport(boolean blockClose) {
      this.blockClose = blockClose;
    }

    @Override
    public void start() {}

    @Override
    public java.io.InputStream stdout() {
      return null;
    }

    @Override
    public java.io.OutputStream stdin() {
      return null;
    }

    @Override
    public java.io.InputStream stderr() {
      return null;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public String description() {
      return "recording transport";
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
      closeEntered.countDown();
      if (!blockClose) return;
      try {
        if (!allowClose.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release controlled transport close");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }

  private static final class RecordingSubmissionExecutor extends ScheduledThreadPoolExecutor {
    private final boolean gateFirstSubmission;
    private final AtomicInteger submissionCount = new AtomicInteger();
    private final CountDownLatch submissionEntered = new CountDownLatch(1);
    private final CountDownLatch allowSubmission = new CountDownLatch(1);

    private RecordingSubmissionExecutor(boolean gateFirstSubmission) {
      super(1);
      this.gateFirstSubmission = gateFirstSubmission;
    }

    @Override
    public void execute(Runnable command) {
      int submission = submissionCount.incrementAndGet();
      if (gateFirstSubmission && submission == 1) {
        submissionEntered.countDown();
        try {
          if (!allowSubmission.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting to release controlled reader submission");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      super.execute(() -> {});
    }
  }

  private static final class BudgetConsumingExecutor extends ScheduledThreadPoolExecutor {
    private final long requiredWaitNanos;
    private final AtomicInteger fallbackShutdownCount = new AtomicInteger();
    private volatile boolean terminated;

    private BudgetConsumingExecutor(long requiredWaitMillis) {
      super(1);
      requiredWaitNanos = TimeUnit.MILLISECONDS.toNanos(requiredWaitMillis);
    }

    @Override
    public boolean isShutdown() {
      return true;
    }

    @Override
    public boolean isTerminated() {
      return terminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      if (terminated) {
        return true;
      }
      long allowedWaitNanos = Math.max(0L, unit.toNanos(timeout));
      TimeUnit.NANOSECONDS.sleep(Math.min(requiredWaitNanos, allowedWaitNanos));
      if (allowedWaitNanos >= requiredWaitNanos) {
        terminated = true;
      }
      return terminated;
    }

    @Override
    public List<Runnable> shutdownNow() {
      fallbackShutdownCount.incrementAndGet();
      terminated = true;
      return super.shutdownNow();
    }
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
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }

  private static final class PkRestoreLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();
    private final CountDownLatch startCompleted = new CountDownLatch(1);
    private final CountDownLatch restoreFailure = new CountDownLatch(1);
    private final CountDownLatch restoreEntered = new CountDownLatch(1);
    private final CountDownLatch allowRestore = new CountDownLatch(1);
    private String loadedSgf = "";
    private Runnable mutateOnFirstCommand;
    private Runnable mutateOnStart;
    private Runnable onLifecycleReservation;
    private int clearWithoutPonderCount;
    private boolean commandMutated;
    private boolean readyAfterStart = true;
    private boolean failRestore;
    private boolean blockRestore;
    private List<Leelaz> resolvedMirrors = List.of();
    private int mirrorResolutionCount;
    private volatile int restoreCount;
    private volatile int ponderCount;
    private volatile boolean deferBoardSynchronizationCompletion;
    private volatile Runnable pendingBoardSynchronizationCompletion;

    private PkRestoreLeelaz() throws Exception {
      super("");
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            commands.add(command);
            if (command.startsWith("loadsgf ")) {
              restoreEntered.countDown();
              if (blockRestore) {
                assertTrue(allowRestore.await(2, TimeUnit.SECONDS));
              }
              if (failRestore) {
                restoreFailure.countDown();
                return ExactSnapshotRestoreProtocolFixture.Response.error(
                    "controlled PK restore failure");
              }
              loadedSgf = Files.readString(Path.of(command.substring("loadsgf ".length())));
              restoreCount++;
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
      if (!commandMutated && mutateOnFirstCommand != null) {
        commandMutated = true;
        mutateOnFirstCommand.run();
      }
    }

    @Override
    public void notPondering() {}


    @Override
    public void clearBestMoves() {}

    @Override
    public void clearWithoutPonder() {
      clearWithoutPonderCount++;
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      if (onLifecycleReservation != null) {
        onLifecycleReservation.run();
      }
      return super.beginExclusiveGtpLifecycleReservation(owner);
    }

    @Override
    public void startEngine(int index) {
      if (mutateOnStart != null) {
        mutateOnStart.run();
      }
      started = true;
      isLoaded = readyAfterStart;
      isCheckingName = false;
      startCompleted.countDown();
    }

    @Override
    public void nameCmd() {}

    @Override
    public void ponder() {
      ponderCount++;
    }

    @Override
    void confirmBoardSynchronization(
        Leelaz mirror, Runnable onSuccess, Consumer<String> onFailure) {
      if (deferBoardSynchronizationCompletion) {
        pendingBoardSynchronizationCompletion = onSuccess;
      } else {
        onSuccess.run();
      }
    }

    @Override
    Leelaz resolveLoadSgfMirrorEngine() {
      if (resolvedMirrors.isEmpty()) {
        return super.resolveLoadSgfMirrorEngine();
      }
      int index = Math.min(mirrorResolutionCount++, resolvedMirrors.size() - 1);
      return resolvedMirrors.get(index);
    }
  }

  private static final class PreparedRestoreBoard extends Board {
    private CountDownLatch restoreCompleted;
    private boolean preparedRestoreReceived;
    private boolean genericRestoreReceived;
    private boolean rootRestoreReceived;
    private boolean engineGameInitialization;

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      receivePreparedRestore(preparedRestore, false);
    }

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore,
        boolean isEngineGame) {
      receivePreparedRestore(preparedRestore, isEngineGame);
    }

    private void receivePreparedRestore(
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore, boolean isEngineGame) {
      if (preparedRestore == null) {
        genericRestoreReceived = true;
      } else {
        preparedRestoreReceived = true;
        engineGameInitialization = isEngineGame;
        preparedRestore.execute();
      }
      restoreCompleted.countDown();
    }

    @Override
    public void resendMoveToEngine(Leelaz engine, boolean loadEngine) {
      genericRestoreReceived = true;
      restoreCompleted.countDown();
    }

    @Override
    public void resendMoveToEngineFromRoot(
        Leelaz engine,
        Leelaz mirrorEngine,
        boolean loadEngine,
        boolean isEngineGame,
        ArrayList<featurecat.lizzie.rules.Movelist> moves,
        Double gameKomi) {
      rootRestoreReceived = true;
      engineGameInitialization = isEngineGame;
      restoreCompleted.countDown();
    }

    @Override
    public void restoreMoveNumber(
        ArrayList<featurecat.lizzie.rules.Movelist> mv,
        boolean isEngineGame,
        Leelaz engine,
        boolean loadEngine) {
      genericRestoreReceived = true;
      restoreCompleted.countDown();
    }
  }
  private static final class UpdateEnginesState {
    private final int targetWidth;
    private final int targetHeight;
    private final boolean doubleEngine;
    private final boolean mirrorStartFails;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz previousMirror = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final Menu previousMenu = LizzieFrame.menu;
    private final Config previousConfig = Lizzie.config;
    private final JFontMenu previousEngineMenu = Menu.engineMenu;
    private final JFontMenu previousEngineMenu2 = Menu.engineMenu2;
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final int previousEngineNo2 = EngineManager.currentEngineNo2;
    private final int previousBoardWidth = Board.boardWidth;
    private final int previousBoardHeight = Board.boardHeight;
    private final UpdateForegroundLeelaz previousForegroundEngine;
    private final UpdateForegroundLeelaz previousSecondaryEngine;
    private final UpdateBoard board;
    private final EngineManager manager;
    private final String updateEngineCommand;
    private final Path commandLog;
    private final Path startupGate;
    private final Path boardFenceGate;
    private final Path loadSgfFailure;
    private final Path catchUpGate;
    private final Path fenceFailure;
    private boolean cleanupLifecycleSettled;
    private boolean cleanupProcessesStopped;
    private boolean cleanupExecutorsStopped;
    private List<ScheduledExecutorService> capturedReaderExecutors = List.of();
    private boolean restored;

    private UpdateEnginesState(int targetWidth, int targetHeight) throws Exception {
      this(targetWidth, targetHeight, false);
    }

    private UpdateEnginesState(int targetWidth, int targetHeight, boolean doubleEngine)
        throws Exception {
      this(targetWidth, targetHeight, doubleEngine, false);
    }

    private UpdateEnginesState(
        int targetWidth, int targetHeight, boolean doubleEngine, boolean mirrorStartFails)
        throws Exception {
      this.targetWidth = targetWidth;
      this.targetHeight = targetHeight;
      this.doubleEngine = doubleEngine;
      this.mirrorStartFails = mirrorStartFails;
      previousForegroundEngine = new UpdateForegroundLeelaz();
      previousForegroundEngine.oriEnginename = "update-target";
      previousForegroundEngine.started = true;
      previousForegroundEngine.isLoaded = true;
      previousSecondaryEngine = doubleEngine ? new UpdateForegroundLeelaz() : null;
      if (previousSecondaryEngine != null) {
        previousSecondaryEngine.oriEnginename = "update-mirror";
        previousSecondaryEngine.started = true;
        previousSecondaryEngine.isLoaded = true;
      }
      commandLog = Files.createTempFile("lizzie-update-engine-", ".log");
      startupGate = Files.createTempFile("lizzie-update-engine-startup-", ".gate");
      boardFenceGate = Files.createTempFile("lizzie-update-engine-fence-", ".gate");
      loadSgfFailure = Files.createTempFile("lizzie-update-engine-loadsgf-", ".failure");
      catchUpGate = Files.createTempFile("lizzie-update-engine-catchup-", ".gate");
      fenceFailure = Files.createTempFile("lizzie-update-engine-fence-", ".failure");
      Files.delete(loadSgfFailure);
      Files.delete(startupGate);
      Files.delete(boardFenceGate);
      Files.delete(catchUpGate);
      Files.delete(fenceFailure);
      updateEngineCommand =
          updateEngineCommand(
              commandLog,
              startupGate,
              loadSgfFailure,
              boardFenceGate,
              catchUpGate,
              fenceFailure);
      board = allocate(UpdateBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = historyWithStone(3, 3, 6.5);
      history.add(moveNode(15, 15, Stone.WHITE, true, 1));
      board.setHistory(history);
      manager = new EngineManager(List.of());
    }

    private void install() {
      Config config = allocateUnchecked(Config.class);
      config.extraMode = doubleEngine ? ExtraMode.Double_Engine : ExtraMode.Normal;
      JSONObject engineConfig =
          new JSONObject()
              .put("command", updateEngineCommand)
              .put("name", "update-target")
              .put("preload", false)
              .put("width", targetWidth)
              .put("height", targetHeight)
              .put("komi", 7.5);
      JSONArray engines = new JSONArray().put(engineConfig);
      if (doubleEngine) {
        JSONObject mirrorConfig =
            new JSONObject(engineConfig.toString()).put("name", "update-mirror");
        if (mirrorStartFails) {
          // A remote-compute command has no saved credential in tests, so the mirror's
          // startEngine throws IOException before any process launches.
          mirrorConfig.put("command", RemoteComputeConfig.COMMAND_ZHIZI);
        }
        engines.put(mirrorConfig);
      }
      config.leelazConfig = new JSONObject().put("engine-settings-list", engines);
      config.uiConfig = new JSONObject();
      Lizzie.config = config;
      Lizzie.frame = allocateUnchecked(SilentSwitchFrame.class);
      Lizzie.gtpConsole = allocateUnchecked(SilentGtpConsole.class);
      LizzieFrame.toolbar = allocateUnchecked(SilentSwitchToolbar.class);
      LizzieFrame.toolbar.enginePkBlack = new JComboBox<>();
      LizzieFrame.toolbar.enginePkWhite = new JComboBox<>();
      LizzieFrame.menu = allocateUnchecked(SilentUpdateMenu.class);
      Menu.engineMenu = new JFontMenu();
      Menu.engineMenu2 = new JFontMenu();
      Lizzie.leelaz = previousForegroundEngine;
      Lizzie.leelaz2 = previousSecondaryEngine;
      Lizzie.board = board;
      Lizzie.engineStartupStatus.checking("engine.starting", "update replacement");
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = doubleEngine ? 1 : -1;
    }

    private void releaseStartup() throws Exception {
      awaitReplacementProcessLaunch(30_000L);
      // Both replacement fixtures block their first `name` on startupGate. Opening the
      // gate after only one name lets the ready engine start dual-engine loadsgf before
      // the second process exists, so the restore waits 5s for a response that never
      // comes. Later `name` commands wait on the fence gate, so this count is only safe
      // before the startup gate is written.
      waitForCommandCount(commandLog, "name", expectedReplacementProcesses(), 10_000L);
      Files.writeString(startupGate, "ready");
    }

    private int expectedReplacementProcesses() {
      return doubleEngine && !mirrorStartFails ? 2 : 1;
    }

    private void awaitReplacementProcessLaunch(long timeoutMillis) throws Exception {
      assertFalse(manager.engineList == null || manager.engineList.isEmpty());
      int expected = expectedReplacementProcesses();
      assertTrue(
          manager.engineList.size() >= expected,
          "replacement engine list is smaller than the expected process count");
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      for (int index = 0; index < expected; index++) {
        Leelaz replacement = manager.engineList.get(index);
        Process replacementProcess = null;
        while (replacementProcess == null && System.nanoTime() < deadline) {
          replacementProcess = (Process) getLeelazField(replacement, "process");
          if (replacementProcess == null) {
            Thread.sleep(10L);
          }
        }
        assertNotNull(
            replacementProcess, "timed out waiting for replacement process launch index=" + index);
        assertTrue(
            replacementProcess.isAlive(),
            "replacement process exited before sending name index=" + index);
      }
    }

    private void releaseBoardFence() throws Exception {
      Files.writeString(boardFenceGate, "ready");
    }
    private void releaseCatchUp() throws Exception {
      Files.writeString(catchUpGate, "ready");
    }

    private void failFence() throws Exception {
      Files.writeString(fenceFailure, "fail");
    }
    private void failLoadSgf() throws Exception {
      Files.writeString(loadSgfFailure, "fail");
    }

    private void restore() {
      if (restored) {
        return;
      }
      try {
        Files.writeString(startupGate, "ready");
      } catch (Exception ignored) {
      }
      try {
        releaseBoardFence();
      } catch (Exception ignored) {
      }
      try {
        releaseCatchUp();
      } catch (Exception ignored) {
      }
      List<Leelaz> replacementEngines =
          manager.engineList == null ? List.of() : new ArrayList<>(manager.engineList);
      List<Leelaz> lifecycleParticipants = new ArrayList<>(replacementEngines);
      lifecycleParticipants.add(previousForegroundEngine);
      if (previousSecondaryEngine != null) {
        lifecycleParticipants.add(previousSecondaryEngine);
      }
      cleanupLifecycleSettled = awaitReplacementLifecycleSettlement(lifecycleParticipants, 10_000L);
      CapturedReaderExecutors executorCapture = captureReaderExecutors(replacementEngines);
      capturedReaderExecutors = executorCapture.executors;
      cleanupProcessesStopped = stopReplacementProcesses(replacementEngines);
      boolean capturedExecutorsStopped =
          awaitCapturedReaderExecutorsStopped(capturedReaderExecutors, 2_000L);
      cleanupExecutorsStopped = executorCapture.complete && capturedExecutorsStopped;
      if (!cleanupLifecycleSettled) {
        // Drain late work for isolation, but never let test cleanup overwrite the production
        // settlement result captured before forceQuit.
        awaitReplacementLifecycleSettlement(lifecycleParticipants, 2_000L);
      }
      try {
        SwingUtilities.invokeAndWait(() -> {});
      } catch (Exception ignored) {
      }
      try {
        Files.deleteIfExists(commandLog);
        Files.deleteIfExists(startupGate);
        Files.deleteIfExists(loadSgfFailure);
        Files.deleteIfExists(boardFenceGate);
        Files.deleteIfExists(catchUpGate);
        Files.deleteIfExists(fenceFailure);
      } catch (Exception ignored) {
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
      Lizzie.config = previousConfig;
      Menu.engineMenu = previousEngineMenu;
      Menu.engineMenu2 = previousEngineMenu2;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      restored = true;
      if (!cleanupLifecycleSettled || !cleanupProcessesStopped || !cleanupExecutorsStopped) {
        throw new AssertionError(
            "updateEngines teardown did not settle: lifecycleSettled="
                + cleanupLifecycleSettled
                + ", processesStopped="
                + cleanupProcessesStopped
                + ", executorsStopped="
                + cleanupExecutorsStopped);
      }
    }

    private static boolean awaitReplacementLifecycleSettlement(
        List<Leelaz> lifecycleParticipants, long timeoutMillis) {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      while (System.nanoTime() < deadline) {
        boolean lifecycleActive = false;
        for (Leelaz engine : lifecycleParticipants) {
          if (engine != null && engine.hasExclusiveGtpWorkInProgress()) {
            lifecycleActive = true;
            break;
          }
        }
        if (!lifecycleActive) {
          return true;
        }
        try {
          Thread.sleep(10L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      for (Leelaz engine : lifecycleParticipants) {
        if (engine != null && engine.hasExclusiveGtpWorkInProgress()) {
          return false;
        }
      }
      return true;
    }

    private static boolean stopReplacementProcesses(List<Leelaz> replacementEngines) {
      return stopReplacementProcesses(replacementEngines, 2_000L);
    }

    private static boolean stopReplacementProcesses(
        List<Leelaz> replacementEngines, long productionExitTimeoutMillis) {
      boolean allStopped = true;
      for (Leelaz engine : replacementEngines) {
        Process runningProcess = null;
        try {
          runningProcess = (Process) getLeelazField(engine, "process");
          engine.forceQuit();
        } catch (Exception cleanupFailure) {
          allStopped = false;
        }
        if (runningProcess == null) {
          continue;
        }
        if (!awaitExactProcessExit(runningProcess, productionExitTimeoutMillis)) {
          // This is test-only leak prevention. A fallback can make teardown safe, but it must
          // never turn a missed production deadline green.
          allStopped = false;
          try {
            runningProcess.destroyForcibly();
          } catch (RuntimeException cleanupFailure) {
            allStopped = false;
          }
          if (!awaitExactProcessExit(runningProcess, 2_000L)) {
            allStopped = false;
          }
        }
        if (runningProcess.isAlive()) {
          allStopped = false;
        }
      }
      return allStopped;
    }

    private static CapturedReaderExecutors captureReaderExecutors(List<Leelaz> replacementEngines) {
      List<ScheduledExecutorService> captured = new ArrayList<>();
      boolean complete = true;
      for (Leelaz engine : replacementEngines) {
        try {
          Object binding = getLeelazField(engine, "readerStreamBinding");
          if (binding == null) {
            continue;
          }
          Object readerExecutorLock = getField(binding, "readerExecutorLock");
          synchronized (readerExecutorLock) {
            for (String fieldName : List.of("stdoutExecutor", "stderrExecutor")) {
              ScheduledExecutorService service =
                  (ScheduledExecutorService) getField(binding, fieldName);
              if (service != null
                  && captured.stream().noneMatch(capturedService -> capturedService == service)) {
                captured.add(service);
              }
            }
          }
        } catch (Exception captureFailure) {
          complete = false;
        }
      }
      return new CapturedReaderExecutors(List.copyOf(captured), complete);
    }

    private static boolean awaitExactProcessExit(Process process, long timeoutMillis) {
      boolean interrupted = false;
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      try {
        while (process.isAlive()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0L) {
            break;
          }
          try {
            if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
              break;
            }
          } catch (InterruptedException interruption) {
            interrupted = true;
          }
        }
        return !process.isAlive();
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private static boolean awaitCapturedReaderExecutorsStopped(
        List<ScheduledExecutorService> capturedExecutors, long timeoutMillis) {
      boolean allStopped = true;
      long productionDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      List<ScheduledExecutorService> fallbackCleanup = new ArrayList<>();
      for (ScheduledExecutorService service : capturedExecutors) {
        boolean shutdownByProduction = service.isShutdown();
        boolean terminatedByProduction =
            shutdownByProduction && awaitExactExecutorTerminationUntil(service, productionDeadline);
        if (!terminatedByProduction) {
          allStopped = false;
          fallbackCleanup.add(service);
        }
      }
      for (ScheduledExecutorService service : fallbackCleanup) {
        service.shutdownNow();
      }
      long fallbackDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      for (ScheduledExecutorService service : fallbackCleanup) {
        awaitExactExecutorTerminationUntil(service, fallbackDeadline);
      }
      return allStopped;
    }

    private static final class CapturedReaderExecutors {
      private final List<ScheduledExecutorService> executors;
      private final boolean complete;

      private CapturedReaderExecutors(List<ScheduledExecutorService> executors, boolean complete) {
        this.executors = executors;
        this.complete = complete;
      }
    }

    private static boolean awaitExactExecutorTerminationUntil(
        ScheduledExecutorService service, long deadlineNanos) {
      boolean interrupted = false;
      try {
        while (!service.isTerminated()) {
          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0L) {
            break;
          }
          try {
            if (service.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
              break;
            }
          } catch (InterruptedException interruption) {
            interrupted = true;
          }
        }
        return service.isTerminated();
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private static <T> T allocateUnchecked(Class<T> type) {
      try {
        return allocate(type);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }

    private static String updateEngineCommand(
        Path commandLog,
        Path startupGate,
        Path loadSgfFailure,
        Path boardFenceGate,
        Path catchUpGate,
        Path fenceFailure)
        throws Exception {
      boolean windows =
          System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
      Path javaExecutable =
          Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
              .toAbsolutePath()
              .normalize();
      Path testClasses =
          Path.of(
                  UpdateEngineGtpFixture.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI())
              .toAbsolutePath()
              .normalize();
      return commandQuote(javaExecutable.toString())
          + " -cp "
          + commandQuote(testClasses.toString())
          + " "
          + UpdateEngineGtpFixture.class.getName()
          + " "
          + commandQuote(commandLog.toString())
          + " "
          + commandQuote(startupGate.toString())
          + " "
          + commandQuote(loadSgfFailure.toString())
          + " "
          + commandQuote(boardFenceGate.toString())
          + " "
          + commandQuote(catchUpGate.toString())
          + " "
          + commandQuote(fenceFailure.toString());
    }

    private static String commandQuote(String value) {
      if (value.indexOf('"') >= 0) {
        throw new IllegalArgumentException("command argument contains a double quote: " + value);
      }
      return "\"" + value + "\"";
    }
  }

  private static final class UpdateForegroundLeelaz extends Leelaz {
    private Runnable onForceQuit;

    private UpdateForegroundLeelaz() throws Exception {
      super("");
    }

    @Override
    public void forceQuit() {
      if (onForceQuit != null) {
        onForceQuit.run();
      }
      started = false;
    }
  }

  private static final class UpdateBoard extends Board {
    private int clearCount;
    private int rootRestoreCount;
    private ArrayList<featurecat.lizzie.rules.Movelist> rootMoves;
    private Double rootKomi;

    @Override
    public void clear(boolean isEngineGame) {
      clearCount++;
      setHistory(new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
    }

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      preparedRestore.execute();
    }

    @Override
    public void resendMoveToEngineFromRoot(
        Leelaz engine,
        Leelaz mirrorEngine,
        boolean loadEngine,
        boolean isEngineGame,
        ArrayList<featurecat.lizzie.rules.Movelist> moves,
        Double gameKomi) {
      rootRestoreCount++;
      rootMoves = featurecat.lizzie.rules.Movelist.copyList(moves);
      rootKomi = gameKomi;
    }
  }

  private static final class SilentUpdateMenu extends Menu {
    @Override
    public void updateEngineMenu() {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void changeEngineIcon2(int index, int mode) {}

    @Override
    public void changeicon(int index) {}

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void showPda(boolean show) {}
  }

  private static final class LeaseConflictEngineManager extends EngineManager {
    private int leaseConflictCount;

    private LeaseConflictEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      leaseConflictCount++;
    }
  }

  private static final class HistorySwapBoard extends Board {
    private BoardHistoryList firstHistory;
    private BoardHistoryList secondHistory;
    private int historyCalls;

    @Override
    public BoardHistoryList getHistory() {
      historyCalls++;
      return historyCalls <= 3 ? firstHistory : secondHistory;
    }

    @Override
    public ArrayList<featurecat.lizzie.rules.Movelist> getMoveList() {
      return new ArrayList<>();
    }

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      preparedRestore.execute();
      throw new PreparedRestoreObserved();
    }
  }

  private static final class SilentSwitchToolbar extends BottomToolbar {
    @Override
    public void reSetButtonLocation() {}
  }

  private static final class RecordingSwitchLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();
    private Runnable onLifecycleReservation;
    private String loadedSgf = "";
    private int boardSynchronizationConfirmations;
    private Runnable boardSynchronizationCompletion;
    private int ponderCount;
    private int responseFreshenedAfterPonderCount = -1;

    private RecordingSwitchLeelaz() throws Exception {
      super("");
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            commands.add(command);
            if (command.startsWith("loadsgf ")) {
              loadedSgf = Files.readString(Path.of(command.substring("loadsgf ".length())));
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }

    @Override
    public void nameCmdfornoponder() {
      commands.add("name");
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      return beginLifecycleReservation(null);
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      return beginLifecycleReservation(owner);
    }

    private ExclusiveGtpLifecycleReservation beginLifecycleReservation(Object owner) {
      if (onLifecycleReservation != null) {
        onLifecycleReservation.run();
      }
      return owner == null
          ? super.beginExclusiveGtpLifecycleReservation()
          : super.beginExclusiveGtpLifecycleReservation(owner);
    }

    @Override
    public void notPondering() {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void ponder() {
      ponderCount++;
      Pondering();
    }

    @Override
    public void setResponseUpToDate() {
      super.setResponseUpToDate();
      responseFreshenedAfterPonderCount = ponderCount;
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      boardSynchronizationConfirmations++;
      boardSynchronizationCompletion = onSuccess;
    }

    private void completeBoardSynchronization() {
      Runnable completion = boardSynchronizationCompletion;
      boardSynchronizationCompletion = null;
      if (completion != null) {
        completion.run();
      }
    }

    @Override
    public void loadSgf(Path sgfFile, Runnable afterConsumed) {
          try {
        loadedSgf = Files.readString(sgfFile);
          } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
          }
      afterConsumed.run();
    }
  }

  private static final class RestartIndexTestEnvironment implements AutoCloseable {
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final Menu previousMenu = LizzieFrame.menu;
    private final JFontMenu previousEngineMenu = Menu.engineMenu;
    private final BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    private final Config previousConfig = Lizzie.config;
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final int previousEngineNo2 = EngineManager.currentEngineNo2;
    private final DeferredBoardSynchronizationEngineManager manager;

    private RestartIndexTestEnvironment(
        List<RestartIndexLeelaz> engines, int primaryIndex, int secondaryIndex) throws Exception {
      List<Leelaz> catalog = new ArrayList<>(engines);
      manager = new DeferredBoardSynchronizationEngineManager(catalog);
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(CountingRestartGateFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      Menu.engineMenu = new SilentJFontMenu();
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      Lizzie.board = preparedRestoreBoard();
      engines.forEach(
          engine -> {
            engine.started = true;
            engine.isLoaded = true;
          });
      Lizzie.leelaz = catalog.get(primaryIndex);
      Lizzie.leelaz2 = catalog.get(secondaryIndex);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = primaryIndex;
      EngineManager.currentEngineNo2 = secondaryIndex;
    }

    private void completeDeferredSwitch() {
      Runnable completion = manager.afterSync;
      manager.afterSync = null;
      if (completion != null) {
        completion.run();
      }
    }

    @Override
    public void close() throws Exception {
      completeDeferredSwitch();
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
      Menu.engineMenu = previousEngineMenu;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  private static final class RestartIndexLeelaz extends Leelaz {
    private int shutdownCount;
    private int startIndex = -1;

    private RestartIndexLeelaz(String command) throws Exception {
      super(command);
    }

    @Override
    public void shutdown() {
      shutdownCount++;
      started = false;
    }

    @Override
    public void startEngine(int index) {
      startIndex = index;
      started = true;
      isLoaded = true;
      isCheckingName = false;
    }

    @Override
    void initializeAfterExplicitRestartBoardSynchronization(boolean resumePonder) {}

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      onSuccess.run();
    }
  }

  private static final class RecordingSwitchBoard extends Board {
    private boolean preparedRestoreReceived;

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      preparedRestoreReceived = true;
      preparedRestore.execute();
      throw new PreparedRestoreObserved();
    }
  }

  private static final class PreparedRestoreObserved extends RuntimeException {}

  private static final class TargetChangingList extends AbstractList<Leelaz> {
    private final Leelaz current;
    private final Leelaz firstTarget;
    private final Leelaz laterTarget;
    private int targetReads;

    private TargetChangingList(Leelaz current, Leelaz firstTarget, Leelaz laterTarget) {
      this.current = current;
      this.firstTarget = firstTarget;
      this.laterTarget = laterTarget;
    }

    @Override
    public Leelaz get(int index) {
      if (index == 0) {
        return current;
      }
      if (index == 1) {
        return targetReads++ == 0 ? firstTarget : laterTarget;
      }
      throw new IndexOutOfBoundsException(index);
    }

    @Override
    public int size() {
      return 2;
    }
  }

  private static final class DeferredBoardSynchronizationEngineManager extends EngineManager {
    private Runnable synchronization;
    private Runnable afterSync;
    private Leelaz synchronizationEngine;

    private DeferredBoardSynchronizationEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void synchronizeEngineWhenReady(
        Leelaz engine, Runnable synchronization, Runnable afterSync) {
      this.synchronizationEngine = engine;
      this.synchronization = synchronization;
      this.afterSync = afterSync;
    }

    @Override
    protected void showSameEngineSelection() {}
  }
  private static final class SetupGuardEngineManager extends EngineManager {
    private int setupModeBlockCount;

    private SetupGuardEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showSetupModeEngineUnavailable() {
      setupModeBlockCount++;
    }
  }

  private static final class CountingRestartGateFrame extends LizzieFrame {
    private int beginCount;
    private int reSetLocCount;

    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public RestartInteractionGate beginRestartInteractionGate() {
      beginCount++;
      return () -> {};
    }

    @Override
    public void reSetLoc() {
      reSetLocCount++;
    }

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refresh() {}
  }

  private static final class CountingRestartMenu extends Menu {
    private int updateCount;

    @Override
    public void changeicon(int index) {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {
      updateCount++;
    }
  }

  private static final class FailingRestartGateFrame extends LizzieFrame {
    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public RestartInteractionGate beginRestartInteractionGate() {
      throw new IllegalStateException("controlled restart gate failure");
    }
  }

  private static final class GateFailureEngineManager extends EngineManager {
    private int failureCount;

    private GateFailureEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class OrderedLifecycleLeelaz extends Leelaz {
    private final String name;
    private final List<String> reservationOrder;
    private final boolean rejectReservation;
    private int reservationAttempts;

    private OrderedLifecycleLeelaz(
        String name, List<String> reservationOrder, boolean rejectReservation) throws Exception {
      super("");
      this.name = name;
      this.reservationOrder = reservationOrder;
      this.rejectReservation = rejectReservation;
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      return beginLifecycleReservation(null);
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      return beginLifecycleReservation(owner);
    }

    private ExclusiveGtpLifecycleReservation beginLifecycleReservation(Object owner) {
      reservationAttempts++;
      reservationOrder.add(name);
      if (rejectReservation) {
        return null;
      }
      return owner == null
          ? super.beginExclusiveGtpLifecycleReservation()
          : super.beginExclusiveGtpLifecycleReservation(owner);
    }
  }

  private static final class FenceTrackingLeelaz extends Leelaz {
    private Runnable confirmation;
    private Consumer<String> rejection;

    private FenceTrackingLeelaz() throws Exception {
      super("");
    }

    @Override
    void initializeAfterExplicitRestartBoardSynchronization(boolean resumePonder) {}

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      confirmation = onSuccess;
      rejection = onFailure;
    }
  }

  private static final class ReceiptAwareFenceLeelaz extends Leelaz {
    private boolean receiptSeenByBoardFence;

    private ReceiptAwareFenceLeelaz() throws Exception {
      super("");
      isKatago = true;
      commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      receiptSeenByBoardFence = hasRestartBootstrapReceiptContext(this);
      onSuccess.run();
    }
  }

  private static final class ReceiptSynchronizationEngineManager extends EngineManager {
    private final CountDownLatch completed = new CountDownLatch(1);

    private ReceiptSynchronizationEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    private void synchronize(Leelaz engine, Runnable afterSync) {
      synchronize(engine, () -> {}, afterSync);
    }

    private void synchronize(Leelaz engine, Runnable synchronization, Runnable afterSync) {
      synchronizeEngineWhenReady(
          engine,
          synchronization,
          () -> {
            afterSync.run();
            completed.countDown();
          });
    }
  }

  private static final class RecoverySwitchEngineManager extends EngineManager {
    private final Leelaz target;
    private Runnable afterSync;
    private int failureCount;

    private RecoverySwitchEngineManager(List<Leelaz> engines, Leelaz target) {
      super(engines);
      this.target = target;
    }

    @Override
    protected void switchEngineInternal(
        int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
      Lizzie.leelaz = target;
      target.started = true;
      target.isLoaded = true;
      this.afterSync = afterSync;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class ReadinessFailureEngineManager extends EngineManager {
    private final Leelaz target;
    private final long timeoutMillis;
    private final CountDownLatch completed = new CountDownLatch(1);
    private int failureCount;
    private int synchronizationCount;

    private ReadinessFailureEngineManager(List<Leelaz> engines, Leelaz target, long timeoutMillis) {
      super(engines);
      this.target = target;
      this.timeoutMillis = timeoutMillis;
    }

    @Override
    protected void switchEngineInternal(
        int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
      Lizzie.leelaz = target;
      synchronizeEngineWhenReady(
          target,
          () -> synchronizationCount++,
          () -> {
            afterSync.run();
            completed.countDown();
          });
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return timeoutMillis;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class ControlledReadinessEngineManager extends EngineManager {
    private final Leelaz target;
    private final long timeoutMillis;
    private final CountDownLatch synchronizationStarted = new CountDownLatch(1);
    private final CountDownLatch allowSynchronizationToComplete = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private int failureCount;
    private int synchronizationCount;

    private ControlledReadinessEngineManager(
        List<Leelaz> engines, Leelaz target, long timeoutMillis) {
      super(engines);
      this.target = target;
      this.timeoutMillis = timeoutMillis;
    }

    @Override
    protected void switchEngineInternal(
        int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
      Lizzie.leelaz = target;
      synchronizeEngineWhenReady(
          target,
          () -> {
            synchronizationStarted.countDown();
            await(allowSynchronizationToComplete);
            synchronizationCount++;
          },
          () -> {
            afterSync.run();
            completed.countDown();
          });
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return timeoutMillis;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled board synchronization interrupted", ex);
      }
    }
  }

  private static final class ControlledReadinessLeelaz extends Leelaz {
    private final AtomicInteger loadedReadCount = new AtomicInteger();
    private final CountDownLatch firstLoadedReadEntered = new CountDownLatch(1);
    private final CountDownLatch allowFirstLoadedRead = new CountDownLatch(1);
    private final CountDownLatch secondLoadedReadEntered = new CountDownLatch(1);
    private final CountDownLatch allowSecondLoadedRead = new CountDownLatch(1);
    private final CountDownLatch thirdLoadedReadEntered = new CountDownLatch(1);
    private final CountDownLatch allowThirdLoadedRead = new CountDownLatch(1);
    private final long tuningTimeoutMillis;

    private ControlledReadinessLeelaz(long tuningTimeoutMillis) throws Exception {
      super("");
      this.tuningTimeoutMillis = tuningTimeoutMillis;
    }

    @Override
    public boolean isLoaded() {
      int read = loadedReadCount.incrementAndGet();
      if (read == 1) {
        firstLoadedReadEntered.countDown();
        await(allowFirstLoadedRead);
      } else if (read == 2) {
        secondLoadedReadEntered.countDown();
        await(allowSecondLoadedRead);
      } else if (read == 3) {
        thirdLoadedReadEntered.countDown();
        await(allowThirdLoadedRead);
      }
      return super.isLoaded();
    }

    @Override
    long engineTuningSynchronizationTimeoutMillis() {
      return tuningTimeoutMillis;
    }

    private void releaseLoadedReads() {
      allowFirstLoadedRead.countDown();
      allowSecondLoadedRead.countDown();
      allowThirdLoadedRead.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled readiness read interrupted", ex);
      }
    }
  }

  private static final class TrackingShutdownLeelaz extends Leelaz {
    private int shutdownCount;

    private TrackingShutdownLeelaz() throws Exception {
      super("");
    }

    @Override
    public void shutdown() {
      shutdownCount++;
    }

    @Override
    public void normalQuit() {
      shutdownCount++;
    }
  }

  private static final class TrackingRestartLeelaz extends Leelaz {
    private final CountDownLatch processDeadCheckEntered = new CountDownLatch(1);
    private final CountDownLatch releaseProcessDeadCheck = new CountDownLatch(1);
    private final CountDownLatch secondProcessDeadCheckEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSecondProcessDeadCheck = new CountDownLatch(1);
    private final CountDownLatch restartCompleted = new CountDownLatch(1);
    private boolean processDead;
    private boolean blockProcessDeadCheck;
    private boolean blockSecondProcessDeadCheck;
    private int processDeadCheckCount;
    private int restartCount;

    private TrackingRestartLeelaz() throws Exception {
      super("");
    }

    @Override
    public boolean isProcessDead() {
      processDeadCheckCount++;
      if (blockProcessDeadCheck) {
        processDeadCheckEntered.countDown();
        await(releaseProcessDeadCheck);
      }
      if (blockSecondProcessDeadCheck && processDeadCheckCount == 2) {
        secondProcessDeadCheckEntered.countDown();
        await(releaseSecondProcessDeadCheck);
      }
      return processDead;
    }

    @Override
    public void normalQuit() {
      // The controlled remote transport is already dead; the automatic restart start
      // must not touch real transport or UI state.
    }

    @Override
    public void startEngine(int index) {
      restartCount++;
      // Mark the engine stopped so automatic restart readiness fails fast and the attempt's
      // completion claim is released deterministically without touching a real board or streams.
      started = false;
      restartCompleted.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled restart check interrupted", ex);
      }
    }
  }
}
