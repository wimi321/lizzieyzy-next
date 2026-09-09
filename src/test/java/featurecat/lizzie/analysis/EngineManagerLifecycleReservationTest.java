package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlan;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.EngineData;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  void updateEnginesClaimErrorClosesCapturedLifecycleAndPreservesCleanupFailure()
      throws Exception {
    AssertionError claimFailure = new AssertionError("controlled update claim failure");
    AssertionError cleanupFailure = new AssertionError("controlled update cleanup failure");
    UpdateClaimErrorLeelaz replacement =
        new UpdateClaimErrorLeelaz(claimFailure, cleanupFailure);
    try (UpdateFailureState state = new UpdateFailureState(replacement, null)) {
      state.install();

      AssertionError thrown =
          assertThrows(AssertionError.class, state.manager::updateEngines);

      assertSame(claimFailure, thrown);
      assertEquals(1, thrown.getSuppressed().length);
      assertSame(cleanupFailure, thrown.getSuppressed()[0]);
      assertFalse(EngineManager.isUpdating);
      assertLifecycleAvailable(state.previousForegroundEngine);
      assertLifecycleAvailable(replacement);
    }
  }

  @Test
  void updateEnginesCreationIOExceptionAlwaysClearsUpdatingState() throws Exception {
    IOException creationFailure = new IOException("controlled update creation failure");
    UpdateFailureLeelaz replacement = new UpdateFailureLeelaz();
    try (UpdateFailureState state =
        new UpdateFailureState(replacement, null, creationFailure)) {
      state.install();

      IOException thrown = assertThrows(IOException.class, state.manager::updateEngines);

      assertSame(creationFailure, thrown);
      assertEquals(1, state.manager.createAttemptCount);
      assertFalse(EngineManager.isUpdating);
      assertLifecycleAvailable(state.previousForegroundEngine);
      assertLifecycleAvailable(replacement);
    }
  }

  @Test
  void updateEnginesCreationErrorAlwaysClearsUpdatingState() throws Exception {
    AssertionError creationFailure = new AssertionError("controlled update creation error");
    UpdateFailureLeelaz replacement = new UpdateFailureLeelaz();
    try (UpdateFailureState state =
        new UpdateFailureState(replacement, null, creationFailure)) {
      state.install();

      AssertionError thrown =
          assertThrows(AssertionError.class, state.manager::updateEngines);

      assertSame(creationFailure, thrown);
      assertEquals(1, state.manager.createAttemptCount);
      assertFalse(EngineManager.isUpdating);
      assertLifecycleAvailable(state.previousForegroundEngine);
      assertLifecycleAvailable(replacement);
    }
  }

  @Test
  void updateEnginesDoesNotSuppressCleanupFailureOntoItself() throws Exception {
    AssertionError sharedFailure = new AssertionError("shared update claim and cleanup failure");
    UpdateClaimErrorLeelaz replacement =
        new UpdateClaimErrorLeelaz(sharedFailure, sharedFailure);
    try (UpdateFailureState state = new UpdateFailureState(replacement, null)) {
      state.install();

      AssertionError thrown =
          assertThrows(AssertionError.class, state.manager::updateEngines);

      assertSame(sharedFailure, thrown);
      assertEquals(0, thrown.getSuppressed().length);
      assertFalse(EngineManager.isUpdating);
      assertLifecycleAvailable(state.previousForegroundEngine);
      assertLifecycleAvailable(replacement);
    }
  }

  @Test
  void updateEnginesLeaseConflictPreservesCleanupFailureAndClearsUpdatingState()
      throws Exception {
    AssertionError cleanupFailure = new AssertionError("controlled lease cleanup failure");
    UpdateLeaseConflictLeelaz replacement = new UpdateLeaseConflictLeelaz(cleanupFailure);
    try (UpdateFailureState state = new UpdateFailureState(replacement, null)) {
      state.install();

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, state.manager::updateEngines);

      assertEquals(1, thrown.getSuppressed().length);
      assertSame(cleanupFailure, thrown.getSuppressed()[0]);
      assertFalse(EngineManager.isUpdating);
      assertLifecycleAvailable(state.previousForegroundEngine);
      assertLifecycleAvailable(replacement);
    }
  }

  @Test
  void updateEnginesReplacementThreadStartErrorRetainsCleanupOwnershipUntilStartSucceeds()
      throws Exception {
    AssertionError startFailure = new AssertionError("controlled replacement start failure");
    UpdateFailureLeelaz replacement = new UpdateFailureLeelaz();
    try (UpdateFailureState state = new UpdateFailureState(replacement, startFailure)) {
      state.install();

      AssertionError thrown =
          assertThrows(AssertionError.class, state.manager::updateEngines);

      assertSame(startFailure, thrown);
      assertEquals(0, replacement.startCount);
      assertFalse(EngineManager.isUpdating);
      assertLifecycleAvailable(state.previousForegroundEngine);
      assertLifecycleAvailable(replacement);
      Leelaz.LifecycleCompletionClaim freshClaim =
          replacement.tryBeginLifecycleCompletion(new Object(), null);
      assertNotNull(freshClaim, "the failed Thread.start must release the completion claim");
      freshClaim.abandonBeforeFence();
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
  void updateReadyObserversRunOnlyAfterLifecycleCompletionEndpointsRelease() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19);
    AtomicBoolean lifecycleAvailableAtReady = new AtomicBoolean();
    AtomicReference<Leelaz> replacementRef = new AtomicReference<>();
    Consumer<EngineStartupStatus.Snapshot> listener =
        snapshot -> {
          if (snapshot.state != EngineStartupStatus.State.READY || !snapshot.isCurrent()) {
            return;
          }
          Leelaz replacement = replacementRef.get();
          Leelaz.EngineModeReservation reservation =
              replacement == null ? null : replacement.beginEngineModeReservation();
          lifecycleAvailableAtReady.set(reservation != null);
          if (reservation != null) {
            reservation.close();
          }
        };
    try {
      state.install();
      state.manager.updateEngines();
      Leelaz replacement = state.manager.engineList.get(0);
      assertSame(replacement, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(
          EngineManager.isEmpty,
          "installing the update replacement must retire the no-engine selection atomically");
      replacementRef.set(replacement);
      Lizzie.engineStartupStatus.addListener(listener);
      state.releaseStartup();
      waitForLog(state.commandLog, "loadsgf ", 10_000L);
      awaitLifecycleTransitionReleased(replacement);
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      state.releaseBoardFence();
      awaitEngineStartupReady();
      SwingUtilities.invokeAndWait(() -> {});

      assertTrue(
          lifecycleAvailableAtReady.get(),
          "READY listeners must observe the old lifecycle completion claim already released");
    } finally {
      Lizzie.engineStartupStatus.removeListener(listener);
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
      state.releaseBoardFence();

      // The frozen round (2 loadsgf commands, one per captured engine) restores the
      // pre-navigation frame. Keep the later catch-up responses gated, but do not make
      // cross-process command dispatch depend on which engine reaches its final fence first.
      // The frame recheck then starts a catch-up round whose loadsgf responses remain gated.
      waitForCommandCount(state.commandLog, "loadsgf ", 4, 10_000L);
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertNull(replacement.beginEngineModeReservation());
      assertNull(mirror.beginEngineModeReservation());
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
  void updateEnginesSchedulingFailureKeepsPrimaryWhenLifecycleCloseAlsoFails() throws Exception {
    UpdateEnginesState state = new UpdateEnginesState(19, 19, false, false, true);
    Thread.UncaughtExceptionHandler previousHandler =
        Thread.getDefaultUncaughtExceptionHandler();
    AtomicReference<Throwable> escaped = new AtomicReference<>();
    CountDownLatch escapedFailure = new CountDownLatch(1);
    try {
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, failure) -> {
            if (failure == state.schedulerManager.schedulingFailure
                || failure == state.schedulerManager.lifecycleCloseFailure) {
              if (escaped.compareAndSet(null, failure)) {
                escapedFailure.countDown();
              }
            }
          });
      state.install();
      state.manager.updateEngines();

      assertTrue(
          escapedFailure.await(10, TimeUnit.SECONDS),
          "controlled scheduling failure should escape the replacement worker");
      Throwable failure = escaped.get();
      assertSame(state.schedulerManager.schedulingFailure, failure);
      assertTrue(
          java.util.Arrays.stream(failure.getSuppressed())
              .anyMatch(suppressed -> suppressed == state.schedulerManager.lifecycleCloseFailure),
          () ->
              "lifecycle close failure should be suppressed onto the scheduling failure, suppressed="
                  + java.util.Arrays.toString(failure.getSuppressed()));
      assertEquals(1, state.schedulerManager.lifecycleCloseCount.get());
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
      state.restore();
    }
  }

  @Test
  void updateStartAttemptClosesPublishedFailureWithoutTouchingSameObjectRebind()
      throws Exception {
    PartialPublishedStartLeelaz engine = new PartialPublishedStartLeelaz();
    Leelaz.UpdateEngineStartAttempt attempt = engine.beginUpdateEngineStartAttempt();

    AssertionError failure =
        assertThrows(AssertionError.class, () -> attempt.startEngine(0));
    Object failedIncarnation = attempt.publishedIncarnation();
    assertNotNull(failedIncarnation);

    RecordingTransport replacementTransport = new RecordingTransport(false);
    setLeelazField(engine, "remoteTransport", replacementTransport);
    engine.useRemoteCompute = true;
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    Object replacementIncarnation = engine.currentEngineIncarnation();
    engine.started = true;
    engine.isLoaded = true;

    attempt.failClose(failure);

    assertEquals(1, engine.failedTransport.closeCount.get());
    assertEquals(0, replacementTransport.closeCount.get());
    assertFalse(failedIncarnation == replacementIncarnation);
    assertSame(replacementIncarnation, engine.currentEngineIncarnation());
    assertTrue(engine.started);
    assertTrue(engine.isLoaded);
    engine.forceQuit();
  }

  @Test
  void updateStartAttemptRejectsAStartThatReturnsWithoutPublishingAReader() throws Exception {
    EmptyReturningStartLeelaz engine = new EmptyReturningStartLeelaz();
    Leelaz.UpdateEngineStartAttempt attempt = engine.beginUpdateEngineStartAttempt();

    IOException failure = assertThrows(IOException.class, () -> attempt.startEngine(0));
    assertTrue(failure.getMessage().contains("live reader incarnation"));
    attempt.failClose(failure);

    Leelaz.UpdateEngineStartAttempt retry = engine.beginUpdateEngineStartAttempt();
    retry.failClose(new AssertionError("controlled retry settlement"));
  }

  @Test
  void mirrorAttemptAcquireErrorSettlesTargetWithoutReplacingPrimaryFailure() throws Exception {
    AssertionError cleanupFailure = new AssertionError("controlled target cleanup failure");
    PrestartedAttemptAcquireLeelaz target =
        new PrestartedAttemptAcquireLeelaz(cleanupFailure);
    FailingAttemptAcquireLeelaz mirror = new FailingAttemptAcquireLeelaz();
    Leelaz.UpdateEngineStartAttempt targetAttempt = target.beginUpdateEngineStartAttempt();

    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () ->
                EngineManager.beginMirrorUpdateEngineStartAttempt(
                    targetAttempt, mirror));

    assertSame(mirror.acquisitionFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(cleanupFailure, thrown.getSuppressed()[0]);
    assertEquals(1, target.transport.closeCount.get());

    Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
    retry.failClose(new AssertionError("controlled retry settlement"));
  }

  @Test
  void pairedStartCompletionRejectsReboundMirrorWithoutSettlingEitherAttempt()
      throws Exception {
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    PrestartedAttemptAcquireLeelaz mirror = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt targetAttempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorAttempt = mirror.beginUpdateEngineStartAttempt();
    Object failedMirrorIncarnation = mirrorAttempt.publishedIncarnation();
    RecordingTransport replacementTransport = new RecordingTransport(false);
    setLeelazField(mirror, "remoteTransport", replacementTransport);
    mirror.useRemoteCompute = true;
    mirror.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    Object replacementIncarnation = mirror.currentEngineIncarnation();
    mirror.started = true;
    mirror.isLoaded = true;
    AtomicInteger finalizationCount = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                Leelaz.completeUpdateEngineStartAttempts(
                    targetAttempt, mirrorAttempt, finalizationCount::incrementAndGet));

    assertEquals(0, finalizationCount.get());
    assertFalse(failedMirrorIncarnation == replacementIncarnation);
    mirrorAttempt.failClose(failure);
    targetAttempt.failClose(failure);
    assertEquals(1, target.transport.closeCount.get());
    assertEquals(1, mirror.transport.closeCount.get());
    assertEquals(0, replacementTransport.closeCount.get());
    assertSame(replacementIncarnation, mirror.currentEngineIncarnation());
    assertTrue(mirror.started);
    assertTrue(mirror.isLoaded);

    Leelaz.UpdateEngineStartAttempt targetRetry = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorRetry = mirror.beginUpdateEngineStartAttempt();
    targetRetry.failClose(new AssertionError("controlled target retry settlement"));
    mirrorRetry.failClose(new AssertionError("controlled mirror retry settlement"));
    mirror.forceQuit();
  }

  @Test
  void pairedStartCompletionFinalizationErrorLeavesBothAttemptsFailCloseable()
      throws Exception {
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    PrestartedAttemptAcquireLeelaz mirror = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt targetAttempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorAttempt = mirror.beginUpdateEngineStartAttempt();
    AssertionError finalizationFailure =
        new AssertionError("controlled lifecycle close failure");

    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () ->
                Leelaz.completeUpdateEngineStartAttempts(
                    targetAttempt,
                    mirrorAttempt,
                    () -> {
                      throw finalizationFailure;
                    }));

    assertSame(finalizationFailure, thrown);
    assertEquals(0, target.transport.closeCount.get());
    assertEquals(0, mirror.transport.closeCount.get());
    targetAttempt.failClose(thrown);
    mirrorAttempt.failClose(thrown);
    assertEquals(1, target.transport.closeCount.get());
    assertEquals(1, mirror.transport.closeCount.get());
    Leelaz.UpdateEngineStartAttempt targetRetry = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorRetry = mirror.beginUpdateEngineStartAttempt();
    targetRetry.failClose(new AssertionError("controlled target retry settlement"));
    mirrorRetry.failClose(new AssertionError("controlled mirror retry settlement"));
  }

  @Test
  void lifecycleCompletionFailureWithThrowingMessageStillSettlesAttemptsAndReleasesEndpoints()
      throws Exception {
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    PrestartedAttemptAcquireLeelaz mirror = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt targetAttempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorAttempt = mirror.beginUpdateEngineStartAttempt();
    Leelaz.LifecycleCompletionClaim lifecycle =
        target.tryBeginLifecycleCompletion(new Object(), mirror);
    assertNotNull(lifecycle);
    ThrowingMessageError failure = new ThrowingMessageError();
    AtomicReference<String> failureDetail = new AtomicReference<>();
    CountDownLatch endpointsReleased = new CountDownLatch(1);
    lifecycle.runAfterEndpointRelease(endpointsReleased::countDown);

    lifecycle.completeSuccess(
        () -> {
          throw failure;
        },
        detail -> {
          failureDetail.set(detail);
          mirrorAttempt.failClose(failure);
          targetAttempt.failClose(failure);
        });

    assertEquals("lifecycle completion callback failed", failureDetail.get());
    assertTrue(endpointsReleased.await(2, TimeUnit.SECONDS));
    assertEquals(1, target.transport.closeCount.get());
    assertEquals(1, mirror.transport.closeCount.get());
    assertTrue(
        java.util.Arrays.stream(failure.getSuppressed())
            .anyMatch(suppressed -> suppressed == failure.messageFailure));
    Leelaz.LifecycleCompletionClaim retry =
        target.tryBeginLifecycleCompletion(new Object(), mirror);
    assertNotNull(retry);
    assertTrue(retry.abandonBeforeFence());
  }

  @Test
  void concurrentLifecycleCloseFailureRejectsClaimedGroupCompletionAndFailsClosed()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    BlockingLifecycleCloseLeelaz target = new BlockingLifecycleCloseLeelaz();
    PrestartedAttemptAcquireLeelaz mirror = new PrestartedAttemptAcquireLeelaz(null);
    SilentFailureEngineManager manager =
        new SilentFailureEngineManager(List.of(target, mirror));
    SilentSwitchFrame readyFrame = allocate(SilentSwitchFrame.class);
    EngineManager.InitialEngineStartupSynchronization synchronization =
        EngineManager.InitialEngineStartupSynchronization.capturePrepared(
            null, target, mirror, preparedRestoreBoard(), false, false);
    Leelaz.UpdateEngineStartAttempt targetAttempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorAttempt = mirror.beginUpdateEngineStartAttempt();
    Method beginCompletion =
        EngineManager.InitialEngineStartupSynchronization.class.getDeclaredMethod(
            "beginLifecycleCompletionClaim");
    beginCompletion.setAccessible(true);
    beginCompletion.invoke(synchronization);
    AtomicReference<Throwable> workerCloseFailure = new AtomicReference<>();
    AtomicReference<Throwable> completionFailure = new AtomicReference<>();
    AtomicInteger readyTransitions = new AtomicInteger();
    Consumer<EngineStartupStatus.Snapshot> readyListener =
        snapshot -> {
          if (snapshot.state == EngineStartupStatus.State.READY) {
            readyTransitions.incrementAndGet();
          }
        };
    Thread completion =
        new Thread(
            () -> {
              try {
                manager.completeUpdateEngineReplacementStart(
                    0,
                    target,
                    mirror,
                    targetAttempt,
                    mirrorAttempt,
                    synchronization);
              } catch (Throwable failure) {
                completionFailure.set(failure);
                mirrorAttempt.failClose(failure);
                targetAttempt.failClose(failure);
                manager.reportUpdateEngineStartFailure(
                    0, target, targetAttempt, mirror, mirrorAttempt, failure);
              }
            },
            "claimed-group-finalization");
    Thread workerClose =
        new Thread(
            () -> {
              try {
                synchronization.close();
              } catch (Throwable failure) {
                workerCloseFailure.set(failure);
              }
            },
            "worker-lifecycle-close");
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      setLeelazField(
          target,
          "startupPrimaryEngineGeneration",
          Lizzie.capturePrimaryEngineGeneration(target));
      targetAttempt.bindPrimaryEngineGeneration(
          Lizzie.capturePrimaryEngineGeneration(target));
      Lizzie.leelaz2 = mirror;
      Config fixtureConfig = allocate(Config.class);
      fixtureConfig.uiConfig = new JSONObject();
      fixtureConfig.leelazConfig = new JSONObject();
      Lizzie.config = fixtureConfig;
      Lizzie.frame = readyFrame;
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      LizzieFrame.toolbar = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled group finalization");
      Lizzie.engineStartupStatus.addListener(readyListener);
      workerClose.start();
      assertTrue(target.closeEntered.await(2, TimeUnit.SECONDS));
      invokeCloseBundledStartupDialog(target);
      assertEquals(
          EngineStartupStatus.State.CHECKING,
          Lizzie.engineStartupStatus.snapshot().state,
          "bundled startup must not bypass the pending lifecycle/update terminal owner");
      assertEquals(0, readyTransitions.get());
      completion.start();
      Thread.sleep(100L);
      assertTrue(completion.isAlive(), "finalization close must await the owned close outcome");
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, readyTransitions.get(), "READY must not precede lifecycle close success");
    } finally {
      target.allowClose.countDown();
      completion.join(2_000L);
      workerClose.join(2_000L);
      Lizzie.engineStartupStatus.removeListener(readyListener);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
    assertFalse(completion.isAlive());
    assertFalse(workerClose.isAlive());
    assertSame(target.closeFailure, completionFailure.get());
    assertSame(target.closeFailure, workerCloseFailure.get());
    assertSame(
        target.closeFailure,
        assertThrows(AssertionError.class, synchronization::close));
    assertEquals(1, target.transport.closeCount.get());
    assertEquals(1, mirror.transport.closeCount.get());
    assertFalse(target.started);
    assertFalse(mirror.started);
    assertEquals(
        EngineStartupStatus.State.START_FAILED,
        Lizzie.engineStartupStatus.snapshot().state);
    assertEquals(0, readyTransitions.get(), "failed finalization must never publish READY");
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(0, readyFrame.reSetLocCount, "failed finalization must not queue ready UI");
    Leelaz.LifecycleCompletionClaim retry =
        target.tryBeginLifecycleCompletion(new Object(), mirror);
    assertNotNull(retry);
    assertTrue(retry.abandonBeforeFence());
    Lizzie.engineStartupStatus.ready();
  }

  @Test
  void initializationCommandFailureCannotPublishReadyOrQueueReadyUi() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    FailingReadyCommandLeelaz engine = new FailingReadyCommandLeelaz();
    SilentSwitchFrame frame = allocate(SilentSwitchFrame.class);
    Config config = allocate(Config.class);
    AtomicInteger readyTransitions = new AtomicInteger();
    Consumer<EngineStartupStatus.Snapshot> listener =
        snapshot -> {
          if (snapshot.state == EngineStartupStatus.State.READY) {
            readyTransitions.incrementAndGet();
          }
        };
    try {
      Lizzie.config = config;
      Lizzie.frame = frame;
      LizzieFrame.menu = null;
      LizzieFrame.toolbar = null;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled ready command");
      Lizzie.engineStartupStatus.addListener(listener);

      AssertionError thrown =
          assertThrows(
              AssertionError.class,
              () -> Lizzie.initializeAfterVersionCheck(false, engine, false));

      assertSame(engine.commandFailure, thrown);
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, readyTransitions.get());
      assertEquals(0, frame.reSetLocCount, "failed prepare must not enqueue ready UI");
    } finally {
      Lizzie.engineStartupStatus.removeListener(listener);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void ordinaryInitializationCannotCommitReadyAfterPrimaryGenerationChanges() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    QuietExitLeelaz replacement = new QuietExitLeelaz();
    SwitchingPrimaryDuringInitializationLeelaz engine =
        new SwitchingPrimaryDuringInitializationLeelaz(replacement);
    SilentSwitchFrame frame = allocate(SilentSwitchFrame.class);
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = frame;
      LizzieFrame.menu = null;
      LizzieFrame.toolbar = null;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.engineStartupStatus.checking("engine.starting", "replacement selection");

      Lizzie.initializeAfterVersionCheck(false, engine, false);
      SwingUtilities.invokeAndWait(() -> {});

      assertSame(replacement, Lizzie.leelaz);
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, frame.reSetLocCount, "stale primary must not publish ready UI");
    } finally {
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void profilePersistenceRetryFailureSettlesExactStartWithoutOverwritingFailureStatus()
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    boolean previousSaveFailed = forceStartupProfileSaveFailed(true);
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    FailingSaveConfig config = allocate(FailingSaveConfig.class);
    try {
      Lizzie.config = config;
      Lizzie.frame = null;
      LizzieFrame.menu = null;
      LizzieFrame.toolbar = null;
      Lizzie.setPrimaryEngine(target);
      Lizzie.engineStartupStatus.failed(
          "EngineStartup.profileSaveFailed",
          "Settings could not be saved - click to repair",
          "controlled profile persistence failure");
      EngineStartupStatus.Snapshot profileFailure = Lizzie.engineStartupStatus.snapshot();

      Lizzie.PreparedEngineReadyPublication publication =
          Lizzie.prepareInitializeAfterVersionCheck(
              false, target, false, Lizzie.capturePrimaryEngineGeneration(target));
      assertNotNull(publication);
      assertFalse(publication.readyPublicationEnabled());
      try (Leelaz.UpdateEngineStartCompletion completion =
          Leelaz.claimUpdateEngineStartCompletion(attempt, null)) {
        assertTrue(
            Lizzie.runIfPrimaryEngine(
                target,
                publication.primaryGeneration(),
                () -> completion.complete(publication::prepareReadyStatus)));
      }

      assertSame(profileFailure, Lizzie.engineStartupStatus.snapshot());
      assertEquals(0, target.transport.closeCount.get());
      Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled retry settlement"));
      assertEquals(0, target.transport.closeCount.get());
    } finally {
      forceStartupProfileSaveFailed(previousSaveFailed);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      target.forceQuit();
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void startCompletionGateDefersSameObjectRebindUntilFinalizationReturns() throws Exception {
    PrestartedAttemptAcquireLeelaz engine = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt attempt = engine.beginUpdateEngineStartAttempt();
    Object startedIncarnation = attempt.publishedIncarnation();
    CountDownLatch rebindStarted = new CountDownLatch(1);
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    Thread rebind =
        new Thread(
            () -> {
              rebindStarted.countDown();
              try {
                engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
              } catch (Throwable failure) {
                rebindFailure.set(failure);
              } finally {
                rebindCompleted.countDown();
              }
            },
            "update-completion-gated-rebind");
    try {
      Leelaz.completeUpdateEngineStartAttempts(
          attempt,
          null,
          () -> {
            rebind.start();
            try {
              assertTrue(rebindStarted.await(2, TimeUnit.SECONDS));
              assertFalse(
                  rebindCompleted.await(250, TimeUnit.MILLISECONDS),
                  "same-object rebind must wait without holding up finalization");
              assertSame(startedIncarnation, engine.currentEngineIncarnation());
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
          });

      assertTrue(rebindCompleted.await(2, TimeUnit.SECONDS));
      assertNull(rebindFailure.get());
      assertFalse(startedIncarnation == engine.currentEngineIncarnation());
    } finally {
      rebind.join(2_000L);
      engine.forceQuit();
    }
  }

  @Test
  void claimedStartCompletionWinsConcurrentFailureAndKeepsRebindGated() throws Exception {
    PrestartedAttemptAcquireLeelaz engine = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt attempt = engine.beginUpdateEngineStartAttempt();
    CountDownLatch finalizationEntered = new CountDownLatch(1);
    CountDownLatch allowFinalization = new CountDownLatch(1);
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    AtomicReference<Throwable> completionFailure = new AtomicReference<>();
    Thread completion =
        new Thread(
            () -> {
              try {
                Leelaz.completeUpdateEngineStartAttempts(
                    attempt,
                    null,
                    () -> {
                      finalizationEntered.countDown();
                      try {
                        if (!allowFinalization.await(5, TimeUnit.SECONDS)) {
                          throw new AssertionError("timed out waiting to release finalization");
                        }
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                      }
                    });
              } catch (Throwable failure) {
                completionFailure.set(failure);
              }
            },
            "claimed-update-start-completion");
    Thread rebind =
        new Thread(
            () -> {
              engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
              rebindCompleted.countDown();
            },
            "failure-racing-update-rebind");
    try {
      completion.start();
      assertTrue(finalizationEntered.await(2, TimeUnit.SECONDS));
      assertNull(
          attempt.claimFailClose(new AssertionError("late losing failure")),
          "the exact completion claim is the settlement winner");
      rebind.start();
      assertFalse(
          rebindCompleted.await(250, TimeUnit.MILLISECONDS),
          "a losing failure must not tear down the completion rebind gate");
      allowFinalization.countDown();
      completion.join(2_000L);
      assertFalse(completion.isAlive());
      assertNull(completionFailure.get());
      assertTrue(rebindCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(0, engine.transport.closeCount.get());
    } finally {
      allowFinalization.countDown();
      completion.join(2_000L);
      rebind.join(2_000L);
      engine.forceQuit();
    }
  }

  @Test
  void blockedFailedStartTransportCleanupDoesNotHoldLifecycleOrOtherIdentity()
      throws Exception {
    PrestartedAttemptAcquireLeelaz target =
        new PrestartedAttemptAcquireLeelaz(true, null);
    QuietExitLeelaz other = new QuietExitLeelaz();
    EngineManager manager = new EngineManager(List.of(target, other));
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    CountDownLatch synchronizationReleased = new CountDownLatch(1);
    target.isDownWithError = true;
    try {
      manager.synchronizeUpdateEnginesWhenReady(
          0,
          target,
          null,
          attempt,
          null,
          () -> {
            throw new AssertionError("unready engine must not synchronize");
          },
          synchronizationReleased::countDown);

      assertTrue(target.transport.closeEntered.await(2, TimeUnit.SECONDS));
      assertTrue(
          synchronizationReleased.await(2, TimeUnit.SECONDS),
          "lifecycle release must not wait for a blocked remote close");
      Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled retry settlement"));
      Leelaz.EngineModeReservation otherReservation = other.beginEngineModeReservation();
      assertNotNull(otherReservation);
      otherReservation.close();
    } finally {
      target.transport.allowClose.countDown();
    }
  }

  @Test
  void cleanupSchedulerFailuresFallbackOnlyAfterLifecycleRelease() throws Exception {
    PrestartedAttemptAcquireLeelaz target =
        new PrestartedAttemptAcquireLeelaz(true, null);
    FailingUpdateCleanupSchedulingEngineManager manager =
        new FailingUpdateCleanupSchedulingEngineManager(List.of(target));
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    CountDownLatch lifecycleReleased = new CountDownLatch(1);
    AtomicReference<Throwable> escaped = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                manager.synchronizeUpdateEnginesWhenReady(
                    0,
                    target,
                    null,
                    attempt,
                    null,
                    () -> {},
                    lifecycleReleased::countDown);
              } catch (Throwable failure) {
                escaped.set(failure);
              }
            },
            "update-synchronization-scheduling-failure");
    try {
      caller.start();
      assertTrue(lifecycleReleased.await(2, TimeUnit.SECONDS));
      assertTrue(target.transport.closeEntered.await(2, TimeUnit.SECONDS));
      assertTrue(caller.isAlive(), "synchronous last-resort cleanup is intentionally blocked");
      Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled retry settlement"));
    } finally {
      target.transport.allowClose.countDown();
      caller.join(2_000L);
    }
    assertFalse(caller.isAlive());
    assertSame(manager.synchronizationSchedulingFailure, escaped.get());
    assertTrue(
        java.util.Arrays.stream(escaped.get().getSuppressed())
            .anyMatch(failure -> failure == manager.cleanupThreadFailure));
    assertTrue(
        java.util.Arrays.stream(escaped.get().getSuppressed())
            .anyMatch(failure -> failure == manager.cleanupFallbackFailure));
  }

  @Test
  void updateFailureStatusListenerRunsOutsideSelectionAndEngineLocks() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    SilentFailureEngineManager manager = new SilentFailureEngineManager(List.of(target));
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    Object failedIncarnation = attempt.publishedIncarnation();
    ThrowingMessageError failure = new ThrowingMessageError();
    Leelaz.UpdateEngineStartFailureCleanup cleanup = null;
    AtomicReference<Boolean> engineLockAvailable = new AtomicReference<>();
    Consumer<EngineStartupStatus.Snapshot> listener =
        status -> {
          if (status.state != EngineStartupStatus.State.START_FAILED) {
            return;
          }
          CountDownLatch probeCompleted = new CountDownLatch(1);
          Thread probe =
              new Thread(
                  () -> {
                    engineLockAvailable.set(
                        target.runIfEngineIncarnationFenceUnchanged(
                            failedIncarnation, () -> {}));
                    probeCompleted.countDown();
                  },
                  "failed-status-engine-lock-probe");
          probe.start();
          try {
            if (!probeCompleted.await(2, TimeUnit.SECONDS)) {
              engineLockAvailable.set(false);
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            engineLockAvailable.set(false);
          }
        };
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      attempt.bindPrimaryEngineGeneration(Lizzie.capturePrimaryEngineGeneration(target));
      cleanup = attempt.claimFailClose(failure);
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled update");
      Lizzie.engineStartupStatus.addListener(listener);

      manager.reportUpdateEngineStartFailure(
          0, target, attempt, null, null, failure);
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(Boolean.TRUE, engineLockAvailable.get());
      assertEquals(1, manager.failureCount.get());
      assertTrue(
          java.util.Arrays.stream(failure.getSuppressed())
              .anyMatch(suppressed -> suppressed == failure.messageFailure));
    } finally {
      Lizzie.engineStartupStatus.removeListener(listener);
      if (cleanup != null) {
        cleanup.finish();
      }
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void staleUpdateFailureCannotOverwriteReboundRuntimeStatus() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    SilentFailureEngineManager manager = new SilentFailureEngineManager(List.of(target));
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    AssertionError failure = new AssertionError("controlled stale update failure");
    Leelaz.UpdateEngineStartFailureCleanup cleanup = null;
    RecordingTransport replacementTransport = new RecordingTransport(false);
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      attempt.bindPrimaryEngineGeneration(Lizzie.capturePrimaryEngineGeneration(target));
      cleanup = attempt.claimFailClose(failure);
      setLeelazField(target, "remoteTransport", replacementTransport);
      target.useRemoteCompute = true;
      target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      target.started = true;
      target.isLoaded = true;
      Lizzie.engineStartupStatus.checking("engine.starting", "replacement runtime");

      manager.reportUpdateEngineStartFailure(
          0, target, attempt, null, null, failure);

      assertEquals(
          EngineStartupStatus.State.CHECKING,
          Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(0, manager.failureCount.get());
      assertTrue(target.started);
      assertTrue(target.isLoaded);
    } finally {
      if (cleanup != null) {
        cleanup.finish();
      }
      target.forceQuit();
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void updateFailureCommitSerializesWithPrimaryGenerationChange() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    QuietExitLeelaz interveningPrimary = new QuietExitLeelaz();
    SilentFailureEngineManager manager = new SilentFailureEngineManager(List.of(target));
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    AssertionError failure = new AssertionError("controlled serialized update failure");
    Object incarnation = target.currentEngineIncarnation();
    CountDownLatch endpointHeld = new CountDownLatch(1);
    CountDownLatch releaseEndpoint = new CountDownLatch(1);
    CountDownLatch eventQueueBlocked = new CountDownLatch(1);
    CountDownLatch releaseEventQueue = new CountDownLatch(1);
    AtomicReference<Throwable> reportFailure = new AtomicReference<>();
    Thread endpointBlocker =
        new Thread(
            () ->
                target.runIfEngineIncarnationFenceUnchanged(
                    incarnation,
                    () -> {
                      endpointHeld.countDown();
                      awaitLatch(releaseEndpoint);
                    }),
            "update-failure-endpoint-blocker");
    Thread reporter =
        new Thread(
            () -> {
              try {
                manager.reportUpdateEngineStartFailure(
                    0, target, attempt, null, null, failure);
              } catch (Throwable escaped) {
                reportFailure.set(escaped);
              }
            },
            "update-failure-reporter");
    Thread primaryReplacement =
        new Thread(
            () -> {
              // Exercise an away/back transition: object identity alone is insufficient to fence
              // the old failure once this generation owns a new startup transaction.
              Lizzie.setPrimaryEngine(interveningPrimary);
              Lizzie.setPrimaryEngine(target);
              Lizzie.engineStartupStatus.checking("engine.starting", "replacement generation");
            },
            "update-failure-primary-replacement");
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      attempt.bindPrimaryEngineGeneration(Lizzie.capturePrimaryEngineGeneration(target));
      Lizzie.engineStartupStatus.checking("engine.starting", "failed generation");
      SwingUtilities.invokeLater(
          () -> {
            eventQueueBlocked.countDown();
            awaitLatch(releaseEventQueue);
          });
      assertTrue(eventQueueBlocked.await(2, TimeUnit.SECONDS));
      endpointBlocker.start();
      assertTrue(endpointHeld.await(2, TimeUnit.SECONDS));
      reporter.start();
      assertTrue(awaitThreadState(reporter, Thread.State.BLOCKED, 2_000L));
      primaryReplacement.start();
      assertTrue(
          awaitThreadState(primaryReplacement, Thread.State.BLOCKED, 2_000L),
          "the exact failure commit must retain the PRIMARY fence while awaiting the endpoint");

      releaseEndpoint.countDown();
      reporter.join(2_000L);
      primaryReplacement.join(2_000L);
      assertFalse(reporter.isAlive());
      assertFalse(primaryReplacement.isAlive());
      assertNull(reportFailure.get());
      assertSame(target, Lizzie.leelaz);
      assertEquals(
          EngineStartupStatus.State.CHECKING,
          Lizzie.engineStartupStatus.snapshot().state,
          "the replacement generation's status must linearize after the old exact failure");

      releaseEventQueue.countDown();
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(0, manager.failureCount.get());
    } finally {
      releaseEndpoint.countDown();
      releaseEventQueue.countDown();
      endpointBlocker.join(2_000L);
      reporter.join(2_000L);
      primaryReplacement.join(2_000L);
      attempt.failClose(failure);
      target.forceQuit();
      interveningPrimary.forceQuit();
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void staleUpdateAcquisitionFailureCannotCrossPrimaryAwayBackGeneration() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    QuietExitLeelaz target = new QuietExitLeelaz();
    QuietExitLeelaz interveningPrimary = new QuietExitLeelaz();
    SilentFailureEngineManager manager = new SilentFailureEngineManager(List.of(target));
    AssertionError failure = new AssertionError("controlled stale acquisition failure");
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      Object expectedIncarnation = target.captureEngineIncarnationFence();
      long staleGeneration = Lizzie.capturePrimaryEngineGeneration(target);

      Lizzie.setPrimaryEngine(interveningPrimary);
      Lizzie.setPrimaryEngine(target);
      Lizzie.engineStartupStatus.checking("engine.starting", "replacement generation");
      EngineStartupStatus.Snapshot replacementStatus = Lizzie.engineStartupStatus.snapshot();

      manager.reportUpdateEngineStartAcquisitionFailure(
          0, target, expectedIncarnation, staleGeneration, failure);
      SwingUtilities.invokeAndWait(() -> {});

      assertSame(replacementStatus, Lizzie.engineStartupStatus.snapshot());
      assertEquals(0, manager.failureCount.get());
    } finally {
      target.forceQuit();
      interveningPrimary.forceQuit();
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void deferredEngineGamePrimaryPublicationRejectsAwayBackGeneration() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    QuietExitLeelaz current = new QuietExitLeelaz();
    QuietExitLeelaz candidate = new QuietExitLeelaz();
    QuietExitLeelaz intervening = new QuietExitLeelaz();
    EngineManager manager = new EngineManager(List.of(current, candidate));
    EngineGamePlan plan = EngineGamePlans.harness(0, 1, false);
    CountDownLatch publicationReady = new CountDownLatch(1);
    CountDownLatch allowPublication = new CountDownLatch(1);
    AtomicReference<Boolean> published = new AtomicReference<>();
    Thread publisher = null;
    try {
      Lizzie.engineManager = manager;
      Lizzie.board = preparedRestoreBoard();
      current.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      candidate.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      current.started = true;
      current.isLoaded = true;
      candidate.started = true;
      candidate.isLoaded = true;
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.setPrimaryEngine(current);
      EngineManager.EngineGameOwnerTransaction transaction =
          EngineManager.beginEngineGameTransaction(manager, plan, null, true);
      assertNotNull(transaction);
      assertTrue(EngineManager.transitionEngineGameToDispatched(transaction));
      assertTrue(
          EngineManager.activateEngineGameTransaction(
              transaction,
              current,
              0,
              current.currentEngineIncarnation(),
              candidate.currentEngineIncarnation()));
      long expectedGeneration = Lizzie.capturePrimaryEngineGeneration(current);
      EngineManager.DeferredEngineGamePrimaryPublication publication =
          EngineManager.prepareEngineGamePrimaryPublication(
              manager,
              plan,
              1,
              candidate,
              current,
              expectedGeneration,
              candidate.currentEngineIncarnation(),
              false,
              Lizzie.board,
              Lizzie.board.getContextRevision(),
              Lizzie.board.getHistory().isBlacksTurn());
      assertNotNull(publication);
      publisher =
          new Thread(
              () -> {
                publicationReady.countDown();
                awaitLatch(allowPublication);
                published.set(publication.publish());
              },
              "deferred-engine-game-primary-publication");
      publisher.start();
      assertTrue(publicationReady.await(2, TimeUnit.SECONDS));

      Lizzie.setPrimaryEngine(intervening);
      Lizzie.setPrimaryEngine(current);
      allowPublication.countDown();
      publisher.join(2_000L);

      assertFalse(publisher.isAlive());
      assertEquals(Boolean.FALSE, published.get());
      assertSame(current, Lizzie.leelaz);
    } finally {
      allowPublication.countDown();
      if (publisher != null) {
        publisher.join(2_000L);
      }
      current.forceQuit();
      candidate.forceQuit();
      intervening.forceQuit();
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      Lizzie.board = previousBoard;
      EngineManager.resetEngineGameTransactionStateForTest();
    }
  }

  @Test
  void updateFailureMarksOnlyExactMirrorButStillFailsCurrentTarget() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingLifecycleFailureLeelaz target = new RecordingLifecycleFailureLeelaz();
    RecordingLifecycleFailureLeelaz mirror = new RecordingLifecycleFailureLeelaz();
    SilentFailureEngineManager manager = new SilentFailureEngineManager(List.of(target, mirror));
    Leelaz.UpdateEngineStartAttempt targetAttempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartAttempt mirrorAttempt = mirror.beginUpdateEngineStartAttempt();
    AssertionError failure = new AssertionError("controlled paired update failure");
    RecordingTransport replacementTransport = new RecordingTransport(false);
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      Lizzie.leelaz2 = mirror;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      targetAttempt.bindPrimaryEngineGeneration(
          Lizzie.capturePrimaryEngineGeneration(target));
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled paired update");

      setLeelazField(mirror, "remoteTransport", replacementTransport);
      mirror.useRemoteCompute = true;
      mirror.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      Object replacementIncarnation = mirror.currentEngineIncarnation();
      mirror.started = true;
      mirror.isLoaded = true;

      manager.reportUpdateEngineStartFailure(
          0, target, targetAttempt, mirror, mirrorAttempt, failure);
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(EngineStartupStatus.State.START_FAILED, Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(1, target.lifecycleFailureCount.get());
      assertEquals(
          0,
          mirror.lifecycleFailureCount.get(),
          "a rebound mirror must not receive the old paired attempt's failure bookkeeping");
      assertSame(replacementIncarnation, mirror.currentEngineIncarnation());
      assertTrue(mirror.started);
      assertTrue(mirror.isLoaded);
    } finally {
      targetAttempt.failClose(failure);
      mirrorAttempt.failClose(failure);
      target.forceQuit();
      mirror.forceQuit();
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void queuedUpdateFailureDialogIsDroppedAfterStatusRecovers() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    SilentFailureEngineManager manager = new SilentFailureEngineManager(List.of(target));
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    AssertionError failure = new AssertionError("controlled deferred update failure");
    CountDownLatch eventQueueBlocked = new CountDownLatch(1);
    CountDownLatch releaseEventQueue = new CountDownLatch(1);
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(target);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      attempt.bindPrimaryEngineGeneration(Lizzie.capturePrimaryEngineGeneration(target));
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled update");
      SwingUtilities.invokeLater(
          () -> {
            eventQueueBlocked.countDown();
            awaitLatch(releaseEventQueue);
          });
      assertTrue(eventQueueBlocked.await(2, TimeUnit.SECONDS));

      manager.reportUpdateEngineStartFailure(0, target, attempt, null, null, failure);
      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state);
      Lizzie.engineStartupStatus.ready();
      releaseEventQueue.countDown();
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(0, manager.failureCount.get());
    } finally {
      releaseEventQueue.countDown();
      attempt.failClose(failure);
      target.forceQuit();
      Lizzie.engineManager = previousManager;
      Lizzie.leelaz = previousPrimary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void updateStartAttemptDoesNotHoldEngineLockWhilePublishingStartedIcon() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    Object selectionLock = engineSelectionStateLock();
    IconPublishingStartLeelaz engine = new IconPublishingStartLeelaz();
    EngineManager manager = new EngineManager(List.of(engine));
    Leelaz.UpdateEngineStartAttempt attempt = engine.beginUpdateEngineStartAttempt();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread start =
        new Thread(
            () -> {
              try {
                attempt.startEngine(0);
              } catch (Throwable startFailure) {
                failure.set(startFailure);
              }
            },
            "update-start-lock-order");
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      synchronized (selectionLock) {
        start.start();
        assertTrue(engine.iconPublicationEntered.await(2, TimeUnit.SECONDS));
        Object incarnation = engine.currentEngineIncarnation();
        AtomicBoolean probeSucceeded = new AtomicBoolean();
        CountDownLatch probeCompleted = new CountDownLatch(1);
        Thread engineLockProbe =
            new Thread(
                () -> {
                  probeSucceeded.set(
                      engine.runIfCurrentEngineIncarnation(incarnation, () -> {}));
                  probeCompleted.countDown();
                },
                "selection-to-engine-lock-probe");
        engineLockProbe.start();
        assertTrue(
            probeCompleted.await(2, TimeUnit.SECONDS),
            "start must not hold the engine lock while waiting for selection state");
        assertTrue(probeSucceeded.get());
      }
      start.join(2_000L);
      assertFalse(start.isAlive(), "start must finish after the selection lock is released");
      assertNull(failure.get());
      attempt.complete();
      engine.forceQuit();
    } finally {
      start.join(2_000L);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void terminalReadyUsesPrimaryThenEndpointLockOrderWithoutStaleOverwrite() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    QuietExitLeelaz replacement = new QuietExitLeelaz();
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartCompletion completion =
        Leelaz.claimUpdateEngineStartCompletion(attempt, null);
    Object incarnation = attempt.publishedIncarnation();
    long generation;
    CountDownLatch endpointHeld = new CountDownLatch(1);
    CountDownLatch releaseEndpoint = new CountDownLatch(1);
    AtomicReference<Throwable> settlementFailure = new AtomicReference<>();
    Thread endpointBlocker =
        new Thread(
            () ->
                target.runIfCurrentEngineIncarnation(
                    incarnation,
                    () -> {
                      endpointHeld.countDown();
                      try {
                        if (!releaseEndpoint.await(5, TimeUnit.SECONDS)) {
                          throw new AssertionError("timed out waiting to release endpoint lock");
                        }
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                      }
                    }),
            "terminal-ready-endpoint-blocker");
    try {
      Lizzie.setPrimaryEngine(target);
      generation = Lizzie.capturePrimaryEngineGeneration(target);
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled terminal lock order");
      endpointBlocker.start();
      assertTrue(endpointHeld.await(2, TimeUnit.SECONDS));
      long expectedGeneration = generation;
      Thread settlement =
          new Thread(
              () -> {
                try {
                  if (!Lizzie.runIfPrimaryEngine(
                      target,
                      expectedGeneration,
                      () -> completion.complete(Lizzie.engineStartupStatus::prepareReady))) {
                    throw new AssertionError("terminal primary fence unexpectedly stale");
                  }
                } catch (Throwable failure) {
                  settlementFailure.set(failure);
                }
              },
              "terminal-ready-settlement");
      Thread switchPrimary =
          new Thread(
              () -> {
                Lizzie.setPrimaryEngine(replacement);
                Lizzie.engineStartupStatus.checking("engine.starting", "replacement primary");
              },
              "terminal-ready-primary-switch");
      settlement.start();
      assertTrue(awaitThreadState(settlement, Thread.State.BLOCKED, 2_000L));
      switchPrimary.start();
      Thread.sleep(100L);
      assertTrue(switchPrimary.isAlive(), "primary switch must wait behind terminal settlement");
      releaseEndpoint.countDown();
      settlement.join(2_000L);
      switchPrimary.join(2_000L);

      assertFalse(settlement.isAlive());
      assertFalse(switchPrimary.isAlive());
      assertNull(settlementFailure.get());
      assertSame(replacement, Lizzie.leelaz);
      assertEquals(EngineStartupStatus.State.CHECKING, Lizzie.engineStartupStatus.snapshot().state);
      Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled retry settlement"));
    } finally {
      releaseEndpoint.countDown();
      endpointBlocker.join(2_000L);
      completion.close();
      target.forceQuit();
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void bundledReadyCannotSupersedeDeferredTerminalReadyNotification() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    Config previousConfig = Lizzie.config;
    PrestartedAttemptAcquireLeelaz target = new PrestartedAttemptAcquireLeelaz(null);
    Leelaz.UpdateEngineStartAttempt attempt = target.beginUpdateEngineStartAttempt();
    Leelaz.UpdateEngineStartCompletion completion =
        Leelaz.claimUpdateEngineStartCompletion(attempt, null);
    SilentSwitchFrame frame = allocate(SilentSwitchFrame.class);
    Object incarnation = attempt.publishedIncarnation();
    CountDownLatch endpointHeld = new CountDownLatch(1);
    CountDownLatch releaseEndpoint = new CountDownLatch(1);
    CountDownLatch terminalCommitted = new CountDownLatch(1);
    CountDownLatch allowTerminalPublication = new CountDownLatch(1);
    AtomicReference<Throwable> settlementFailure = new AtomicReference<>();
    AtomicReference<Throwable> bundledFailure = new AtomicReference<>();
    AtomicReference<EngineStartupStatus.PreparedNotification> readyNotification =
        new AtomicReference<>();
    AtomicInteger readyTransitions = new AtomicInteger();
    Consumer<EngineStartupStatus.Snapshot> listener =
        snapshot -> {
          if (snapshot.state == EngineStartupStatus.State.READY) {
            readyTransitions.incrementAndGet();
          }
        };
    Thread endpointBlocker =
        new Thread(
            () ->
                target.runIfCurrentEngineIncarnation(
                    incarnation,
                    () -> {
                      endpointHeld.countDown();
                      awaitLatch(releaseEndpoint);
                    }),
            "bundled-ready-endpoint-blocker");
    Thread settlement = null;
    Thread bundled = null;
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = frame;
      LizzieFrame.menu = null;
      LizzieFrame.toolbar = null;
      Lizzie.setPrimaryEngine(target);
      long generation = Lizzie.capturePrimaryEngineGeneration(target);
      setLeelazField(target, "startupPrimaryEngineGeneration", generation);
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled terminal publication");
      Lizzie.PreparedEngineReadyPublication publication =
          Lizzie.prepareInitializeAfterVersionCheck(false, target, false, generation);
      assertNotNull(publication);
      long checkingRevision = Lizzie.engineStartupStatus.snapshot().revision;
      Lizzie.engineStartupStatus.addListener(listener);

      endpointBlocker.start();
      assertTrue(endpointHeld.await(2, TimeUnit.SECONDS));
      settlement =
          new Thread(
              () -> {
                try {
                  assertTrue(
                      Lizzie.runIfPrimaryEngine(
                          target,
                          generation,
                          () ->
                              readyNotification.set(
                                  completion.complete(publication::prepareReadyStatus))));
                  terminalCommitted.countDown();
                  assertTrue(allowTerminalPublication.await(5, TimeUnit.SECONDS));
                  EngineStartupStatus.PreparedNotification notification = readyNotification.get();
                  if (notification != null && notification.isCurrent()) {
                    notification.run();
                    publication.runPresentation();
                  }
                } catch (Throwable failure) {
                  settlementFailure.set(failure);
                }
              },
              "deferred-terminal-ready-publication");
      settlement.start();
      assertTrue(awaitThreadState(settlement, Thread.State.BLOCKED, 2_000L));
      bundled =
          new Thread(
              () -> {
                try {
                  invokeCloseBundledStartupDialog(target);
                } catch (Throwable failure) {
                  bundledFailure.set(failure);
                }
              },
              "queued-bundled-ready-publication");
      bundled.start();
      assertTrue(awaitThreadState(bundled, Thread.State.BLOCKED, 2_000L));
      releaseEndpoint.countDown();
      assertTrue(terminalCommitted.await(2, TimeUnit.SECONDS));
      bundled.join(2_000L);

      assertFalse(bundled.isAlive());
      assertNull(bundledFailure.get());
      assertSame(readyNotification.get().snapshot(), Lizzie.engineStartupStatus.snapshot());
      assertEquals(checkingRevision + 1L, Lizzie.engineStartupStatus.snapshot().revision);
      allowTerminalPublication.countDown();
      settlement.join(2_000L);
      assertFalse(settlement.isAlive());
      assertNull(settlementFailure.get());
      assertEquals(1, readyTransitions.get());
      assertEquals(1, frame.reSetLocCount);
    } finally {
      releaseEndpoint.countDown();
      allowTerminalPublication.countDown();
      endpointBlocker.join(2_000L);
      if (settlement != null) settlement.join(2_000L);
      if (bundled != null) bundled.join(2_000L);
      Lizzie.engineStartupStatus.removeListener(listener);
      completion.close();
      target.forceQuit();
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.config = previousConfig;
      Lizzie.engineStartupStatus.ready();
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
    for (boolean retiredLocal : List.of(false, true)) {
      for (boolean replacementLocal : List.of(false, true)) {
        assertStaleBindingExitDoesNotAffectReplacement(true, retiredLocal, replacementLocal);
      }
    }
  }

  @Test
  void staleBindingForceQuitDoesNotWriteOrOverwriteReplacementState() throws Exception {
    for (boolean retiredLocal : List.of(false, true)) {
      for (boolean replacementLocal : List.of(false, true)) {
        assertStaleBindingExitDoesNotAffectReplacement(false, retiredLocal, replacementLocal);
      }
    }
  }

  @Test
  void stoppedIconMutationSerializesWithSameObjectReaderRebind() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    QuietExitLeelaz engine = new QuietExitLeelaz();
    Object stoppedIncarnation = engine.currentEngineIncarnation();
    EngineManager manager = new EngineManager(List.of(engine));
    BlockingStoppedIconMenu menu = allocate(BlockingStoppedIconMenu.class);
    menu.iconMutationEntered = new CountDownLatch(1);
    menu.allowIconMutation = new CountDownLatch(1);
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    CountDownLatch rebindStarted = new CountDownLatch(1);
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    Thread rebind =
        new Thread(
            () -> {
              rebindStarted.countDown();
              try {
                rebindReader(engine);
              } catch (Throwable failure) {
                rebindFailure.set(failure);
              } finally {
                rebindCompleted.countDown();
              }
            },
            "same-object-icon-rebind");
    rebind.setDaemon(true);
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = menu;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;

      EngineManager.publishStoppedEngineIconIfCurrent(engine, stoppedIncarnation);
      assertTrue(menu.iconMutationEntered.await(2, TimeUnit.SECONDS));
      assertTrue(menu.mutationOnEdt);

      rebind.start();
      assertTrue(rebindStarted.await(2, TimeUnit.SECONDS));
      assertFalse(
          rebindCompleted.await(250, TimeUnit.MILLISECONDS),
          "same-object rebind must not cross the checked stopped-icon mutation");
      assertSame(stoppedIncarnation, engine.currentEngineIncarnation());

      menu.allowIconMutation.countDown();
      assertTrue(rebindCompleted.await(2, TimeUnit.SECONDS));
      assertNull(rebindFailure.get());
      assertFalse(stoppedIncarnation == engine.currentEngineIncarnation());
      SwingUtilities.invokeAndWait(() -> menu.changeEngineIcon(0, 1));
      assertEquals(1, menu.lastPrimaryMode, "the rebound runtime's icon must remain newest");
    } finally {
      menu.allowIconMutation.countDown();
      rebind.join(2_000L);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      LizzieFrame.menu = previousMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void enginePresentationLeasesIncarnationWithoutNestingSelectionState() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    PresentationLockOrderLeelaz engine =
        new PresentationLockOrderLeelaz(engineSelectionStateLock());
    EngineManager manager = new EngineManager(List.of(engine));
    SilentUpdateMenu menu = allocate(SilentUpdateMenu.class);
    try {
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = menu;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.started = true;
      engine.isLoaded = true;
      Object incarnation = engine.currentEngineIncarnation();

      EngineManager.publishStoppedEngineIconIfCurrent(engine, incarnation);
      EngineManager.publishStartedEngineIconIfCurrent(engine, incarnation);
      EngineManager.publishReadyEngineIconIfCurrent(engine, incarnation);
      manager.publishReplacementEngineMenuStateIfCurrent(
          0, engine, incarnation, "Current engine", 2);
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(2, engine.runtimeUiLeaseChecks.get());
      assertEquals(2, engine.presentationLeaseChecks.get());
      assertFalse(
          engine.selectionLockHeldDuringLeaseClaim.get(),
          "engine presentation must not nest selection and endpoint lock acquisition");
    } finally {
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      LizzieFrame.menu = previousMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  private static void assertStaleBindingExitDoesNotAffectReplacement(
      boolean normalQuit, boolean retiredLocal, boolean replacementLocal) throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Menu previousMenu = LizzieFrame.menu;
    EngineStartupStatus.Snapshot previousStartupStatus = Lizzie.engineStartupStatus.snapshot();
    QuietExitLeelaz engine = new QuietExitLeelaz();
    AssertionError expectedExitFailure =
        normalQuit ? null : new AssertionError("controlled stale force-close failure");
    RecordingSshController retiredSsh =
        retiredLocal ? null : new RecordingSshController(engine, true, expectedExitFailure);
    BlockingDestroyProcess retiredProcess =
        retiredLocal ? new BlockingDestroyProcess(expectedExitFailure) : null;
    RecordingSshController replacementSsh =
        replacementLocal ? null : new RecordingSshController(engine);
    RecordingDestroyProcess replacementProcess =
        replacementLocal ? new RecordingDestroyProcess() : null;
    ScheduledExecutorService retiredStdout = runningReaderExecutor();
    ScheduledExecutorService retiredStderr = runningReaderExecutor();
    ByteArrayOutputStream retiredBytes = new ByteArrayOutputStream();
    BufferedOutputStream retiredOutput = new BufferedOutputStream(retiredBytes);
    ByteArrayOutputStream replacementBytes = new ByteArrayOutputStream();
    Object retiredBinding =
        retiredLocal
            ? installLocalReaderBinding(
                engine, retiredProcess, retiredStdout, retiredStderr, retiredOutput)
            : installJavaSshReaderBinding(
                engine, retiredSsh, retiredStdout, retiredStderr, retiredOutput);
    java.util.concurrent.locks.ReentrantLock retirementFence =
        (java.util.concurrent.locks.ReentrantLock)
            getField(retiredBinding, "analysisOutputMutationLock");
    if (!normalQuit && retiredLocal) {
      // Model a graceful claimant that already owns transport close while forceQuit captures this
      // exact stubborn process. The later same-object rebind must remain untouched.
      setField(retiredBinding, "transportCloseClaimed", true);
    }
    Object retiredForegroundSample = installForegroundSampleSentinel(engine);
    AtomicReference<Throwable> exitFailure = new AtomicReference<>();
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    Thread rebind =
        new Thread(
            () -> {
              try {
                rebindReader(engine, replacementBytes);
              } catch (Throwable failure) {
                rebindFailure.set(failure);
              } finally {
                rebindCompleted.countDown();
              }
            },
            "same-object-exit-rebind");
    rebind.setDaemon(true);
    Object replacementForegroundSample = null;
    RecordingTimer replacementPonderTimer = null;
    BoardData replacementPonderData = null;
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
    boolean retirementFenceHeld = false;
    try {
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = false;
      engine.bindCurrentPrimaryEngineGeneration();
      Lizzie.engineStartupStatus.checking("replacement.still.starting", "controlled");
      retirementFence.lock();
      retirementFenceHeld = true;
      exit.start();
      assertTrue(
          awaitLockWaiter(retirementFence, exit, 3_000L),
          "the stale exit must reach the binding-scoped retirement fence");
      if (replacementLocal) {
        engine.useRemoteCompute = false;
        engine.useJavaSSH = false;
        setLeelazField(engine, "process", replacementProcess);
        setLeelazField(engine, "javaSSH", null);
      } else {
        engine.useRemoteCompute = false;
        engine.useJavaSSH = true;
        setLeelazField(engine, "process", null);
        setLeelazField(engine, "javaSSH", replacementSsh);
      }

      rebind.start();
      assertFalse(
          rebindCompleted.await(250, TimeUnit.MILLISECONDS),
          "a production rebind must wait for the exact old-runtime stop claim");
      retirementFence.unlock();
      retirementFenceHeld = false;
      assertTrue(
          (retiredLocal ? retiredProcess.cleanupEntered : retiredSsh.closeEntered)
              .await(3, TimeUnit.SECONDS));
      assertTrue(rebindCompleted.await(3, TimeUnit.SECONDS));
      assertNull(rebindFailure.get());
      Object replacementOutput = getLeelazField(engine, "outputStream");
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = false;
      replacementForegroundSample = installForegroundSampleSentinel(engine);
      assertFalse(retiredForegroundSample == replacementForegroundSample);
      replacementPonderTimer = new RecordingTimer();
      replacementPonderData = allocate(BoardData.class);
      setLeelazField(engine, "leela0110PonderingTimer", replacementPonderTimer);
      setLeelazField(engine, "leela0110PonderingBoardData", replacementPonderData);
      (retiredLocal ? retiredProcess.allowCleanup : retiredSsh.allowClose).countDown();
      exit.join(3_000L);

      assertFalse(exit.isAlive());
      if (expectedExitFailure == null) {
        assertNull(exitFailure.get());
      } else {
        assertSame(expectedExitFailure, exitFailure.get());
      }
      if (retiredLocal) {
        assertEquals(normalQuit ? 1 : 0, retiredProcess.destroyCount.get());
        assertEquals(normalQuit ? 0 : 1, retiredProcess.forcibleDestroyCount.get());
      } else {
        assertEquals(1, retiredSsh.closeCount.get());
      }
      if (replacementLocal) {
        assertEquals(0, replacementProcess.destroyCount.get());
        assertEquals(0, replacementProcess.forcibleDestroyCount.get());
      } else {
        assertEquals(0, replacementSsh.closeCount.get());
      }
      assertTrue(retiredStdout.awaitTermination(3, TimeUnit.SECONDS));
      assertTrue(retiredStderr.awaitTermination(3, TimeUnit.SECONDS));
      assertSame(replacementOutput, getLeelazField(engine, "outputStream"));
      assertEquals(normalQuit ? "quit\n" : "", retiredBytes.toString(StandardCharsets.UTF_8));
      assertEquals("", replacementBytes.toString(StandardCharsets.UTF_8));
      assertSame(
          replacementForegroundSample,
          foregroundSample(engine),
          "stale remote cleanup must preserve replacement owner bookkeeping");
      assertTrue(engine.started);
      assertTrue(engine.isLoaded);
      assertFalse(engine.isNormalEnd);
      assertEquals(0, replacementPonderTimer.cancelCount.get());
      assertSame(replacementPonderData, getLeelazField(engine, "leela0110PonderingBoardData"));
      assertEquals(
          EngineStartupStatus.State.CHECKING,
          Lizzie.engineStartupStatus.snapshot().state,
          "stale shutdown must not publish READY for the rebound runtime");
    } finally {
      if (retirementFenceHeld) {
        retirementFence.unlock();
      }
      (retiredLocal ? retiredProcess.allowCleanup : retiredSsh.allowClose).countDown();
      exit.join(3_000L);
      rebind.join(3_000L);
      retiredStdout.shutdownNow();
      retiredStderr.shutdownNow();
      if (replacementPonderTimer != null) {
        replacementPonderTimer.cancelForFixtureCleanup();
      }
      removeForegroundSample(engine);
      restoreStartupStatus(previousStartupStatus);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      LizzieFrame.menu = previousMenu;
    }
  }

  @Test
  void normalQuitRemoteCleanupAttemptsTransportAndBothExecutorsAfterErrors() throws Exception {
    QuietExitLeelaz engine = new QuietExitLeelaz();
    AssertionError transportFailure = new AssertionError("controlled transport close failure");
    IllegalStateException stdoutFailure =
        new IllegalStateException("controlled stdout shutdown failure");
    AssertionError stderrFailure = new AssertionError("controlled stderr shutdown failure");
    RecordingTransport transport = new RecordingTransport(false, transportFailure);
    FailingShutdownExecutor stdout = new FailingShutdownExecutor(stdoutFailure);
    FailingShutdownExecutor stderr = new FailingShutdownExecutor(stderrFailure);
    BufferedOutputStream output = new BufferedOutputStream(new ByteArrayOutputStream());
    try {
      Object binding = installRemoteReaderBinding(engine, transport, stdout, stderr);
      setField(binding, "output", output);
      setLeelazField(engine, "outputStream", output);

      AssertionError thrown = assertThrows(AssertionError.class, engine::normalQuit);

      assertSame(transportFailure, thrown);
      assertEquals(1, transport.closeCount.get());
      assertEquals(1, stdout.shutdownCount.get());
      assertEquals(1, stderr.shutdownCount.get());
      assertEquals(2, thrown.getSuppressed().length);
      assertSame(stdoutFailure, thrown.getSuppressed()[0]);
      assertSame(stderrFailure, thrown.getSuppressed()[1]);
      assertNull(
          getLeelazField(engine, "outputStream"),
          "exact output cleanup must still run after transport/executor failures");
    } finally {
      stdout.cleanup();
      stderr.cleanup();
    }
  }

  @Test
  void normalQuitLocalCleanupDestroysProcessAfterStdoutShutdownError() throws Exception {
    QuietExitLeelaz engine = new QuietExitLeelaz();
    IllegalStateException stdoutFailure =
        new IllegalStateException("controlled local stdout shutdown failure");
    FailingShutdownExecutor stdout = new FailingShutdownExecutor(stdoutFailure);
    FailingShutdownExecutor stderr = new FailingShutdownExecutor(null);
    RecordingDestroyProcess process = new RecordingDestroyProcess();
    try {
      installLocalReaderBinding(engine, process, stdout, stderr, null);

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, engine::normalQuit);

      assertSame(stdoutFailure, thrown);
      assertEquals(1, stdout.shutdownCount.get());
      assertEquals(1, stderr.shutdownCount.get());
      assertEquals(1, process.destroyCount.get());
    } finally {
      stdout.cleanup();
      stderr.cleanup();
    }
  }

  @Test
  void forceQuitErrorsStillAttemptEveryCleanupAndClearExactOutput() throws Exception {
    QuietExitLeelaz engine = new QuietExitLeelaz();
    AssertionError transportFailure = new AssertionError("controlled force close failure");
    IllegalStateException stdoutFailure =
        new IllegalStateException("controlled force stdout shutdown failure");
    AssertionError stderrFailure = new AssertionError("controlled force stderr shutdown failure");
    RecordingTransport transport = new RecordingTransport(false, transportFailure);
    FailingShutdownExecutor stdout = new FailingShutdownExecutor(stdoutFailure);
    FailingShutdownExecutor stderr = new FailingShutdownExecutor(stderrFailure);
    BufferedOutputStream output = new BufferedOutputStream(new ByteArrayOutputStream());
    try {
      Object binding = installRemoteReaderBinding(engine, transport, stdout, stderr);
      setField(binding, "output", output);
      setLeelazField(engine, "outputStream", output);

      AssertionError thrown = assertThrows(AssertionError.class, engine::forceQuit);

      assertSame(transportFailure, thrown);
      assertEquals(1, transport.closeCount.get());
      assertEquals(1, stdout.shutdownCount.get());
      assertEquals(1, stderr.shutdownCount.get());
      assertEquals(2, thrown.getSuppressed().length);
      assertSame(stdoutFailure, thrown.getSuppressed()[0]);
      assertSame(stderrFailure, thrown.getSuppressed()[1]);
      assertNull(getLeelazField(engine, "outputStream"));
    } finally {
      stdout.cleanup();
      stderr.cleanup();
    }
  }

  @Test
  void exactClaimNotPonderingErrorDoesNotConsumeTransportCloseOwnership() throws Exception {
    FailingNotPonderingLeelaz engine = new FailingNotPonderingLeelaz();
    RecordingSshController ssh = new RecordingSshController(engine);
    Object incarnation = installJavaSshReaderBinding(engine, ssh, null, null);

    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () -> engine.normalQuitIfCurrentIncarnation(incarnation));
    assertSame(engine.failure, thrown);
    assertEquals(0, ssh.closeCount.get());

    engine.failNotPondering = false;
    assertTrue(engine.normalQuitIfCurrentIncarnation(incarnation));
    assertEquals(1, ssh.closeCount.get());
  }

  @Test
  void exactForceClaimIsNonProtocolIdempotentAndSingleOwner() throws Exception {
    NonProtocolForceLeelaz engine = new NonProtocolForceLeelaz();
    RecordingTransport transport = new RecordingTransport(false);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    BufferedOutputStream output = new BufferedOutputStream(bytes);
    Object binding = installRemoteReaderBinding(engine, transport, null, null);
    setField(binding, "output", output);
    setLeelazField(engine, "outputStream", output);

    Leelaz.ExactForceQuitClaim claim =
        engine.claimForceQuitIfCurrentIncarnation(binding);

    assertNotNull(claim);
    assertNull(engine.claimForceQuitIfCurrentIncarnation(binding));
    assertEquals(0, engine.notPonderingCount.get());
    claim.finish();
    claim.finish();

    assertEquals(0, engine.notPonderingCount.get());
    assertEquals(0, transport.closeCount.get());
    assertEquals(1, transport.abortCount.get());
    assertEquals("", bytes.toString(StandardCharsets.UTF_8));
    assertFalse(engine.forceQuitIfCurrentIncarnation(binding));
  }

  @Test
  void exactForceFinishesWhileGracefulQuitOutputIsBlocked() throws Exception {
    NonProtocolForceLeelaz engine = new NonProtocolForceLeelaz();
    BlockingQuitOutputStream blockedOutput = new BlockingQuitOutputStream();
    RecordingTransport transport = new RecordingTransport(false);
    transport.abortAction = blockedOutput::release;
    BufferedOutputStream output = new BufferedOutputStream(blockedOutput);
    Object binding = installRemoteReaderBinding(engine, transport, null, null);
    setField(binding, "output", output);
    setLeelazField(engine, "outputStream", output);
    Leelaz.ExactNormalQuitClaim graceful =
        engine.claimNormalQuitIfCurrentIncarnation(binding);
    assertNotNull(graceful);
    AtomicReference<Throwable> gracefulFailure = new AtomicReference<>();
    AtomicReference<Throwable> forceFailure = new AtomicReference<>();
    Thread gracefulThread =
        new Thread(
            () -> {
              try {
                graceful.finish();
              } catch (Throwable failure) {
                gracefulFailure.set(failure);
              }
            },
            "blocked-graceful-engine-quit");
    Thread forceThread = null;
    try {
      gracefulThread.start();
      assertTrue(blockedOutput.writeEntered.await(2, TimeUnit.SECONDS));
      Leelaz.ExactForceQuitClaim force =
          engine.claimForceQuitIfCurrentIncarnation(binding);
      assertNotNull(force);
      assertNull(engine.claimForceQuitIfCurrentIncarnation(binding));
      forceThread =
          new Thread(
              () -> {
                try {
                  force.finish();
                } catch (Throwable failure) {
                  forceFailure.set(failure);
                }
              },
              "exact-force-engine-quit");
      forceThread.start();
      forceThread.join(1_000L);

      assertFalse(forceThread.isAlive(), "force cleanup must not wait for graceful output");
      gracefulThread.join(2_000L);
      assertFalse(gracefulThread.isAlive());
      assertNull(forceFailure.get());
      assertNull(gracefulFailure.get());
      assertEquals(1, engine.notPonderingCount.get(), "only the graceful claim may stop pondering");
      assertEquals(1, transport.abortCount.get());
      assertEquals(1, transport.closeCount.get());
      assertEquals("quit\n", blockedOutput.bytes.toString(StandardCharsets.UTF_8));
    } finally {
      blockedOutput.release();
      if (forceThread != null) forceThread.join(2_000L);
      gracefulThread.join(2_000L);
    }
  }

  @Test
  void exactForceClaimClosesOnlyFrozenBindingAcrossRebind() throws Exception {
    NonProtocolForceLeelaz engine = new NonProtocolForceLeelaz();
    RecordingTransport retiredTransport = new RecordingTransport(false);
    RecordingTransport replacementTransport = new RecordingTransport(false);
    BufferedOutputStream retiredOutput =
        new BufferedOutputStream(new ByteArrayOutputStream());
    Object retiredBinding = installRemoteReaderBinding(engine, retiredTransport, null, null);
    setField(retiredBinding, "output", retiredOutput);
    setLeelazField(engine, "outputStream", retiredOutput);
    Leelaz.ExactForceQuitClaim claim =
        engine.claimForceQuitIfCurrentIncarnation(retiredBinding);
    assertNotNull(claim);

    setLeelazField(engine, "remoteTransport", replacementTransport);
    ByteArrayOutputStream replacementBytes = new ByteArrayOutputStream();
    rebindReader(engine, replacementBytes);
    Object replacementBinding = engine.currentEngineIncarnation();
    Object replacementOutput = getLeelazField(engine, "outputStream");
    engine.started = true;
    engine.isLoaded = true;

    assertNull(engine.claimForceQuitIfCurrentIncarnation(retiredBinding));
    claim.finish();
    claim.finish();

    assertFalse(retiredBinding == replacementBinding);
    assertSame(replacementBinding, engine.currentEngineIncarnation());
    assertSame(replacementOutput, getLeelazField(engine, "outputStream"));
    assertTrue(engine.started);
    assertTrue(engine.isLoaded);
    assertEquals(1, retiredTransport.abortCount.get());
    assertEquals(0, retiredTransport.closeCount.get());
    assertEquals(0, replacementTransport.abortCount.get());
    assertEquals(0, replacementTransport.closeCount.get());
    assertEquals("", replacementBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void exactForceEscalatesLocalProcessAfterGracefulClaim() throws Exception {
    NonProtocolForceLeelaz engine = new NonProtocolForceLeelaz();
    RecordingDestroyProcess process = new RecordingDestroyProcess();
    setLeelazField(engine, "process", process);
    engine.useRemoteCompute = false;
    engine.useJavaSSH = false;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    rebindReader(engine, bytes);
    Object binding = engine.currentEngineIncarnation();
    Leelaz.ExactNormalQuitClaim graceful =
        engine.claimNormalQuitIfCurrentIncarnation(binding);
    Leelaz.ExactForceQuitClaim force =
        engine.claimForceQuitIfCurrentIncarnation(binding);

    assertNotNull(graceful);
    assertNotNull(force, "graceful physical-close ownership must not consume force escalation");
    force.finish();
    graceful.finish();

    assertEquals(1, process.forcibleDestroyCount.get());
    assertEquals(1, process.destroyCount.get());
    assertEquals("quit\n", bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void exactForceAggregatesAbortAndExecutorFailuresBeforeClearingOutput() throws Exception {
    NonProtocolForceLeelaz engine = new NonProtocolForceLeelaz();
    AssertionError abortFailure = new AssertionError("controlled exact abort failure");
    IllegalStateException stdoutFailure =
        new IllegalStateException("controlled exact force stdout failure");
    AssertionError stderrFailure = new AssertionError("controlled exact force stderr failure");
    RecordingTransport transport = new RecordingTransport(false, abortFailure);
    FailingShutdownExecutor stdout = new FailingShutdownExecutor(stdoutFailure);
    FailingShutdownExecutor stderr = new FailingShutdownExecutor(stderrFailure);
    BufferedOutputStream output = new BufferedOutputStream(new ByteArrayOutputStream());
    try {
      Object binding = installRemoteReaderBinding(engine, transport, stdout, stderr);
      setField(binding, "output", output);
      setLeelazField(engine, "outputStream", output);
      Leelaz.ExactForceQuitClaim claim =
          engine.claimForceQuitIfCurrentIncarnation(binding);
      assertNotNull(claim);

      AssertionError thrown = assertThrows(AssertionError.class, claim::finish);

      assertSame(abortFailure, thrown);
      assertEquals(0, transport.closeCount.get());
      assertEquals(1, transport.abortCount.get());
      assertEquals(1, stdout.shutdownCount.get());
      assertEquals(1, stderr.shutdownCount.get());
      assertEquals(2, thrown.getSuppressed().length);
      assertSame(stdoutFailure, thrown.getSuppressed()[0]);
      assertSame(stderrFailure, thrown.getSuppressed()[1]);
      assertNull(getLeelazField(engine, "outputStream"));
      claim.finish();
      assertEquals(1, transport.abortCount.get());
    } finally {
      stdout.cleanup();
      stderr.cleanup();
    }
  }

  @Test
  void exactClaimPreservesReboundForegroundSampleAcrossLocalRemoteCombinations()
      throws Exception {
    for (boolean retiredLocal : List.of(false, true)) {
      for (boolean replacementLocal : List.of(false, true)) {
        QuietExitLeelaz engine = new QuietExitLeelaz();
        RecordingDestroyProcess retiredProcess =
            retiredLocal ? new RecordingDestroyProcess() : null;
        RecordingTransport retiredTransport =
            retiredLocal ? null : new RecordingTransport(false);
        RecordingDestroyProcess replacementProcess =
            replacementLocal ? new RecordingDestroyProcess() : null;
        RecordingTransport replacementTransport =
            replacementLocal ? null : new RecordingTransport(false);
        Object retiredBinding =
            retiredLocal
                ? installLocalReaderBinding(engine, retiredProcess, null, null, null)
                : installRemoteReaderBinding(engine, retiredTransport, null, null);
        Object retiredSample = installForegroundSampleSentinel(engine);
        Leelaz.ExactNormalQuitClaim claim =
            engine.claimNormalQuitIfCurrentIncarnation(retiredBinding);
        assertNotNull(claim);

        if (replacementLocal) {
          engine.useRemoteCompute = false;
          engine.useJavaSSH = false;
          setLeelazField(engine, "process", replacementProcess);
          setLeelazField(engine, "remoteTransport", null);
        } else {
          engine.useRemoteCompute = true;
          engine.useJavaSSH = false;
          setLeelazField(engine, "process", null);
          setLeelazField(engine, "remoteTransport", replacementTransport);
        }
        rebindReader(engine);
        Object replacementSample = installForegroundSampleSentinel(engine);
        assertFalse(retiredSample == replacementSample);
        engine.started = true;
        engine.isLoaded = true;

        claim.finish();

        assertSame(
            replacementSample,
            foregroundSample(engine),
            "retiredLocal=" + retiredLocal + ", replacementLocal=" + replacementLocal);
        assertTrue(engine.started);
        assertTrue(engine.isLoaded);
        if (retiredLocal) {
          assertEquals(1, retiredProcess.destroyCount.get());
        } else {
          assertEquals(1, retiredTransport.closeCount.get());
        }
        if (replacementLocal) {
          assertEquals(0, replacementProcess.destroyCount.get());
          assertEquals(0, replacementProcess.forcibleDestroyCount.get());
        } else {
          assertEquals(0, replacementTransport.closeCount.get());
        }
        removeForegroundSample(engine);
      }
    }
  }

  @Test
  void quarantineAdmissionCannotMarkAReboundRuntimeUnavailable() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    BlockingMarkUnavailableLeelaz engine = new BlockingMarkUnavailableLeelaz();
    Object retiredIncarnation = engine.currentEngineIncarnation();
    AtomicReference<Runnable> quarantine = new AtomicReference<>();
    AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
    Thread admission =
        new Thread(
            () -> {
              try {
                quarantine.set(
                    invokeQuarantineStaleInitialEngineIncarnation(
                        engine, retiredIncarnation, 7001L));
              } catch (Throwable failure) {
                admissionFailure.set(failure);
              }
            },
            "quarantine-admission-race");
    try {
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = null;
      engine.isLoaded = true;
      admission.start();
      assertTrue(engine.markUnavailableEntered.await(3, TimeUnit.SECONDS));

      rebindReader(engine);
      Object replacementIncarnation = engine.currentEngineIncarnation();
      engine.isLoaded = true;
      engine.allowMarkUnavailable.countDown();
      admission.join(3_000L);

      assertFalse(admission.isAlive());
      assertNull(admissionFailure.get());
      assertNull(quarantine.get());
      assertFalse(retiredIncarnation == replacementIncarnation);
      assertTrue(engine.isLoaded, "stale admission must not mark the rebound runtime unavailable");
      assertFalse(isFailedEngineQuarantined(engine));
    } finally {
      engine.allowMarkUnavailable.countDown();
      admission.join(3_000L);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
    }
  }

  @Test
  void failedStopThreadAllocationConfigurationOrStartSettlesQuarantineExactlyOnce()
      throws Exception {
    for (String failureStage : List.of("allocation", "configuration", "start")) {
      Leelaz previousPrimary = Lizzie.leelaz;
      Leelaz previousSecondary = Lizzie.leelaz2;
      QuietExitLeelaz engine = new QuietExitLeelaz();
      RecordingSshController ssh = new RecordingSshController(engine);
      Object incarnation = installJavaSshReaderBinding(engine, ssh, null, null);
      AssertionError schedulingFailure =
          new AssertionError("controlled failed-stop " + failureStage + " failure");
      CountDownLatch configuredThreadGate = new CountDownLatch(1);
      AtomicReference<Thread> configuredThread = new AtomicReference<>();
      try {
        Lizzie.setPrimaryEngine(null);
        Lizzie.leelaz2 = null;
        engine.started = true;
        engine.isLoaded = true;
        Runnable stop =
            invokeQuarantineStaleInitialEngineIncarnation(engine, incarnation, 7002L);
        assertNotNull(stop);
        assertTrue(isFailedEngineQuarantined(engine));
        if (failureStage.equals("allocation")) {
          EngineManager.setFailedEngineStopThreadFactoryForTest(
              (ignored, name) -> {
                throw schedulingFailure;
              });
        } else if (failureStage.equals("configuration")) {
          EngineManager.setFailedEngineStopThreadFactoryForTest(
              (ignored, name) -> {
                Thread alreadyStarted =
                    new Thread(
                        () -> {
                          try {
                            configuredThreadGate.await();
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          }
                        },
                        name + "-configured");
                alreadyStarted.start();
                configuredThread.set(alreadyStarted);
                return alreadyStarted;
              });
        } else {
          EngineManager.setFailedEngineStopThreadFactoryForTest(
              (task, name) ->
                  new Thread(task, name) {
                    @Override
                    public synchronized void start() {
                      throw schedulingFailure;
                    }
                  });
        }

        invokeDispatchFailedEngineStop(stop, 7002L);

        assertFalse(isFailedEngineQuarantined(engine));
        assertEquals(1, ssh.closeCount.get());
        Runnable retry =
            invokeQuarantineStaleInitialEngineIncarnation(engine, incarnation, 7003L);
        assertNotNull(retry, "scheduling failure must not strand quarantine admission");
        retry.run();
        assertFalse(isFailedEngineQuarantined(engine));
        assertEquals(1, ssh.closeCount.get(), "the exact transport must settle only once");
      } finally {
        configuredThreadGate.countDown();
        if (configuredThread.get() != null) {
          configuredThread.get().join(3_000L);
        }
        EngineManager.setFailedEngineStopThreadFactoryForTest(null);
        Lizzie.setPrimaryEngine(previousPrimary);
        Lizzie.leelaz2 = previousSecondary;
      }
    }
  }

  @Test
  void failedStopPreClaimErrorStillClosesExactRuntimeAndReleasesQuarantine()
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    FailingNotPonderingLeelaz engine = new FailingNotPonderingLeelaz();
    RecordingSshController ssh = new RecordingSshController(engine);
    Object incarnation = installJavaSshReaderBinding(engine, ssh, null, null);
    try {
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = null;
      engine.started = true;
      engine.isLoaded = true;
      Runnable stop =
          invokeQuarantineStaleInitialEngineIncarnation(engine, incarnation, 7010L);
      assertNotNull(stop);
      assertTrue(isFailedEngineQuarantined(engine));

      stop.run();

      assertFalse(isFailedEngineQuarantined(engine));
      assertEquals(1, ssh.closeCount.get());
      assertFalse(engine.started);
      assertFalse(engine.isLoaded);
      assertSame(incarnation, engine.currentEngineIncarnation());
    } finally {
      EngineManager.setFailedEngineStopThreadFactoryForTest(null);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
    }
  }

  @Test
  void failedStopSchedulerErrorStillClosesAfterPreClaimErrorAndKeepsPrimaryFailure()
      throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    FailingNotPonderingLeelaz engine = new FailingNotPonderingLeelaz();
    RecordingSshController ssh = new RecordingSshController(engine);
    Object incarnation = installJavaSshReaderBinding(engine, ssh, null, null);
    AssertionError schedulingFailure =
        new AssertionError("controlled failed-stop scheduling failure");
    try {
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = null;
      engine.started = true;
      engine.isLoaded = true;
      Runnable stop =
          invokeQuarantineStaleInitialEngineIncarnation(engine, incarnation, 7011L);
      assertNotNull(stop);
      EngineManager.setFailedEngineStopThreadFactoryForTest(
          (task, name) ->
              new Thread(task, name) {
                @Override
                public synchronized void start() {
                  throw schedulingFailure;
                }
              });

      invokeDispatchFailedEngineStop(stop, 7011L);

      assertFalse(isFailedEngineQuarantined(engine));
      assertEquals(1, ssh.closeCount.get());
      assertFalse(engine.started);
      assertFalse(engine.isLoaded);
      assertSame(incarnation, engine.currentEngineIncarnation());
      assertTrue(
          java.util.Arrays.stream(schedulingFailure.getSuppressed())
              .anyMatch(suppressed -> suppressed == engine.failure));
    } finally {
      EngineManager.setFailedEngineStopThreadFactoryForTest(null);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
    }
  }

  @Test
  void failedStopSchedulingDoesNotRetireAnIncarnationThatRecoveredReady() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    QuietExitLeelaz engine = new QuietExitLeelaz();
    RecordingSshController ssh = new RecordingSshController(engine);
    Object incarnation = installJavaSshReaderBinding(engine, ssh, null, null);
    try {
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = null;
      engine.started = true;
      engine.isLoaded = true;
      Runnable stop =
          invokeQuarantineStaleInitialEngineIncarnation(engine, incarnation, 7004L);
      assertNotNull(stop);
      EngineManager.setFailedEngineStopThreadFactoryForTest(
          (task, name) ->
              new Thread(task, name) {
                @Override
                public synchronized void start() {
                  engine.isLoaded = true;
                  throw new AssertionError("controlled scheduling failure after late READY");
                }
              });

      invokeDispatchFailedEngineStop(stop, 7004L);

      assertFalse(isFailedEngineQuarantined(engine));
      assertEquals(0, ssh.closeCount.get());
      assertTrue(engine.started);
      assertTrue(engine.isLoaded);
      assertSame(incarnation, engine.currentEngineIncarnation());
    } finally {
      EngineManager.setFailedEngineStopThreadFactoryForTest(null);
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
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
    try {
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      EngineManager manager = new EngineManager(List.of());
      EngineManager.PkEngineSynchronization black =
          manager.startEngineForPkSynchronization(-1);
      EngineManager.PkEngineSynchronization white =
          manager.startEngineForPkSynchronization(-1);

      assertFalse(manager.finishPkEngineSynchronizations(black, white));

      assertFalse(EngineManager.hasActiveEngineGameTransaction());
      assertTrue(black.isComplete());
      assertTrue(white.isComplete());
    } finally {
      Lizzie.frame = previousFrame;
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
      assertFalse(EngineManager.hasActiveEngineGameTransaction());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void pkStartRestoresTheFrozenTargetWhenCatalogChangesAfterReservation() throws Exception {
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
      assertEquals(1, target.restoreCount);
      assertTrue(target.loadedSgf.contains("AB[dd]"));
      assertTrue(target.loadedSgf.contains("KM[6.5]"));
      assertEquals(0, replacement.restoreCount);
      assertTrue(replacement.loadedSgf.isEmpty());
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
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);

      assertEquals(0, engine.restartCount);
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticProcessRestartDoesNotRaceAnActiveGmaReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
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
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);

      assertEquals(0, engine.restartCount);
    } finally {
      setReadBoardGmaReservation(engine, null);
      reservation.close();
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticProcessRestartLosesTheRaceWhenGmaReservesBeforeRestartDispatch() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
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
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void remoteAutomaticRestartHandsItsReservationToTheBoardRestore() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
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
  void switchReleasesReservedTargetWhenCurrentReservationThrows() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    List<String> reservationOrder = new ArrayList<>();
    IllegalStateException reservationFailure =
        new IllegalStateException("controlled current reservation failure");
    ThrowingLifecycleLeelaz current =
        new ThrowingLifecycleLeelaz("current", reservationOrder, reservationFailure);
    OrderedLifecycleLeelaz target =
        new OrderedLifecycleLeelaz("target", reservationOrder, false);
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.board = null;
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      assertFalse(manager.switchEngineIfAvailable(1, true));

      assertEquals(List.of("target", "current"), reservationOrder);
      assertEquals(0, manager.switchCount);
      assertEquals(1, manager.failureCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      Leelaz.ExclusiveGtpLifecycleReservation retry =
          target.beginExclusiveGtpLifecycleReservation();
      assertNotNull(retry, "the target reservation acquired first must not leak");
      retry.close();
    } finally {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void initialSynchronizationReleasesReservedTargetWhenPreviousReservationThrows()
      throws Exception {
    List<String> reservationOrder = new ArrayList<>();
    AssertionError reservationFailure =
        new AssertionError("controlled previous reservation error");
    ThrowingLifecycleLeelaz previous =
        new ThrowingLifecycleLeelaz("previous", reservationOrder, reservationFailure);
    OrderedLifecycleLeelaz target =
        new OrderedLifecycleLeelaz("target", reservationOrder, false);
    Board board = preparedRestoreBoard();

    AssertionError observed =
        assertThrows(
            AssertionError.class,
            () ->
                EngineManager.InitialEngineStartupSynchronization.capture(
                    previous, target, null, board, false, false));

    assertSame(reservationFailure, observed);
    assertEquals(List.of("target", "previous"), reservationOrder);
    assertFalse(previous.hasExclusiveGtpWorkInProgress());
    assertFalse(target.hasExclusiveGtpWorkInProgress());
    assertNull(getLeelazField(target, "initialEngineSyncAdmission"));
    Leelaz.ExclusiveGtpLifecycleReservation retry =
        target.beginExclusiveGtpLifecycleReservation();
    assertNotNull(retry, "the target must be reservable after startup capture aborts");
    retry.close();
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
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    FenceTrackingLeelaz current = new FenceTrackingLeelaz();
    current.started = true;
    current.isLoaded = true;
    setEngineStateUnrestored(current, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current), current);
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.board = preparedRestoreBoard();
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      assertFalse(manager.switchEngineIfAvailable(0, true));

      assertNull(manager.afterSync);
      assertNull(current.confirmation);
      assertTrue(current.hasUnrestoredReadBoardGmaState());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void selectingTheSameForegroundEngineSkipsASecondBoardConfirmation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    FenceTrackingLeelaz current = new FenceTrackingLeelaz();
    current.started = true;
    current.isLoaded = true;
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current), current);
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.board = preparedRestoreBoard();
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertNotNull(manager.afterSync);
      manager.afterSync.run();

      assertNull(current.confirmation);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
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
      target.completeBoardSynchronization();
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

  @Test
  void explicitRestartFinalInitializationFailureWithoutPreparedRestoreFailsClosedAndReleasesOnce()
      throws Exception {
    assertUnpreparedExplicitRestartFinalInitializationFailure(true, true);
    assertUnpreparedExplicitRestartFinalInitializationFailure(false, false);
  }

  private void assertUnpreparedExplicitRestartFinalInitializationFailure(
      boolean main, boolean callbackOnEdt) throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    EngineManager previousManager = Lizzie.engineManager;
    Menu previousMenu = LizzieFrame.menu;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    TrackingRestartActionLeelaz primary = new TrackingRestartActionLeelaz();
    FailingFinalInitializationLeelaz target = new FailingFinalInitializationLeelaz();
    FinalInitializationFailureEngineManager manager =
        new FinalInitializationFailureEngineManager(
            main ? List.of(target) : List.of(primary, target));
    AtomicInteger releaseCount = new AtomicInteger();
    AtomicReference<Throwable> escaped = new AtomicReference<>();
    try {
      primary.started = true;
      primary.isLoaded = true;
      target.started = true;
      target.isLoaded = true;
      target.isCheckingName = false;
      Lizzie.leelaz = main ? target : primary;
      Lizzie.leelaz2 = main ? null : target;
      Lizzie.engineManager = manager;
      LizzieFrame.menu = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = main ? -1 : 1;
      Lizzie.engineStartupStatus.ready();

      Field trackerField = EngineManager.class.getDeclaredField("engineSwitchUiTracker");
      trackerField.setAccessible(true);
      EngineManager.EngineSwitchUiTracker tracker =
          (EngineManager.EngineSwitchUiTracker) trackerField.get(manager);
      int targetIndex = main ? 0 : 1;
      EngineManager.EngineSwitchUiSnapshot switching =
          tracker.begin(
              main,
              targetIndex,
              "restart-target",
              target,
              targetIndex,
              "restart-target",
              target);

      Field transactionField = EngineManager.class.getDeclaredField("engineSwitchTransaction");
      transactionField.setAccessible(true);
      assertNull(
          ((AtomicReference<?>) transactionField.get(manager)).get(),
          "this regression must exercise the no-transaction explicit restart path");

      Method callbackFactory =
          java.util.Arrays.stream(EngineManager.class.getDeclaredMethods())
              .filter(
                  method ->
                      method.getName().equals("releaseEngineLifecycleAfterBoardSync")
                          && method.getParameterCount() == 7
                          && method.getParameterTypes()[5] == Runnable.class)
              .findFirst()
              .orElseThrow();
      callbackFactory.setAccessible(true);
      Runnable completion =
          (Runnable)
              callbackFactory.invoke(
                  manager,
                  main ? target : primary,
                  target,
                  main,
                  true,
                  false,
                  (Runnable) releaseCount::incrementAndGet,
                  null);

      if (callbackOnEdt) {
        SwingUtilities.invokeAndWait(
            () -> {
              try {
                completion.run();
              } catch (Throwable failure) {
                escaped.set(failure);
              }
            });
      } else {
        Thread worker =
            new Thread(
                () -> {
                  try {
                    completion.run();
                  } catch (Throwable failure) {
                    escaped.set(failure);
                  }
                },
                "explicit-restart-final-initialization-test");
        worker.start();
        worker.join(2000L);
        assertFalse(worker.isAlive(), "the lifecycle completion worker must return");
      }
      SwingUtilities.invokeAndWait(() -> {});

      assertNull(escaped.get(), "final initialization failure must not escape its callback thread");
      assertFalse(target.isLoaded, "a target that failed final initialization is not routable");
      EngineManager.EngineSwitchUiSnapshot failed = manager.engineSwitchUiSnapshot(main);
      assertEquals(switching.token(), failed.token(), "the exact admitted token must be failed");
      assertEquals(EngineManager.EngineSwitchUiPhase.FAILED, failed.phase());
      assertEquals(1, target.lifecycleFailureCount);
      assertEquals(main ? 1 : 0, target.primaryInitializationCount);
      assertEquals(main ? 0 : 1, target.secondaryInitializationCount);
      assertEquals(1, manager.failureCount);
      assertEquals(1, releaseCount.get(), "the lifecycle release must run exactly once");
      assertEquals(
          main ? EngineStartupStatus.State.START_FAILED : EngineStartupStatus.State.READY,
          Lizzie.engineStartupStatus.snapshot().state,
          "startup status must reflect the remaining routable primary owner");
      assertNull(((AtomicReference<?>) transactionField.get(manager)).get());
    } finally {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.engineManager = previousManager;
      LizzieFrame.menu = previousMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void boardFenceSetupErrorWithDistinctFailureCleanupErrorSettlesFailureOnce()
      throws Exception {
    assertBoardFenceSetupAndFailureCleanupErrorSettleOnce(false);
  }

  @Test
  void boardFenceSetupErrorReusedByFailureCleanupSettlesFailureOnce() throws Exception {
    assertBoardFenceSetupAndFailureCleanupErrorSettleOnce(true);
  }

  private void assertBoardFenceSetupAndFailureCleanupErrorSettleOnce(
      boolean reuseSetupFailure) throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    AssertionError setupFailure = new AssertionError("controlled board fence setup failure");
    AssertionError cleanupFailure =
        reuseSetupFailure
            ? setupFailure
            : new AssertionError("controlled board fence failure cleanup");
    SetupFailureFenceLeelaz target = new SetupFailureFenceLeelaz(setupFailure);
    SetupFailureFenceEngineManager manager =
        new SetupFailureFenceEngineManager(List.of(target));
    AtomicInteger releaseCount = new AtomicInteger();
    try {
      target.started = true;
      target.isLoaded = true;
      target.oriEnginename = "setup-failure-target";
      Lizzie.leelaz = target;
      Lizzie.leelaz2 = null;
      Lizzie.frame = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      Field trackerField = EngineManager.class.getDeclaredField("engineSwitchUiTracker");
      trackerField.setAccessible(true);
      EngineManager.EngineSwitchUiTracker tracker =
          (EngineManager.EngineSwitchUiTracker) trackerField.get(manager);
      EngineManager.EngineSwitchUiSnapshot switching =
          tracker.begin(
              true,
              0,
              target.oriEnginename,
              target,
              0,
              target.oriEnginename,
              target);

      Class<?> restoreType =
          Class.forName("featurecat.lizzie.analysis.EngineManager$PreparedLifecycleRestore");
      Method capture =
          java.util.Arrays.stream(restoreType.getDeclaredMethods())
              .filter(
                  method ->
                      method.getName().equals("capture") && method.getParameterCount() == 7)
              .findFirst()
              .orElseThrow();
      capture.setAccessible(true);
      Object lifecycleRestore =
          capture.invoke(null, null, target, null, null, null, new ArrayList<>(), false);
      setField(lifecycleRestore, "engineSwitchUiToken", switching.token());
      setField(lifecycleRestore, "engineSwitchUiIndex", 0);
      setField(lifecycleRestore, "engineSwitchUiMain", true);

      Method callbackFactory =
          java.util.Arrays.stream(EngineManager.class.getDeclaredMethods())
              .filter(
                  method ->
                      method.getName().equals("releaseEngineLifecycleAfterBoardSync")
                          && method.getParameterCount() == 7
                          && method.getParameterTypes()[5] == Runnable.class)
              .findFirst()
              .orElseThrow();
      callbackFactory.setAccessible(true);
      Runnable completion =
          (Runnable)
              callbackFactory.invoke(
                  manager,
                  target,
                  target,
                  true,
                  false,
                  false,
                  (Runnable)
                      () -> {
                        releaseCount.incrementAndGet();
                        throw cleanupFailure;
                      },
                  lifecycleRestore);

      AssertionError thrown = assertThrows(AssertionError.class, completion::run);

      assertSame(cleanupFailure, thrown);
      assertEquals(1, manager.failureCount);
      assertEquals(1, target.lifecycleFailureCount);
      assertEquals(1, releaseCount.get());
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
    } finally {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
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
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      Lizzie.engineStartupStatus.ready();
      secondary.restartOutput = gatedOutput;

      manager.reStartEngine2();
      assertEquals(1, secondary.shutdownCount);
      assertNotNull(manager.afterSync);

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
      awaitReservationReleased(secondary);
      fenceSettled = true;

      assertFalse(secondary.isLoaded());
      assertEquals(0, secondary.secondaryTerminalCount);
      assertEquals(0, secondary.initializationCount);
      assertEquals(0, primary.ponderCount);
      assertEquals(0, secondary.ponderCount);
      assertFalse(secondary.responseWatermarkWhileLifecycleHeld);
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
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    CountingRestartFrame frame = allocate(CountingRestartFrame.class);
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
  void staleRestartFailureReceiptCannotUnloadReboundRuntime() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    setCapabilityDiscoveryComplete(engine, true);
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    Lizzie.leelaz = engine;
    Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
    try {
      reservation = engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
      rebindReader(engine);
      Runnable staleFailure =
          engine.currentRestartBoardSynchronizationFailureAction(
              "controlled stale restart board failure");

      rebindReader(engine);
      engine.isLoaded = true;
      staleFailure.run();

      assertTrue(engine.isLoaded());
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
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
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz current = new Leelaz("");
    Leelaz target = unavailableStartedEngine();
    target.isDownWithError = true;
    ReadinessFailureEngineManager manager =
        new ReadinessFailureEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;
      Lizzie.engineManager = manager;

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertTrue(manager.failurePresented.await(1, TimeUnit.SECONDS));
      assertNull(Lizzie.leelaz);
      assertTrue(EngineManager.isEmpty);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertFalse(target.isLoaded());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.engineManager = previousManager;
    }
  }

  @Test
  void targetReadinessTimeoutReleasesBothSwitchReservations() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz current = new Leelaz("");
    Leelaz target = unavailableStartedEngine();
    ReadinessFailureEngineManager manager =
        new ReadinessFailureEngineManager(List.of(current, target), target, 10L);
    try {
      Lizzie.leelaz = current;
      Lizzie.engineManager = manager;

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertTrue(manager.failurePresented.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.engineManager = previousManager;
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
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(10L);
    target.isTuning = true;
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;
      Lizzie.engineManager = manager;
      target.releaseLoadedReads();

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertTrue(manager.failurePresented.await(1, TimeUnit.SECONDS));
      assertNull(Lizzie.leelaz);
      assertTrue(EngineManager.isEmpty);
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
      Lizzie.engineManager = previousManager;
    }
  }

  @Test
  void synchronizationReceiptPreflightErrorReleasesOrdinarySwitchOwnershipOnce()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingSwitchLeelaz primary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    PreflightFailureLeelaz target = new PreflightFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(List.of(primary, secondary, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      primary.started = secondary.started = target.started = true;
      primary.isLoaded = secondary.isLoaded = target.isLoaded = true;
      target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertNotNull(target.captureEngineIncarnationFence());
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      manager.switchEngine(2, false);

      assertEquals(1, target.preflightCount);
      assertEquals(1, target.detachCount);
      assertFalse(primary.hasExclusiveGtpWorkInProgress());
      assertFalse(secondary.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertSame(secondary, Lizzie.leelaz2);
      assertFalse(target.isLoaded());
      assertEquals(EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(false).phase());
      assertNull(activeEngineSwitchTransaction(manager));
    } finally {
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  @Test
  void synchronizationWorkerStartThenThrowDelegatesAfterSyncExactlyOnce() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    engine.isCheckingName = false;
    StartThenThrowSynchronizationEngineManager manager =
        new StartThenThrowSynchronizationEngineManager(List.of(engine));
    AtomicInteger synchronizationCount = new AtomicInteger();
    AtomicInteger afterSyncCount = new AtomicInteger();

    manager.synchronizeForTest(
        engine,
        () -> {
          synchronizationCount.incrementAndGet();
          manager.workerClaimed.countDown();
        },
        () -> {
          afterSyncCount.incrementAndGet();
          manager.completed.countDown();
        });

    assertTrue(manager.completed.await(2, TimeUnit.SECONDS));
    assertEquals(1, manager.startCount);
    assertEquals(1, synchronizationCount.get());
    assertEquals(1, afterSyncCount.get());
  }

  @Test
  void rollbackWorkerStartThenThrowLeavesTheWorkerAsSoleSettlementOwner() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    BlockingRollbackRecoveryLeelaz engine = new BlockingRollbackRecoveryLeelaz();
    StartThenThrowRollbackEngineManager manager =
        new StartThenThrowRollbackEngineManager(List.of(engine), engine);
    try {
      Lizzie.board = preparedRestoreBoard();
      Lizzie.frame = null;
      LizzieFrame.menu = null;
      Lizzie.engineManager = manager;
      Lizzie.setPrimaryEngine(null);
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      engine.started = true;
      engine.isLoaded = true;
      engine.isCheckingName = false;
      Object recovery = installSyntheticFailedRollbackRecovery(manager, engine, Lizzie.board);

      SwingUtilities.invokeAndWait(() -> invokeFailedRollbackRecoveryDispatch(manager, recovery));
      engine.allowReservation.countDown();

      assertTrue(manager.rollbackFinished.await(2, TimeUnit.SECONDS));
      assertEquals(1, manager.startCount);
      assertEquals(1, engine.reservationCount.get());
      assertEquals(1L, engine.recoveryCompleted.getCount());
      assertEquals(1, manager.settlementCount);
      assertNull(activeFailedRollbackRecovery(manager));
      assertNull(Lizzie.leelaz);
      assertEquals(0, manager.failureCount);
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
      engine.allowReservation.countDown();
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void ordinaryCloseErrorPublishesNoTargetSelectionPdaActiveUiOrReady() throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    FailingDetachRecordingSwitchLeelaz target =
        new FailingDetachRecordingSwitchLeelaz();
    DeferredLifecycleFenceEngineManager manager =
        new DeferredLifecycleFenceEngineManager(List.of(current, target));
    RecordingPdaMenu menu = allocate(RecordingPdaMenu.class);
    menu.engines = new ArrayList<>();
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.menu = menu;
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      current.started = target.started = true;
      current.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      target.isKataGoPda = true;
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      target.installFreshCommandOutputForTest(targetTransport);
      assertNotNull(target.captureEngineIncarnationFence());
      Lizzie.setPrimaryEngine(current);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled close failure");

      manager.switchEngine(1, true);
      assertNotNull(manager.synchronization);
      manager.synchronization.run();
      manager.afterSync.run();
      assertNotNull(((RecordingSwitchLeelaz) target).boardSynchronizationCompletion);

      AssertionError thrown =
          assertThrows(AssertionError.class, target::completeBoardSynchronization);

      assertSame(target.closeFailure, thrown);
      assertTrue(menu.engines.stream().noneMatch(engine -> engine == target));
      assertEquals(0, manager.engineSwitchUiSnapshot(true).activeIndex());
      assertEquals(EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertTrue(Lizzie.leelaz != target);
      assertTrue(Lizzie.engineStartupStatus.snapshot().state
          != EngineStartupStatus.State.READY);
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertNull(activeEngineSwitchTransaction(manager));
    } finally {
      current.completeBoardSynchronization();
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void ordinaryTransactionRemainsAuthoritativeUntilCloseAndTerminalCommitFinish()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    BlockingDetachRecordingSwitchLeelaz target =
        new BlockingDetachRecordingSwitchLeelaz();
    RecordingSwitchLeelaz laterTarget = new RecordingSwitchLeelaz();
    DeferredLifecycleFenceEngineManager manager =
        new DeferredLifecycleFenceEngineManager(List.of(current, target, laterTarget));
    AtomicReference<Throwable> completionFailure = new AtomicReference<>();
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.menu = allocate(SilentUpdateMenu.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      current.started = target.started = laterTarget.started = true;
      current.isLoaded = target.isLoaded = laterTarget.isLoaded = true;
      target.isCheckingName = laterTarget.isCheckingName = false;
      Lizzie.setPrimaryEngine(current);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);
      manager.synchronization.run();
      manager.afterSync.run();
      Thread completion =
          new Thread(
              () -> {
                try {
                  target.completeBoardSynchronization();
                } catch (Throwable failure) {
                  completionFailure.set(failure);
                }
              },
              "controlled-ordinary-terminal-close");
      completion.setDaemon(true);
      completion.start();
      assertTrue(target.detachEntered.await(2, TimeUnit.SECONDS));

      manager.switchEngine(2, true);

      assertSame(target, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertTrue(laterTarget.commands.isEmpty());
      assertNotNull(activeEngineSwitchTransaction(manager));
      target.allowDetach.countDown();
      completion.join(2000L);

      assertFalse(completion.isAlive());
      assertNull(completionFailure.get());
      assertSame(target, Lizzie.leelaz);
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNull(activeEngineSwitchTransaction(manager));
      assertEquals(1, manager.conflictCount);
    } finally {
      target.allowDetach.countDown();
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void staleSynchronizationFailureCannotUnloadOrStopReboundSameObjectIncarnation()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RebindingSynchronizationFailureLeelaz target =
        new RebindingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      current.started = target.started = true;
      current.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      Lizzie.setPrimaryEngine(current);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);
      assertNotNull(manager.synchronizationWork);
      target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      target.isLoaded = true;
      target.started = true;
      target.isCheckingName = false;
      target.failSynchronization = true;
      manager.synchronizationWork.run();

      assertTrue(target.isLoaded);
      assertTrue(target.started);
      assertNull(Lizzie.leelaz);
      assertEquals(0, target.normalQuitCount);
      assertEquals(1, target.notPonderingCount);
      assertNull(manager.failurePresentation);
      assertEquals(0, manager.failureCount);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.IDLE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertNull(activeEngineSwitchTransaction(manager));
    } finally {
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void queuedSynchronizationFailureDialogRevalidatesManagerBoardCatalogAndPrimary()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingSwitchLeelaz primary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    RebindingSynchronizationFailureLeelaz target =
        new RebindingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(
            new ArrayList<>(List.of(primary, secondary, target)));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      primary.started = secondary.started = target.started = true;
      primary.isLoaded = secondary.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      target.installFreshCommandOutputForTest(targetTransport);
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      manager.switchEngine(2, false);
      target.isCheckingName = false;
      target.failResponseFreshening = true;
      manager.synchronizationWork.run();
      assertNotNull(manager.failurePresentation);
      assertEquals(1, target.responseFresheningCount);

      manager.engineList.set(2, new RecordingSwitchLeelaz());
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = new EngineManager(List.of(primary));
      Lizzie.setPrimaryEngine(new RecordingSwitchLeelaz());
      Lizzie.engineStartupStatus.checking("engine.starting", "new authority");
      manager.failurePresentation.run();

      assertEquals(0, manager.failureCount);
    } finally {
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void synchronizationFailureUsesThePrimaryGenerationFrozenAtProvisionalInstall()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz away = new RecordingSwitchLeelaz();
    RebindingSynchronizationFailureLeelaz target =
        new RebindingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      current.started = away.started = target.started = true;
      current.isLoaded = away.isLoaded = target.isLoaded = true;
      target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      Lizzie.setPrimaryEngine(current);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);
      assertNotNull(manager.synchronizationWork);
      target.started = true;
      target.isLoaded = true;
      target.isCheckingName = false;
      Lizzie.setPrimaryEngine(away);
      Lizzie.setPrimaryEngine(target);
      target.failSynchronization = true;
      manager.synchronizationWork.run();

      assertTrue(target.isLoaded);
      assertTrue(target.started);
      assertSame(target, Lizzie.leelaz);
      assertEquals(0, target.normalQuitCount);
      assertEquals(1, target.notPonderingCount);
      assertEquals(0, manager.failureCount);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.IDLE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNull(activeEngineSwitchTransaction(manager));
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void synchronizationFailureActionRunsAfterTheExactEndpointLockIsReleased()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    LockOrderingSynchronizationFailureLeelaz target =
        new LockOrderingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(List.of(current, target));
    Thread synchronizationWorker = null;
    Thread selectionContender = null;
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      current.started = target.started = true;
      current.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      target.selectionLock = engineSelectionStateLock();
      target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      Object targetIncarnation = target.captureEngineIncarnationFence();
      Lizzie.setPrimaryEngine(current);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);
      assertNotNull(manager.synchronizationWork);
      target.isCheckingName = false;
      int notPonderingBeforeSynchronization = target.notPonderingCount;
      target.failSynchronization = true;
      synchronizationWorker =
          new Thread(manager.synchronizationWork, "synchronization-failure-lock-order");
      synchronizationWorker.setDaemon(true);
      synchronizationWorker.start();
      assertTrue(target.failureActionEntered.await(2, TimeUnit.SECONDS));

      CountDownLatch selectionHeld = new CountDownLatch(1);
      selectionContender =
          new Thread(
              () -> {
                synchronized (target.selectionLock) {
                  selectionHeld.countDown();
                  target.runIfEngineIncarnationFenceUnchanged(targetIncarnation, () -> {});
                }
              },
              "synchronization-failure-selection-contender");
      selectionContender.setDaemon(true);
      selectionContender.start();
      assertTrue(selectionHeld.await(2, TimeUnit.SECONDS));
      target.allowFailureActionSelection.countDown();

      synchronizationWorker.join(2_000L);
      selectionContender.join(2_000L);
      assertFalse(synchronizationWorker.isAlive());
      assertFalse(selectionContender.isAlive());
      assertEquals(0L, target.failureActionCompleted.getCount());
      assertTrue(
          ((RebindingSynchronizationFailureLeelaz) target)
              .twoNotPonderingCalls.await(2, TimeUnit.SECONDS));
      assertEquals(notPonderingBeforeSynchronization + 2, target.notPonderingCount);
      assertNull(activeEngineSwitchTransaction(manager));
    } finally {
      target.allowFailureActionSelection.countDown();
      if (synchronizationWorker != null) synchronizationWorker.join(2_000L);
      if (selectionContender != null) selectionContender.join(2_000L);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void boardSupersessionPublishesNeutralStateForTheAbandonedSwitchToken()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    RecordingSwitchLeelaz current = new RecordingSwitchLeelaz();
    RebindingSynchronizationFailureLeelaz target =
        new RebindingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(List.of(current, target));
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Normal;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      current.started = target.started = true;
      current.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      target.installFreshCommandOutputForTest(targetTransport);
      Lizzie.setPrimaryEngine(current);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.switchEngine(1, true);
      assertNotNull(manager.synchronizationWork);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.SWITCHING,
          manager.engineSwitchUiSnapshot(true).phase());
      int publicationsBeforeFailure = manager.uiPublicationCount;
      Lizzie.board = preparedRestoreBoard();
      target.isCheckingName = false;
      target.failSynchronization = true;
      manager.synchronizationWork.run();

      assertEquals(
          EngineManager.EngineSwitchUiPhase.IDLE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNotNull(manager.lastPublishedSnapshot);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.IDLE, manager.lastPublishedSnapshot.phase());
      assertTrue(manager.uiPublicationCount > publicationsBeforeFailure);
      assertTrue(target.started);
      assertTrue(target.isLoaded);
      assertEquals(0, target.normalQuitCount);
      assertNull(Lizzie.leelaz);
      assertNull(activeEngineSwitchTransaction(manager));
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void synchronizationFailureSettlementPinsTheExactIncarnationUntilRollbackCompletes()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingSwitchLeelaz primary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    BlockingFailureActionSynchronizationLeelaz target =
        new BlockingFailureActionSynchronizationLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(
            new ArrayList<>(List.of(primary, secondary, target)));
    Thread synchronizationWorker = null;
    Thread rebindWorker = null;
    CountDownLatch rebindStarted = new CountDownLatch(1);
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      primary.started = secondary.started = target.started = true;
      primary.isLoaded = secondary.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      target.installFreshCommandOutputForTest(targetTransport);
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      manager.switchEngine(2, false);
      target.isCheckingName = false;
      ((RebindingSynchronizationFailureLeelaz) target).failResponseFreshening = true;
      synchronizationWorker =
          new Thread(manager.synchronizationWork, "synchronization-failure-settlement-lease");
      synchronizationWorker.setDaemon(true);
      synchronizationWorker.start();
      assertTrue(target.failureActionEntered.await(2, TimeUnit.SECONDS));

      rebindWorker =
          new Thread(
              () -> {
                rebindStarted.countDown();
                try {
                  target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
                  target.started = true;
                  target.isLoaded = true;
                  target.isCheckingName = false;
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                } finally {
                  rebindCompleted.countDown();
                }
              },
              "synchronization-failure-rebind-after-commit");
      rebindWorker.setDaemon(true);
      rebindWorker.start();
      assertTrue(rebindStarted.await(2, TimeUnit.SECONDS));
      assertTrue(awaitThreadState(rebindWorker, Thread.State.WAITING, 2_000L));
      assertFalse(
          rebindCompleted.await(10, TimeUnit.MILLISECONDS),
          "the exact failure settlement must gate a replacement incarnation");

      target.allowFailureAction.countDown();
      synchronizationWorker.join(2_000L);
      assertFalse(synchronizationWorker.isAlive());
      assertTrue(rebindCompleted.await(2, TimeUnit.SECONDS));
      rebindWorker.join(2_000L);

      assertNull(rebindFailure.get());
      assertEquals(0L, target.failureActionCompleted.getCount());
      assertSame(secondary, Lizzie.leelaz2);
      assertTrue(target.started);
      assertTrue(target.isLoaded);
      assertNotNull(manager.failurePresentation);
      manager.failurePresentation.run();
      assertEquals(0, manager.failureCount);
      assertNull(activeEngineSwitchTransaction(manager));
    } finally {
      target.allowFailureAction.countDown();
      if (synchronizationWorker != null) synchronizationWorker.join(2_000L);
      if (rebindWorker != null) rebindWorker.join(2_000L);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  @Test
  void synchronizationFailurePresentationPinsTheExactIncarnationWhileShown()
      throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingSwitchLeelaz primary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    RebindingSynchronizationFailureLeelaz target =
        new RebindingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(
            new ArrayList<>(List.of(primary, secondary, target)));
    Thread presentationWorker = null;
    Thread rebindWorker = null;
    CountDownLatch rebindStarted = new CountDownLatch(1);
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      primary.started = secondary.started = target.started = true;
      primary.isLoaded = secondary.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      target.installFreshCommandOutputForTest(targetTransport);
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      manager.switchEngine(2, false);
      target.isCheckingName = false;
      target.failResponseFreshening = true;
      manager.synchronizationWork.run();
      assertNotNull(manager.failurePresentation);
      manager.blockFailurePresentation = true;

      presentationWorker =
          new Thread(manager.failurePresentation, "synchronization-failure-presentation-lease");
      presentationWorker.setDaemon(true);
      presentationWorker.start();
      assertTrue(manager.failurePresentationEntered.await(2, TimeUnit.SECONDS));
      rebindWorker =
          new Thread(
              () -> {
                rebindStarted.countDown();
                try {
                  target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
                  target.started = true;
                  target.isLoaded = true;
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                } finally {
                  rebindCompleted.countDown();
                }
              },
              "synchronization-failure-rebind-during-presentation");
      rebindWorker.setDaemon(true);
      rebindWorker.start();
      assertTrue(rebindStarted.await(2, TimeUnit.SECONDS));
      assertTrue(awaitThreadState(rebindWorker, Thread.State.WAITING, 2_000L));
      assertFalse(
          rebindCompleted.await(10, TimeUnit.MILLISECONDS),
          "a replacement incarnation must wait for the claimed failure presentation");

      manager.allowFailurePresentation.countDown();
      presentationWorker.join(2_000L);
      assertFalse(presentationWorker.isAlive());
      assertTrue(rebindCompleted.await(2, TimeUnit.SECONDS));
      rebindWorker.join(2_000L);

      assertNull(rebindFailure.get());
      assertEquals(1, manager.failureCount);
      assertTrue(target.started);
      assertTrue(target.isLoaded);
      assertSame(secondary, Lizzie.leelaz2);
    } finally {
      manager.allowFailurePresentation.countDown();
      if (presentationWorker != null) presentationWorker.join(2_000L);
      if (rebindWorker != null) rebindWorker.join(2_000L);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  @Test
  void synchronizationFailurePresentationPinsBoardAuthorityWhileShown()
      throws Exception {
    assertSynchronizationFailurePresentationPinsAuthority(
        PresentationAuthoritySupersession.BOARD);
  }

  @Test
  void synchronizationFailurePresentationPinsManagerAuthorityWhileShown()
      throws Exception {
    assertSynchronizationFailurePresentationPinsAuthority(
        PresentationAuthoritySupersession.MANAGER);
  }

  @Test
  void synchronizationFailurePresentationPinsPrimaryAuthorityWhileShown()
      throws Exception {
    assertSynchronizationFailurePresentationPinsAuthority(
        PresentationAuthoritySupersession.PRIMARY);
  }

  private static void assertSynchronizationFailurePresentationPinsAuthority(
      PresentationAuthoritySupersession supersession) throws Exception {
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousEngineNo2 = EngineManager.currentEngineNo2;
    RecordingSwitchLeelaz primary = new RecordingSwitchLeelaz();
    RecordingSwitchLeelaz secondary = new RecordingSwitchLeelaz();
    RebindingSynchronizationFailureLeelaz target =
        new RebindingSynchronizationFailureLeelaz();
    DeferredSynchronizationWorkEngineManager manager =
        new DeferredSynchronizationWorkEngineManager(
            new ArrayList<>(List.of(primary, secondary, target)));
    Board replacementBoard = preparedRestoreBoard();
    RecordingSwitchLeelaz replacementPrimary = new RecordingSwitchLeelaz();
    EngineManager replacementManager = new EngineManager(List.of(primary));
    Thread presentationWorker = null;
    Thread authorityWriter = null;
    Thread rebindWorker = null;
    AtomicReference<Throwable> presentationFailure = new AtomicReference<>();
    AtomicReference<Throwable> authorityFailure = new AtomicReference<>();
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    CountDownLatch authorityWriterStarted = new CountDownLatch(1);
    CountDownLatch authorityWriterCompleted = new CountDownLatch(1);
    CountDownLatch rebindCompleted = new CountDownLatch(1);
    try {
      Config config = allocate(Config.class);
      config.fastChange = true;
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      LizzieFrame.toolbar = allocate(SilentSwitchToolbar.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.engineManager = manager;
      primary.started = secondary.started = target.started = true;
      primary.isLoaded = secondary.isLoaded = target.isLoaded = true;
      target.isCheckingName = false;
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      target.installFreshCommandOutputForTest(targetTransport);
      Lizzie.setPrimaryEngine(primary);
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;

      manager.switchEngine(2, false);
      target.isCheckingName = false;
      target.failResponseFreshening = true;
      manager.synchronizationWork.run();
      assertNotNull(manager.failurePresentation);
      manager.blockFailurePresentation = true;

      presentationWorker =
          new Thread(
              () -> {
                try {
                  manager.failurePresentation.run();
                } catch (Throwable failure) {
                  presentationFailure.set(failure);
                }
              },
              "synchronization-failure-post-claim-authority-" + supersession);
      presentationWorker.setDaemon(true);
      presentationWorker.start();
      assertTrue(manager.failurePresentationEntered.await(2, TimeUnit.SECONDS));

      authorityWriter =
          new Thread(
              () -> {
                authorityWriterStarted.countDown();
                try {
                  switch (supersession) {
                    case BOARD:
                      Lizzie.setBoard(replacementBoard);
                      break;
                    case MANAGER:
                      Lizzie.setEngineManager(replacementManager);
                      break;
                    case PRIMARY:
                      Lizzie.setPrimaryEngine(replacementPrimary);
                      break;
                  }
                } catch (Throwable failure) {
                  authorityFailure.set(failure);
                } finally {
                  authorityWriterCompleted.countDown();
                }
              },
              "synchronization-failure-authority-writer-" + supersession);
      authorityWriter.setDaemon(true);
      authorityWriter.start();
      assertTrue(authorityWriterStarted.await(2, TimeUnit.SECONDS));
      assertTrue(awaitThreadState(authorityWriter, Thread.State.WAITING, 2_000L));
      assertFalse(
          authorityWriterCompleted.await(10, TimeUnit.MILLISECONDS),
          "authority mutation must wait for the active failure presentation");

      manager.allowFailurePresentation.countDown();
      presentationWorker.join(2_000L);

      assertFalse(presentationWorker.isAlive());
      assertNull(presentationFailure.get());
      assertEquals(1, manager.failureCount);
      assertTrue(authorityWriterCompleted.await(2, TimeUnit.SECONDS));
      authorityWriter.join(2_000L);
      assertFalse(authorityWriter.isAlive());
      assertNull(authorityFailure.get());

      rebindWorker =
          new Thread(
              () -> {
                try {
                  target.installFreshCommandOutputForTest(new ByteArrayOutputStream());
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                } finally {
                  rebindCompleted.countDown();
                }
              },
              "synchronization-failure-post-claim-authority-rebind-" + supersession);
      rebindWorker.setDaemon(true);
      rebindWorker.start();
      assertTrue(rebindCompleted.await(2, TimeUnit.SECONDS));
      rebindWorker.join(2_000L);
      assertFalse(rebindWorker.isAlive());
      assertNull(rebindFailure.get());
    } finally {
      manager.allowFailurePresentation.countDown();
      if (presentationWorker != null) presentationWorker.join(2_000L);
      if (authorityWriter != null) authorityWriter.join(2_000L);
      if (rebindWorker != null) rebindWorker.join(2_000L);
      Lizzie.engineManager = previousManager;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
    }
  }

  private enum PresentationAuthoritySupersession {
    BOARD,
    MANAGER,
    PRIMARY
  }

  @Test
  void engineIncarnationLeaseRejectsOwnerRebindWithoutStrandingLaterRebinds()
      throws Exception {
    RecordingSwitchLeelaz engine = new RecordingSwitchLeelaz();
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    Object incarnation = engine.captureEngineIncarnationFence();
    Leelaz.EngineIncarnationLease lease =
        engine.claimEngineIncarnationLease(incarnation);
    assertNotNull(lease);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> engine.installFreshCommandOutputForTest(new ByteArrayOutputStream()));
      assertSame(incarnation, engine.captureEngineIncarnationFence());
    } finally {
      lease.close();
    }

    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    assertNotSame(incarnation, engine.captureEngineIncarnationFence());
  }

  private static void assertPublishedTerminalStateFailsImmediately(
      Consumer<ControlledReadinessLeelaz> publishTerminalState, String stateDescription)
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    EngineManager previousManager = Lizzie.engineManager;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(500L);
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 5000L);
    try {
      Lizzie.leelaz = current;
      Lizzie.engineManager = manager;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      publishTerminalState.accept(target);
      target.allowFirstLoadedRead.countDown();

      assertTrue(
          manager.completed.await(500, TimeUnit.MILLISECONDS),
          stateDescription + " should fail before the five-second startup timeout");
      target.releaseLoadedReads();
      assertTrue(manager.failurePresented.await(1, TimeUnit.SECONDS));
      assertNull(Lizzie.leelaz);
      assertTrue(EngineManager.isEmpty);
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
      Lizzie.engineManager = previousManager;
    }
  }

  private static ControlledReadinessLeelaz unavailableControlledEngine(long tuningTimeoutMillis)
      throws Exception {
    ControlledReadinessLeelaz engine = new ControlledReadinessLeelaz(tuningTimeoutMillis);
    engine.started = true;
    engine.isLoaded = false;
    engine.isCheckingName = true;
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    return engine;
  }

  private static Leelaz unavailableStartedEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = false;
    engine.isCheckingName = true;
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    return engine;
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

  private static void invokeCloseBundledStartupDialog(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("closeBundledStartupDialog");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for controlled test latch");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
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

  private static Runnable invokeQuarantineStaleInitialEngineIncarnation(
      Leelaz engine, Object incarnation, long token) throws Exception {
    Method method =
        EngineManager.class.getDeclaredMethod(
            "quarantineStaleInitialEngineIncarnation", Leelaz.class, Object.class, long.class);
    method.setAccessible(true);
    return (Runnable) method.invoke(null, engine, incarnation, token);
  }

  private static void invokeDispatchFailedEngineStop(Runnable stop, long token) throws Exception {
    Method method =
        EngineManager.class.getDeclaredMethod("dispatchFailedEngineStop", Runnable.class, long.class);
    method.setAccessible(true);
    method.invoke(null, stop, token);
  }

  @SuppressWarnings("rawtypes")
  private static boolean isFailedEngineQuarantined(Leelaz engine) throws Exception {
    Field field = EngineManager.class.getDeclaredField("FAILED_ENGINE_QUARANTINES");
    field.setAccessible(true);
    Map quarantines = (Map) field.get(null);
    synchronized (engineSelectionStateLock()) {
      return quarantines.containsKey(engine);
    }
  }

  private static Object engineSelectionStateLock() throws Exception {
    Field field = EngineManager.class.getDeclaredField("ENGINE_SELECTION_STATE_LOCK");
    field.setAccessible(true);
    return field.get(null);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object installForegroundSampleSentinel(Object owner) throws Exception {
    Field field = AnalysisResourceCoordinator.class.getDeclaredField("FOREGROUND_SAMPLES");
    field.setAccessible(true);
    Map samples = (Map) field.get(null);
    Object sentinel = new Object();
    synchronized (samples) {
      samples.put(owner, sentinel);
    }
    return sentinel;
  }

  @SuppressWarnings("rawtypes")
  private static Object foregroundSample(Object owner) throws Exception {
    Field field = AnalysisResourceCoordinator.class.getDeclaredField("FOREGROUND_SAMPLES");
    field.setAccessible(true);
    Map samples = (Map) field.get(null);
    synchronized (samples) {
      return samples.get(owner);
    }
  }

  @SuppressWarnings("rawtypes")
  private static void removeForegroundSample(Object owner) throws Exception {
    Field field = AnalysisResourceCoordinator.class.getDeclaredField("FOREGROUND_SAMPLES");
    field.setAccessible(true);
    Map samples = (Map) field.get(null);
    synchronized (samples) {
      samples.remove(owner);
    }
  }

  private static void restoreStartupStatus(EngineStartupStatus.Snapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    switch (snapshot.state) {
      case CHECKING:
        Lizzie.engineStartupStatus.checking(snapshot.messageKey, snapshot.fallback);
        break;
      case NEEDS_REPAIR:
        Lizzie.engineStartupStatus.needsRepair(
            snapshot.messageKey, snapshot.fallback, snapshot.detail);
        break;
      case START_FAILED:
        Lizzie.engineStartupStatus.failed(
            snapshot.messageKey, snapshot.fallback, snapshot.detail);
        break;
      case READY:
      default:
        Lizzie.engineStartupStatus.ready();
        break;
    }
  }

  private static void setField(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  /**
   * Installs the same provisional selection and exact runtime authority as the production switch
   * body before a readiness-only test substitutes its controlled synchronization worker.
   */
  private static void installPreparedSynchronizationFailureAuthority(
      EngineManager manager,
      EngineManager.PreparedEngineSwitch preparedSwitch,
      Leelaz target,
      boolean main) {
    if (manager == null || target == null) {
      throw new AssertionError("controlled switch authority is unavailable");
    }
    try {
      Object transaction;
      if (preparedSwitch == null) {
        Field transactionField =
            EngineManager.class.getDeclaredField("engineSwitchTransaction");
        transactionField.setAccessible(true);
        transaction = ((AtomicReference<?>) transactionField.get(manager)).get();
      } else {
        transaction = getField(preparedSwitch, "engineSwitchTransaction");
      }
      if (transaction == null) {
        throw new AssertionError("controlled switch transaction is unavailable");
      }
      Leelaz previous =
          preparedSwitch == null
              ? (Leelaz) getField(transaction, "previousEngine")
              : (Leelaz) getField(preparedSwitch, "previousEngine");
      Method install =
          EngineManager.class.getDeclaredMethod(
              "installProvisionalEngineSelection",
              boolean.class,
              Leelaz.class,
              Leelaz.class,
              transaction.getClass());
      install.setAccessible(true);
      if (!(Boolean) install.invoke(null, main, previous, target, transaction)) {
        throw new AssertionError("controlled provisional selection was rejected");
      }
      Object incarnation = target.captureEngineIncarnationFence();
      if (incarnation == null) {
        throw new AssertionError("controlled target has no exact runtime binding");
      }
      setField(transaction, "targetInstalled", true);
      setField(transaction, "targetEngineIncarnation", incarnation);
      setField(transaction, "targetEngineIncarnationCaptured", true);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
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

  private static Object installLocalReaderBinding(
      Leelaz engine,
      Process process,
      ScheduledExecutorService stdout,
      ScheduledExecutorService stderr,
      BufferedOutputStream output)
      throws Exception {
    engine.useRemoteCompute = false;
    engine.useJavaSSH = false;
    Class<?> bindingType = Class.forName(Leelaz.class.getName() + "$ReaderStreamBinding");
    java.lang.reflect.Constructor<?> constructor =
        bindingType.getDeclaredConstructor(
            java.io.BufferedReader.class,
            java.io.BufferedReader.class,
            BufferedOutputStream.class,
            Process.class,
            EngineTransport.class,
            SSHController.class,
            long.class,
            long.class);
    constructor.setAccessible(true);
    Object binding =
        constructor.newInstance(
            null,
            null,
            output,
            process,
            null,
            null,
            2L,
            Lizzie.capturePrimaryEngineGeneration(engine));
    setField(binding, "stdoutExecutor", stdout);
    setField(binding, "stderrExecutor", stderr);
    setLeelazField(engine, "process", process);
    setLeelazField(engine, "readerStreamBinding", binding);
    setLeelazField(engine, "outputStream", output);
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
    java.lang.reflect.Constructor<?> constructor =
        bindingType.getDeclaredConstructor(
            java.io.BufferedReader.class,
            java.io.BufferedReader.class,
            BufferedOutputStream.class,
            Process.class,
            EngineTransport.class,
            SSHController.class,
            long.class,
            long.class);
    constructor.setAccessible(true);
    Object binding =
        constructor.newInstance(null, null, output, null, null, ssh, incarnation, -1L);
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

  private static boolean awaitLockWaiter(
      java.util.concurrent.locks.ReentrantLock lock, Thread thread, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (lock.hasQueuedThread(thread)) return true;
      Thread.sleep(5L);
    }
    return lock.hasQueuedThread(thread);
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

  private static boolean forceStartupProfileSaveFailed(boolean value) throws Exception {
    Field field = Lizzie.class.getDeclaredField("startupProfileSaveFailed");
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


  private static void rebindReader(Leelaz engine) {
    rebindReader(engine, new ByteArrayOutputStream());
  }

  private static void rebindReader(Leelaz engine, ByteArrayOutputStream output) {
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
          output,
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

  private static void assertLifecycleAvailable(Leelaz engine) {
    assertFalse(engine.hasExclusiveGtpWorkInProgress());
    assertFalse(engine.hasExclusiveGtpLifecycleTransitionForTest());
    Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
    assertNotNull(reservation);
    reservation.close();
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

  private static Object activeEngineSwitchTransaction(EngineManager manager) throws Exception {
    Field field = EngineManager.class.getDeclaredField("engineSwitchTransaction");
    field.setAccessible(true);
    return ((AtomicReference<?>) field.get(manager)).get();
  }

  private static Object activeFailedRollbackRecovery(EngineManager manager) throws Exception {
    Field field = EngineManager.class.getDeclaredField("failedRollbackRecovery");
    field.setAccessible(true);
    return ((AtomicReference<?>) field.get(manager)).get();
  }

  private static Object installSyntheticFailedRollbackRecovery(
      EngineManager manager, Leelaz engine, Board board) throws Exception {
    Field trackerField = EngineManager.class.getDeclaredField("engineSwitchUiTracker");
    trackerField.setAccessible(true);
    EngineManager.EngineSwitchUiTracker tracker =
        (EngineManager.EngineSwitchUiTracker) trackerField.get(manager);
    EngineManager.EngineSwitchUiSnapshot switching =
        tracker.begin(true, -1, "", null, 0, "rollback", engine);
    EngineManager.EngineSwitchUiSnapshot failed =
        tracker.fail(switching.token(), true, "controlled rollback").orElseThrow();
    Class<?> recoveryType =
        java.util.Arrays.stream(EngineManager.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("FailedRollbackRecovery"))
            .findFirst()
            .orElseThrow();
    java.lang.reflect.Constructor<?> constructor = recoveryType.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object recovery =
        constructor.newInstance(failed, engine, 0, board, null, false, null);
    Field recoveryField = EngineManager.class.getDeclaredField("failedRollbackRecovery");
    recoveryField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<Object> activeRecovery =
        (AtomicReference<Object>) recoveryField.get(manager);
    activeRecovery.set(recovery);
    return recovery;
  }

  private static void invokeFailedRollbackRecoveryDispatch(
      EngineManager manager, Object recovery) {
    try {
      Method method =
          EngineManager.class.getDeclaredMethod(
              "dispatchFailedRollbackRecovery", recovery.getClass());
      method.setAccessible(true);
      method.invoke(manager, recovery);
    } catch (java.lang.reflect.InvocationTargetException invocationFailure) {
      Throwable failure = invocationFailure.getCause();
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      throw new AssertionError(failure);
    } catch (ReflectiveOperationException reflectionFailure) {
      throw new AssertionError(reflectionFailure);
    }
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

  private static class DeferredSynchronizationWorkEngineManager extends EngineManager {
    private Runnable synchronizationWork;
    private Runnable failurePresentation;
    private int failureCount;
    private int uiPublicationCount;
    private EngineSwitchUiSnapshot lastPublishedSnapshot;
    private boolean blockFailurePresentation;
    private final CountDownLatch failurePresentationEntered = new CountDownLatch(1);
    private final CountDownLatch allowFailurePresentation = new CountDownLatch(1);

    private DeferredSynchronizationWorkEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected Thread createEngineSynchronizationThread(Runnable synchronization) {
      synchronizationWork = synchronization;
      return new Thread(synchronization, "controlled-deferred-synchronization");
    }

    @Override
    protected void configureEngineSynchronizationThread(Thread worker) {}

    @Override
    protected void startEngineSynchronizationThread(Thread worker) {}

    @Override
    protected void enqueueEngineSynchronizationFailurePresentation(Runnable presentation) {
      failurePresentation = presentation;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      if (blockFailurePresentation) {
        failurePresentationEntered.countDown();
        awaitLatch(allowFailurePresentation);
      }
      failureCount++;
    }

    @Override
    protected void publishEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {
      uiPublicationCount++;
      lastPublishedSnapshot = snapshot;
    }
  }

  private static final class StartThenThrowSynchronizationEngineManager extends EngineManager {
    private final AssertionError startFailure =
        new AssertionError("controlled start-after-worker synchronization failure");
    private final CountDownLatch workerClaimed = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private int startCount;

    private StartThenThrowSynchronizationEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    private void synchronizeForTest(
        Leelaz engine, Runnable synchronization, Runnable afterSync) {
      synchronizeEngineWhenReady(engine, synchronization, afterSync);
    }

    @Override
    protected void startEngineSynchronizationThread(Thread worker) {
      startCount++;
      worker.start();
      awaitLatch(workerClaimed);
      throw startFailure;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {}
  }

  private static final class StartThenThrowRollbackEngineManager extends EngineManager {
    private final AssertionError startFailure =
        new AssertionError("controlled start-after-worker rollback failure");
    private final BlockingRollbackRecoveryLeelaz engine;
    private final CountDownLatch rollbackFinished = new CountDownLatch(1);
    private int startCount;
    private int failureCount;
    private int settlementCount;

    private StartThenThrowRollbackEngineManager(
        List<Leelaz> engines, BlockingRollbackRecoveryLeelaz engine) {
      super(engines);
      this.engine = engine;
    }

    @Override
    protected void startFailedRollbackRecoveryWorker(Thread worker) {
      startCount++;
      worker.start();
      awaitLatch(engine.reservationEntered);
      throw startFailure;
    }

    @Override
    protected Thread createFailedRollbackRecoveryWorker(Runnable work, String name) {
      return super.createFailedRollbackRecoveryWorker(
          () -> {
            try {
              work.run();
            } finally {
              rollbackFinished.countDown();
            }
          },
          name);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }

    @Override
    protected void publishEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {
      settlementCount++;
    }
  }

  private static final class DeferredLifecycleFenceEngineManager extends EngineManager {
    private Runnable synchronization;
    private Runnable afterSync;
    private int conflictCount;

    private DeferredLifecycleFenceEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void synchronizeEngineWhenReady(
        Leelaz engine, Runnable synchronization, Runnable afterSync) {
      this.synchronization = synchronization;
      this.afterSync = afterSync;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {}

    @Override
    protected void showForegroundEngineLeaseInUse() {
      conflictCount++;
    }

    @Override
    protected void publishEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {}
  }

  private static final class FinalInitializationFailureEngineManager extends EngineManager {
    private int failureCount;

    private FinalInitializationFailureEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class FailingFinalInitializationLeelaz extends Leelaz {
    private int primaryInitializationCount;
    private int secondaryInitializationCount;
    private int lifecycleFailureCount;

    private FailingFinalInitializationLeelaz() throws Exception {
      super("");
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      onSuccess.run();
    }

    @Override
    void initializeAfterExplicitRestartBoardSynchronization(boolean resumePonder) {
      primaryInitializationCount++;
      throw new IllegalStateException("controlled primary final initialization failure");
    }

    @Override
    void completeSecondaryExplicitRestartBoardSynchronization() {
      secondaryInitializationCount++;
      throw new AssertionError("controlled secondary final initialization failure");
    }

    @Override
    void markLifecycleBoardSynchronizationFailed(
        String detail, boolean preserveUnrestoredState) {
      lifecycleFailureCount++;
    }
  }

  private static final class SetupFailureFenceLeelaz extends Leelaz {
    private final AssertionError setupFailure;
    private int lifecycleFailureCount;

    private SetupFailureFenceLeelaz(AssertionError setupFailure) throws Exception {
      super("");
      this.setupFailure = setupFailure;
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      throw setupFailure;
    }

    @Override
    void markLifecycleBoardSynchronizationFailed(
        String detail, boolean preserveUnrestoredState) {
      lifecycleFailureCount++;
    }
  }

  private static final class SetupFailureFenceEngineManager extends EngineManager {
    private int failureCount;

    private SetupFailureFenceEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }

    @Override
    protected void publishEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {}
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
      this.afterSync = target.withCurrentRestartBootstrapReceipt(afterSync);
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
    private java.io.OutputStream restartOutput;

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
      if (restartOutput == null) rebindReader(this);
      else installFreshCommandOutputForTest(restartOutput);
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

  private static class QuietExitLeelaz extends Leelaz {
    protected QuietExitLeelaz() throws Exception {
      super("");
    }

    @Override
    public void leela0110StopPonder() {}
  }

  private static final class PresentationLockOrderLeelaz extends QuietExitLeelaz {
    private final Object selectionLock;
    private final AtomicBoolean selectionLockHeldDuringLeaseClaim = new AtomicBoolean();
    private final AtomicInteger runtimeUiLeaseChecks = new AtomicInteger();
    private final AtomicInteger presentationLeaseChecks = new AtomicInteger();

    private PresentationLockOrderLeelaz(Object selectionLock) throws Exception {
      this.selectionLock = selectionLock;
    }

    @Override
    EngineRuntimeUiLease claimEngineRuntimeUiLeaseIfCurrent(Object expectedIncarnation) {
      runtimeUiLeaseChecks.incrementAndGet();
      selectionLockHeldDuringLeaseClaim.compareAndSet(
          false, Thread.holdsLock(selectionLock));
      return super.claimEngineRuntimeUiLeaseIfCurrent(expectedIncarnation);
    }

    @Override
    EngineRuntimeUiLease claimEnginePresentationLeaseIfCurrent(
        Object expectedIncarnation, boolean requireParserReady) {
      presentationLeaseChecks.incrementAndGet();
      selectionLockHeldDuringLeaseClaim.compareAndSet(
          false, Thread.holdsLock(selectionLock));
      return super.claimEnginePresentationLeaseIfCurrent(
          expectedIncarnation, requireParserReady);
    }
  }

  private static final class PartialPublishedStartLeelaz extends QuietExitLeelaz {
    private final RecordingTransport failedTransport = new RecordingTransport(false);
    private final AssertionError startFailure =
        new AssertionError("controlled failure after reader publication");

    private PartialPublishedStartLeelaz() throws Exception {
      super();
    }

    @Override
    public void startEngine(int index) {
      try {
        setLeelazField(this, "remoteTransport", failedTransport);
      } catch (Exception reflectionFailure) {
        throw new AssertionError(reflectionFailure);
      }
      useRemoteCompute = true;
      installFreshCommandOutputForTest(new ByteArrayOutputStream());
      started = true;
      isLoaded = true;
      throw startFailure;
    }
  }

  private static final class EmptyReturningStartLeelaz extends QuietExitLeelaz {
    private EmptyReturningStartLeelaz() throws Exception {
      super();
    }

    @Override
    public void startEngine(int index) {
      started = false;
      isLoaded = false;
    }
  }

  private static final class FailingReadyCommandLeelaz extends QuietExitLeelaz {
    private final AssertionError commandFailure =
        new AssertionError("controlled post-restore engine command failure");

    private FailingReadyCommandLeelaz() throws Exception {
      super();
    }

    @Override
    public void notPondering() {
      throw commandFailure;
    }
  }

  private static final class FailingSaveConfig extends Config {
    private FailingSaveConfig() throws IOException {
      super();
    }

    @Override
    public void save() throws IOException {
      throw new IOException("controlled profile persistence retry failure");
    }
  }

  private static final class SwitchingPrimaryDuringInitializationLeelaz extends QuietExitLeelaz {
    private final Leelaz replacement;

    private SwitchingPrimaryDuringInitializationLeelaz(Leelaz replacement) throws Exception {
      super();
      this.replacement = replacement;
    }

    @Override
    public void notPondering() {
      Lizzie.setPrimaryEngine(replacement);
    }

    @Override
    public void setResponseUpToDate() {}
  }

  private static class PrestartedAttemptAcquireLeelaz extends QuietExitLeelaz {
    private final RecordingTransport transport;
    private boolean seedAttempt = true;

    private PrestartedAttemptAcquireLeelaz(Throwable cleanupFailure) throws Exception {
      this(false, cleanupFailure);
    }

    private PrestartedAttemptAcquireLeelaz(boolean blockClose, Throwable cleanupFailure)
        throws Exception {
      super();
      transport = new RecordingTransport(blockClose, cleanupFailure);
    }

    @Override
    UpdateEngineStartAttempt beginUpdateEngineStartAttempt() {
      UpdateEngineStartAttempt attempt = super.beginUpdateEngineStartAttempt();
      if (seedAttempt) {
        seedAttempt = false;
        try {
          attempt.startEngine(0);
        } catch (IOException startFailure) {
          throw new AssertionError(startFailure);
        }
      }
      return attempt;
    }

    @Override
    public void startEngine(int index) {
      try {
        setLeelazField(this, "remoteTransport", transport);
      } catch (Exception reflectionFailure) {
        throw new AssertionError(reflectionFailure);
      }
      useRemoteCompute = true;
      installFreshCommandOutputForTest(new ByteArrayOutputStream());
      started = true;
      isLoaded = true;
    }
  }

  private static final class RecordingLifecycleFailureLeelaz
      extends PrestartedAttemptAcquireLeelaz {
    private final AtomicInteger lifecycleFailureCount = new AtomicInteger();

    private RecordingLifecycleFailureLeelaz() throws Exception {
      super(null);
    }

    @Override
    void markLifecycleBoardSynchronizationFailed(String detail, boolean preserveUnrestoredState) {
      lifecycleFailureCount.incrementAndGet();
    }
  }

  private static final class FailingAttemptAcquireLeelaz extends QuietExitLeelaz {
    private final AssertionError acquisitionFailure =
        new AssertionError("controlled mirror attempt acquisition failure");

    private FailingAttemptAcquireLeelaz() throws Exception {
      super();
    }

    @Override
    UpdateEngineStartAttempt beginUpdateEngineStartAttempt() {
      throw acquisitionFailure;
    }
  }

  private static final class ThrowingMessageError extends AssertionError {
    private final AssertionError messageFailure =
        new AssertionError("controlled failure-detail extraction error");

    @Override
    public String getMessage() {
      throw messageFailure;
    }
  }

  private static final class BlockingLifecycleCloseLeelaz extends QuietExitLeelaz {
    private final AssertionError closeFailure =
        new AssertionError("controlled lifecycle cleanup failure");
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch allowClose = new CountDownLatch(1);
    private final RecordingTransport transport = new RecordingTransport(false);
    private boolean seedAttempt = true;

    private BlockingLifecycleCloseLeelaz() throws Exception {
      super();
    }

    @Override
    UpdateEngineStartAttempt beginUpdateEngineStartAttempt() {
      UpdateEngineStartAttempt attempt = super.beginUpdateEngineStartAttempt();
      if (seedAttempt) {
        seedAttempt = false;
        try {
          attempt.startEngine(0);
        } catch (IOException startFailure) {
          throw new AssertionError(startFailure);
        }
      }
      return attempt;
    }

    @Override
    public void startEngine(int index) {
      try {
        setLeelazField(this, "remoteTransport", transport);
      } catch (Exception reflectionFailure) {
        throw new AssertionError(reflectionFailure);
      }
      useRemoteCompute = true;
      installFreshCommandOutputForTest(new ByteArrayOutputStream());
      started = true;
      isLoaded = true;
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      closeEntered.countDown();
      try {
        if (!allowClose.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release lifecycle close");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      super.detachInitialEngineSyncAdmission(admission);
      throw closeFailure;
    }
  }

  private static final class FailingUpdateCleanupSchedulingEngineManager
      extends EngineManager {
    private final AssertionError synchronizationSchedulingFailure =
        new AssertionError("controlled synchronization thread scheduling failure");
    private final AssertionError cleanupThreadFailure =
        new AssertionError("controlled cleanup thread scheduling failure");
    private final AssertionError cleanupFallbackFailure =
        new AssertionError("controlled cleanup fallback scheduling failure");

    private FailingUpdateCleanupSchedulingEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected Thread createUpdateEngineSynchronizationThread(Runnable synchronization) {
      return new Thread() {
        @Override
        public synchronized void start() {
          throw synchronizationSchedulingFailure;
        }
      };
    }

    @Override
    protected Thread createUpdateEngineStartCleanupThread(Runnable cleanup) {
      throw cleanupThreadFailure;
    }

    @Override
    protected void executeUpdateEngineStartCleanupFallback(Runnable cleanup) {
      throw cleanupFallbackFailure;
    }
  }

  private static final class FailingOuterUpdateSchedulerEngineManager extends EngineManager {
    private final AssertionError schedulingFailure =
        new AssertionError("controlled outer update synchronization scheduling failure");
    private final AssertionError lifecycleCloseFailure =
        new AssertionError("controlled outer update lifecycle close failure");
    private final AtomicInteger lifecycleCloseCount = new AtomicInteger();

    private FailingOuterUpdateSchedulerEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected Thread createUpdateEngineSynchronizationThread(Runnable synchronization) {
      return new Thread() {
        @Override
        public synchronized void start() {
          throw schedulingFailure;
        }
      };
    }

    @Override
    protected void closeUpdateEngineLifecycleSynchronization(
        EngineManager.InitialEngineStartupSynchronization synchronization) {
      lifecycleCloseCount.incrementAndGet();
      try {
        super.closeUpdateEngineLifecycleSynchronization(synchronization);
      } finally {
        // Always throw the injected close failure. Real close() can also throw during
        // racy engine teardown; Java then suppresses that onto this controlled Error.
        throw lifecycleCloseFailure;
      }
    }
  }

  private static final class SilentFailureEngineManager extends EngineManager {
    private final AtomicInteger failureCount = new AtomicInteger();

    private SilentFailureEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount.incrementAndGet();
    }
  }

  private static final class IconPublishingStartLeelaz extends QuietExitLeelaz {
    private final CountDownLatch iconPublicationEntered = new CountDownLatch(1);

    private IconPublishingStartLeelaz() throws Exception {
      super();
    }

    @Override
    public void startEngine(int index) {
      installFreshCommandOutputForTest(new ByteArrayOutputStream());
      started = true;
      isLoaded = true;
      Object incarnation = currentEngineIncarnation();
      iconPublicationEntered.countDown();
      EngineManager.publishStartedEngineIconIfCurrent(this, incarnation);
    }
  }

  private static final class BlockingMarkUnavailableLeelaz extends QuietExitLeelaz {
    private final CountDownLatch markUnavailableEntered = new CountDownLatch(1);
    private final CountDownLatch allowMarkUnavailable = new CountDownLatch(1);

    private BlockingMarkUnavailableLeelaz() throws Exception {
      super();
    }

    @Override
    boolean markUnavailableIfCurrentIncarnation(Object expectedIncarnation) {
      markUnavailableEntered.countDown();
      try {
        if (!allowMarkUnavailable.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release quarantine admission");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      return super.markUnavailableIfCurrentIncarnation(expectedIncarnation);
    }
  }

  private static final class FailingNotPonderingLeelaz extends QuietExitLeelaz {
    private final AssertionError failure =
        new AssertionError("controlled notPondering failure before exact claim");
    private boolean failNotPondering = true;

    private FailingNotPonderingLeelaz() throws Exception {
      super();
    }

    @Override
    public void notPondering() {
      if (failNotPondering) {
        throw failure;
      }
      super.notPondering();
    }
  }

  private static final class NonProtocolForceLeelaz extends QuietExitLeelaz {
    private final AtomicInteger notPonderingCount = new AtomicInteger();

    private NonProtocolForceLeelaz() throws Exception {
      super();
    }

    @Override
    public void notPondering() {
      notPonderingCount.incrementAndGet();
    }
  }

  private static final class FailingShutdownExecutor extends ScheduledThreadPoolExecutor {
    private final Throwable failure;
    private final AtomicInteger shutdownCount = new AtomicInteger();

    private FailingShutdownExecutor(Throwable failure) {
      super(1);
      this.failure = failure;
    }

    @Override
    public void shutdown() {
      shutdownCount.incrementAndGet();
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      super.shutdown();
    }

    private void cleanup() {
      super.shutdownNow();
    }
  }

  private static class RecordingDestroyProcess extends Process {
    protected final AtomicInteger destroyCount = new AtomicInteger();
    protected final AtomicInteger forcibleDestroyCount = new AtomicInteger();
    private volatile boolean alive = true;

    protected RecordingDestroyProcess() {}

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
        throw new IllegalThreadStateException("recording process still alive");
      }
      return 0;
    }

    @Override
    public void destroy() {
      destroyCount.incrementAndGet();
      alive = false;
    }

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

  private static final class BlockingQuitOutputStream extends java.io.OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch allowWrite = new CountDownLatch(1);

    @Override
    public void write(int value) {
      awaitRelease();
      bytes.write(value);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
      awaitRelease();
      bytes.write(buffer, offset, length);
    }

    private void awaitRelease() {
      writeEntered.countDown();
      try {
        if (!allowWrite.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting for exact force abort");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }

    private void release() {
      allowWrite.countDown();
    }
  }

  private static final class BlockingDestroyProcess extends RecordingDestroyProcess {
    private final Throwable cleanupFailure;
    private final CountDownLatch cleanupEntered = new CountDownLatch(1);
    private final CountDownLatch allowCleanup = new CountDownLatch(1);

    private BlockingDestroyProcess(Throwable cleanupFailure) {
      this.cleanupFailure = cleanupFailure;
    }

    @Override
    public void destroy() {
      awaitCleanupRelease();
      super.destroy();
      throwCleanupFailure();
    }

    @Override
    public Process destroyForcibly() {
      awaitCleanupRelease();
      Process destroyed = super.destroyForcibly();
      throwCleanupFailure();
      return destroyed;
    }

    private void awaitCleanupRelease() {
      cleanupEntered.countDown();
      try {
        if (!allowCleanup.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release controlled process cleanup");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }

    private void throwCleanupFailure() {
      if (cleanupFailure instanceof RuntimeException) {
        throw (RuntimeException) cleanupFailure;
      }
      if (cleanupFailure instanceof Error) {
        throw (Error) cleanupFailure;
      }
    }
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

  private static final class RecordingTimer extends java.util.Timer {
    private final AtomicInteger cancelCount = new AtomicInteger();

    private RecordingTimer() {
      super(true);
    }

    @Override
    public void cancel() {
      cancelCount.incrementAndGet();
      super.cancel();
    }

    private void cancelForFixtureCleanup() {
      super.cancel();
    }
  }

  private static final class RecordingSshController extends SSHController {
    private final boolean blockClose;
    private final Throwable closeFailure;
    private final AtomicInteger closeCount = new AtomicInteger();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch allowClose = new CountDownLatch(1);

    private RecordingSshController(Leelaz owner) {
      this(owner, false);
    }

    private RecordingSshController(Leelaz owner, boolean blockClose) {
      this(owner, blockClose, null);
    }

    private RecordingSshController(
        Leelaz owner, boolean blockClose, Throwable closeFailure) {
      super(owner, "127.0.0.1", "22");
      this.blockClose = blockClose;
      this.closeFailure = closeFailure;
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
      closeEntered.countDown();
      if (blockClose) {
        try {
          if (!allowClose.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting to release controlled SSH close");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      if (closeFailure instanceof RuntimeException) {
        throw (RuntimeException) closeFailure;
      }
      if (closeFailure instanceof Error) {
        throw (Error) closeFailure;
      }
    }

  }

  private static final class RecordingTransport implements EngineTransport {
    private final boolean blockClose;
    private final Throwable closeFailure;
    private final AtomicInteger closeCount = new AtomicInteger();
    private final AtomicInteger abortCount = new AtomicInteger();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch allowClose = new CountDownLatch(1);
    private volatile Runnable abortAction = () -> {};

    private RecordingTransport(boolean blockClose) {
      this(blockClose, null);
    }

    private RecordingTransport(boolean blockClose, Throwable closeFailure) {
      this.blockClose = blockClose;
      this.closeFailure = closeFailure;
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
      if (blockClose) {
        try {
          if (!allowClose.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting to release controlled transport close");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      if (closeFailure instanceof RuntimeException) {
        throw (RuntimeException) closeFailure;
      }
      if (closeFailure instanceof Error) {
        throw (Error) closeFailure;
      }
    }

    @Override
    public void abort() {
      abortCount.incrementAndGet();
      abortAction.run();
      if (closeFailure instanceof RuntimeException) {
        throw (RuntimeException) closeFailure;
      }
      if (closeFailure instanceof Error) {
        throw (Error) closeFailure;
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
  private static class UpdateFailureLeelaz extends Leelaz {
    private int startCount;

    private UpdateFailureLeelaz() throws Exception {
      super("");
      oriEnginename = "update-target";
      currentEnginename = oriEnginename;
      oriWidth = 19;
      oriHeight = 19;
      width = 19;
      height = 19;
    }

    @Override
    public void startEngine(int index) {
      startCount++;
      started = true;
      isLoaded = true;
    }
  }

  private static final class UpdateClaimErrorLeelaz extends UpdateFailureLeelaz {
    private final AssertionError claimFailure;
    private AssertionError cleanupFailure;

    private UpdateClaimErrorLeelaz(
        AssertionError claimFailure, AssertionError cleanupFailure) throws Exception {
      this.claimFailure = claimFailure;
      this.cleanupFailure = cleanupFailure;
    }

    @Override
    LifecycleCompletionClaim tryBeginLifecycleCompletion(Object owner, Leelaz frozenMirror) {
      throw claimFailure;
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      super.detachInitialEngineSyncAdmission(admission);
      AssertionError failure = cleanupFailure;
      cleanupFailure = null;
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static final class UpdateLeaseConflictLeelaz extends UpdateFailureLeelaz {
    private AssertionError cleanupFailure;

    private UpdateLeaseConflictLeelaz(AssertionError cleanupFailure) throws Exception {
      this.cleanupFailure = cleanupFailure;
    }

    @Override
    LifecycleCompletionClaim tryBeginLifecycleCompletion(Object owner, Leelaz frozenMirror) {
      return null;
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      super.detachInitialEngineSyncAdmission(admission);
      AssertionError failure = cleanupFailure;
      cleanupFailure = null;
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static final class UpdateFailureEngineManager extends EngineManager {
    private final Leelaz replacement;
    private final Error replacementStartFailure;
    private final Throwable createFailure;
    private int createAttemptCount;

    private UpdateFailureEngineManager(Leelaz replacement, Error replacementStartFailure) {
      this(replacement, replacementStartFailure, null);
    }

    private UpdateFailureEngineManager(
        Leelaz replacement, Error replacementStartFailure, Throwable createFailure) {
      super(List.of());
      this.replacement = replacement;
      this.replacementStartFailure = replacementStartFailure;
      this.createFailure = createFailure;
    }

    @Override
    protected Leelaz createUnstartedEngine(EngineData engineData) throws IOException {
      createAttemptCount++;
      if (createFailure instanceof IOException) {
        throw (IOException) createFailure;
      }
      if (createFailure instanceof RuntimeException) {
        throw (RuntimeException) createFailure;
      }
      if (createFailure instanceof Error) {
        throw (Error) createFailure;
      }
      return replacement;
    }

    @Override
    protected void startUpdateEngineReplacement(Thread replacementStart) {
      if (replacementStartFailure != null) {
        throw replacementStartFailure;
      }
      super.startUpdateEngineReplacement(replacementStart);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {}

    @Override
    protected void showForegroundEngineLeaseInUse() {}
  }

  private static final class UpdateFailureState implements AutoCloseable {
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final Config previousConfig = Lizzie.config;
    private final JFontMenu previousEngineMenu = Menu.engineMenu;
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final boolean previousUpdating = EngineManager.isUpdating;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final int previousEngineNo2 = EngineManager.currentEngineNo2;
    private final int previousBoardWidth = Board.boardWidth;
    private final int previousBoardHeight = Board.boardHeight;
    private final UpdateForegroundLeelaz previousForegroundEngine;
    private final UpdateFailureEngineManager manager;

    private UpdateFailureState(UpdateFailureLeelaz replacement, Error replacementStartFailure)
        throws Exception {
      this(replacement, replacementStartFailure, null);
    }

    private UpdateFailureState(
        UpdateFailureLeelaz replacement,
        Error replacementStartFailure,
        Throwable createFailure)
        throws Exception {
      previousForegroundEngine = new UpdateForegroundLeelaz();
      previousForegroundEngine.oriEnginename = "update-target";
      previousForegroundEngine.started = true;
      previousForegroundEngine.isLoaded = true;
      manager =
          new UpdateFailureEngineManager(replacement, replacementStartFailure, createFailure);
    }

    private void install() throws Exception {
      Config config = allocate(Config.class);
      config.extraMode = ExtraMode.Normal;
      config.leelazConfig =
          new JSONObject()
              .put(
                  "engine-settings-list",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("command", "controlled-update-engine")
                              .put("name", "update-target")
                              .put("preload", false)
                              .put("width", 19)
                              .put("height", 19)
                              .put("komi", 7.5)));
      config.uiConfig = new JSONObject();
      Lizzie.config = config;
      Lizzie.frame = allocate(SilentSwitchFrame.class);
      Lizzie.board = preparedRestoreBoard();
      Lizzie.leelaz = previousForegroundEngine;
      Lizzie.leelaz2 = null;
      Menu.engineMenu = new SilentJFontMenu();
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      EngineManager.isEmpty = false;
      EngineManager.isUpdating = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = -1;
    }

    @Override
    public void close() {
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Menu.engineMenu = previousEngineMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isUpdating = previousUpdating;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
    }
  }

  private static final class UpdateEnginesState {
    private final int targetWidth;
    private final int targetHeight;
    private final boolean doubleEngine;
    private final boolean mirrorStartFails;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz previousMirror = Lizzie.leelaz2;
    private final EngineManager previousEngineManager = Lizzie.engineManager;
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
    private final FailingOuterUpdateSchedulerEngineManager schedulerManager;
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
      this(targetWidth, targetHeight, doubleEngine, mirrorStartFails, false);
    }

    private UpdateEnginesState(
        int targetWidth,
        int targetHeight,
        boolean doubleEngine,
        boolean mirrorStartFails,
        boolean failSynchronizationScheduling)
        throws Exception {
      this.targetWidth = targetWidth;
      this.targetHeight = targetHeight;
      this.doubleEngine = doubleEngine;
      this.mirrorStartFails = mirrorStartFails;
      schedulerManager =
          failSynchronizationScheduling
              ? new FailingOuterUpdateSchedulerEngineManager(List.of())
              : null;
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
      manager = schedulerManager == null ? new EngineManager(List.of()) : schedulerManager;
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
      Lizzie.engineManager = manager;
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
      Lizzie.engineManager = previousEngineManager;
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

  private static class UpdateForegroundLeelaz extends Leelaz {
    private Runnable onForceQuit;

    protected UpdateForegroundLeelaz() throws Exception {
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

  private static final class BlockingStoppedIconMenu extends Menu {
    private CountDownLatch iconMutationEntered;
    private CountDownLatch allowIconMutation;
    private volatile boolean mutationOnEdt;
    private volatile int lastPrimaryMode = -1;

    @Override
    public void changeEngineIcon(int index, int mode) {
      mutationOnEdt = SwingUtilities.isEventDispatchThread();
      if (mode == 0) {
        iconMutationEntered.countDown();
        try {
          if (!allowIconMutation.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting to release stopped-icon mutation");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      lastPrimaryMode = mode;
    }

    @Override
    public void changeEngineIcon2(int index, int mode) {}
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

  private static class RecordingSwitchLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();
    private Runnable onLifecycleReservation;
    private String loadedSgf = "";
    private int boardSynchronizationConfirmations;
    private Runnable boardSynchronizationCompletion;
    private int ponderCount;
    private int responseFreshenedAfterPonderCount = -1;

    protected RecordingSwitchLeelaz() throws Exception {
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

    void completeBoardSynchronization() {
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

  private static final class PreflightFailureLeelaz extends RecordingSwitchLeelaz {
    private final AssertionError preflightFailure =
        new AssertionError("controlled restart receipt preflight failure");
    private int preflightCount;
    private int detachCount;

    private PreflightFailureLeelaz() throws Exception {
      super();
    }

    @Override
    Runnable withCurrentRestartBootstrapReceipt(Runnable action) {
      preflightCount++;
      throw preflightFailure;
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      detachCount++;
      super.detachInitialEngineSyncAdmission(admission);
    }
  }

  private static class RebindingSynchronizationFailureLeelaz
      extends RecordingSwitchLeelaz {
    private final AssertionError synchronizationFailure =
        new AssertionError("controlled synchronization failure");
    boolean failSynchronization;
    private boolean failResponseFreshening;
    private int normalQuitCount;
    int notPonderingCount;
    private int responseFresheningCount;
    private final CountDownLatch twoNotPonderingCalls = new CountDownLatch(2);

    private RebindingSynchronizationFailureLeelaz() throws Exception {
      super();
    }

    @Override
    public synchronized void notPondering() {
      notPonderingCount++;
      twoNotPonderingCalls.countDown();
      if (failSynchronization) {
        failSynchronization = false;
        throw synchronizationFailure;
      }
      super.notPondering();
    }

    @Override
    public void setResponseUpToDate() {
      responseFresheningCount++;
      if (failResponseFreshening) {
        throw synchronizationFailure;
      }
      super.setResponseUpToDate();
    }

    @Override
    public void normalQuit() {
      normalQuitCount++;
    }
  }

  private static final class LockOrderingSynchronizationFailureLeelaz
      extends RebindingSynchronizationFailureLeelaz {
    private final CountDownLatch failureActionEntered = new CountDownLatch(1);
    private final CountDownLatch allowFailureActionSelection = new CountDownLatch(1);
    private final CountDownLatch failureActionCompleted = new CountDownLatch(1);
    private Object selectionLock;

    private LockOrderingSynchronizationFailureLeelaz() throws Exception {
      super();
    }

    @Override
    Runnable currentRestartBoardSynchronizationFailureAction(String detail) {
      return () -> {
        failureActionEntered.countDown();
        awaitLatch(allowFailureActionSelection);
        synchronized (selectionLock) {
          failureActionCompleted.countDown();
        }
      };
    }
  }

  private static final class BlockingFailureActionSynchronizationLeelaz
      extends RebindingSynchronizationFailureLeelaz {
    private final CountDownLatch failureActionEntered = new CountDownLatch(1);
    private final CountDownLatch allowFailureAction = new CountDownLatch(1);
    private final CountDownLatch failureActionCompleted = new CountDownLatch(1);

    private BlockingFailureActionSynchronizationLeelaz() throws Exception {
      super();
    }

    @Override
    Runnable currentRestartBoardSynchronizationFailureAction(String detail) {
      return () -> {
        failureActionEntered.countDown();
        awaitLatch(allowFailureAction);
        failureActionCompleted.countDown();
      };
    }
  }

  private static final class FailingDetachRecordingSwitchLeelaz
      extends RecordingSwitchLeelaz {
    private final AssertionError closeFailure =
        new AssertionError("controlled ordinary lifecycle close failure");

    private FailingDetachRecordingSwitchLeelaz() throws Exception {
      super();
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      super.detachInitialEngineSyncAdmission(admission);
      throw closeFailure;
    }
  }

  private static final class BlockingDetachRecordingSwitchLeelaz
      extends RecordingSwitchLeelaz {
    private final CountDownLatch detachEntered = new CountDownLatch(1);
    private final CountDownLatch allowDetach = new CountDownLatch(1);

    private BlockingDetachRecordingSwitchLeelaz() throws Exception {
      super();
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      super.detachInitialEngineSyncAdmission(admission);
      detachEntered.countDown();
      awaitLatch(allowDetach);
    }
  }

  private static final class BlockingRollbackRecoveryLeelaz extends RecordingSwitchLeelaz {
    private final CountDownLatch reservationEntered = new CountDownLatch(1);
    private final CountDownLatch allowReservation = new CountDownLatch(1);
    private final CountDownLatch recoveryCompleted = new CountDownLatch(1);
    private final AtomicInteger reservationCount = new AtomicInteger();

    private BlockingRollbackRecoveryLeelaz() throws Exception {
      super();
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      reservationCount.incrementAndGet();
      reservationEntered.countDown();
      awaitLatch(allowReservation);
      return super.beginExclusiveGtpLifecycleReservation(owner);
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      try {
        onSuccess.run();
      } finally {
        recoveryCompleted.countDown();
      }
    }
  }

  private static final class RecordingPdaMenu extends Menu {
    private List<Leelaz> engines = new ArrayList<>();

    @Override
    public void showPdaForEngine(Leelaz engine, long primaryGeneration, boolean show) {
      engines.add(engine);
    }

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void changeEngineIcon2(int index, int mode) {}
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
      Lizzie.frame = allocate(CountingRestartFrame.class);
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
      Runnable synchronization = manager.synchronization;
      manager.synchronization = null;
      if (synchronization != null) {
        synchronization.run();
      }
      Runnable completion = manager.afterSync;
      manager.afterSync = null;
      if (completion != null) {
        completion.run();
      }
    }

    @Override
    public void close() throws Exception {
      Throwable completionFailure = null;
      try {
        completeDeferredSwitch();
        SwingUtilities.invokeAndWait(() -> {});
      } catch (Throwable failure) {
        completionFailure = failure;
      } finally {
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
      if (completionFailure instanceof Exception) {
        throw (Exception) completionFailure;
      }
      if (completionFailure instanceof Error) {
        throw (Error) completionFailure;
      }
    }
  }

  private static final class RestartIndexLeelaz extends Leelaz {
    private int shutdownCount;
    private int startIndex = -1;

    private RestartIndexLeelaz(String command) throws Exception {
      super(command);
      ExactSnapshotRestoreProtocolFixture.install(
          this, ignored -> ExactSnapshotRestoreProtocolFixture.Response.success());
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

  private static final class CountingRestartFrame extends LizzieFrame {
    private int reSetLocCount;


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

  private static final class ThrowingLifecycleLeelaz extends Leelaz {
    private final String name;
    private final List<String> reservationOrder;
    private final RuntimeException runtimeFailure;
    private final Error errorFailure;

    private ThrowingLifecycleLeelaz(
        String name, List<String> reservationOrder, RuntimeException failure) throws Exception {
      super("");
      this.name = name;
      this.reservationOrder = reservationOrder;
      this.runtimeFailure = failure;
      this.errorFailure = null;
    }

    private ThrowingLifecycleLeelaz(String name, List<String> reservationOrder, Error failure)
        throws Exception {
      super("");
      this.name = name;
      this.reservationOrder = reservationOrder;
      this.runtimeFailure = null;
      this.errorFailure = failure;
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      return failReservation();
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      return failReservation();
    }

    private ExclusiveGtpLifecycleReservation failReservation() {
      reservationOrder.add(name);
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      throw errorFailure;
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
    private final CountDownLatch failurePresented = new CountDownLatch(1);
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
      installPreparedSynchronizationFailureAuthority(this, preparedSwitch, target, isMain);
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
      failurePresented.countDown();
    }
  }

  private static final class ControlledReadinessEngineManager extends EngineManager {
    private final Leelaz target;
    private final long timeoutMillis;
    private final CountDownLatch synchronizationStarted = new CountDownLatch(1);
    private final CountDownLatch allowSynchronizationToComplete = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private final CountDownLatch failurePresented = new CountDownLatch(1);
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
      installPreparedSynchronizationFailureAuthority(this, preparedSwitch, target, isMain);
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
      failurePresented.countDown();
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
