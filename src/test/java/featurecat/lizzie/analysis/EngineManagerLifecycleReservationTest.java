package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class EngineManagerLifecycleReservationTest {

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
      assertTrue(executedTarget.hasExclusiveGtpWorkInProgress());
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
      awaitReservationReleased(preparedTarget);
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
  void updateEnginesDifferentSizeSkipsFrozenExactRestoreAndClearsBoard() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(13, 19);
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      state.releaseStartup();

      waitForLog(state.commandLog, "list_commands", 2000L);
      awaitReservationReleased(replacement);
      assertEquals(0, countCommands(Files.readString(state.commandLog), "loadsgf "));
      assertEquals(1, state.board.clearCount);
      assertEquals(1, state.board.rootRestoreCount);
      assertEquals(1, state.board.rootMoves.size());
      assertEquals(15, state.board.rootMoves.get(0).x);
      assertEquals(15, state.board.rootMoves.get(0).y);
      assertEquals(6.5, state.board.rootKomi);
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
  void pkStartDoesNotReserveTheMirrorCapturedByRestore() throws Exception {
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
      assertNull(engine.beginExclusiveGtpLifecycleReservation());
      Leelaz.ExclusiveGtpLifecycleReservation capturedMirrorReservation =
          capturedMirror.beginExclusiveGtpLifecycleReservation();
      assertNotNull(capturedMirrorReservation);
      capturedMirrorReservation.close();
      Leelaz.ExclusiveGtpLifecycleReservation unrelatedMirrorReservation =
          laterMirror.beginExclusiveGtpLifecycleReservation();
      assertNotNull(unrelatedMirrorReservation);
      unrelatedMirrorReservation.close();
      engine.allowRestore.countDown();
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
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
      Leelaz.ExclusiveGtpLifecycleReservation mirrorReservation =
          mirror.beginExclusiveGtpLifecycleReservation();
      assertNotNull(mirrorReservation);
      mirrorReservation.close();
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

  private static BoardData moveNode(int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
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
    BoardData snapshot = BoardData.empty(19, 19);
    snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
    BoardHistoryList history = new BoardHistoryList(snapshot);
    history.getGameInfo().setKomiNoMenu(6.5);
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
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(engine));
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
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(engine));
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
  void automaticProcessRestartLosesTheRaceWhenGmaReservesBeforeRestartDispatch()
      throws Exception {
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
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
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
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
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
  void switchReservesDistinctTargetBeforeTouchingCurrentOwner() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    List<String> reservationOrder = new java.util.ArrayList<>();
    OrderedLifecycleLeelaz current = new OrderedLifecycleLeelaz("current", reservationOrder, false);
    OrderedLifecycleLeelaz target = new OrderedLifecycleLeelaz("target", reservationOrder, true);
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
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
  void failedRecoverySwitchFenceLeavesTargetUnavailableAndReleasesReservations()
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

  private void assertForegroundActivationStartsAnalysis(boolean reopenCurrentEngine) throws Exception {
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
    engine.Pondering();
    RecoverySwitchEngineManager manager = new RecoverySwitchEngineManager(List.of(engine), engine);
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
      assertTrue(engine.resumePonderIntent);
      assertEquals(1, engine.ponderCount);
      assertTrue(engine.ponderWhileLifecycleHeld);
      assertTrue(engine.isResponseUpToDate());
      assertEquals(1, frame.reSetLocCount);
      assertEquals(1, menu.updateCount);
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
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
  void switchWaitsForPublishedNameCheckAndBoardSynchronizationBeforeCompleting()
      throws Exception {
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

  private static ControlledReadinessLeelaz unavailableControlledEngine(
      long tuningTimeoutMillis) throws Exception {
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

  private static String waitForLog(Path log, String marker, long timeoutMillis) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    String content = "";
    while (System.currentTimeMillis() < deadline) {
      content = Files.readString(log);
      if (content.contains(marker)) {
        return content;
      }
      Thread.sleep(10L);
    }
    assertTrue(content.contains(marker), "timed out waiting for engine log marker: " + marker);
    return content;
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
        int index,
        boolean isMain,
        PreparedEngineSwitch preparedSwitch,
        Runnable afterSync) {
      switchCount++;
      this.afterSync = afterSync;
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      conflictCount++;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
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
      if (emitPonderCommand) {
        cmdNumber++;
      }
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      confirmation = onSuccess;
      rejection = onFailure;
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
    public void clearKataEstimate() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void refresh() {}

    @Override
    public void setPdaAndWrn(double pda, double wrn) {}
  }

  private static final class SilentJFontMenu extends JFontMenu {
    @Override
    public void setText(String text) {}
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
    public void ponder() {}

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
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz previousMirror = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final Config previousConfig = Lizzie.config;
    private final JFontMenu previousEngineMenu = Menu.engineMenu;
    private final JFontMenu previousEngineMenu2 = Menu.engineMenu2;
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final int previousEngineNo2 = EngineManager.currentEngineNo2;
    private final int previousBoardWidth = Board.boardWidth;
    private final int previousBoardHeight = Board.boardHeight;
    private final UpdateForegroundLeelaz previousForegroundEngine;
    private final UpdateBoard board;
    private final EngineManager manager;
    private final Path commandScript;
    private final Path commandLog;
    private final Path startupGate;
    private final Path loadSgfFailure;

    private UpdateEnginesState(int targetWidth, int targetHeight) throws Exception {
      this.targetWidth = targetWidth;
      this.targetHeight = targetHeight;
      previousForegroundEngine = new UpdateForegroundLeelaz();
      previousForegroundEngine.oriEnginename = "update-target";
      previousForegroundEngine.started = true;
      previousForegroundEngine.isLoaded = true;
      commandScript = Files.createTempFile("lizzie-update-engine-", ".sh");
      commandLog = Files.createTempFile("lizzie-update-engine-", ".log");
      startupGate = Files.createTempFile("lizzie-update-engine-startup-", ".gate");
      loadSgfFailure = Files.createTempFile("lizzie-update-engine-loadsgf-", ".failure");
      Files.delete(loadSgfFailure);
      Files.delete(startupGate);
      Files.writeString(commandScript, updateEngineScript());
      assertTrue(commandScript.toFile().setExecutable(true));
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
      config.extraMode = ExtraMode.Normal;
      config.leelazConfig =
          new JSONObject()
              .put(
                  "engine-settings-list",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put(
                                  "command",
                                  commandScript.toString()
                                      + " "
                                      + commandLog
                                      + " "
                                      + startupGate
                                      + " "
                                      + loadSgfFailure)
                              .put("name", "update-target")
                              .put("preload", false)
                              .put("width", targetWidth)
                              .put("height", targetHeight)
                              .put("komi", 7.5)));
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
      Lizzie.leelaz2 = null;
      Lizzie.board = board;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = -1;
    }

    private void releaseStartup() throws Exception {
      Files.writeString(startupGate, "ready");
    }

    private void failLoadSgf() throws Exception {
      Files.writeString(loadSgfFailure, "fail");
    }

    private void restore() {
      try {
        releaseStartup();
      } catch (Exception ignored) {
      }
      if (manager.engineList != null) {
        for (Leelaz engine : manager.engineList) {
          try {
            engine.forceQuit();
          } catch (Exception ignored) {
          }
        }
      }
      try {
        Files.deleteIfExists(commandScript);
        Files.deleteIfExists(commandLog);
        Files.deleteIfExists(startupGate);
        Files.deleteIfExists(loadSgfFailure);
      } catch (Exception ignored) {
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      Menu.engineMenu = previousEngineMenu;
      Menu.engineMenu2 = previousEngineMenu2;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
    }

    private static <T> T allocateUnchecked(Class<T> type) {
      try {
        return allocate(type);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }

    private static String updateEngineScript() {
      return "#!/bin/sh\n"
          + "log=\"$1\"\n"
          + "gate=\"$2\"\n"
          + "loadsgf_failure=\"$3\"\n"
          + "while IFS= read -r line; do\n"
          + "  printf '%s\\n"
          + "' \"$line\" >> \"$log\"\n"
          + "  rest=\"$line\"\n"
          + "  id=\"\"\n"
          + "  case \"$line\" in\n"
          + "    [0-9]*\\ *) id=\"${line%% *}\"; rest=\"${line#* }\" ;;\n"
          + "  esac\n"
          + "  case \"$rest\" in\n"
          + "    loadsgf\\ *)\n"
          + "      printf 'SGF:%s\\n"
          + "' \"$(cat \"${rest#loadsgf }\")\" >> \"$log\"\n"
          + "      if [ -f \"$loadsgf_failure\" ]; then\n"
          + "        if [ -n \"$id\" ]; then printf '?%s controlled restore failure\\n"
          + "\\n"
          + "' \"$id\"; else printf '? controlled restore failure\\n"
          + "\\n"
          + "'; fi\n"
          + "        continue\n"
          + "      fi ;;\n"
          + "  esac\n"
          + "  case \"$rest\" in\n"
          + "    name) while [ ! -f \"$gate\" ]; do sleep 0.01; done ;;\n"
          + "  esac\n"
          + "  if [ -n \"$id\" ]; then printf '=%s\\n"
          + "\\n"
          + "' \"$id\"; else\n"
          + "    case \"$rest\" in\n"
          + "      name) printf '= KataGo\\n"
          + "\\n"
          + "' ;;\n"
          + "      version) printf '= 1.15\\n"
          + "\\n"
          + "' ;;\n"
          + "      list_commands) printf '= protocol_version\\n"
          + "\\n"
          + "' ;;\n"
          + "      *) printf '=\\n"
          + "\\n"
          + "' ;;\n"
          + "    esac\n"
          + "  fi\n"
          + "  [ \"$rest\" = quit ] && exit 0\n"
          + "done\n";
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
        int index,
        boolean isMain,
        PreparedEngineSwitch preparedSwitch,
        Runnable afterSync) {
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
        int index,
        boolean isMain,
        PreparedEngineSwitch preparedSwitch,
        Runnable afterSync) {
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
        int index,
        boolean isMain,
        PreparedEngineSwitch preparedSwitch,
        Runnable afterSync) {
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
    public void restartClosedEngine(int index) {
      restartCount++;
      restartCompleted.countDown();
    }

    @Override
    public void restartClosedEngine(int index, Runnable afterBoardRestore) {
      restartCount++;
      if (afterBoardRestore != null) {
        afterBoardRestore.run();
      }
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
