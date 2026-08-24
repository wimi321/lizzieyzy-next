package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.EngineCountDown;
import featurecat.lizzie.rules.Stone;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineManagerEngineGameStateMachineTest {
  private EngineManager previousManager;
  private EngineGameInfo previousGameInfo;
  private Leelaz previousPrimary;
  private Leelaz previousSecondary;
  private Config previousConfig;
  private LizzieFrame previousFrame;
  private BottomToolbar previousToolbar;
  private Menu previousMenu;
  private JFontMenu previousEngineMenu;
  private Board previousBoard;
  private GtpConsolePane previousGtpConsole;
  private boolean previousEngineGame;
  private boolean previousPreEngineGame;
  private boolean previousEmpty;
  private int previousEngineNo;

  private StateMachineLeelaz black;
  private StateMachineLeelaz white;
  private StateMachineLeelaz preload;
  private TrackingFrame frame;
  private TrackingToolbar toolbar;

  @BeforeEach
  void installStateMachineFixture() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
    previousManager = Lizzie.engineManager;
    previousGameInfo = EngineManager.engineGameInfo;
    previousPrimary = Lizzie.leelaz;
    previousSecondary = Lizzie.leelaz2;
    previousConfig = Lizzie.config;
    previousFrame = Lizzie.frame;
    previousToolbar = LizzieFrame.toolbar;
    previousMenu = LizzieFrame.menu;
    previousEngineMenu = Menu.engineMenu;
    previousBoard = Lizzie.board;
    previousGtpConsole = Lizzie.gtpConsole;
    previousEngineGame = EngineManager.isEngineGame;
    previousPreEngineGame = EngineManager.isPreEngineGame;
    previousEmpty = EngineManager.isEmpty;
    previousEngineNo = EngineManager.currentEngineNo;

    EngineManager.resetEngineGameTransactionStateForTest();
    Config config = allocate(Config.class);
    config.enginePkPonder = false;
    Lizzie.config = config;
    black = new StateMachineLeelaz();
    white = new StateMachineLeelaz();
    preload = new StateMachineLeelaz();
    black.bindLiveRuntime();
    white.bindLiveRuntime();
    preload.bindLiveRuntime();
    Lizzie.setPrimaryEngine(black);
    Lizzie.leelaz2 = null;
    frame = allocate(TrackingFrame.class);
    frame.inputAttempts = new AtomicInteger();
    toolbar = allocate(TrackingToolbar.class);
    toolbar.enableAttempts = new AtomicInteger();
    Lizzie.frame = frame;
    LizzieFrame.toolbar = toolbar;
    LizzieFrame.menu = allocate(SilentMenu.class);
    Menu.engineMenu = new JFontMenu();
    Lizzie.board = preparedBoard();
    Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
    EngineManager.isEmpty = false;
    EngineManager.currentEngineNo = 0;
  }

  @AfterEach
  void restoreStateMachineFixture() throws Exception {
    EngineManager.resetEngineGameTransactionStateForTest();
    black.leela0110StopPonder();
    white.leela0110StopPonder();
    preload.leela0110StopPonder();
    black.started = false;
    black.isLoaded = false;
    white.started = false;
    white.isLoaded = false;
    preload.started = false;
    preload.isLoaded = false;
    Lizzie.engineManager = previousManager;
    EngineManager.engineGameInfo = previousGameInfo;
    EngineManager.isEngineGame = previousEngineGame;
    EngineManager.isPreEngineGame = previousPreEngineGame;
    EngineManager.isEmpty = previousEmpty;
    EngineManager.currentEngineNo = previousEngineNo;
    Lizzie.setPrimaryEngine(previousPrimary);
    Lizzie.leelaz2 = previousSecondary;
    Lizzie.config = previousConfig;
    Lizzie.frame = previousFrame;
    LizzieFrame.toolbar = previousToolbar;
    LizzieFrame.menu = previousMenu;
    Menu.engineMenu = previousEngineMenu;
    Lizzie.board = previousBoard;
    Lizzie.gtpConsole = previousGtpConsole;
    SwingUtilities.invokeAndWait(() -> {});
  }

  @Test
  void admissionPublishesInfoAndEpochAtomicallyAndRejectsStalePredecessor() {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo first = gameInfo();
    EngineGameInfo replacement = gameInfo();

    EngineManager.EngineGameTransaction transaction =
        EngineManager.beginEngineGameTransaction(manager, first, null, true);

    assertNotNull(transaction);
    assertSame(first, EngineManager.engineGameInfo);
    assertEquals(EngineManager.EngineGamePhase.PREPARING, transaction.phase());
    assertNull(EngineManager.beginEngineGameTransaction(manager, replacement, null, true));
    assertSame(first, EngineManager.engineGameInfo);

    long staleEpoch = transaction.epoch();
    EngineManager.resetEngineGameTransactionStateForTest();
    assertNull(EngineManager.beginEngineGameTransaction(manager, replacement, staleEpoch, true));
    assertSame(first, EngineManager.engineGameInfo);
  }

  @Test
  void terminalCancellationSerializesWithInFlightStageAndRejectsLateCallback() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    CountDownLatch stageEntered = new CountDownLatch(1);
    CountDownLatch releaseStage = new CountDownLatch(1);
    CountDownLatch stageDone = new CountDownLatch(1);
    CountDownLatch clearStarted = new CountDownLatch(1);
    CountDownLatch clearDone = new CountDownLatch(1);
    AtomicInteger effects = new AtomicInteger();
    Thread stage =
        new Thread(
            () -> {
              EngineManager.runIfCurrentEngineGameTransaction(
                  transaction,
                  () -> {
                    stageEntered.countDown();
                    await(releaseStage);
                    effects.incrementAndGet();
                  });
              stageDone.countDown();
            },
            "engine-game-in-flight-stage");
    Thread clearer =
        new Thread(
            () -> {
              clearStarted.countDown();
              manager.clearEngineGame();
              clearDone.countDown();
            },
            "engine-game-cancel");

    stage.start();
    assertTrue(stageEntered.await(2, TimeUnit.SECONDS));
    clearer.start();
    assertTrue(clearStarted.await(2, TimeUnit.SECONDS));
    assertFalse(clearDone.await(100, TimeUnit.MILLISECONDS));
    releaseStage.countDown();

    assertTrue(stageDone.await(2, TimeUnit.SECONDS));
    assertTrue(clearDone.await(2, TimeUnit.SECONDS));
    assertEquals(1, effects.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertFalse(
        EngineManager.runIfCurrentEngineGameTransaction(transaction, effects::incrementAndGet));
    assertEquals(1, effects.get());
  }

  @Test
  void schedulerFailureBeforeStartFailsOnceAndRejectsWorkerEffects() {
    WorkerSeamEngineManager manager = installManager(new WorkerSeamEngineManager(allEngines()));
    manager.workerMode = WorkerMode.THROW_BEFORE_START;
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    AtomicInteger effects = new AtomicInteger();

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                manager.dispatchEngineGameWorker(
                    transaction, "before-start", effects::incrementAndGet));

    assertSame(manager.schedulingFailure, failure);
    assertEquals(0, effects.get());
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertFalse(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void schedulerStartThenThrowLeavesTheStartedWorkerAsSoleOwner() throws Exception {
    WorkerSeamEngineManager manager = installManager(new WorkerSeamEngineManager(allEngines()));
    manager.workerMode = WorkerMode.START_THEN_THROW;
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    AtomicInteger effects = new AtomicInteger();
    CountDownLatch taskEntered = new CountDownLatch(1);
    CountDownLatch taskDone = new CountDownLatch(1);
    manager.taskEntered = taskEntered;

    assertTrue(
        manager.dispatchEngineGameWorker(
            transaction,
            "start-then-throw",
            () -> {
              effects.incrementAndGet();
              taskEntered.countDown();
              taskDone.countDown();
            }));

    assertTrue(taskDone.await(2, TimeUnit.SECONDS));
    assertEquals(1, effects.get());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
    assertEquals(EngineManager.EngineGamePhase.PREPARING, transaction.phase());
  }

  @Test
  void retiredPkReadinessWorkerReleasesItsOwnerWithoutTouchingReplacementIncarnation()
      throws Exception {
    ReadinessGateEngineManager manager =
        installManager(new ReadinessGateEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    Object oldIncarnation = black.currentEngineIncarnation();
    assertTrue(
        EngineManager.bindEngineGameStartupIncarnation(
            transaction, black, oldIncarnation));
    black.isLoaded = false;
    black.isCheckingName = true;
    AtomicInteger synchronizationRuns = new AtomicInteger();
    AtomicInteger lifecycleCloses = new AtomicInteger();

    EngineManager.PkEngineSynchronization completion =
        manager.synchronizePkEngineWhenReadyForTest(
            transaction,
            black,
            oldIncarnation,
            synchronizationRuns::incrementAndGet,
            lifecycleCloses::incrementAndGet);

    assertTrue(manager.readinessProbeEntered.await(2, TimeUnit.SECONDS));
    manager.clearEngineGame();
    black.bindLiveRuntime();
    Object replacementIncarnation = black.currentEngineIncarnation();
    assertNotSame(oldIncarnation, replacementIncarnation);
    black.isCheckingName = false;
    black.isLoaded = true;
    manager.releaseReadinessProbe.countDown();

    assertFalse(completion.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
    awaitOperationsReleased(transaction);
    assertEquals(0, synchronizationRuns.get());
    assertEquals(1, lifecycleCloses.get());
    assertTrue(black.isLoaded, "stale readiness cleanup must preserve replacement readiness");
    assertSame(replacementIncarnation, black.currentEngineIncarnation());
  }

  @Test
  void freshEngineGameBootstrapKeepsBoardDefaultsAndOrdinaryPresentationUntouched()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    double previousDefaultKomi = GameInfo.DEFAULT_KOMI;
    double boardKomi = Lizzie.board.getHistory().getGameInfo().getKomi();
    black.firstLoad = true;
    black.komi = 9.5f;
    black.width = 13;
    black.height = 13;
    frame.isShowingHeatmap = true;
    frame.isShowingPolicy = true;
    black.trackEngineGameBootstrapCompletion();
    try {
      black.dispatchEngineGameBootstrapCommandsForTest(transaction);
      assertTrue(black.engineGameBootstrapCompleted.await(2, TimeUnit.SECONDS));

      assertTrue(black.commandText().contains("name"));
      assertTrue(black.commandText().contains("boardsize 13"));
      assertEquals(boardKomi, Lizzie.board.getHistory().getGameInfo().getKomi());
      assertEquals(previousDefaultKomi, GameInfo.DEFAULT_KOMI);
      assertFalse(black.firstLoad);

      black.publishEngineGameBootstrapPresentationForTest(transaction);
      assertTrue(frame.isShowingHeatmap);
      assertTrue(frame.isShowingPolicy);

      settleAllCommandResponses(black, transaction);
      awaitOperationsReleased(transaction);
    } finally {
      GameInfo.DEFAULT_KOMI = previousDefaultKomi;
    }
  }

  @Test
  void cancelledFreshBootstrapCannotSendOrPublishAfterItsWorkerGate() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    double previousDefaultKomi = GameInfo.DEFAULT_KOMI;
    double boardKomi = Lizzie.board.getHistory().getGameInfo().getKomi();
    black.firstLoad = true;
    black.komi = 9.5f;
    black.width = 13;
    black.height = 13;
    frame.isShowingHeatmap = true;
    frame.isShowingPolicy = true;
    black.blockEngineGameBootstrapBeforeCommands();
    try {
      black.dispatchEngineGameBootstrapCommandsForTest(transaction);
      assertTrue(black.engineGameBootstrapEntered.await(2, TimeUnit.SECONDS));

      manager.clearEngineGame();
      black.bindLiveRuntime();
      black.isLoaded = true;
      black.releaseEngineGameBootstrap.countDown();
      awaitOperationsReleased(transaction);

      assertEquals("", black.commandText());
      assertEquals(boardKomi, Lizzie.board.getHistory().getGameInfo().getKomi());
      assertEquals(previousDefaultKomi, GameInfo.DEFAULT_KOMI);
      assertTrue(black.firstLoad);
      black.publishEngineGameBootstrapPresentationForTest(transaction);
      assertTrue(frame.isShowingHeatmap);
      assertTrue(frame.isShowingPolicy);
    } finally {
      black.releaseEngineGameBootstrap.countDown();
      GameInfo.DEFAULT_KOMI = previousDefaultKomi;
    }
  }

  @Test
  void participantSynchronizationTimeoutFailsClosedAndRestoresEveryUiStep() {
    ImmediateUiEngineManager manager = installManager();
    manager.timeoutMillis = 10L;
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    assertTrue(EngineManager.transitionEngineGameToDispatched(transaction));
    frame.throwOnInputRestore = true;
    toolbar.controlsEnabled = false;
    Menu.engineMenu.setEnabled(false);
    Lizzie.board.isPkBoard = true;

    assertFalse(
        manager.finishPkEngineSynchronizations(
            transaction,
            new EngineManager.PkEngineSynchronization(),
            new EngineManager.PkEngineSynchronization()));

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertFalse(EngineManager.isCurrentEngineGameTransaction(transaction));
    assertFalse(Lizzie.board.isPkBoard);
    assertEquals(1, frame.inputAttempts.get());
    assertEquals(1, toolbar.enableAttempts.get());
    assertTrue(toolbar.controlsEnabled);
    assertTrue(Menu.engineMenu.isEnabled());
  }

  @Test
  void failedStageHasOneTerminalOwnerAndLateSiblingIsFenced() {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    AssertionError primary = new AssertionError("controlled engine command failure");
    AtomicInteger lateEffects = new AtomicInteger();

    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () ->
                EngineManager.runIfCurrentEngineGameTransaction(
                    transaction,
                    () -> {
                      throw primary;
                    })));

    EngineManager.failEngineGameTransaction(
        transaction, new AssertionError("late sibling failure"));
    assertFalse(
        EngineManager.runIfCurrentEngineGameTransaction(transaction, lateEffects::incrementAndGet));
    assertEquals(0, lateEffects.get());
    assertSame(primary, transaction.terminalFailure());
  }

  @Test
  void retirementBlocksReplacementUntilRollbackPresentationCompletes() {
    DeferredUiEngineManager manager = installManager(new DeferredUiEngineManager(allEngines()));
    EngineGameInfo first = gameInfo();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, first);

    manager.clearEngineGame();
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertNotNull(manager.pendingUi.get());
    EngineGameInfo replacement = gameInfo();
    assertNull(EngineManager.beginEngineGameTransaction(manager, replacement, null, true));
    assertSame(first, EngineManager.engineGameInfo);

    manager.runPendingUi();
    EngineManager.EngineGameTransaction replacementTransaction =
        EngineManager.beginEngineGameTransaction(manager, replacement, null, true);
    assertNotNull(replacementTransaction);
    assertSame(replacement, EngineManager.engineGameInfo);
  }

  @Test
  void activeGameRejectsPonderRoutingMutation() {
    ImmediateUiEngineManager manager = installManager();
    activeTransaction(manager, gameInfo(), black, 0);
    Lizzie.config.enginePkPonder = false;

    assertFalse(EngineManager.setEngineGamePonderEnabled(true));
    assertFalse(Lizzie.config.enginePkPonder);
    assertSame(black, Lizzie.leelaz);
  }

  @Test
  void deferredPrimaryPublishesOnlyExactParticipantAndUpdatesSelectionAtomically() {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo gameInfo = gameInfo();
    activeTransaction(manager, gameInfo, black, 0);
    long generation = Lizzie.capturePrimaryEngineGeneration(black);

    EngineManager.DeferredEngineGamePrimaryPublication nonParticipant =
        publication(manager, gameInfo, 2, preload, black, generation, false);
    assertNotNull(nonParticipant);
    assertFalse(nonParticipant.publish());
    assertSame(black, Lizzie.leelaz);

    EngineManager.DeferredEngineGamePrimaryPublication exact =
        publication(manager, gameInfo, 1, white, black, generation, false);
    assertNotNull(exact);
    assertTrue(exact.publish());
    assertSame(white, Lizzie.leelaz);
    assertEquals(1, EngineManager.currentEngineNo);
    assertFalse(EngineManager.isEmpty);
  }

  @Test
  void deferredPrimaryRejectsRebindBoardAdvanceAndPriorEpoch() {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo gameInfo = gameInfo();
    activeTransaction(manager, gameInfo, black, 0);
    long generation = Lizzie.capturePrimaryEngineGeneration(black);

    EngineManager.DeferredEngineGamePrimaryPublication rebound =
        publication(manager, gameInfo, 1, white, black, generation, false);
    white.bindLiveRuntime();
    assertFalse(rebound.publish());
    assertSame(black, Lizzie.leelaz);

    EngineManager.DeferredEngineGamePrimaryPublication boardAdvanced =
        publication(manager, gameInfo, 0, black, black, generation, false);
    Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
    assertFalse(boardAdvanced.publish());

    Lizzie.board = preparedBoard();
    EngineManager.DeferredEngineGamePrimaryPublication priorEpoch =
        publication(manager, gameInfo, 0, black, black, generation, false);
    manager.clearEngineGame();
    activeTransaction(manager, gameInfo, black, 0);
    assertFalse(priorEpoch.publish());
    assertSame(black, Lizzie.leelaz);
  }

  @Test
  void deferredPrimaryRejectsLineContextCapturedBeforeNextEpoch() {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo reusedBatchInfo = gameInfo();
    activeTransaction(manager, reusedBatchInfo, black, 0);
    EngineManager.EngineGamePrimaryContext staleLineContext =
        EngineManager.captureEngineGamePrimaryContext();
    assertNotNull(staleLineContext);

    manager.clearEngineGame();
    activeTransaction(manager, reusedBatchInfo, black, 0);

    assertNull(
        EngineManager.prepareEngineGamePrimaryPublication(
            staleLineContext,
            1,
            white,
            black,
            Lizzie.capturePrimaryEngineGeneration(black),
            white.currentEngineIncarnation(),
            Lizzie.board,
            Lizzie.board.getContextRevision(),
            Lizzie.board.getHistory().isBlacksTurn()));
    assertSame(black, Lizzie.leelaz);
  }

  @Test
  void ponderPublicationRequiresParticipantForCapturedTurn() {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo gameInfo = gameInfo();
    activeTransaction(manager, gameInfo, black, 0);
    Lizzie.config.enginePkPonder = true;
    long generation = Lizzie.capturePrimaryEngineGeneration(black);
    assertTrue(Lizzie.board.getHistory().isBlacksTurn());

    EngineManager.DeferredEngineGamePrimaryPublication wrongTurn =
        publication(manager, gameInfo, 1, white, black, generation, true);
    assertNotNull(wrongTurn);
    assertFalse(wrongTurn.publish());

    EngineManager.DeferredEngineGamePrimaryPublication mover =
        publication(manager, gameInfo, 0, black, black, generation, true);
    assertNotNull(mover);
    assertTrue(mover.publish());
    assertSame(black, Lizzie.leelaz);
    assertEquals(0, EngineManager.currentEngineNo);
  }

  @Test
  void participantStartupBudgetCannotBeCutOffByLegacyThirtySecondDefault() {
    BudgetAwareEngineManager manager =
        installManager(new BudgetAwareEngineManager(allEngines()));
    EngineGameInfo gameInfo = gameInfo();

    assertEquals(
        TimeUnit.SECONDS.toMillis(125L), manager.configuredStartupBudget(gameInfo));
  }

  @Test
  void eachParticipantCanExtendTheTransactionForObservedOpenClTuning() {
    ImmediateUiEngineManager manager = installManager();
    manager.timeoutMillis = 10L;
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    long initialDeadline = EngineManager.engineGameDeadlineNanos(transaction);

    black.isTuning = true;
    long blackDeadline = EngineManager.engineGameDeadlineNanos(transaction);
    white.isTuning = true;
    white.tuningTimeoutMillis = TimeUnit.SECONDS.toMillis(140L);
    long whiteDeadline = EngineManager.engineGameDeadlineNanos(transaction);

    assertTrue(blackDeadline > initialDeadline);
    assertTrue(whiteDeadline > blackDeadline);
  }

  @Test
  void stopPublishesPkCommentRoutingBeforeRetiringTheTransaction() {
    StopCommentEngineManager manager =
        installManager(new StopCommentEngineManager(allEngines()));
    activeTransaction(manager, gameInfo(), black, 0);

    assertSame(
        manager.stopAfterComment,
        assertThrows(AssertionError.class, () -> manager.stopEngineGame(0, false)));
    assertTrue(manager.sawForcedEngineGame);
    assertFalse(manager.sawActiveFlag);
    assertFalse(EngineManager.isSaveingEngineSGF);
  }

  @Test
  void duplicateStopCannotClearTheTerminalOwnersSavingFlag() throws Exception {
    BlockingSaveEngineManager manager =
        installManager(new BlockingSaveEngineManager(allEngines()));
    activeTransaction(manager, gameInfo(), black, 0);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread firstStop =
        new Thread(
            () -> {
              try {
                manager.stopEngineGame(0, false);
              } catch (Throwable failure) {
                firstFailure.set(failure);
              }
            },
            "engine-game-first-stop");

    firstStop.start();
    assertTrue(manager.savingStarted.await(2, TimeUnit.SECONDS));
    manager.stopEngineGame(0, false);
    assertTrue(EngineManager.isSaveingEngineSGF);
    manager.releaseSaving.countDown();
    firstStop.join(2000L);

    assertFalse(firstStop.isAlive());
    assertSame(manager.stopFailure, firstFailure.get());
    assertFalse(EngineManager.isSaveingEngineSGF);
  }

  @Test
  void pureEngineGameHistoryCommitHasNoBoardOrEngineSideEffectsForNewAndReplayNodes()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode parent = history.getCurrentHistoryNode();
    boolean parentTurn = parent.getData().blackToPlay;
    black.maybeAdjustPdaCalls.set(0);
    white.maybeAdjustPdaCalls.set(0);

    Board.EngineGameMoveCommit coordinate =
        board.commitEngineGamePlace(
            history, parent, true, 3, 3, Stone.BLACK, false, false, false);
    assertNotNull(coordinate);
    BoardHistoryNode coordinateNode = coordinate.node();
    assertEquals(0, board.clearAfterMoveCalls.get());
    assertEquals(0, black.maybeAdjustPdaCalls.get());
    assertEquals(0, white.maybeAdjustPdaCalls.get());
    assertEquals("", black.commandText());
    assertEquals("", white.commandText());

    assertTrue(history.previous().isPresent());
    Board.EngineGameMoveCommit replayedCoordinate =
        board.commitEngineGamePlace(
            history, parent, true, 3, 3, Stone.BLACK, false, false, false);
    assertNotNull(replayedCoordinate);
    assertSame(coordinateNode, replayedCoordinate.node());
    assertTrue(history.previous().isPresent());

    Board.EngineGameMoveCommit pass =
        board.commitEngineGamePass(history, parent, true, Stone.BLACK, false);
    assertNotNull(pass);
    BoardHistoryNode passNode = pass.node();
    assertTrue(history.previous().isPresent());
    Board.EngineGameMoveCommit replayedPass =
        board.commitEngineGamePass(history, parent, true, Stone.BLACK, false);
    assertSame(passNode, replayedPass.node());
    assertEquals(parentTurn, parent.getData().blackToPlay);
    assertEquals(0, board.clearAfterMoveCalls.get());
    assertEquals(0, black.maybeAdjustPdaCalls.get());
    assertEquals(0, white.maybeAdjustPdaCalls.get());
    assertEquals("", black.commandText());
    assertEquals("", white.commandText());
  }

  @Test
  void pureCoordinateCommitRejectsWrongTurnAndNeverFastForwardsWrongColorMainChild()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode parent = history.getCurrentHistoryNode();
    parent.getData().addProperty("PL", "B");
    history.place(3, 3, Stone.WHITE);
    BoardHistoryNode wrongColorChild = history.getCurrentHistoryNode();
    assertTrue(history.previous().isPresent());
    black.maybeAdjustPdaCalls.set(0);

    assertNull(
        board.commitEngineGamePlace(
            history, parent, true, 3, 3, Stone.WHITE, false, false, false));
    assertSame(parent, history.getCurrentHistoryNode());
    assertSame(wrongColorChild, parent.getVariation(0).orElse(null));

    Board.EngineGameMoveCommit committed =
        board.commitEngineGamePlace(
            history, parent, true, 3, 3, Stone.BLACK, false, false, false);
    assertNotNull(committed);
    assertNotSame(wrongColorChild, committed.node());
    assertSame(committed.node(), history.getCurrentHistoryNode());
    assertEquals(2, parent.numberOfChildren());
    assertSame(wrongColorChild, parent.getVariation(0).orElse(null));
    assertEquals(0, black.maybeAdjustPdaCalls.get());
  }

  @Test
  void purePassCommitRejectsWrongTurnAndNeverFastForwardsWrongColorMainChild()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode parent = history.getCurrentHistoryNode();
    parent.getData().addProperty("PL", "B");
    history.pass(Stone.WHITE);
    BoardHistoryNode wrongColorChild = history.getCurrentHistoryNode();
    assertTrue(history.previous().isPresent());
    black.maybeAdjustPdaCalls.set(0);

    assertNull(board.commitEngineGamePass(history, parent, true, Stone.WHITE, false));
    assertSame(parent, history.getCurrentHistoryNode());
    assertSame(wrongColorChild, parent.getVariation(0).orElse(null));

    Board.EngineGameMoveCommit committed =
        board.commitEngineGamePass(history, parent, true, Stone.BLACK, false);
    assertNotNull(committed);
    assertNotSame(wrongColorChild, committed.node());
    assertSame(committed.node(), history.getCurrentHistoryNode());
    assertEquals(2, parent.numberOfChildren());
    assertSame(wrongColorChild, parent.getVariation(0).orElse(null));
    assertEquals(0, black.maybeAdjustPdaCalls.get());
  }

  @Test
  void engineGameRollbackRejectsParentMismatchWithoutMovingHistoryCursor() throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode parent = history.getCurrentHistoryNode();
    Board.EngineGameMoveCommit committed =
        board.commitEngineGamePlace(
            history, parent, true, 3, 3, Stone.BLACK, false, false, false);
    assertNotNull(committed);
    BoardHistoryNode unrelatedParent =
        new BoardHistoryList(BoardData.empty(19, 19)).getCurrentHistoryNode();

    assertFalse(board.rollbackEngineGameMove(history, unrelatedParent, committed));

    assertSame(committed.node(), history.getCurrentHistoryNode());
    assertSame(committed.node(), parent.getVariation(0).orElse(null));
  }

  @Test
  void primaryPublicationFailureRollsBackNewHistoryChildWithoutSideEffects() throws Exception {
    BlockingCommitBoard board = blockingCommitBoard();
    Lizzie.board = board;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    EngineManager.EngineGamePrimaryContext primary =
        EngineManager.captureEngineGamePrimaryContext(
            black, black.currentEngineIncarnation());
    EngineManager.EngineGameMoveResponseContext move =
        EngineManager.captureEngineGameAnalysisMoveContext(primary);
    BoardHistoryNode parent = board.getHistory().getCurrentHistoryNode();
    AtomicReference<EngineManager.EngineGamePostMoveToken> result = new AtomicReference<>();
    AtomicReference<Throwable> commitFailure = new AtomicReference<>();
    Thread commit =
        new Thread(
            () -> {
              try {
                result.set(EngineManager.commitEngineGameMove(move, 3, 3, black, 0));
              } catch (Throwable failure) {
                commitFailure.set(failure);
              }
            },
            "engine-game-primary-race");

    commit.start();
    assertTrue(board.boardCommitted.await(2, TimeUnit.SECONDS));
    Lizzie.setPrimaryEngine(white);
    board.releaseCommit.countDown();
    commit.join(2000L);

    assertFalse(commit.isAlive());
    assertNull(result.get());
    assertTrue(commitFailure.get() instanceof IllegalStateException);
    assertSame(parent, board.getHistory().getCurrentHistoryNode());
    assertEquals(0, parent.numberOfChildren());
    assertEquals(0, board.clearAfterMoveCalls.get());
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
  }

  @Test
  void primaryPublicationFailureRewindsToParentButPreservesReusedVariation() throws Exception {
    RecordingBoard seed = recordingBoard();
    BoardHistoryList history = seed.getHistory();
    BoardHistoryNode parent = history.getCurrentHistoryNode();
    Board.EngineGameMoveCommit seeded =
        seed.commitEngineGamePlace(
            history, parent, true, 3, 3, Stone.BLACK, false, false, false);
    BoardHistoryNode existing = seeded.node();
    assertTrue(history.previous().isPresent());
    BlockingCommitBoard board = blockingCommitBoard(history);
    Lizzie.board = board;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    EngineManager.EngineGamePrimaryContext primary =
        EngineManager.captureEngineGamePrimaryContext(
            black, black.currentEngineIncarnation());
    EngineManager.EngineGameMoveResponseContext move =
        EngineManager.captureEngineGameAnalysisMoveContext(primary);
    AtomicReference<EngineManager.EngineGamePostMoveToken> result = new AtomicReference<>();
    AtomicReference<Throwable> commitFailure = new AtomicReference<>();
    Thread commit =
        new Thread(
            () -> {
              try {
                result.set(EngineManager.commitEngineGameMove(move, 3, 3, black, 0));
              } catch (Throwable failure) {
                commitFailure.set(failure);
              }
            },
            "engine-game-reused-primary-race");

    commit.start();
    assertTrue(board.boardCommitted.await(2, TimeUnit.SECONDS));
    Lizzie.setPrimaryEngine(white);
    board.releaseCommit.countDown();
    commit.join(2000L);

    assertNull(result.get());
    assertTrue(commitFailure.get() instanceof IllegalStateException);
    assertSame(parent, history.getCurrentHistoryNode());
    assertEquals(1, parent.numberOfChildren());
    assertSame(existing, parent.getVariation(0).orElse(null));
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
  }

  @Test
  void resetExplicitlySettlesReservedGenmoveAndRetiresItsTransaction() throws Exception {
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
    Thread sender =
        new Thread(
            () -> {
              try {
                black.sendCommand("protocol_version");
              } catch (Throwable failure) {
                senderFailure.set(failure);
              }
            },
            "engine-game-blocking-prior-write");

    sender.start();
    assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
    assertTrue(black.genmoveForPk("B", transaction));
    black.resetGtpCommandStateForTest("controlled binding reset");

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    output.releaseWrite.countDown();
    sender.join(2000L);
    assertFalse(sender.isAlive());
    assertNull(senderFailure.get());
    assertFalse(output.text().contains("genmove"));
  }

  @Test
  void exactNumberedGenmoveErrorSettlesCarrierAndFailsCurrentTransaction() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = firstCommandId(black.commandText());
    black.parseEngineGameLineForTest("?" + commandId + " controlled failure");

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void unnumberedGenmoveTerminalFramesDoNotCommitOrSettleExactTurn() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = commandIdFor(black.commandText(), "genmove B");

    black.parseEngineGameLineForTest("= D4");
    black.parseEngineGameLineForTest("play D4");
    black.parseEngineGameLineForTest("? stale predecessor failure");

    assertEquals(EngineManager.EngineGamePhase.ACTIVE, transaction.phase());
    assertEquals(1, transaction.operationsInFlightForTest());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());

    black.parseEngineGameLineForTest("=" + commandId + " D4");

    assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void unmatchedStrictStartupTerminalFramesCannotEnterOrdinaryNameParser() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    black.isLoaded = false;
    black.isCheckingName = true;

    black.sendEngineGameStartupCommandForTest("name", transaction);
    int commandId = commandIdFor(black.commandText(), "name");
    String commandsAfterName = black.commandText();

    black.dispatchReaderLineForTest("= KataGo");
    black.dispatchReaderLineForTest("=" + (commandId + 100_000) + " KataGo");

    assertTrue(black.isCheckingName);
    assertFalse(black.isKatago);
    assertFalse(black.isLoaded);
    assertEquals(commandsAfterName, black.commandText());
    assertEquals(1, transaction.operationsInFlightForTest());
  }

  @Test
  void recentParameterCacheRequiresMatchingIdAndNeverEntersGenericParser() throws Exception {
    black.pda = 0.25;
    black.isInputCommand = true;

    black.getParameterScadule(true, TimeUnit.MINUTES.toMillis(2));
    int pdaCommandId =
        commandIdFor(black.commandText(), "kata-get-param playoutDoublingAdvantage");
    int wrnCommandId =
        commandIdFor(black.commandText(), "kata-get-param analysisWideRootNoise");
    int rulesCommandId = commandIdFor(black.commandText(), "kata-get-rules");

    black.dispatchReaderLineForTest("= D4");
    black.dispatchReaderLineForTest("=" + (pdaCommandId + 100_000) + " D4");

    assertEquals(0.25, black.pda, 0.0001);
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
    assertTrue(black.isInputCommand);

    black.dispatchReaderLineForTest("=" + pdaCommandId + " 1.75");

    assertEquals(1.75, black.pda, 0.0001);
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
    assertTrue(black.isInputCommand);

    String rulesPayload =
        "{\"scoring\":\"AREA\",\"ko\":\"POSITIONAL\",\"suicide\":true,"
            + "\"tax\":\"NONE\",\"whiteHandicapBonus\":\"N\",\"hasButton\":false}";
    black.dispatchReaderLineForTest("= " + rulesPayload);
    black.dispatchReaderLineForTest(
        "=" + (rulesCommandId + 100_000) + " " + rulesPayload);

    assertEquals("", black.recentRulesLine);
    assertTrue(black.getRcentLine);

    black.dispatchReaderLineForTest("=" + rulesCommandId + " " + rulesPayload);

    assertEquals("= " + rulesPayload, black.recentRulesLine);
    assertEquals("= " + rulesPayload, Lizzie.config.currentKataGoRules);
    assertEquals(4, black.usingSpecificRules);
    assertFalse(black.getRcentLine);
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
    assertTrue(black.isInputCommand);

    // Settle the deliberately out-of-order WRN response after the read cycle has closed. It still
    // belongs to its numbered pending command and must remain isolated from the generic parser.
    black.dispatchReaderLineForTest("=" + wrnCommandId + " 0.5");
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
    assertTrue(black.isInputCommand);
    black.isInputCommand = false;
  }

  @Test
  void malformedUnnumberedPdaDiagnosticDoesNotFailExactGenmoveCarrier() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = commandIdFor(black.commandText(), "genmove B");

    black.dispatchReaderLineForTest("PDA: not-a-number");

    assertEquals(EngineManager.EngineGamePhase.ACTIVE, transaction.phase());
    assertEquals(1, transaction.operationsInFlightForTest());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());

    black.dispatchReaderLineForTest("=" + commandId + " D4");
    assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void emptyAndMalformedPassingResponsesFailClosedWithoutBoardMutation() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo emptyGame = gameInfo();
    emptyGame.isGenmove = true;
    EngineManager.EngineGameTransaction empty =
        activeTransaction(manager, emptyGame, black, 0);
    assertTrue(black.genmoveForPk("B", empty));
    int emptyId = firstCommandId(black.commandText());
    black.parseEngineGameLineForTest("=" + emptyId);
    assertEquals(EngineManager.EngineGamePhase.FAILED, empty.phase());
    assertEquals(0, empty.operationsInFlightForTest());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());

    EngineManager.resetEngineGameTransactionStateForTest();
    black.bindLiveRuntime();
    white.bindLiveRuntime();
    Lizzie.setPrimaryEngine(black);
    Lizzie.board = preparedBoard();
    EngineGameInfo passingGame = gameInfo();
    passingGame.isGenmove = true;
    EngineManager.EngineGameTransaction passing =
        activeTransaction(manager, passingGame, black, 0);
    assertTrue(black.genmoveForPk("B", passing));
    int passingId = firstCommandId(black.commandText());
    black.parseEngineGameLineForTest("=" + passingId + " Passing");
    assertTrue(EngineManager.isCurrentEngineGameTransaction(passing));
    black.parseEngineGameLineForTest("not-a-coordinate");
    assertEquals(EngineManager.EngineGamePhase.FAILED, passing.phase());
    assertEquals(0, passing.operationsInFlightForTest());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void validCoordinateAndPassResponsesCommitRealBoardAndSerializeNextGenmoveBehindPlayAck()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    Lizzie.config.enginePkPonder = true;
    white.requireResponseBeforeSend = true;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    assertTrue(black.genmoveForPk("B", transaction));
    int blackGenmoveId = commandIdFor(black.commandText(), "genmove B");
    black.parseEngineGameLineForTest("=" + blackGenmoveId + " D4");

    assertEquals(1, board.getHistory().getMoveNumber());
    assertFalse(board.getHistory().isBlacksTurn());
    assertSame(black, Lizzie.leelaz);
    assertEquals(0, EngineManager.currentEngineNo);
    assertFalse(EngineManager.isEmpty);
    assertTrue(white.commandText().contains("play B D4"));
    assertFalse(white.commandText().contains("genmove W"));

    int whitePlayId = commandIdFor(white.commandText(), "play B D4");
    white.processCommandResponseLineForTest("=" + whitePlayId);
    assertTrue(white.commandText().contains("genmove W"));
    assertTrue(
        commandLineIndex(white.commandText(), "play B D4")
            < commandLineIndex(white.commandText(), "genmove W"));

    int whiteGenmoveId = commandIdFor(white.commandText(), "genmove W");
    white.parseEngineGameLineForTest("=" + whiteGenmoveId + " pass");

    assertEquals(2, board.getHistory().getMoveNumber());
    assertTrue(board.getHistory().isBlacksTurn());
    assertSame(white, Lizzie.leelaz);
    assertEquals(1, EngineManager.currentEngineNo);
    assertFalse(EngineManager.isEmpty);
    assertTrue(black.commandText().contains("play W pass"));
    assertTrue(black.commandText().contains("genmove B"));
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void exactPostMoveClockSynchronizesBothFrozenColorsOnceBeforePlayAndGenmove()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    Lizzie.config.enginePkPonder = true;
    black.requireResponseBeforeSend = true;
    white.requireResponseBeforeSend = true;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    EngineCountDown blackClock = engineClock("kata-time_settings fischer 10 2", black, true);
    EngineCountDown whiteClock = engineClock("kata-time_settings fischer 10 2", white, false);
    assertTrue(
        EngineManager.installEngineGameCountDownsForTest(
            transaction, blackClock, whiteClock));

    assertTrue(black.genmoveForPk("B", transaction));
    int blackGenmoveId = commandIdFor(black.commandText(), "genmove B");
    black.parseEngineGameLineForTest("=" + blackGenmoveId + " D4");

    assertEquals(1, board.getHistory().getMoveNumber());
    assertEquals(1, commandLineCount(white.commandText(), "time_left W 12.00 0"));
    assertFalse(white.commandText().contains("play B D4"));
    assertFalse(white.commandText().contains("genmove W"));

    int whiteTimeId = commandIdFor(white.commandText(), "time_left W 12.00 0");
    white.processCommandResponseLineForTest("=" + whiteTimeId);
    assertTrue(white.commandText().contains("play B D4"));
    assertFalse(white.commandText().contains("genmove W"));
    int whitePlayId = commandIdFor(white.commandText(), "play B D4");
    white.processCommandResponseLineForTest("=" + whitePlayId);
    int whiteGenmoveId = commandIdFor(white.commandText(), "genmove W");
    white.parseEngineGameLineForTest("=" + whiteGenmoveId + " pass");

    assertEquals(2, board.getHistory().getMoveNumber());
    assertEquals(1, commandLineCount(black.commandText(), "time_left B 12.00 0"));
    assertFalse(black.commandText().contains("play W pass"));
    int blackTimeId = commandIdFor(black.commandText(), "time_left B 12.00 0");
    black.processCommandResponseLineForTest("=" + blackTimeId);
    assertTrue(black.commandText().contains("play W pass"));
    assertTrue(
        commandLineIndex(black.commandText(), "time_left B 12.00 0")
            < commandLineIndex(black.commandText(), "play W pass"));
    assertTrue(
        commandLineIndex(white.commandText(), "time_left W 12.00 0")
            < commandLineIndex(white.commandText(), "play B D4"));
    assertEquals(1, commandLineCount(white.commandText(), "time_left W 12.00 0"));
    assertEquals(1, commandLineCount(black.commandText(), "time_left B 12.00 0"));
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void exactTimeLeftErrorFailsOwningTransactionBeforeAnySuccessorCommand() throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    Lizzie.config.enginePkPonder = true;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    EngineCountDown whiteClock = engineClock("kata-time_settings byoyomi 0 5 3", white, false);
    assertTrue(EngineManager.installEngineGameCountDownsForTest(transaction, null, whiteClock));

    assertTrue(black.genmoveForPk("B", transaction));
    int genmoveId = commandIdFor(black.commandText(), "genmove B");
    black.parseEngineGameLineForTest("=" + genmoveId + " D4");
    int timeId = commandIdFor(white.commandText(), "time_left W 5.00 3");
    assertFalse(white.commandText().contains("play B D4"));
    assertFalse(white.commandText().contains("genmove W"));

    white.processCommandResponseLineForTest("?" + timeId + " unsupported time_left");

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(1, board.getHistory().getMoveNumber());
    assertFalse(white.commandText().contains("play B D4"));
    assertFalse(white.commandText().contains("genmove W"));
  }

  @Test
  void invalidExactClockOwnershipFailsInsteadOfStrandingCommittedMove() throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    Lizzie.config.enginePkPonder = true;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    EngineCountDown whiteClock = engineClock("kata-time_settings byoyomi 0 5 3", white, false);
    assertTrue(EngineManager.installEngineGameCountDownsForTest(transaction, null, whiteClock));
    // Corrupt the frozen color after installation to model a stale/reused clock object. The
    // committed move must fail explicitly; consuming its once-only clock claim may never stall the
    // game with no successor command.
    whiteClock.initialize(true);

    assertTrue(black.genmoveForPk("B", transaction));
    int genmoveId = commandIdFor(black.commandText(), "genmove B");
    black.parseEngineGameLineForTest("=" + genmoveId + " D4");

    assertEquals(1, board.getHistory().getMoveNumber());
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertFalse(white.commandText().contains("time_left"));
    assertFalse(white.commandText().contains("play B D4"));
    assertFalse(white.commandText().contains("genmove W"));
    assertTrue(transaction.terminalFailure().getMessage().contains("clock lost exact"));
  }

  @Test
  void countdownTickCrossingByoyomiAndTerminalPerformsNoLateEngineWrite() {
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    EngineCountDown blackClock = engineClock("kata-time_settings byoyomi 0 0.01 2", black, true);
    assertTrue(EngineManager.installEngineGameCountDownsForTest(transaction, blackClock, null));

    EngineManager.tickEngineGameCountDownForTest(transaction);
    EngineManager.tickEngineGameCountDownForTest(transaction);
    assertEquals(0, commandLineCount(black.commandText(), "time_left"));

    manager.clearEngineGame();
    EngineManager.tickEngineGameCountDownForTest(transaction);
    blackClock.countDownCentiseconds();

    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(0, commandLineCount(black.commandText(), "time_left"));
  }

  @Test
  void opponentPlayErrorFailsExactGameAndCancelsQueuedNextGenmove() throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    Lizzie.config.enginePkPonder = true;
    white.requireResponseBeforeSend = true;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    assertTrue(black.genmoveForPk("B", transaction));
    int genmoveId = commandIdFor(black.commandText(), "genmove B");
    black.parseEngineGameLineForTest("=" + genmoveId + " D4");
    int playId = commandIdFor(white.commandText(), "play B D4");
    assertFalse(white.commandText().contains("genmove W"));

    white.processCommandResponseLineForTest("?" + playId + " illegal move");

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(1, board.getHistory().getMoveNumber());
    assertFalse(white.commandText().contains("genmove W"));
  }

  @Test
  void exactPostMoveNameErrorFailsOwningGameOnce() throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, gameInfo(), black, 0);
    EngineManager.EngineGamePrimaryContext primary =
        EngineManager.captureEngineGamePrimaryContext(
            black, black.currentEngineIncarnation());
    EngineManager.EngineGameMoveResponseContext response =
        EngineManager.captureEngineGameAnalysisMoveContext(primary);
    EngineManager.EngineGamePostMoveToken postMove =
        EngineManager.commitEngineGameMove(response, 3, 3, black, 0);
    assertNotNull(postMove);
    assertTrue(black.nameCmdfornoponder(postMove));
    int nameId = commandIdFor(black.commandText(), "name");

    black.processCommandResponseLineForTest("?" + nameId + " unsupported");

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(1, board.getHistory().getMoveNumber());
  }

  @Test
  void responseOnFirstPhysicalGenmoveByteFindsInstalledCarrierAndCommitsExactlyOnce()
      throws Exception {
    ImmediateGenmoveResponseOutput output = new ImmediateGenmoveResponseOutput();
    black.bindLiveRuntime(output);
    output.arm(black, "=" + black.nextEngineGameResponseCommandIdForTest() + " D4");
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    Lizzie.config.enginePkPonder = true;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    assertTrue(black.genmoveForPk("B", transaction));

    assertNull(output.responseFailure.get());
    assertEquals(1, output.responses.get());
    assertEquals(1, board.getHistory().getMoveNumber());
    assertFalse(board.getHistory().isBlacksTurn());
    assertSame(black, Lizzie.leelaz);
    assertTrue(white.commandText().contains("play B D4"));
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void terminalAfterNumberedResponseSettlementFencesAllLateParserSideEffects()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    black.blockAfterEngineGameResponseSettlement();
    assertTrue(black.genmoveForPk("B", transaction));
    int genmoveId = commandIdFor(black.commandText(), "genmove B");
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseEngineGameLineForTest("=" + genmoveId + " resign");
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "engine-game-response-settled-terminal-race");

    parser.start();
    assertTrue(black.responseSettled.await(2, TimeUnit.SECONDS));
    manager.clearEngineGame();
    String blackCommandsAfterTerminal = black.commandText();
    String whiteCommandsAfterTerminal = white.commandText();
    int movesAfterTerminal = board.getHistory().getMoveNumber();
    black.releaseSettledResponse.countDown();
    parser.join(2000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertFalse(black.resigned);
    assertEquals(movesAfterTerminal, board.getHistory().getMoveNumber());
    assertEquals(blackCommandsAfterTerminal, black.commandText());
    assertEquals(whiteCommandsAfterTerminal, white.commandText());

    EngineGameInfo successorInfo = gameInfo();
    successorInfo.isGenmove = true;
    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, successorInfo, black, 0);
    assertTrue(EngineManager.isCurrentEngineGameTransaction(successor));
  }

  @Test
  void stopCancelsQueuedGenmoveBeforePhysicalWriteAndReturnsWithoutWaitingForPriorWrite()
      throws Exception {
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
    Thread sender =
        new Thread(
            () -> {
              try {
                black.sendCommand("protocol_version");
              } catch (Throwable failure) {
                senderFailure.set(failure);
              }
            },
            "engine-game-stop-before-genmove-write");

    sender.start();
    assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
    assertTrue(black.genmoveForPk("B", transaction));
    manager.clearEngineGame();

    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertFalse(output.text().contains("genmove"));
    output.releaseWrite.countDown();
    sender.join(2000L);
    assertFalse(sender.isAlive());
    assertNull(senderFailure.get());
    assertFalse(output.text().contains("genmove"));
  }

  @Test
  void reservedQueuedStartupBeforeBytesNeverSchedulesPhysicalForce() throws Exception {
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
    Thread sender =
        new Thread(
            () -> {
              try {
                black.sendCommand("protocol_version");
              } catch (Throwable failure) {
                senderFailure.set(failure);
              }
            },
            "engine-game-stop-before-startup-write");

    sender.start();
    assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    manager.clearEngineGame();

    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
    assertFalse(manager.hasPendingWatchdog());
    assertEquals(0, black.forceQuitAttempts.get());
    assertFalse(output.text().contains("clear_cache"));
    output.releaseWrite.countDown();
    sender.join(2000L);
    assertFalse(sender.isAlive());
    assertNull(senderFailure.get());
    assertFalse(output.text().contains("clear_cache"));
  }

  @Test
  void genericInFlightOperationNeverSchedulesPhysicalForce() throws Exception {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    CountDownLatch operationEntered = new CountDownLatch(1);
    CountDownLatch releaseOperation = new CountDownLatch(1);
    AtomicReference<Throwable> operationFailure = new AtomicReference<>();
    Thread operation =
        new Thread(
            () -> {
              try {
                EngineManager.runIfCurrentEngineGameOperation(
                    transaction,
                    () -> {
                      operationEntered.countDown();
                      await(releaseOperation);
                    });
              } catch (Throwable failure) {
                operationFailure.set(failure);
              }
            },
            "generic-engine-game-operation");

    operation.start();
    assertTrue(operationEntered.await(2, TimeUnit.SECONDS));
    manager.clearEngineGame();

    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(1, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
    assertFalse(manager.hasPendingWatchdog());
    assertEquals(0, black.forceQuitAttempts.get());
    assertNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));

    releaseOperation.countDown();
    operation.join(2_000L);
    assertFalse(operation.isAlive());
    assertNull(operationFailure.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertNotNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));
  }

  @Test
  void physicalRequestWithoutResponseForcesOnceAndCompletesRetirement() throws Exception {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    assertEquals(1, transaction.operationsInFlightForTest());
    assertEquals(1, transaction.openPhysicalRequestsForTest());
    manager.clearEngineGame();

    assertTrue(manager.hasPendingWatchdog());
    assertNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));
    manager.runWatchdog();

    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(1, black.successfulForceQuits.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
    assertNotNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));
  }

  @Test
  void naturalResponseBeforeGracePreventsPhysicalForce() throws Exception {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    int commandId = commandIdFor(black.commandText(), "clear_cache");
    manager.clearEngineGame();
    assertTrue(manager.hasPendingWatchdog());

    black.processCommandResponseLineForTest("=" + commandId);
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
    manager.runWatchdog();

    assertEquals(0, black.forceQuitAttempts.get());
    assertEquals(0, black.successfulForceQuits.get());
  }

  @Test
  void responseRacingClaimedForceClosesPhysicalLeaseExactlyOnce() throws Exception {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    black.blockForceQuit();
    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    int commandId = commandIdFor(black.commandText(), "clear_cache");
    manager.clearEngineGame();
    AtomicReference<Throwable> watchdogFailure = new AtomicReference<>();
    Thread watchdog =
        new Thread(
            () -> {
              try {
                manager.runWatchdog();
              } catch (Throwable failure) {
                watchdogFailure.set(failure);
              }
            },
            "engine-game-response-force-race");

    watchdog.start();
    assertTrue(black.forceQuitEntered.await(2, TimeUnit.SECONDS));
    black.processCommandResponseLineForTest("=" + commandId);
    assertEquals(
        1,
        transaction.operationsInFlightForTest(),
        "a response cannot steal a lease after the force owner has claimed it");
    black.releaseForceQuit.countDown();
    watchdog.join(2_000L);

    assertFalse(watchdog.isAlive());
    assertNull(watchdogFailure.get());
    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
  }

  @Test
  void watchdogCannotForceReplacementAfterOldBindingWasRebound() {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    Object oldBinding = black.currentEngineIncarnation();
    EngineManager.EngineGamePhysicalRequestLease request =
        EngineManager.claimEngineGameStartupOutput(transaction, black, oldBinding);
    assertNotNull(request);
    manager.clearEngineGame();

    black.bindLiveRuntime();
    Object replacement = black.currentEngineIncarnation();
    assertNotSame(oldBinding, replacement);
    manager.runWatchdog();

    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(0, black.successfulForceQuits.get());
    assertSame(replacement, black.currentEngineIncarnation());
    assertTrue(black.started);
    assertTrue(black.isLoaded);
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void sameBindingWithMultiplePhysicalRequestsPerformsOneAbort() {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    black.sendEngineGameStartupCommandForTest("name", transaction);
    assertEquals(2, transaction.operationsInFlightForTest());
    assertEquals(2, transaction.openPhysicalRequestsForTest());
    manager.clearEngineGame();
    manager.runWatchdog();

    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(1, black.successfulForceQuits.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
  }

  @Test
  void forceFailureIsDiagnosedButStillReleasesEveryClaimedLease() {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    RuntimeException forceFailure = new IllegalStateException("controlled exact force failure");
    black.forceQuitFailure = forceFailure;

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    black.sendEngineGameStartupCommandForTest("name", transaction);
    manager.clearEngineGame();
    manager.runWatchdog();

    assertSame(forceFailure, transaction.terminalFailure());
    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(0, black.successfulForceQuits.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
    assertNotNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));
  }

  @Test
  void blackAndWhitePhysicalStreamsAreForcedIndependently() {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    white.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    assertEquals(2, transaction.operationsInFlightForTest());
    manager.clearEngineGame();
    manager.runWatchdog();

    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(1, white.forceQuitAttempts.get());
    assertEquals(1, black.successfulForceQuits.get());
    assertEquals(1, white.successfulForceQuits.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
  }

  @Test
  void schedulerFailureUsesIndependentFallbackAndCannotStrandRetirement() throws Exception {
    FailingWatchdogSchedulerManager manager =
        installManager(new FailingWatchdogSchedulerManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    black.blockForceQuit();
    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);

    manager.clearEngineGame();
    assertTrue(black.forceQuitEntered.await(2, TimeUnit.SECONDS));
    black.releaseForceQuit.countDown();
    assertTrue(black.forceQuitFinished.await(2, TimeUnit.SECONDS));
    awaitNoEngineGameOperations(transaction);

    assertSame(manager.schedulingFailure, transaction.terminalFailure());
    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, transaction.openPhysicalRequestsForTest());
    assertNotNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));
  }

  @Test
  void graceConfigurationFailureFallsBackToDefaultBound() {
    WatchdogEngineManager manager = installManager(new WatchdogEngineManager(allEngines()));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    RuntimeException graceFailure =
        new IllegalStateException("controlled physical-watchdog grace failure");
    manager.graceFailure = graceFailure;
    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);

    manager.clearEngineGame();

    assertSame(graceFailure, transaction.terminalFailure());
    assertTrue(manager.hasPendingWatchdog());
    manager.runWatchdog();
    assertEquals(1, black.forceQuitAttempts.get());
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void terminalDoesNotWaitForParserQueuedBehindBlockedExactStateWrite() throws Exception {
    ArmableBlockingWriteOutput output = new ArmableBlockingWriteOutput();
    black.bindLiveRuntime(output);
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 30", transaction);
    int analyzeId = commandIdFor(black.commandText(), "kata-analyze B 30");
    black.processCommandResponseLineForTest("=" + analyzeId);
    black.blockAfterAnalysisInfoAdmissionSnapshotCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
    AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    CountDownLatch parserCompleted = new CountDownLatch(1);
    CountDownLatch terminalCompleted = new CountDownLatch(1);
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              } finally {
                parserCompleted.countDown();
              }
            },
            "engine-game-parser-before-exact-state-write");
    Thread sender =
        new Thread(
            () -> {
              try {
                black.sendEngineGameStartupCommandForTest("clear_board", transaction);
              } catch (Throwable failure) {
                senderFailure.set(failure);
              }
            },
            "engine-game-blocked-exact-state-write");
    Thread terminal =
        new Thread(
            () -> {
              try {
                manager.clearEngineGame();
              } catch (Throwable failure) {
                terminalFailure.set(failure);
              } finally {
                terminalCompleted.countDown();
              }
            },
            "engine-game-state-write-terminal");

    boolean parserReturnedBeforeTransport;
    boolean terminalReturnedBeforeTransport;
    parser.start();
    try {
      assertTrue(black.analysisInfoSnapshotCaptured.await(2, TimeUnit.SECONDS));
      output.arm();
      sender.start();
      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
      assertTrue(black.getBestMoves().isEmpty());
      black.releaseAnalysisInfoSnapshot.countDown();
      parserReturnedBeforeTransport = parserCompleted.await(2, TimeUnit.SECONDS);
      terminal.start();
      terminalReturnedBeforeTransport = terminalCompleted.await(2, TimeUnit.SECONDS);
    } finally {
      black.releaseAnalysisInfoSnapshot.countDown();
      output.releaseWrite.countDown();
    }
    terminal.join(2_000L);
    sender.join(2_000L);
    parser.join(2_000L);

    assertTrue(parserReturnedBeforeTransport);
    assertTrue(terminalReturnedBeforeTransport);
    assertFalse(parser.isAlive());
    assertFalse(terminal.isAlive());
    assertFalse(sender.isAlive());
    assertNull(parserFailure.get());
    assertNull(senderFailure.get());
    assertNull(terminalFailure.get());
    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
  }

  @Test
  void terminalDoesNotWaitForGenmoveParserBehindBlockedPhysicalWrite() throws Exception {
    ArmableBlockingWriteOutput output = new ArmableBlockingWriteOutput();
    black.bindLiveRuntime(output);
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    black.isKatago = true;
    output.arm();
    AtomicBoolean genmoveAccepted = new AtomicBoolean();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
    AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    CountDownLatch parserCompleted = new CountDownLatch(1);
    CountDownLatch terminalCompleted = new CountDownLatch(1);
    Thread sender =
        new Thread(
            () -> {
              try {
                genmoveAccepted.set(black.genmoveForPk("B", transaction));
              } catch (Throwable failure) {
                senderFailure.set(failure);
              }
            },
            "engine-game-blocked-genmove-write");
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseEngineGameLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              } finally {
                parserCompleted.countDown();
              }
            },
            "engine-game-info-during-blocked-genmove-write");
    Thread terminal =
        new Thread(
            () -> {
              try {
                manager.clearEngineGame();
              } catch (Throwable failure) {
                terminalFailure.set(failure);
              } finally {
                terminalCompleted.countDown();
              }
            },
            "engine-game-genmove-write-terminal");

    boolean parserReturnedBeforeTransport;
    boolean terminalReturnedBeforeTransport;
    sender.start();
    try {
      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
      parser.start();
      parserReturnedBeforeTransport = parserCompleted.await(2, TimeUnit.SECONDS);
      terminal.start();
      terminalReturnedBeforeTransport = terminalCompleted.await(2, TimeUnit.SECONDS);
      assertEquals(1, transaction.operationsInFlightForTest());
    } finally {
      output.releaseWrite.countDown();
    }
    terminal.join(2_000L);
    sender.join(2_000L);
    parser.join(2_000L);

    assertTrue(parserReturnedBeforeTransport);
    assertTrue(terminalReturnedBeforeTransport);
    assertFalse(parser.isAlive());
    assertFalse(terminal.isAlive());
    assertFalse(sender.isAlive());
    assertNull(parserFailure.get());
    assertNull(senderFailure.get());
    assertNull(terminalFailure.get());
    assertTrue(genmoveAccepted.get());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    int commandId = commandIdFor(output.text(), "kata-genmove_analyze B 0");
    black.parseEngineGameLineForTest("?" + commandId + " retired request");
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void startupCommandErrorFailsPreparingTransactionAndReleasesPhysicalLease() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    int commandId = commandIdFor(black.commandText(), "clear_cache");
    assertEquals(1, transaction.operationsInFlightForTest());
    black.processCommandResponseLineForTest("?" + commandId + " unsupported startup command");

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void exactStartupCommandRequiresItsMatchingProtocolId() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());

    black.sendEngineGameStartupCommandForTest("clear_cache", transaction);
    int commandId = commandIdFor(black.commandText(), "clear_cache");
    assertEquals(1, transaction.operationsInFlightForTest());

    black.processCommandResponseLineForTest("=");
    assertEquals(1, transaction.operationsInFlightForTest());
    black.processCommandResponseLineForTest("=" + commandId);

    assertEquals(0, transaction.operationsInFlightForTest());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void strictStartupResponseTimeoutSettlesPhysicalPermitAndQuarantinesLateResponse()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    AtomicInteger responses = new AtomicInteger();
    Runnable response = responses::incrementAndGet;

    black.sendEngineGameStartupCommandWithResponseForTest(
        "kata-set-param analysisWideRootNoise 0.1", transaction, response);
    int commandId =
        commandIdFor(black.commandText(), "kata-set-param analysisWideRootNoise 0.1");
    assertEquals(1, transaction.operationsInFlightForTest());

    black.retireTimedOutNormalCommandForTest(response);

    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, responses.get());
    black.processCommandResponseLineForTest("=" + commandId);
    assertEquals(0, transaction.operationsInFlightForTest());
    assertEquals(0, responses.get());
  }

  @Test
  void queuedStartupResponseTimeoutCancelsBeforeAnyBytesAndReleasesRetirement()
      throws Exception {
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    Runnable response = () -> {};
    Thread blocker = new Thread(() -> black.sendCommand("protocol_version"));

    blocker.start();
    assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
    black.sendEngineGameStartupCommandWithResponseForTest("clear_cache", transaction, response);

    black.retireTimedOutNormalCommandForTest(response);
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertFalse(output.text().contains("clear_cache"));

    output.releaseWrite.countDown();
    blocker.join(2_000L);
    assertFalse(blocker.isAlive());
    assertFalse(output.text().contains("clear_cache"));
  }

  @Test
  void freshBootstrapCommandsWriteBeforeLoadedAndUseOneExactPhysicalPermit() throws Exception {
    black.started = false;
    black.isLoaded = false;
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, game);

    black.sendEngineGameStartupCommandForTest("name", transaction);
    int nameId = commandIdFor(black.commandText(), "name");
    assertEquals(1, transaction.operationsInFlightForTest());
    black.processCommandResponseLineForTest("=" + nameId + " KataGo");
    assertEquals(0, transaction.operationsInFlightForTest());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));

    black.started = true;
    black.isLoaded = true;
    AtomicReference<Boolean> accepted = new AtomicReference<>(false);
    assertTrue(EngineManager.transitionEngineGameToDispatched(transaction));
    assertTrue(
        EngineManager.activateEngineGameTransaction(
            transaction,
            black,
            0,
            black.currentEngineIncarnation(),
            white.currentEngineIncarnation()));
    Leelaz.runWithEngineGameStartupCommandContext(
        transaction, () -> accepted.set(black.genmoveForPk("B", transaction)));
    assertTrue(accepted.get());
    assertEquals(
        1,
        transaction.operationsInFlightForTest(),
        "the dedicated genmove carrier must not receive a second startup permit");
  }

  @Test
  void stoppedGameRejectsDeferredNamePostActionBeforeAnyDerivedCommand() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    Lizzie.config.chkLzsaiEngineMem = true;
    Lizzie.config.autoLoadLzsaiEngineMem = true;
    Lizzie.config.txtLzsaiEngineMem = "256";
    black.isLoaded = false;
    black.isCheckingName = true;
    black.blockStartupPostActionWorker();

    black.sendEngineGameStartupCommandForTest("name", transaction);
    int nameId = commandIdFor(black.commandText(), "name");
    black.parseStartupCommandResponseForTest("=" + nameId + " LeelaZero");
    assertTrue(black.startupPostWorkerEntered.await(2, TimeUnit.SECONDS));

    manager.clearEngineGame();
    black.releaseStartupPostWorker.countDown();
    black.startupPostWorker.join(2_000L);

    assertFalse(black.startupPostWorker.isAlive());
    assertNull(black.startupPostWorkerFailure.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertFalse(black.commandText().contains("lz-setoption"));
    assertFalse(black.isLoaded);
  }

  @Test
  void stoppedGameCannotPublishLoadedAfterLastPostCommandWasPhysicallyWritten()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    Lizzie.config.chkLzsaiEngineMem = true;
    Lizzie.config.autoLoadLzsaiEngineMem = true;
    Lizzie.config.txtLzsaiEngineMem = "256";
    black.isLoaded = false;
    black.isCheckingName = true;
    black.blockAfterStartupPostActionCommands();

    black.sendEngineGameStartupCommandForTest("name", transaction);
    int nameId = commandIdFor(black.commandText(), "name");
    black.parseStartupCommandResponseForTest("=" + nameId + " LeelaZero");
    assertTrue(black.startupPostCommandsEntered.await(2, TimeUnit.SECONDS));
    assertTrue(black.commandText().contains("lz-setoption"));
    int setOptionId =
        commandIdFor(
            black.commandText(), "lz-setoption name Maximum Memory Use (MiB) value 256");

    manager.clearEngineGame();
    black.releaseStartupPostCommands.countDown();
    black.startupPostWorker.join(2_000L);

    assertFalse(black.startupPostWorker.isAlive());
    assertNull(black.startupPostWorkerFailure.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertFalse(black.isLoaded);
    assertEquals(1, transaction.operationsInFlightForTest());

    black.processCommandResponseLineForTest("=" + setOptionId);
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void retiredNameResponseSettlesWithoutEnteringClassifierOrMutatingReusedEndpoint()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    black.isLoaded = false;
    black.isCheckingName = true;
    black.blockAfterStartupResponseOwnerCapture();
    int previousLeelaVersion = Lizzie.config.leelaversion;
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();

    black.sendEngineGameStartupCommandForTest("name", transaction);
    int nameId = commandIdFor(black.commandText(), "name");
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseStartupCommandResponseForTest("=" + nameId + " KataGo");
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "retired-engine-game-name-response");
    parser.start();
    assertTrue(black.startupResponseOwnerCaptured.await(2, TimeUnit.SECONDS));

    manager.clearEngineGame();
    black.releaseStartupResponseOwner.countDown();
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
    assertTrue(black.isCheckingName);
    assertFalse(black.isKatago);
    assertFalse(black.isLoaded);
    assertEquals(previousLeelaVersion, Lizzie.config.leelaversion);
  }

  @Test
  void analysisOwnerInstalledAtPhysicalWriteSuppressesEarlyOutputUntilActivation()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    assertTrue(EngineManager.transitionEngineGameToDispatched(transaction));
    assertTrue(
        EngineManager.bindEngineGameStartupIncarnation(
            transaction, black, black.currentEngineIncarnation()));
    assertTrue(
        EngineManager.bindEngineGameStartupIncarnation(
            transaction, white, white.currentEngineIncarnation()));
    black.isKatago = true;

    black.sendEngineGameStartupCommandForTest("kata-analyze B 1", transaction);

    assertEquals("EXACT_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());

    int commandId = commandIdFor(black.commandText(), "kata-analyze B 1");
    black.processCommandResponseLineForTest("=" + commandId);
    assertTrue(
        EngineManager.activateEngineGameTransaction(
            transaction,
            black,
            0,
            black.currentEngineIncarnation(),
            white.currentEngineIncarnation()));

    black.parseAnalysisLineForTest(kataAnalysisInfo());

    assertEquals(1, black.exactAnalysisActions.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void displayPublicationFailureDoesNotSplitPayloadOrFailExactGame() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 3", transaction);
    int commandId = commandIdFor(black.commandText(), "kata-analyze B 3");
    black.processCommandResponseLineForTest("=" + commandId);
    black.setBestMovesForEngineGameTest(List.of(move("Q16", 20, 55.0)));
    black.analysisDisplayPublicationFailure =
        new IllegalStateException("controlled display publication failure");

    black.parseAnalysisLineForTest(kataAnalysisInfo());

    assertEquals(EngineManager.EngineGamePhase.ACTIVE, transaction.phase());
    assertEquals(1, black.getBestMoves().size());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    assertEquals(40, black.getBestMovesPlayouts());
    assertEquals(2.5, black.scoreMean, 0.0001);
    assertEquals(0.75, black.scoreStdev, 0.0001);
  }

  @Test
  void terminalAfterExactOutputRouteCaptureDropsLateMainInfoWithoutOrdinaryFallback()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 2", transaction);
    int commandId = commandIdFor(black.commandText(), "kata-analyze B 2");
    black.processCommandResponseLineForTest("=" + commandId);
    black.blockAfterAnalysisOutputRouteCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "retired-analysis-main-info");

    parser.start();
    assertTrue(black.analysisRouteCaptured.await(2, TimeUnit.SECONDS));
    manager.clearEngineGame();
    black.releaseAnalysisRoute.countDown();
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void stdoutColorStatusUsesCapturedOwnerAndRejectsSameBindingSuccessor()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.sendEngineGameStartupCommandForTest("lz-analyze 17", transaction);
    int firstCommandId = commandIdFor(black.commandText(), "lz-analyze 17");
    black.processCommandResponseLineForTest("=" + firstCommandId);

    black.parseAnalysisLineForTest("| ST x 17, x x 6.5, x x x x x x");

    assertTrue(black.isColorEngine);
    assertEquals(17, black.stage);
    assertEquals(6.5f, black.komi);

    black.blockAfterAnalysisOutputRouteCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(
                    "| ST x 29, x x 9.5, x x x x x x");
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "stdout-color-status-owner-successor");
    parser.start();
    try {
      assertTrue(black.analysisRouteCaptured.await(2, TimeUnit.SECONDS));
      black.sendEngineGameStartupCommandForTest("lz-analyze 29", transaction);
    } finally {
      black.releaseAnalysisRoute.countDown();
    }
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(17, black.stage);
    assertEquals(6.5f, black.komi);
    int successorCommandId = commandIdFor(black.commandText(), "lz-analyze 29");
    black.processCommandResponseLineForTest("=" + successorCommandId);
  }

  @Test
  void stderrMalkovichPdaUsesCapturedOwnerAndRejectsSameBindingSuccessor()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 18", transaction);
    int firstCommandId = commandIdFor(black.commandText(), "kata-analyze B 18");
    black.processCommandResponseLineForTest("=" + firstCommandId);

    black.parseAnalysisErrorLineForTest("MALKOVICH: (PDA 2.75)");
    assertEquals(2.75, black.pda);

    black.blockAfterAnalysisOutputRouteCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisErrorLineForTest("MALKOVICH: (PDA 9.75)");
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "stderr-malkovich-owner-successor");
    parser.start();
    try {
      assertTrue(black.analysisRouteCaptured.await(2, TimeUnit.SECONDS));
      black.sendEngineGameStartupCommandForTest("kata-analyze W 19", transaction);
    } finally {
      black.releaseAnalysisRoute.countDown();
    }
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(2.75, black.pda);
    int successorCommandId = commandIdFor(black.commandText(), "kata-analyze W 19");
    black.processCommandResponseLineForTest("=" + successorCommandId);
  }

  @Test
  void analysisInfoClearAfterAdmissionSnapshotDropsIngressInfo() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 20");
    black.processCommandResponseLineForTest("=");
    black.setBestMovesForEngineGameTest(List.of(move("Q16", 20, 55.0)));
    black.blockAfterAnalysisInfoAdmissionSnapshotCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "analysis-info-epoch-clear-race");
    parser.start();
    try {
      assertTrue(black.analysisInfoSnapshotCaptured.await(2, TimeUnit.SECONDS));
      black.clearBestMoves();
    } finally {
      black.releaseAnalysisInfoSnapshot.countDown();
    }
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
    assertEquals(0, black.ordinaryAnalysisActions.get());
  }

  @Test
  void sameBindingExactSuccessorCancelsOldDeferredPrimaryPublication() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), white, 1);
    assertSame(white, Lizzie.leelaz);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 21", transaction);
    int firstCommandId = commandIdFor(black.commandText(), "kata-analyze B 21");
    black.processCommandResponseLineForTest("=" + firstCommandId);
    black.blockBeforeAnalysisPrimaryPublication();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "exact-analysis-deferred-primary-owner-race");
    parser.start();
    assertTrue(black.analysisPrimaryPublicationEntered.await(2, TimeUnit.SECONDS));

    black.sendEngineGameStartupCommandForTest("kata-analyze W 22", transaction);
    int successorCommandId = commandIdFor(black.commandText(), "kata-analyze W 22");
    black.releaseAnalysisPrimaryPublication.countDown();
    parser.join(2_000L);
    black.processCommandResponseLineForTest("=" + successorCommandId);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertSame(white, Lizzie.leelaz);
    assertEquals("EXACT_CURRENT", black.analysisOutputRouteForTest());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void engineGameAdmissionWinsBetweenOrdinaryRouteAndActionWithoutAutoplayLeak()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 3");
    black.processCommandResponseLineForTest("=");
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.blockBeforeOrdinaryAnalysisOutputAdmission();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "ordinary-analysis-engine-game-admission-race");

    parser.start();
    assertTrue(black.ordinaryAnalysisAdmissionEntered.await(2, TimeUnit.SECONDS));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    black.releaseOrdinaryAnalysisAdmission.countDown();
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void sameBindingOrdinarySuccessorCancelsOldOwnerPostAction() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 31");
    black.processCommandResponseLineForTest("=");
    black.blockBeforeOrdinaryAnalysisOutputAdmission();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "ordinary-analysis-same-binding-owner-race");
    parser.start();
    assertTrue(black.ordinaryAnalysisAdmissionEntered.await(2, TimeUnit.SECONDS));

    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 32");
    black.releaseOrdinaryAnalysisAdmission.countDown();
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
  }

  @Test
  void ordinaryInfoTargetChangeAfterCommitPreventsAutoPlay() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 34");
    black.processCommandResponseLineForTest("=");
    black.blockBeforeOrdinaryAnalysisOutputAdmission();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "ordinary-analysis-display-target-race");

    parser.start();
    assertTrue(black.ordinaryAnalysisAdmissionEntered.await(2, TimeUnit.SECONDS));
    frame.displayNodeOverride =
        new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
    black.releaseOrdinaryAnalysisAdmission.countDown();
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void boardClearKomiPairReleasesEndpointBeforeAnalysisAdmissionDrain() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.blockInsideOrdinaryAnalysisOutputAction(
        () -> black.sendCommand("protocol_version"));
    black.trackStatefulOrdinaryPairAdmission();
    AtomicBoolean forwardAccepted = new AtomicBoolean();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    AtomicReference<Throwable> forwardFailure = new AtomicReference<>();
    CountDownLatch parserCompleted = new CountDownLatch(1);
    CountDownLatch forwardCompleted = new CountDownLatch(1);
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              } finally {
                parserCompleted.countDown();
              }
            },
            "ordinary-analysis-action-before-board-clear-pair");
    Thread forward =
        new Thread(
            () -> {
              try {
                forwardAccepted.set(
                    black.forwardBoardClearWithKomi("komi 7.5", 7.5, false));
              } catch (Throwable failure) {
                forwardFailure.set(failure);
              } finally {
                forwardCompleted.countDown();
              }
            },
            "board-clear-pair-after-analysis-admission");

    parser.start();
    try {
      assertTrue(black.ordinaryAnalysisActionEntered.await(2, TimeUnit.SECONDS));
      forward.start();
      assertTrue(black.statefulOrdinaryPairAdmitted.await(2, TimeUnit.SECONDS));
      black.releaseOrdinaryAnalysisAction.countDown();
      assertTrue(parserCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(forwardCompleted.await(2, TimeUnit.SECONDS));
    } finally {
      black.releaseOrdinaryAnalysisAction.countDown();
    }
    parser.join(2_000L);
    forward.join(2_000L);

    assertFalse(parser.isAlive());
    assertFalse(forward.isAlive());
    assertNull(parserFailure.get());
    assertNull(forwardFailure.get());
    assertTrue(forwardAccepted.get());
    String commands = black.commandText();
    assertTrue(commands.indexOf("clear_board") < commands.indexOf("komi 7.5"), commands);
  }

  @Test
  void boardClearKomiPairCannotReorderPrimaryAndMirrorWithConcurrentKomi() throws Exception {
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = white;
    black.blockAfterStatefulOrdinaryPairAdmission();
    AtomicBoolean pairAccepted = new AtomicBoolean();
    AtomicReference<Throwable> pairFailure = new AtomicReference<>();
    AtomicReference<Throwable> komiFailure = new AtomicReference<>();
    CountDownLatch concurrentKomiStarted = new CountDownLatch(1);
    CountDownLatch concurrentKomiCompleted = new CountDownLatch(1);
    Thread pair =
        new Thread(
            () -> {
              try {
                pairAccepted.set(
                    black.forwardBoardClearWithKomi("komi 6.5", 6.5, false));
              } catch (Throwable failure) {
                pairFailure.set(failure);
              }
            },
            "ordered-board-clear-komi-pair");
    Thread concurrentKomi =
        new Thread(
            () -> {
              concurrentKomiStarted.countDown();
              try {
                black.komi(7.5);
              } catch (Throwable failure) {
                komiFailure.set(failure);
              } finally {
                concurrentKomiCompleted.countDown();
              }
            },
            "ordered-concurrent-komi");

    pair.start();
    try {
      assertTrue(black.statefulOrdinaryPairAdmitted.await(2, TimeUnit.SECONDS));
      concurrentKomi.start();
      assertTrue(concurrentKomiStarted.await(2, TimeUnit.SECONDS));
      assertTrue(
          concurrentKomiCompleted.await(2, TimeUnit.SECONDS),
          "dual-endpoint admission must not hold a caller lock while transport is deferred");
    } finally {
      black.releaseStatefulOrdinaryPairAdmission.countDown();
    }
    pair.join(2_000L);
    concurrentKomi.join(2_000L);

    assertFalse(pair.isAlive());
    assertFalse(concurrentKomi.isAlive());
    assertNull(pairFailure.get());
    assertNull(komiFailure.get());
    assertTrue(pairAccepted.get());
    assertEquals(7.5f, black.komi);
    String primaryCommands = black.commandText();
    String secondaryCommands = white.commandText();
    assertTrue(
        commandLineIndex(primaryCommands, "clear_board")
            < commandLineIndex(primaryCommands, "komi 6.5"));
    assertTrue(
        commandLineIndex(primaryCommands, "komi 6.5")
            < commandLineIndex(primaryCommands, "komi 7.5"));
    assertTrue(
        commandLineIndex(secondaryCommands, "clear_board")
            < commandLineIndex(secondaryCommands, "komi 6.5"));
    assertTrue(
        commandLineIndex(secondaryCommands, "komi 6.5")
            < commandLineIndex(secondaryCommands, "komi 7.5"));
  }

  @Test
  void boardClearKomiPairCannotReorderWithConcurrentMirroredPlay() throws Exception {
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = white;
    black.blockAfterStatefulOrdinaryPairAdmission();
    AtomicBoolean pairAccepted = new AtomicBoolean();
    AtomicReference<Throwable> pairFailure = new AtomicReference<>();
    AtomicReference<Throwable> playFailure = new AtomicReference<>();
    CountDownLatch playCompleted = new CountDownLatch(1);
    Thread pair =
        new Thread(
            () -> {
              try {
                pairAccepted.set(
                    black.forwardBoardClearWithKomi("komi 6.5", 6.5, false));
              } catch (Throwable failure) {
                pairFailure.set(failure);
              }
            },
            "board-clear-komi-before-mirrored-play");
    Thread play =
        new Thread(
            () -> {
              try {
                black.playMoveNoPonder(Stone.BLACK, "Q16");
              } catch (Throwable failure) {
                playFailure.set(failure);
              } finally {
                playCompleted.countDown();
              }
            },
            "mirrored-play-after-board-clear-komi");

    pair.start();
    try {
      assertTrue(black.statefulOrdinaryPairAdmitted.await(2, TimeUnit.SECONDS));
      play.start();
      assertTrue(playCompleted.await(2, TimeUnit.SECONDS));
    } finally {
      black.releaseStatefulOrdinaryPairAdmission.countDown();
    }
    pair.join(2_000L);
    play.join(2_000L);

    assertFalse(pair.isAlive());
    assertFalse(play.isAlive());
    assertNull(pairFailure.get());
    assertNull(playFailure.get());
    assertTrue(pairAccepted.get());
    for (String commands : List.of(black.commandText(), white.commandText())) {
      assertTrue(
          commandLineIndex(commands, "clear_board")
              < commandLineIndex(commands, "komi 6.5"));
      assertTrue(
          commandLineIndex(commands, "komi 6.5")
              < commandLineIndex(commands, "play B Q16"));
    }
  }

  @Test
  void delayedOlderKomiPublicationCannotOverwriteNewerAdmission() throws Exception {
    black.blockAfterNextCurrentStatefulAdmissionCheck();
    AtomicReference<Throwable> olderFailure = new AtomicReference<>();
    AtomicReference<Throwable> newerFailure = new AtomicReference<>();
    Thread older =
        new Thread(
            () -> {
              try {
                black.komi(6.5);
              } catch (Throwable failure) {
                olderFailure.set(failure);
              }
            },
            "delayed-older-komi-publication");
    Thread newer =
        new Thread(
            () -> {
              try {
                black.komi(7.5);
              } catch (Throwable failure) {
                newerFailure.set(failure);
              }
            },
            "newer-komi-publication");

    older.start();
    try {
      assertTrue(black.statefulOrdinaryPublicationChecked.await(2, TimeUnit.SECONDS));
      newer.start();
      awaitThreadState(newer, Thread.State.BLOCKED);
      assertTrue(newer.isAlive());
    } finally {
      black.releaseStatefulOrdinaryPublication.countDown();
    }
    older.join(2_000L);
    newer.join(2_000L);

    assertFalse(older.isAlive());
    assertFalse(newer.isAlive());
    assertNull(olderFailure.get());
    assertNull(newerFailure.get());
    assertEquals(7.5f, black.komi);
    assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());
  }

  @Test
  void failedPrimaryMirroredPlayCannotReplayLaterOnSecondary() {
    installManager();
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = white;
    black.requestCurrentReaderShutdownForTest();

    assertThrows(
        IllegalStateException.class,
        () -> black.playMoveNoPonder(Stone.BLACK, "Q16"));

    white.sendCommandNoLeelaz2("protocol_version");
    assertFalse(white.commandText().contains("play B Q16"), white.commandText());
    assertTrue(white.commandText().contains("protocol_version"), white.commandText());
  }

  @Test
  void failedPrimaryStateBatchCannotReplayLaterOnSecondary() {
    installManager();
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = white;
    black.requestCurrentReaderShutdownForTest();

    assertThrows(
        IllegalStateException.class,
        () -> black.forwardBoardClearWithKomi("komi 7.5", 7.5, false));

    white.sendCommandNoLeelaz2("protocol_version");
    String secondaryCommands = white.commandText();
    assertFalse(secondaryCommands.contains("clear_board"), secondaryCommands);
    assertFalse(secondaryCommands.contains("komi 7.5"), secondaryCommands);
    assertTrue(secondaryCommands.contains("protocol_version"), secondaryCommands);
  }

  @Test
  void failedSecondPrimaryStateCommandCancelsEntireSecondaryBatch() {
    ShutdownAfterFirstFlushOutput primaryOutput = new ShutdownAfterFirstFlushOutput();
    black.bindLiveRuntime(primaryOutput);
    primaryOutput.arm(black);
    installManager();
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = white;

    assertThrows(
        IllegalStateException.class,
        () -> black.forwardBoardClearWithKomi("komi 7.5", 7.5, false));

    white.sendCommandNoLeelaz2("protocol_version");
    String secondaryCommands = white.commandText();
    assertTrue(primaryOutput.text().contains("clear_board"), primaryOutput.text());
    assertFalse(primaryOutput.text().contains("komi 7.5"), primaryOutput.text());
    assertFalse(secondaryCommands.contains("clear_board"), secondaryCommands);
    assertFalse(secondaryCommands.contains("komi 7.5"), secondaryCommands);
    assertTrue(secondaryCommands.contains("protocol_version"), secondaryCommands);
  }

  @Test
  void commandStateResetCancelsQueuedMirrorBeforeLaterSecondaryDrain() throws Exception {
    black.requireResponseBeforeSend = true;
    white.requireResponseBeforeSend = true;
    black.sendCommandWithResponseForTest("name", () -> {});
    white.sendCommandWithResponseForTest("name", () -> {});
    Lizzie.config.extraMode = ExtraMode.Double_Engine;
    Lizzie.leelaz2 = white;

    black.playMoveNoPonder(Stone.BLACK, "Q16");
    assertFalse(black.commandText().contains("play B Q16"), black.commandText());
    assertFalse(white.commandText().contains("play B Q16"), white.commandText());

    black.resetGtpCommandStateForTest("controlled paired-command reset");
    white.processCommandResponseLineForTest("= white");
    white.sendCommandNoLeelaz2("protocol_version");

    String secondaryCommands = white.commandText();
    assertFalse(secondaryCommands.contains("play B Q16"), secondaryCommands);
    assertTrue(secondaryCommands.contains("protocol_version"), secondaryCommands);
  }

  @Test
  void currentAnalysisOwnerSurvivesUnansweredOrdinaryCommandButNotPhysicalStateSuccessor()
      throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 34");
    black.sendCommandWithResponseForTest("name", () -> {});

    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);

    black.playMoveNoPonder(Stone.BLACK, "Q16");

    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
  }

  @Test
  void stateCommandTailInfoCannotAdoptSuccessorPayloadEpoch() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());

    black.playMoveNoPonder(Stone.BLACK, "Q16");
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());

    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 36");
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    assertEquals(40, black.getBestMovesPlayouts());
  }

  @Test
  void stateCommandRetiresAnalysisBeforeItsFirstPhysicalByteIsObservable() throws Exception {
    ImmediateAnalysisTailOutput output = new ImmediateAnalysisTailOutput();
    black.bindLiveRuntime(output);
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    assertEquals(1, black.ordinaryAnalysisActions.get());

    output.arm(black, kataAnalysisInfo());
    black.playMoveNoPonder(Stone.BLACK, "Q16");

    assertEquals(1, output.tails.get());
    assertNull(output.tailFailure.get());
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
    assertEquals(
        1,
        black.ordinaryAnalysisActions.get(),
        "tail info observed from the first state-command byte must already be retired");
  }

  @Test
  void shutdownRequestedBindingRejectsTransactionlessStateBeforePayloadOrBytes()
      throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    assertEquals(40, black.getBestMovesPlayouts());
    String commandsBeforeShutdown = black.commandText();

    black.requestCurrentReaderShutdownForTest();

    assertThrows(
        IllegalStateException.class,
        () -> black.playMoveNoPonder(Stone.BLACK, "Q16"));
    assertEquals(commandsBeforeShutdown, black.commandText());
    assertEquals(1, black.getBestMoves().size());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    assertEquals(40, black.getBestMovesPlayouts());
  }

  @Test
  void queuedSuccessorAnalysisSurvivesStateCommandReturn() throws Exception {
    ImmediateQueuedAnalysisOutput output = new ImmediateQueuedAnalysisOutput();
    black.bindLiveRuntime(output);
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());

    output.arm(black, "kata-analyze W 36");
    black.playMoveNoPonder(Stone.BLACK, "Q16");

    assertEquals(1, output.enqueues.get());
    assertNull(output.enqueueFailure.get());
    assertTrue(
        commandLineIndex(black.commandText(), "play B Q16")
            < commandLineIndex(black.commandText(), "kata-analyze W 36"));
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    assertEquals(40, black.getBestMovesPlayouts());
  }

  @Test
  void failedStateCommandPoisonsDependentAnalysisUntilFreshStateRebuild() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());

    black.playMoveNoPonder(Stone.BLACK, "Q16");
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 36");

    // Streaming output remains valid while the preceding state command is merely pending.
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());

    black.processCommandResponseLineForTest("? illegal move");

    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());

    // Neither the dependent analysis acknowledgement nor an unrelated command can revive the
    // poisoned position lineage.
    black.processCommandResponseLineForTest("=");
    black.sendCommandNoLeelaz2("name");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());

    // A full state replacement deliberately starts a clean lineage; its successor may stream
    // before the clear_board acknowledgement just like a normal pending state command.
    black.sendCommandNoLeelaz2("clear_board");
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 37");
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    assertEquals(40, black.getBestMovesPlayouts());
    black.processCommandResponseLineForTest("=");
    black.processCommandResponseLineForTest("=");
  }

  @Test
  void timedOutStateCommandPoisonsAndClearsDependentAnalysis() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 38");
    black.processCommandResponseLineForTest("=");
    Runnable playResponse = () -> {};

    black.sendCommandWithResponseForTest("play B Q16", playResponse);
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 39");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    assertEquals(1, black.getBestMoves().size());

    black.retireTimedOutNormalCommandForTest(playResponse);

    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
    black.processCommandResponseLineForTest("=");
  }

  @Test
  void suppressedStateTimeoutPoisonsWithoutRetrySpin() throws Exception {
    installManager();
    black.isKatago = true;
    Runnable playResponse = () -> {};
    black.sendCommandWithResponseForTest("play B Q16", playResponse);
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 40");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    black.suppressGlobalEnginePresentationForTest();
    assertTrue(black.suppressesGlobalEnginePresentation(black.analysisReaderBindingForTest()));
    AtomicReference<Throwable> timeoutFailure = new AtomicReference<>();
    Thread timeout =
        new Thread(
            () -> {
              try {
                black.retireTimedOutNormalCommandForTest(playResponse);
              } catch (Throwable failure) {
                timeoutFailure.set(failure);
              }
            },
            "suppressed-state-timeout");
    timeout.setDaemon(true);

    timeout.start();
    timeout.join(2_000L);

    assertFalse(timeout.isAlive(), "suppressed state failure must not spin on hidden ownership");
    assertNull(timeoutFailure.get());
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
    black.processCommandResponseLineForTest("=");
  }

  @Test
  void clearCallerCannotEraseSuccessorPublishedBeforeItsReturn() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 35");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(1, black.getBestMoves().size());
    black.afterClearStateCommand =
        () -> {
          black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 36");
          black.parseAnalysisLineForTest(kataAnalysisInfo());
        };

    black.clear();

    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    assertEquals(1, black.getBestMoves().size());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    assertEquals(40, black.getBestMovesPlayouts());
  }

  @Test
  void exactRestorePositionCommandsRetirePriorAnalysisOwner() throws Exception {
    installManager();
    black.isKatago = true;
    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 37");
    black.processCommandResponseLineForTest("=");
    black.parseAnalysisLineForTest(kataAnalysisInfo());

    black.sendCommandNoLeelaz2("set_position");
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());

    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 38");
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    black.sendCommandNoLeelaz2("rectangular_boardsize 13 19");
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    assertTrue(black.getBestMoves().isEmpty());
  }

  @Test
  void ordinaryGenmoveAnalyzePublishesOwnerAndAcceptsInfoOnFreshBinding()
      throws Exception {
    installManager();
    black.isKatago = true;

    black.sendOrdinaryAnalysisCommandForTest("kata-genmove_analyze B 33");

    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
    black.parseAnalysisLineForTest(kataAnalysisInfo());
    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(1, black.ordinaryAnalysisActions.get());
    black.processCommandResponseLineForTest("=");
  }

  @Test
  void retirementAndRecoverySuppressionBlockOrdinaryOwnershipUntilPhysicalReplacement()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 4", transaction);
    int exactCommandId = commandIdFor(black.commandText(), "kata-analyze B 4");

    manager.clearEngineGame();

    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals(1, transaction.operationsInFlightForTest());
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    String beforeRejectedOrdinary = black.commandText();
    assertThrows(
        IllegalStateException.class,
        () -> black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 4"));
    assertEquals(beforeRejectedOrdinary, black.commandText());
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());

    black.processCommandResponseLineForTest("=" + exactCommandId);
    awaitOperationsReleased(transaction);
    black.suppressGlobalEnginePresentationForTest();
    assertTrue(black.suppressesGlobalEnginePresentation(black.analysisReaderBindingForTest()));
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());

    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 5");
    black.processCommandResponseLineForTest("=");

    assertFalse(black.suppressesGlobalEnginePresentation(black.analysisReaderBindingForTest()));
    assertEquals("ORDINARY_CURRENT", black.analysisOutputRouteForTest());
  }

  @Test
  void shutdownRequestRejectsRecoveryBindingBeforeEndpointStateFlip() throws Exception {
    installManager();
    Object recoveryToken = new Object();
    Object binding = black.authorizeAnalysisOutputRecoveryForCurrentBinding(recoveryToken);
    assertNotNull(binding);
    AtomicBoolean settlementRan = new AtomicBoolean();

    black.requestCurrentReaderShutdownForTest();

    assertTrue(black.isStarted());
    assertTrue(black.isLoaded());
    assertNull(black.authorizeAnalysisOutputRecoveryForCurrentBinding(recoveryToken));
    assertFalse(
        black.tryRunIfCurrentLiveRecoveryBinding(
            binding, recoveryToken, () -> settlementRan.set(true)));
    assertFalse(
        black.completeAnalysisOutputRecovery(
            binding,
            recoveryToken,
            false,
            () -> {
              settlementRan.set(true);
              return true;
            }));
    assertFalse(settlementRan.get());
    assertSame(recoveryToken, black.analysisOutputRecoveryTokenForTest());
  }

  @Test
  void exactRecoveryAuthorizationNeverContaminatesReplacementBinding() {
    installManager();
    Object staleBinding = black.analysisReaderBindingForTest();
    black.bindLiveRuntime();
    Object replacementBinding = black.analysisReaderBindingForTest();
    Object recoveryToken = new Object();

    assertNotSame(staleBinding, replacementBinding);
    assertNull(
        black.authorizeAnalysisOutputRecoveryForExactBinding(
            staleBinding, recoveryToken));
    assertSame(replacementBinding, black.analysisReaderBindingForTest());
    assertNull(black.analysisOutputRecoveryTokenForTest());
  }

  @Test
  void recoverySuppressionHandshakeCoversBindingPublicationRace() throws Exception {
    installManager();
    black.blockBeforeReaderBindingPublication();
    AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
    Thread rebind =
        new Thread(
            () -> {
              try {
                black.bindLiveRuntime();
              } catch (Throwable failure) {
                rebindFailure.set(failure);
              }
            },
            "analysis-recovery-binding-publication-race");

    rebind.start();
    try {
      assertTrue(black.readerBindingPublicationEntered.await(2, TimeUnit.SECONDS));
      black.suppressGlobalEnginePresentationUntilPhysicalAnalysisOwnership();
    } finally {
      black.releaseReaderBindingPublication.countDown();
    }
    rebind.join(2_000L);

    assertFalse(rebind.isAlive());
    assertNull(rebindFailure.get());
    Object publishedBinding = black.analysisReaderBindingForTest();
    assertTrue(black.suppressesGlobalEnginePresentation(publishedBinding));
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
  }

  @Test
  void sameBindingBatchSuccessorReplacesRetiredExactOwnerOnlyAtPhysicalWrite()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction first = activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 6", first);
    int firstCommandId = commandIdFor(black.commandText(), "kata-analyze B 6");
    black.processCommandResponseLineForTest("=" + firstCommandId);
    manager.clearEngineGame();
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());

    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, gameInfo(), black, 0);
    assertEquals(
        "EXACT_RETIRED",
        black.analysisOutputRouteForTest(),
        "admission alone must not repoint the unnumbered stream");

    black.sendEngineGameStartupCommandForTest("kata-analyze W 6", successor);
    int successorCommandId = commandIdFor(black.commandText(), "kata-analyze W 6");
    black.processCommandResponseLineForTest("=" + successorCommandId);

    assertEquals("EXACT_CURRENT", black.analysisOutputRouteForTest());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(successor));
  }

  @Test
  void delegatedBytesFollowedByFlushFailureQuarantineOwnerAndInvalidateOutput() {
    FlushFailingOutput output = new FlushFailingOutput();
    black.bindLiveRuntime(output);
    black.isKatago = true;

    black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 61");

    assertTrue(output.text().contains("kata-analyze B 61"));
    Object binding = black.analysisReaderBindingForTest();
    assertTrue(black.suppressesGlobalEnginePresentation(binding));
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
    String bytesAfterFailure = output.text();

    black.sendOrdinaryAnalysisCommandForTest("kata-analyze W 62");

    assertEquals(bytesAfterFailure, output.text(), "the polluted stream must stay invalidated");
    assertTrue(black.suppressesGlobalEnginePresentation(binding));
  }

  @Test
  void transactionlessOwnerAdmissionSpansPhysicalWriteAndFlush() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    black.isKatago = true;
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    AtomicReference<EngineManager.EngineGameTransaction> admitted = new AtomicReference<>();
    CountDownLatch admissionStarted = new CountDownLatch(1);
    CountDownLatch admissionFinished = new CountDownLatch(1);
    Thread writer =
        new Thread(
            () -> {
              try {
                black.sendOrdinaryAnalysisCommandForTest("kata-analyze B 63");
              } catch (Throwable failure) {
                writerFailure.set(failure);
              }
            },
            "transactionless-analysis-physical-owner");
    writer.start();
    assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
    Thread admission =
        new Thread(
            () -> {
              admissionStarted.countDown();
              admitted.set(
                  EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));
              admissionFinished.countDown();
            },
            "engine-game-admission-behind-analysis-write");
    admission.start();

    try {
      assertTrue(admissionStarted.await(2, TimeUnit.SECONDS));
      awaitThreadState(admission, Thread.State.WAITING, Thread.State.BLOCKED);
      assertEquals(
          1L,
          admissionFinished.getCount(),
          "game admission must not cross owner installation before flush");
    } finally {
      output.releaseWrite.countDown();
    }
    writer.join(2_000L);
    admission.join(2_000L);

    assertFalse(writer.isAlive());
    assertFalse(admission.isAlive());
    assertNull(writerFailure.get());
    assertNotNull(admitted.get());
    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest());
  }

  @Test
  void exactOwnerMutationWaitsUntilPhysicalWriteAndFlushComplete() throws Exception {
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction transaction =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.setBestMovesForEngineGameTest(List.of(move("Q16", 20, 55.0)));
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    CountDownLatch parserFinished = new CountDownLatch(1);
    Thread writer =
        new Thread(
            () -> {
              try {
                black.sendEngineGameStartupCommandForTest("kata-analyze B 64", transaction);
              } catch (Throwable failure) {
                writerFailure.set(failure);
              }
            },
            "exact-analysis-physical-owner");
    writer.start();
    assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              } finally {
                parserFinished.countDown();
              }
            },
            "exact-analysis-parser-behind-physical-owner");
    parser.start();

    try {
      assertFalse(
          parserFinished.await(100, TimeUnit.MILLISECONDS),
          "the parser must not mutate while the exact owner's bytes are still unflushed");
      assertEquals(0, black.exactAnalysisActions.get());
    } finally {
      output.releaseWrite.countDown();
    }
    writer.join(2_000L);
    parser.join(2_000L);

    assertFalse(writer.isAlive());
    assertFalse(parser.isAlive());
    assertNull(writerFailure.get());
    assertNull(parserFailure.get());
    assertEquals(0, black.exactAnalysisActions.get());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());

    black.parseAnalysisLineForTest(kataAnalysisInfo());

    assertEquals(1, black.exactAnalysisActions.get());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    int commandId = commandIdFor(black.commandText(), "kata-analyze B 64");
    black.processCommandResponseLineForTest("=" + commandId);
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
  }

  @Test
  void zenAndLeela0110RetiredOutputsNeverFallThroughToOrdinaryAutoplay() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineManager.EngineGameTransaction zen = activeTransaction(manager, gameInfo(), black, 0);
    black.isZen = true;
    black.sendEngineGameStartupCommandForTest("lz-analyze 7", zen);
    int zenCommandId = commandIdFor(black.commandText(), "lz-analyze 7");
    black.processCommandResponseLineForTest("=" + zenCommandId);
    manager.clearEngineGame();
    LizzieFrame.toolbar.isAutoPlay = true;

    black.parseAnalysisErrorLineForTest("I pass");

    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());

    black.isZen = false;
    black.isLeela0110 = true;
    EngineManager.EngineGameTransaction leela0110 =
        activeTransaction(manager, gameInfo(), black, 0);
    black.sendEngineGameStartupCommandForTest("time_left b 0 0", leela0110);
    int timeCommandId = commandIdFor(black.commandText(), "time_left b 0 0");
    black.processCommandResponseLineForTest("=" + timeCommandId);
    manager.clearEngineGame();

    black.parseAnalysisErrorLineForTest("=====");

    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void engineGameAdmissionBetweenOrdinaryZenRouteAndPassPreventsBoardMutation()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    black.isZen = true;
    LizzieFrame.toolbar.isAutoPlay = true;
    black.sendOrdinaryAnalysisCommandForTest("lz-analyze 8");
    black.processCommandResponseLineForTest("=");
    black.blockBeforeOrdinaryAnalysisOutputAdmission();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseAnalysisErrorLineForTest("I pass");
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "ordinary-zen-engine-game-admission-race");

    parser.start();
    assertTrue(black.ordinaryAnalysisAdmissionEntered.await(2, TimeUnit.SECONDS));
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo());
    black.releaseOrdinaryAnalysisAdmission.countDown();
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(transaction));
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
  }

  @Test
  void numberedGenmoveCarrierRoutesInfoWithoutInstallingUnnumberedAnalysisOwner()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    black.isKatago = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, white, 1);
    white.scoreMean = 91.0;
    white.scoreStdev = 92.0;
    black.scoreMean = -11.0;
    black.scoreStdev = -12.0;

    assertTrue(black.genmoveForPk("B", transaction));
    assertFalse(black.hasAnalysisOutputOwnershipForTest());
    assertEquals("GENMOVE_CURRENT", black.analysisOutputRouteForTest(kataAnalysisInfo()));

    black.parseEngineGameLineForTest(kataAnalysisInfo());

    assertEquals(0, black.exactAnalysisActions.get());
    assertEquals(0, black.ordinaryAnalysisActions.get());
    assertEquals(1, black.getBestMoves().size());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    assertEquals(40, black.getBestMovesPlayouts());
    assertEquals(2.5, black.scoreMean, 0.0001);
    assertEquals(0.75, black.scoreStdev, 0.0001);
    assertEquals(91.0, white.scoreMean, 0.0001);
    assertEquals(92.0, white.scoreStdev, 0.0001);
    assertEquals(
        2.5,
        Lizzie.board.getHistory().getCurrentHistoryNode().getData().scoreMean,
        0.0001);
    assertEquals(
        0.75,
        Lizzie.board.getHistory().getCurrentHistoryNode().getData().scoreStdev,
        0.0001);
    int commandId = firstCommandId(black.commandText());
    black.parseEngineGameLineForTest("?" + commandId + " controlled failure");
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void numberedGenmoveResignKeepsKataScoreOnPublishingParticipant() throws Exception {
    StopCommentEngineManager manager =
        installManager(new StopCommentEngineManager(allEngines()));
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    black.isKatago = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, white, 1);
    white.scoreMean = 91.0;
    white.scoreStdev = 92.0;
    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = firstCommandId(black.commandText());

    black.parseEngineGameLineForTest(kataAnalysisInfo());
    assertSame(
        manager.stopAfterComment,
        assertThrows(
            AssertionError.class,
            () -> black.parseEngineGameLineForTest("=" + commandId + " resign")));

    assertTrue(black.resigned);
    assertEquals(2.5, black.scoreMean, 0.0001);
    assertEquals(0.75, black.scoreStdev, 0.0001);
    assertEquals(91.0, white.scoreMean, 0.0001);
    assertEquals(92.0, white.scoreStdev, 0.0001);
  }

  @Test
  void boardDataClearCannotZeroSuccessorAnalysisScore() {
    black.isKatago = true;
    white.isKatago = true;
    Lizzie.leelaz2 = white;
    black.scoreMean = 2.5;
    black.scoreStdev = 0.75;
    white.scoreMean = 8.5;
    white.scoreStdev = 1.25;
    BoardData displayData = Lizzie.board.getHistory().getCurrentHistoryNode().getData();
    displayData.scoreMean = 99.0;
    displayData.scoreStdev = 98.0;
    displayData.scoreMean2 = 97.0;
    displayData.scoreStdev2 = 96.0;

    displayData.tryToClearBestMoves();

    assertEquals(0.0, displayData.scoreMean, 0.0001);
    assertEquals(0.0, displayData.scoreStdev, 0.0001);
    assertEquals(0.0, displayData.scoreMean2, 0.0001);
    assertEquals(0.0, displayData.scoreStdev2, 0.0001);
    assertEquals(2.5, black.scoreMean, 0.0001);
    assertEquals(0.75, black.scoreStdev, 0.0001);
    assertEquals(8.5, white.scoreMean, 0.0001);
    assertEquals(1.25, white.scoreStdev, 0.0001);
  }

  @Test
  void numberedGenmoveInfoCapturedBeforeClearCannotReviveClearedPayload() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    black.isKatago = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, white, 1);
    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = firstCommandId(black.commandText());
    black.setBestMovesForEngineGameTest(List.of(move("Q16", 20, 55.0)));
    black.blockAfterAnalysisInfoAdmissionSnapshotCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseEngineGameLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "genmove-info-epoch-clear-race");
    parser.start();
    try {
      assertTrue(black.analysisInfoSnapshotCaptured.await(2, TimeUnit.SECONDS));
      black.clearBestMoves();
    } finally {
      black.releaseAnalysisInfoSnapshot.countDown();
    }
    parser.join(2_000L);

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
    black.parseEngineGameLineForTest("?" + commandId + " controlled failure");
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void malformedNumberedGenmoveInfoDoesNotFailItsResponseCarrier() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    black.isKatago = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, white, 1);
    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = firstCommandId(black.commandText());

    black.parseEngineGameLineForTest(
        "info move D4 visits malformed winrate 0.51 scoreLead 2.5 prior 0.2 pv D4");

    assertEquals(EngineManager.EngineGamePhase.ACTIVE, transaction.phase());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, black.getBestMovesPlayouts());
    black.parseEngineGameLineForTest("?" + commandId + " controlled failure");
    assertEquals(EngineManager.EngineGamePhase.FAILED, transaction.phase());
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void terminalAfterGenmoveRouteCaptureDropsLateInfo() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    black.isKatago = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, white, 1);
    assertTrue(black.genmoveForPk("B", transaction));
    int commandId = firstCommandId(black.commandText());
    black.blockAfterAnalysisOutputRouteCapture();
    AtomicReference<Throwable> parserFailure = new AtomicReference<>();
    Thread parser =
        new Thread(
            () -> {
              try {
                black.parseEngineGameLineForTest(kataAnalysisInfo());
              } catch (Throwable failure) {
                parserFailure.set(failure);
              }
            },
            "genmove-info-terminal-route-race");
    parser.start();
    try {
      assertTrue(black.analysisRouteCaptured.await(2, TimeUnit.SECONDS));
      manager.clearEngineGame();
    } finally {
      black.releaseAnalysisRoute.countDown();
    }
    parser.join(2_000L);
    black.parseEngineGameLineForTest("?" + commandId + " retired request");

    assertFalse(parser.isAlive());
    assertNull(parserFailure.get());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertTrue(black.getBestMoves().isEmpty());
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void leela0110GenmoveClockUpdateDoesNotClaimUnnumberedAnalysisOwnership()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo game = gameInfo();
    game.isGenmove = true;
    black.isLeela0110 = true;
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);

    black.timeLeft("b", 10, 1, true);

    assertTrue(
        black.commandText().lines().map(String::trim).anyMatch("time_left b 10 1"::equals));
    assertFalse(black.hasAnalysisOutputOwnershipForTest());
    black.processCommandResponseLineForTest("=");
    assertTrue(black.genmoveForPk("B", transaction));
    assertEquals("GENMOVE_CURRENT", black.analysisOutputRouteForTest(kataAnalysisInfo()));
    int genmoveId = commandIdFor(black.commandText(), "genmove B");
    black.parseEngineGameLineForTest("?" + genmoveId + " controlled failure");
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  @Test
  void leela0110TimerInheritsExactContextRetiresAndCannotClearSuccessor()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    black.isLeela0110 = true;
    assertTrue(black.installLeela0110PonderStateForTest(null));
    assertTrue(black.hasLeela0110PonderStateForTest(null));
    EngineManager.EngineGameTransaction first =
        activeTransaction(manager, gameInfo(), black, 0);
    Leelaz.runWithEngineGameStartupCommandContext(first, black::ponder);
    assertTrue(black.hasLeela0110PonderStateForTest(first));
    int firstTimeLeft = commandIdFor(black.commandText(), "time_left b 0 0");

    manager.clearEngineGame();

    assertFalse(black.hasLeela0110PonderStateForTest(first));
    black.processCommandResponseLineForTest("=" + firstTimeLeft);
    awaitOperationsReleased(first);
    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, gameInfo(), black, 0);
    assertTrue(black.installLeela0110PonderStateForTest(successor));
    assertTrue(black.hasLeela0110PonderStateForTest(successor));

    black.cancelEngineGameRequests(first);

    assertTrue(black.hasLeela0110PonderStateForTest(successor));
  }

  @Test
  void sameBindingLeela0110ReplacementWaitsForOldPhysicalWriteAndFlush()
      throws Exception {
    installManager();
    BlockingFirstWriteOutput output = new BlockingFirstWriteOutput();
    black.bindLiveRuntime(output);
    black.isLeela0110 = true;
    assertTrue(black.installLeela0110PonderStateForTest(null));
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
    AtomicReference<Boolean> replacementInstalled = new AtomicReference<>(false);
    CountDownLatch replacementStarted = new CountDownLatch(1);
    CountDownLatch replacementFinished = new CountDownLatch(1);
    Thread writer =
        new Thread(
            () -> {
              try {
                black.sendLeela0110PonderCommandForTest("time_left b 0 0", null);
              } catch (Throwable failure) {
                writerFailure.set(failure);
              }
            },
            "leela0110-old-state-physical-write");
    Thread replacement =
        new Thread(
            () -> {
              replacementStarted.countDown();
              try {
                black.leela0110StopPonder();
                replacementInstalled.set(black.installLeela0110PonderStateForTest(null));
              } catch (Throwable failure) {
                replacementFailure.set(failure);
              } finally {
                replacementFinished.countDown();
              }
            },
            "leela0110-same-binding-state-replacement");

    writer.start();
    try {
      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
      replacement.start();
      assertTrue(replacementStarted.await(2, TimeUnit.SECONDS));
      awaitThreadState(replacement, Thread.State.WAITING, Thread.State.BLOCKED);
      assertEquals(
          1L,
          replacementFinished.getCount(),
          "same-binding replacement must wait until the old state's write is flushed");
    } finally {
      output.releaseWrite.countDown();
    }
    writer.join(2_000L);
    replacement.join(2_000L);

    assertFalse(writer.isAlive());
    assertFalse(replacement.isAlive());
    assertNull(writerFailure.get());
    assertNull(replacementFailure.get());
    assertEquals(Boolean.TRUE, replacementInstalled.get());
    assertTrue(black.hasLeela0110PonderStateForTest(null));
    assertTrue(output.text().contains("time_left b 0 0"));
  }

  @Test
  void finalRetirementPassClearsLeela0110StateInstalledByAdmittedLateOperation()
      throws Exception {
    ImmediateUiEngineManager manager = installManager();
    black.isLeela0110 = true;
    EngineManager.EngineGameTransaction first =
        activeTransaction(manager, gameInfo(), black, 0);
    CountDownLatch operationEntered = new CountDownLatch(1);
    CountDownLatch releaseOperation = new CountDownLatch(1);
    AtomicReference<Throwable> operationFailure = new AtomicReference<>();
    Thread operation =
        new Thread(
            () -> {
              try {
                EngineManager.runEngineGameIoStepForTest(
                    first,
                    () -> {
                      operationEntered.countDown();
                      await(releaseOperation);
                      assertTrue(black.installLeela0110PonderStateForTest(first));
                    });
              } catch (Throwable failure) {
                operationFailure.set(failure);
              }
            },
            "late-leela0110-operation-before-retirement");
    operation.start();
    assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

    manager.clearEngineGame();
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, first.phase());
    releaseOperation.countDown();
    operation.join(2_000L);
    awaitOperationsReleased(first);

    assertFalse(operation.isAlive());
    assertNull(operationFailure.get());
    assertFalse(black.hasLeela0110PonderStateForTest(first));
    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, gameInfo(), black, 0);
    assertTrue(black.installLeela0110PonderStateForTest(successor));
  }

  @Test
  void leela0110StateIsBindingScopedAcrossRebindAndStaleShutdown() {
    black.isLeela0110 = true;
    Object oldBinding = black.analysisReaderBindingForTest();
    assertTrue(black.installLeela0110PonderStateForTest(null));

    black.bindLiveRuntime();

    assertFalse(black.hasLeela0110PonderStateForTest(null));
    assertTrue(black.installLeela0110PonderStateForTest(null));
    black.shutdownReaderBindingForTest(oldBinding);
    assertTrue(black.hasLeela0110PonderStateForTest(null));
  }

  @Test
  void staleBindingWithoutOwnershipAndSameParticipantSlotsAreFailClosed() throws Exception {
    ImmediateUiEngineManager manager = installManager();
    Object oldBinding = black.analysisReaderBindingForTest();
    black.bindLiveRuntime();

    assertEquals("EXACT_RETIRED", black.analysisOutputRouteForTest(oldBinding));
    black.parseAnalysisLineForTest(kataAnalysisInfo(), oldBinding);
    assertEquals(0, black.ordinaryAnalysisActions.get());

    EngineGameInfo invalid = gameInfo();
    invalid.whiteEngineIndex = invalid.blackEngineIndex;
    assertNull(EngineManager.beginEngineGameTransaction(manager, invalid, null, true));
  }

  @Test
  void staleAnalysisContextCannotMoveOrStopSuccessorGame() {
    ImmediateUiEngineManager manager = installManager();
    EngineGameInfo first = gameInfo();
    EngineManager.EngineGameTransaction old = activeTransaction(manager, first, black, 0);
    EngineManager.EngineGamePrimaryContext stale =
        EngineManager.captureEngineGamePrimaryContext(
            black, black.currentEngineIncarnation());
    black.setBestMovesForEngineGameTest(List.of(move("D4", 10_000, 60.0)));

    manager.clearEngineGame();
    EngineGameInfo successorInfo = gameInfo();
    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, successorInfo, black, 0);
    int beforeMove = Lizzie.board.getHistory().getMoveNumber();
    black.notifyAutoPkForEngineGameTest(true, stale);

    assertEquals(EngineManager.EngineGamePhase.CANCELLED, old.phase());
    assertTrue(EngineManager.isCurrentEngineGameTransaction(successor));
    assertEquals(beforeMove, Lizzie.board.getHistory().getMoveNumber());
    assertEquals("", black.commandText());
    assertEquals("", white.commandText());
  }

  @Test
  void exactAnalysisDoublePassUsesSingleTerminalOwnerAndPreservesTerminalReason()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    BoardHistoryNode root = board.getHistory().getCurrentHistoryNode();
    assertNotNull(
        board.commitEngineGamePass(
            board.getHistory(), root, true, Stone.BLACK, false));
    StopCommentEngineManager manager =
        installManager(new StopCommentEngineManager(allEngines()));
    EngineGameInfo game = gameInfo();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, white, 1);
    EngineManager.EngineGamePrimaryContext context =
        EngineManager.captureEngineGamePrimaryContext(
            white, white.currentEngineIncarnation());
    white.setBestMovesForEngineGameTest(List.of(move("pass", 10_000, 60.0)));

    assertSame(
        manager.stopAfterComment,
        assertThrows(
            AssertionError.class,
            () -> white.notifyAutoPkForEngineGameTest(true, context)));

    assertTrue(white.doublePass);
    assertTrue(white.resigned);
    assertEquals(2, board.getHistory().getMoveNumber());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
  }

  @Test
  void exactAnalysisLastNoCapturePointTerminatesWithoutSendingPostMoveCommands()
      throws Exception {
    RecordingBoard board = recordingBoard();
    Lizzie.board = board;
    BoardData root = board.getHistory().getData();
    Arrays.fill(root.stones, Stone.WHITE);
    root.stones[Board.getIndex(3, 3)] = Stone.EMPTY;
    Lizzie.config.noCapture = true;
    StopCommentEngineManager manager =
        installManager(new StopCommentEngineManager(allEngines()));
    EngineGameInfo game = gameInfo();
    EngineManager.EngineGameTransaction transaction = activeTransaction(manager, game, black, 0);
    EngineManager.EngineGamePrimaryContext context =
        EngineManager.captureEngineGamePrimaryContext(
            black, black.currentEngineIncarnation());
    black.setBestMovesForEngineGameTest(
        List.of(move(Board.convertCoordinatesToName(3, 3), 10_000, 60.0)));

    assertSame(
        manager.stopAfterComment,
        assertThrows(
            AssertionError.class,
            () -> black.notifyAutoPkForEngineGameTest(true, context)));

    assertTrue(black.outOfMoveNum);
    assertTrue(black.resigned);
    assertEquals(1, board.getHistory().getMoveNumber());
    assertEquals(EngineManager.EngineGamePhase.CANCELLED, transaction.phase());
    assertEquals("", black.commandText());
    assertEquals("", white.commandText());
  }

  @Test
  void queuedLegacyRollbackCannotClobberSuccessorGameUi() {
    DeferredUiEngineManager manager = installManager(new DeferredUiEngineManager(allEngines()));
    EngineGameInfo legacy = gameInfo();
    EngineManager.engineGameInfo = legacy;
    EngineManager.isPreEngineGame = true;
    Lizzie.board.isPkBoard = true;
    toolbar.controlsEnabled = false;
    Menu.engineMenu.setEnabled(false);

    manager.clearEngineGame();
    assertNotNull(manager.pendingUi.get());
    EngineGameInfo successorInfo = gameInfo();
    EngineManager.EngineGameTransaction successor =
        beginPreparing(manager, successorInfo);
    Lizzie.board.isPkBoard = true;
    int inputBefore = frame.inputAttempts.get();
    int toolbarBefore = toolbar.enableAttempts.get();

    manager.runPendingUi();

    assertTrue(EngineManager.isCurrentEngineGameTransaction(successor));
    assertTrue(Lizzie.board.isPkBoard);
    assertEquals(inputBefore, frame.inputAttempts.get());
    assertEquals(toolbarBefore, toolbar.enableAttempts.get());
    assertFalse(Menu.engineMenu.isEnabled());
  }

  @Test
  void remoteParticipantRecoveryGatesStartReadinessAndFinalConfirmation() throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    EngineManager.EngineGameTransaction failed =
        activeTransaction(manager, gameInfo(), black, 0);
    Object failedIncarnation = black.currentEngineIncarnation();
    black.enableDeferredRecoveryStart = true;
    black.sendDeferredRecoveryBootstrapStateCommand = true;

    manager.restartUnresponsiveRemoteEngine(black, 0);

    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));
    assertEquals(EngineManager.EngineGamePhase.FAILED, failed.phase());
    assertSame(failedIncarnation, manager.recovery.failedIncarnationForTest());
    assertEquals(0, black.deferredRecoveryStartCount.get());
    assertTrue(EngineManager.hasDeferredEngineGameRecoveryGateForTest());
    assertTrue(EngineManager.hasEngineGameAnalysisOutputBarrier());
    AtomicBoolean ordinaryOutputAdmitted = new AtomicBoolean();
    assertFalse(
        EngineManager.runIfNoEngineGameAnalysisOutputBarrier(
            () -> ordinaryOutputAdmitted.set(true)));
    assertFalse(ordinaryOutputAdmitted.get());
    assertNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));

    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.READINESS));
    assertEquals(1, black.deferredRecoveryStartCount.get());
    Object replacementIncarnation = black.currentEngineIncarnation();
    assertNotSame(failedIncarnation, replacementIncarnation);
    assertTrue(
        black.recoveryTransport.commands().contains("komi 6.25"),
        black.recoveryTransport.commands().toString());
    assertTrue(black.suppressesGlobalEnginePresentation(replacementIncarnation));
    assertTrue(EngineManager.hasEngineGameAnalysisOutputBarrier());
    assertNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));

    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION));
    List<String> recoveryCommands = black.recoveryTransport.commands();
    assertTrue(recoveryCommands.contains("komi 7.5"), recoveryCommands.toString());
    assertTrue(recoveryCommands.contains("clear_board"), recoveryCommands.toString());
    int quarantinedMoveCount = black.getBestMoves().size();
    black.parseStartupCommandResponseForTest(
        "info move D4 visits 100 winrate 5000 prior 1000 order 0 pv D4");
    assertEquals(quarantinedMoveCount, black.getBestMoves().size());
    assertTrue(black.suppressesGlobalEnginePresentation(replacementIncarnation));
    assertTrue(EngineManager.hasEngineGameAnalysisOutputBarrier());
    assertNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));

    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
    assertFalse(EngineManager.hasDeferredEngineGameRecoveryGateForTest());
    assertFalse(EngineManager.hasEngineGameAnalysisOutputBarrier());
    assertNull(black.analysisOutputRecoveryTokenForTest());
    assertFalse(black.suppressesGlobalEnginePresentation(replacementIncarnation));
    assertTrue(
        EngineManager.runIfNoEngineGameAnalysisOutputBarrier(
            () -> ordinaryOutputAdmitted.set(true)));
    assertTrue(ordinaryOutputAdmitted.get());
    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, gameInfo(), black, 0);
    black.isKatago = true;
    black.sendEngineGameStartupCommandForTest("kata-analyze B 20", successor);
    assertEquals("EXACT_CURRENT", black.analysisOutputRouteForTest(kataAnalysisInfo()));
    black.parseStartupCommandResponseForTest(
        "info move D4 visits 100 winrate 5000 prior 1000 order 0 pv D4");
    assertFalse(black.getBestMoves().isEmpty());
    assertEquals("D4", black.getBestMoves().get(0).coordinate);
    assertTrue(EngineManager.isCurrentEngineGameTransaction(successor));
  }

  @Test
  void pkRestartEntryDefersExactParticipantUntilRetirement() throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    EngineManager.EngineGameTransaction failed =
        activeTransaction(manager, gameInfo(), black, 0);
    Object failedIncarnation = black.currentEngineIncarnation();
    black.enableDeferredRecoveryStart = true;

    manager.restartEngineForPk(0);

    assertEquals(EngineManager.EngineGamePhase.FAILED, failed.phase());
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));
    assertSame(failedIncarnation, manager.recovery.failedIncarnationForTest());
    assertEquals(0, black.deferredRecoveryStartCount.get());
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.READINESS));
    assertEquals(1, black.deferredRecoveryStartCount.get());
    assertNotSame(failedIncarnation, black.currentEngineIncarnation());
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
  }

  @Test
  void openClCompatibilityMutationWaitsForExactTransactionRetirement() throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), true));
    activeTransaction(manager, gameInfo(), black, 0);
    Object failedIncarnation = black.currentEngineIncarnation();
    black.enableDeferredRecoveryStart = true;
    black.allowDeferredOpenClRecovery = true;

    assertEquals(
        EngineManager.EngineGameRecoveryDisposition.HANDLED,
        EngineManager.requestEngineGameParticipantRecovery(
            manager,
            black,
            failedIncarnation,
            EngineManager.EngineGameRecoveryCause.OPENCL_NATIVE_EXIT));
    assertEquals(0, black.deferredOpenClPrepareCount.get());
    assertEquals(0, black.deferredRecoveryStartCount.get());

    manager.runPendingUi();
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));
    assertEquals(0, black.deferredOpenClPrepareCount.get());
    assertEquals(0, black.deferredRecoveryStartCount.get());
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.READINESS));
    assertEquals(1, black.deferredOpenClPrepareCount.get());
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
  }

  @Test
  void oldFailedBindingCannotRebindOrFailSuccessor() throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), true));
    EngineManager.EngineGameTransaction failed =
        activeTransaction(manager, gameInfo(), black, 0);
    Object failedIncarnation = black.currentEngineIncarnation();
    black.enableDeferredRecoveryStart = true;

    assertEquals(
        EngineManager.EngineGameRecoveryDisposition.HANDLED,
        EngineManager.requestEngineGameParticipantRecovery(
            manager,
            black,
            failedIncarnation,
            EngineManager.EngineGameRecoveryCause.OPENCL_NATIVE_EXIT));

    assertEquals(EngineManager.EngineGamePhase.FAILED, failed.phase());
    assertEquals(0, black.deferredOpenClPrepareCount.get());
    assertEquals(0, black.deferredRecoveryStartCount.get());
    assertNull(EngineManager.beginEngineGameTransaction(manager, gameInfo(), null, true));

    black.bindLiveRuntime();
    Object successorIncarnation = black.currentEngineIncarnation();
    assertNotSame(failedIncarnation, successorIncarnation);
    assertFalse(black.forceQuitIfCurrentIncarnation(failedIncarnation));
    assertTrue(black.isCurrentLiveEngineIncarnation(successorIncarnation));
    manager.runPendingUi();

    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
    assertEquals(0, black.deferredRecoveryStartCount.get());
    assertSame(successorIncarnation, black.currentEngineIncarnation());
    EngineManager.EngineGameTransaction successor =
        activeTransaction(manager, gameInfo(), black, 0);

    assertEquals(
        EngineManager.EngineGameRecoveryDisposition.HANDLED,
        EngineManager.requestEngineGameParticipantRecovery(
            manager,
            black,
            failedIncarnation,
            EngineManager.EngineGameRecoveryCause.REMOTE_DISCONNECT));
    assertTrue(EngineManager.isCurrentEngineGameTransaction(successor));
    assertSame(successorIncarnation, black.currentEngineIncarnation());
  }

  @Test
  void recoveryRequestDoesNotWaitForBlockedPhysicalWriter() throws Exception {
    CloseReleasedWriteOutput output = new CloseReleasedWriteOutput();
    CloseReleasedTransport transport = new CloseReleasedTransport(output);
    setRemoteTransport(black, transport);
    black.useRemoteCompute = true;
    black.bindLiveRuntime(output);
    Object failedIncarnation = black.currentEngineIncarnation();
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    EngineManager.EngineGameTransaction failed =
        activeTransaction(manager, gameInfo(), black, 0);
    black.enableDeferredRecoveryStart = true;
    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
    AtomicReference<Throwable> requestFailure = new AtomicReference<>();
    Thread sender =
        new Thread(
            () -> {
              try {
                black.sendCommand("protocol_version");
              } catch (Throwable failure) {
                senderFailure.set(failure);
              }
            },
            "engine-game-recovery-blocked-writer");
    Thread request =
        new Thread(
            () -> {
              try {
                manager.restartUnresponsiveRemoteEngine(black, 0);
              } catch (Throwable failure) {
                requestFailure.set(failure);
              }
            },
            "engine-game-recovery-request");

    sender.start();
    try {
      assertTrue(output.writeEntered.await(2, TimeUnit.SECONDS));
      request.start();
      request.join(1_000L);

      assertFalse(request.isAlive(), "terminal recovery must not wait for the physical writer");
      assertNull(requestFailure.get());
      assertEquals(EngineManager.EngineGamePhase.FAILED, failed.phase());
      assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));

      manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
      assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.READINESS));
      assertEquals(1, black.deferredRecoveryStartCount.get());
      assertNotSame(failedIncarnation, black.currentEngineIncarnation());
      sender.join(2_000L);
      assertFalse(sender.isAlive());
      assertNull(senderFailure.get());
      assertEquals(1, transport.closeCount.get());
      manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
      assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION));
      manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
      assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
    } finally {
      output.close();
      manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
      manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
      manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
      request.join(2_000L);
      sender.join(2_000L);
    }
  }

  @Test
  void preparingParticipantFailureUsesFrozenStartupIncarnation() throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    EngineManager.EngineGameTransaction failed = beginPreparing(manager, gameInfo());
    Object startupIncarnation = black.currentEngineIncarnation();
    assertTrue(
        EngineManager.recordEngineGameStartupIncarnation(
            failed, black, startupIncarnation));
    black.enableDeferredRecoveryStart = true;

    assertEquals(
        EngineManager.EngineGameRecoveryDisposition.HANDLED,
        EngineManager.requestEngineGameParticipantRecovery(
            manager,
            black,
            startupIncarnation,
            EngineManager.EngineGameRecoveryCause.REMOTE_DISCONNECT));

    assertEquals(EngineManager.EngineGamePhase.FAILED, failed.phase());
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));
    assertSame(startupIncarnation, manager.recovery.failedIncarnationForTest());
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.READINESS));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
  }

  @Test
  void preparingParticipantWithoutPublishedStartupBindingSuppressesPredecessorRestart() {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    EngineManager.EngineGameTransaction preparing = beginPreparing(manager, gameInfo());
    Object predecessorIncarnation = black.currentEngineIncarnation();

    assertEquals(
        EngineManager.EngineGameRecoveryDisposition.HANDLED,
        EngineManager.requestEngineGameParticipantRecovery(
            manager,
            black,
            predecessorIncarnation,
            EngineManager.EngineGameRecoveryCause.REMOTE_DISCONNECT));

    assertTrue(EngineManager.isCurrentEngineGameTransaction(preparing));
    assertEquals(EngineManager.EngineGamePhase.PREPARING, preparing.phase());
    assertFalse(EngineManager.hasDeferredEngineGameRecoveryGateForTest());
    assertSame(predecessorIncarnation, black.currentEngineIncarnation());
  }

  @Test
  void recoveryStartFailureAfterBindingCleansExactRuntimeWithoutForegroundPresentation()
      throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    activeTransaction(manager, gameInfo(), black, 0);
    black.enableDeferredRecoveryStart = true;
    black.throwAfterDeferredRecoveryBinding = true;
    frame.isShowingHeatmap = true;
    frame.isShowingPolicy = true;
    Board foregroundBoard = Lizzie.board;
    double foregroundKomi = foregroundBoard.getHistory().getGameInfo().getKomi();

    manager.restartUnresponsiveRemoteEngine(black, 0);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));

    Object failedReplacement = black.currentEngineIncarnation();
    assertTrue(black.deferredRecoveryIsolationObserved);
    assertFalse(black.isCurrentLiveEngineIncarnation(failedReplacement));
    assertTrue(black.suppressesGlobalEnginePresentation(failedReplacement));
    assertFalse(black.started);
    assertFalse(black.isLoaded);
    assertNotNull(manager.recoveryFailureDetail);
    assertFalse(EngineManager.hasDeferredEngineGameRecoveryGateForTest());
    assertSame(foregroundBoard, Lizzie.board);
    assertEquals(foregroundKomi, Lizzie.board.getHistory().getGameInfo().getKomi());
    assertTrue(frame.isShowingHeatmap);
    assertTrue(frame.isShowingPolicy);
    Leelaz.UpdateEngineStartAttempt retry = black.beginUpdateEngineStartAttempt();
    retry.failClose(new AssertionError("controlled post-recovery cleanup probe"));
  }

  @Test
  void recoveryBindingQuarantinesAsyncBootstrapConfigAndOrdinaryAnalysisOutput()
      throws Exception {
    RecoveryGateEngineManager manager =
        installManager(new RecoveryGateEngineManager(allEngines(), false));
    activeTransaction(manager, gameInfo(), black, 0);
    black.enableDeferredRecoveryStart = true;
    black.blockStartupPostActionWorker();
    Lizzie.config.leelaversion = 314;
    Object startupStatus = Lizzie.engineStartupStatus.snapshot();
    frame.isShowingHeatmap = true;
    frame.isShowingPolicy = true;

    manager.restartUnresponsiveRemoteEngine(black, 0);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.START));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.START);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.READINESS));
    Object recoveryIncarnation = black.currentEngineIncarnation();
    assertTrue(black.suppressesGlobalEnginePresentation(recoveryIncarnation));

    black.isCheckingName = true;
    black.parseStartupCommandResponseForTest("= KataGoPda");
    assertTrue(black.startupPostWorkerEntered.await(2, TimeUnit.SECONDS));
    String startupCommands = black.commandText();
    assertEquals(314, Lizzie.config.leelaversion);
    assertSame(startupStatus, Lizzie.engineStartupStatus.snapshot());
    assertFalse(startupCommands.contains("getpda"));
    assertFalse(startupCommands.contains("getdympdacap"));
    assertFalse(startupCommands.contains("kata-get-param"));

    int engineMovesBefore = black.getBestMoves().size();
    int boardMovesBefore = Lizzie.board.getHistory().getMoveNumber();
    int boardSuggestionsBefore = Lizzie.board.getData().bestMoves.size();
    black.parseStartupCommandResponseForTest(
        "info move D4 visits 100 winrate 5000 prior 1000 order 0 pv D4");
    black.parseRecoveryStderrForTest("Nodes: 1000 I pass");
    black.dispatchBundledStartupTimeoutPresentationForTest(
        recoveryIncarnation, "controlled recovery startup timeout");
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(engineMovesBefore, black.getBestMoves().size());
    assertEquals(boardMovesBefore, Lizzie.board.getHistory().getMoveNumber());
    assertEquals(boardSuggestionsBefore, Lizzie.board.getData().bestMoves.size());
    assertTrue(frame.isShowingHeatmap);
    assertTrue(frame.isShowingPolicy);
    assertSame(startupStatus, Lizzie.engineStartupStatus.snapshot());

    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.READINESS);
    assertTrue(manager.stageEntered(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION));
    manager.releaseStage(EngineManager.DeferredEngineGameRecoveryStage.CONFIRMATION);
    assertTrue(manager.recoveryFinished.await(2, TimeUnit.SECONDS));
    assertFalse(black.suppressesGlobalEnginePresentation(recoveryIncarnation));

    // This callback was created while the binding was quarantined but executes only after the
    // recovery batch releases its output barrier. Its frozen authority must remain isolated even
    // though the successfully recovered binding is now eligible for a future exact game owner.
    black.releaseStartupPostWorker.countDown();
    black.startupPostWorker.join(2_000L);
    assertFalse(black.startupPostWorker.isAlive());
    assertNull(black.startupPostWorkerFailure.get());
    startupCommands = black.commandText();
    assertFalse(startupCommands.contains("getpda"));
    assertFalse(startupCommands.contains("getdympdacap"));
    assertFalse(startupCommands.contains("kata-get-param"));
    assertEquals(314, Lizzie.config.leelaversion);
    assertSame(startupStatus, Lizzie.engineStartupStatus.snapshot());
    assertTrue(frame.isShowingHeatmap);
    assertTrue(frame.isShowingPolicy);
  }

  private ImmediateUiEngineManager installManager() {
    return installManager(new ImmediateUiEngineManager(allEngines()));
  }

  private <T extends EngineManager> T installManager(T manager) {
    Lizzie.engineManager = manager;
    return manager;
  }

  private List<Leelaz> allEngines() {
    return List.of(black, white, preload);
  }

  private static MoveData move(String coordinate, int playouts, double winrate) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.playouts = playouts;
    move.winrate = winrate;
    return move;
  }

  private static String kataAnalysisInfo() {
    return "info move D4 visits 40 winrate 0.51 scoreLead 2.5 scoreStdev 0.75 prior 0.2 pv D4";
  }

  private static int firstCommandId(String output) {
    String firstLine = output.lines().findFirst().orElseThrow();
    return Integer.parseInt(firstLine.trim().split("\\s+", 2)[0]);
  }

  private static int commandIdFor(String output, String command) {
    return output
        .lines()
        .map(String::trim)
        .filter(line -> line.endsWith(command))
        .map(line -> line.split("\\s+", 2)[0])
        .mapToInt(Integer::parseInt)
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing command '" + command + "' in: " + output));
  }

  private static int commandLineIndex(String output, String command) {
    String[] lines = output.split("\\R");
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].trim().endsWith(command)) {
        return index;
      }
    }
    throw new AssertionError("missing command '" + command + "' in: " + output);
  }

  private static int commandLineCount(String output, String command) {
    return (int)
        output.lines().map(String::trim).filter(line -> line.contains(command)).count();
  }

  private static EngineCountDown engineClock(
      String settings, Leelaz engine, boolean blackToPlay) {
    EngineCountDown clock = new EngineCountDown();
    assertTrue(clock.setEngineCountDown(settings, engine));
    clock.initialize(blackToPlay);
    return clock;
  }

  private static RecordingBoard recordingBoard() throws Exception {
    RecordingBoard board = allocate(RecordingBoard.class);
    board.clearAfterMoveCalls = new AtomicInteger();
    board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
    return board;
  }

  private static BlockingCommitBoard blockingCommitBoard() throws Exception {
    return blockingCommitBoard(new BoardHistoryList(BoardData.empty(19, 19)));
  }

  private static BlockingCommitBoard blockingCommitBoard(BoardHistoryList history)
      throws Exception {
    BlockingCommitBoard board = allocate(BlockingCommitBoard.class);
    board.clearAfterMoveCalls = new AtomicInteger();
    board.boardCommitted = new CountDownLatch(1);
    board.releaseCommit = new CountDownLatch(1);
    board.setHistory(history);
    return board;
  }

  private static EngineGameInfo gameInfo() {
    EngineGameInfo gameInfo = new EngineGameInfo();
    gameInfo.blackEngineIndex = 0;
    gameInfo.whiteEngineIndex = 1;
    gameInfo.firstEngineIndex = 0;
    gameInfo.secondEngineIndex = 1;
    gameInfo.blackResignWinrate = 0.0;
    gameInfo.whiteResignWinrate = 0.0;
    gameInfo.blackResignMoveCounts = 2;
    gameInfo.whiteResignMoveCounts = 2;
    return gameInfo;
  }

  private static EngineManager.EngineGameTransaction beginPreparing(
      EngineManager manager, EngineGameInfo gameInfo) {
    EngineManager.EngineGameTransaction transaction =
        EngineManager.beginEngineGameTransaction(manager, gameInfo, null, true);
    assertNotNull(transaction);
    return transaction;
  }

  private static EngineManager.EngineGameTransaction activeTransaction(
      EngineManager manager, EngineGameInfo gameInfo, Leelaz selected, int selectedIndex) {
    EngineManager.EngineGameTransaction transaction = beginPreparing(manager, gameInfo);
    assertTrue(EngineManager.transitionEngineGameToDispatched(transaction));
    assertTrue(
        EngineManager.activateEngineGameTransaction(
            transaction,
            selected,
            selectedIndex,
            manager.engineList.get(gameInfo.blackEngineIndex).currentEngineIncarnation(),
            manager.engineList.get(gameInfo.whiteEngineIndex).currentEngineIncarnation()));
    return transaction;
  }

  private static EngineManager.DeferredEngineGamePrimaryPublication publication(
      EngineManager manager,
      EngineGameInfo gameInfo,
      int index,
      Leelaz candidate,
      Leelaz previousPrimary,
      long previousGeneration,
      boolean ponder) {
    return EngineManager.prepareEngineGamePrimaryPublication(
        manager,
        gameInfo,
        index,
        candidate,
        previousPrimary,
        previousGeneration,
        candidate.currentEngineIncarnation(),
        ponder,
        Lizzie.board,
        Lizzie.board.getContextRevision(),
        Lizzie.board.getHistory().isBlacksTurn());
  }

  private static Board preparedBoard() {
    try {
      Board board = allocate(Board.class);
      board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      return board;
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for engine-game test latch");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void awaitThreadState(Thread thread, Thread.State... expectedStates) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    Thread.State observed = thread.getState();
    while (System.nanoTime() < deadline) {
      observed = thread.getState();
      for (Thread.State expected : expectedStates) {
        if (observed == expected) {
          return;
        }
      }
      if (observed == Thread.State.TERMINATED) {
        break;
      }
      Thread.yield();
    }
    throw new AssertionError(
        "thread "
            + thread.getName()
            + " did not enter one of "
            + Arrays.toString(expectedStates)
            + "; observed "
            + observed);
  }

  private static void awaitOperationsReleased(
      EngineManager.EngineGameTransaction transaction) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (transaction.operationsInFlightForTest() != 0 && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  private static void settleAllCommandResponses(
      StateMachineLeelaz engine, EngineManager.EngineGameTransaction transaction)
      throws InterruptedException {
    java.util.HashSet<Integer> settled = new java.util.HashSet<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (transaction.operationsInFlightForTest() != 0 && System.nanoTime() < deadline) {
      for (String line : engine.commandText().split("\\R")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        int id = Integer.parseInt(trimmed.split("\\s+", 2)[0]);
        if (settled.add(id)) {
          engine.processCommandResponseLineForTest("=" + id);
        }
      }
      Thread.sleep(5L);
    }
    assertEquals(0, transaction.operationsInFlightForTest());
  }

  private static void awaitNoEngineGameOperations(
      EngineManager.EngineGameTransaction transaction) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
    while (System.nanoTime() < deadline
        && (transaction.operationsInFlightForTest() != 0
            || !transaction.retirementFinishedForTest())) {
      Thread.onSpinWait();
    }
    assertEquals(0, transaction.operationsInFlightForTest());
    assertTrue(
        transaction.retirementFinishedForTest(),
        "fallback watchdog retirement must finish before a successor can be admitted");
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static void setRemoteTransport(Leelaz engine, EngineTransport transport)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("remoteTransport");
    field.setAccessible(true);
    field.set(engine, transport);
  }

  private enum WorkerMode {
    NORMAL,
    THROW_BEFORE_START,
    START_THEN_THROW
  }

  private static class ImmediateUiEngineManager extends EngineManager {
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(30L);

    private ImmediateUiEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected long engineGameStartupTimeoutMillis() {
      return timeoutMillis;
    }

    @Override
    protected long engineGameStartupTimeoutMillis(EngineGameInfo gameInfo) {
      return timeoutMillis;
    }

    @Override
    protected void dispatchEngineGameUi(Runnable update) {
      update.run();
    }
  }

  private static final class ReadinessGateEngineManager extends ImmediateUiEngineManager {
    private final CountDownLatch readinessProbeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseReadinessProbe = new CountDownLatch(1);

    private ReadinessGateEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    void afterPkEngineReadinessProbeForTest(
        EngineManager.EngineGameTransaction transaction,
        Leelaz engine,
        Object expectedIncarnation) {
      readinessProbeEntered.countDown();
      boolean interrupted = false;
      while (true) {
        try {
          releaseReadinessProbe.await();
          break;
        } catch (InterruptedException waitInterrupted) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class BudgetAwareEngineManager extends EngineManager {
    private BudgetAwareEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return TimeUnit.SECONDS.toMillis(125L);
    }

    private long configuredStartupBudget(EngineGameInfo gameInfo) {
      return super.engineGameStartupTimeoutMillis(gameInfo);
    }
  }

  private static final class StopCommentEngineManager extends ImmediateUiEngineManager {
    private final AssertionError stopAfterComment =
        new AssertionError("controlled stop after PK comment routing");
    private volatile boolean sawForcedEngineGame;
    private volatile boolean sawActiveFlag;

    private StopCommentEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void appendEngineGameStopComment(boolean forceEngineGame) {
      sawForcedEngineGame = forceEngineGame;
      sawActiveFlag = EngineManager.isEngineGame;
      throw stopAfterComment;
    }
  }

  private static final class BlockingSaveEngineManager extends ImmediateUiEngineManager {
    private final CountDownLatch savingStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSaving = new CountDownLatch(1);
    private final AssertionError stopFailure =
        new AssertionError("controlled failure after duplicate stop check");

    private BlockingSaveEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    public void stopCountDown() {
      savingStarted.countDown();
      await(releaseSaving);
      throw stopFailure;
    }
  }

  private static final class DeferredUiEngineManager extends ImmediateUiEngineManager {
    private final AtomicReference<Runnable> pendingUi = new AtomicReference<>();

    private DeferredUiEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void dispatchEngineGameUi(Runnable update) {
      if (!pendingUi.compareAndSet(null, update)) {
        throw new AssertionError("duplicate rollback presentation");
      }
    }

    private void runPendingUi() {
      Runnable update = pendingUi.getAndSet(null);
      assertNotNull(update);
      update.run();
    }
  }

  private static final class WatchdogEngineManager extends ImmediateUiEngineManager {
    private final AtomicReference<Runnable> pendingWatchdog = new AtomicReference<>();
    private volatile RuntimeException graceFailure;

    private WatchdogEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected long engineGamePhysicalRequestForceGraceMillis() {
      if (graceFailure != null) {
        throw graceFailure;
      }
      return 5_000L;
    }

    @Override
    protected void scheduleEngineGamePhysicalRequestWatchdog(
        Runnable task, long graceMillis, String name) {
      assertEquals(5_000L, graceMillis);
      if (!pendingWatchdog.compareAndSet(null, task)) {
        throw new AssertionError("duplicate engine-game physical watchdog");
      }
    }

    private boolean hasPendingWatchdog() {
      return pendingWatchdog.get() != null;
    }

    private void runWatchdog() {
      Runnable task = pendingWatchdog.getAndSet(null);
      assertNotNull(task);
      task.run();
    }
  }

  private static final class FailingWatchdogSchedulerManager
      extends ImmediateUiEngineManager {
    private final AssertionError schedulingFailure =
        new AssertionError("controlled physical-watchdog scheduling failure");

    private FailingWatchdogSchedulerManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected long engineGamePhysicalRequestForceGraceMillis() {
      return 0L;
    }

    @Override
    protected void scheduleEngineGamePhysicalRequestWatchdog(
        Runnable task, long graceMillis, String name) {
      assertEquals(0L, graceMillis);
      throw schedulingFailure;
    }
  }

  private static final class RecoveryGateEngineManager extends ImmediateUiEngineManager {
    private final CountDownLatch[] entered = new CountDownLatch[3];
    private final CountDownLatch[] released = new CountDownLatch[3];
    private final boolean deferRollback;
    private final AtomicReference<Runnable> pendingUi = new AtomicReference<>();
    private final CountDownLatch recoveryFinished = new CountDownLatch(1);
    private volatile EngineManager.EngineGameDeferredRecovery recovery;
    private volatile String recoveryFailureDetail;

    private RecoveryGateEngineManager(List<Leelaz> engines, boolean deferRollback) {
      super(engines);
      this.deferRollback = deferRollback;
      for (int index = 0; index < entered.length; index++) {
        entered[index] = new CountDownLatch(1);
        released[index] = new CountDownLatch(1);
      }
    }

    @Override
    protected void dispatchEngineGameUi(Runnable update) {
      if (!deferRollback) {
        update.run();
        return;
      }
      if (!pendingUi.compareAndSet(null, update)) {
        throw new AssertionError("duplicate deferred recovery rollback presentation");
      }
    }

    @Override
    protected void beforeDeferredEngineGameRecoveryStage(
        EngineManager.EngineGameDeferredRecovery recovery,
        EngineManager.DeferredEngineGameRecoveryStage stage) {
      this.recovery = recovery;
      entered[stage.ordinal()].countDown();
      await(released[stage.ordinal()]);
    }

    @Override
    protected void afterDeferredEngineGameRecovery(
        EngineManager.EngineGameDeferredRecovery recovery, String failureDetail) {
      this.recovery = recovery;
      recoveryFailureDetail = failureDetail;
      recoveryFinished.countDown();
    }

    private boolean stageEntered(EngineManager.DeferredEngineGameRecoveryStage stage)
        throws InterruptedException {
      return entered[stage.ordinal()].await(2, TimeUnit.SECONDS);
    }

    private void releaseStage(EngineManager.DeferredEngineGameRecoveryStage stage) {
      released[stage.ordinal()].countDown();
    }

    private void runPendingUi() {
      Runnable update = pendingUi.getAndSet(null);
      assertNotNull(update);
      update.run();
    }
  }

  private static final class WorkerSeamEngineManager extends ImmediateUiEngineManager {
    private final AssertionError schedulingFailure =
        new AssertionError("controlled scheduling failure");
    private volatile WorkerMode workerMode = WorkerMode.NORMAL;
    private volatile CountDownLatch taskEntered;

    private WorkerSeamEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected Thread createEngineGameWorker(Runnable task, String name) {
      return new Thread(task, name) {
        @Override
        public synchronized void start() {
          if (workerMode == WorkerMode.THROW_BEFORE_START) {
            throw schedulingFailure;
          }
          super.start();
          if (workerMode == WorkerMode.START_THEN_THROW) {
            await(taskEntered);
            throw schedulingFailure;
          }
        }
      };
    }
  }

  private static final class StateMachineLeelaz extends Leelaz {
    private long tuningTimeoutMillis = TimeUnit.SECONDS.toMillis(120L);
    private final AtomicInteger maybeAdjustPdaCalls = new AtomicInteger();
    private final AtomicInteger exactAnalysisActions = new AtomicInteger();
    private final AtomicInteger ordinaryAnalysisActions = new AtomicInteger();
    private ByteArrayOutputStream commandOutput;
    private volatile CountDownLatch responseSettled;
    private volatile CountDownLatch releaseSettledResponse;
    private volatile CountDownLatch startupPostWorkerEntered;
    private volatile CountDownLatch releaseStartupPostWorker;
    private volatile CountDownLatch startupPostCommandsEntered;
    private volatile CountDownLatch releaseStartupPostCommands;
    private volatile CountDownLatch startupResponseOwnerCaptured;
    private volatile CountDownLatch releaseStartupResponseOwner;
    private volatile CountDownLatch engineGameBootstrapEntered;
    private volatile CountDownLatch releaseEngineGameBootstrap;
    private volatile CountDownLatch engineGameBootstrapCompleted;
    private volatile CountDownLatch forceQuitEntered;
    private volatile CountDownLatch releaseForceQuit;
    private volatile CountDownLatch forceQuitFinished;
    private volatile RuntimeException forceQuitFailure;
    private volatile CountDownLatch analysisRouteCaptured;
    private volatile CountDownLatch releaseAnalysisRoute;
    private volatile CountDownLatch analysisInfoSnapshotCaptured;
    private volatile CountDownLatch releaseAnalysisInfoSnapshot;
    private volatile CountDownLatch analysisPrimaryPublicationEntered;
    private volatile CountDownLatch releaseAnalysisPrimaryPublication;
    private volatile CountDownLatch ordinaryAnalysisAdmissionEntered;
    private volatile CountDownLatch releaseOrdinaryAnalysisAdmission;
    private volatile CountDownLatch ordinaryAnalysisActionEntered;
    private volatile CountDownLatch releaseOrdinaryAnalysisAction;
    private volatile Runnable ordinaryAnalysisActionAfterRelease;
    private volatile CountDownLatch statefulOrdinaryPairAdmitted;
    private volatile CountDownLatch releaseStatefulOrdinaryPairAdmission;
    private final AtomicBoolean blockNextStatefulOrdinaryPublication = new AtomicBoolean();
    private volatile CountDownLatch statefulOrdinaryPublicationChecked;
    private volatile CountDownLatch releaseStatefulOrdinaryPublication;
    private volatile Runnable afterClearStateCommand;
    private volatile CountDownLatch readerBindingPublicationEntered;
    private volatile CountDownLatch releaseReaderBindingPublication;
    private volatile RuntimeException analysisDisplayPublicationFailure;
    private volatile Thread startupPostWorker;
    private final AtomicReference<Throwable> startupPostWorkerFailure = new AtomicReference<>();
    private final AtomicInteger forceQuitAttempts = new AtomicInteger();
    private final AtomicInteger successfulForceQuits = new AtomicInteger();
    private volatile boolean enableDeferredRecoveryStart;
    private volatile boolean sendDeferredRecoveryBootstrapStateCommand;
    private volatile boolean throwAfterDeferredRecoveryBinding;
    private volatile boolean deferredRecoveryIsolationObserved;
    private volatile boolean runStartupPostActionsInline;
    private volatile boolean allowDeferredOpenClRecovery;
    private final AtomicInteger deferredRecoveryStartCount = new AtomicInteger();
    private final AtomicInteger deferredOpenClPrepareCount = new AtomicInteger();
    private volatile ExactSnapshotRestoreProtocolFixture.Transport recoveryTransport;

    private StateMachineLeelaz() throws Exception {
      super("");
    }

    private void bindLiveRuntime() {
      recoveryTransport = null;
      commandOutput = new ByteArrayOutputStream();
      installFreshCommandOutputForTest(commandOutput);
      started = true;
      isLoaded = true;
      isNormalEnd = false;
    }

    private void bindLiveRuntime(OutputStream output) {
      recoveryTransport = null;
      commandOutput = output instanceof ByteArrayOutputStream ? (ByteArrayOutputStream) output : null;
      installFreshCommandOutputForTest(output);
      started = true;
      isLoaded = true;
      isNormalEnd = false;
    }

    private String commandText() {
      ExactSnapshotRestoreProtocolFixture.Transport recoveryOutput = recoveryTransport;
      if (recoveryOutput != null) {
        return String.join(System.lineSeparator(), recoveryOutput.rawCommands());
      }
      return commandOutput == null ? "" : commandOutput.toString();
    }

    @Override
    public void startEngine(int index) throws IOException {
      if (!enableDeferredRecoveryStart) {
        super.startEngine(index);
        return;
      }
      deferredRecoveryStartCount.incrementAndGet();
      bindLiveRuntime();
      recoveryTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              this, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      deferredRecoveryIsolationObserved = Leelaz.isDeferredEngineGameRecoveryStartup();
      if (sendDeferredRecoveryBootstrapStateCommand) {
        sendCommandNoLeelaz2("komi 6.25");
      }
      if (throwAfterDeferredRecoveryBinding) {
        throw new AssertionError("controlled recovery failure after reader publication");
      }
      isCheckingName = false;
      isDownWithError = false;
    }

    @Override
    boolean prepareBundledOpenClRecoveryForFailedIncarnation(Object expectedIncarnation) {
      deferredOpenClPrepareCount.incrementAndGet();
      return allowDeferredOpenClRecovery
          && isCurrentEngineIncarnation(expectedIncarnation);
    }

    @Override
    void confirmBoardSynchronization(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      if (enableDeferredRecoveryStart) {
        onSuccess.run();
      } else {
        super.confirmBoardSynchronization(onSuccess, onFailure);
      }
    }

    @Override
    public void nameCmd() {
      if (!enableDeferredRecoveryStart) {
        super.nameCmd();
      }
    }

    private void blockAfterEngineGameResponseSettlement() {
      responseSettled = new CountDownLatch(1);
      releaseSettledResponse = new CountDownLatch(1);
    }

    private void blockStartupPostActionWorker() {
      startupPostWorkerEntered = new CountDownLatch(1);
      releaseStartupPostWorker = new CountDownLatch(1);
    }

    private void blockAfterStartupPostActionCommands() {
      startupPostCommandsEntered = new CountDownLatch(1);
      releaseStartupPostCommands = new CountDownLatch(1);
    }

    private void blockAfterStartupResponseOwnerCapture() {
      startupResponseOwnerCaptured = new CountDownLatch(1);
      releaseStartupResponseOwner = new CountDownLatch(1);
    }

    private void blockEngineGameBootstrapBeforeCommands() {
      engineGameBootstrapEntered = new CountDownLatch(1);
      releaseEngineGameBootstrap = new CountDownLatch(1);
    }

    private void trackEngineGameBootstrapCompletion() {
      engineGameBootstrapCompleted = new CountDownLatch(1);
    }

    private void blockAfterAnalysisOutputRouteCapture() {
      analysisRouteCaptured = new CountDownLatch(1);
      releaseAnalysisRoute = new CountDownLatch(1);
    }

    private void blockAfterAnalysisInfoAdmissionSnapshotCapture() {
      analysisInfoSnapshotCaptured = new CountDownLatch(1);
      releaseAnalysisInfoSnapshot = new CountDownLatch(1);
    }

    private void blockBeforeOrdinaryAnalysisOutputAdmission() {
      ordinaryAnalysisAdmissionEntered = new CountDownLatch(1);
      releaseOrdinaryAnalysisAdmission = new CountDownLatch(1);
    }

    private void blockInsideOrdinaryAnalysisOutputAction(Runnable afterRelease) {
      ordinaryAnalysisActionEntered = new CountDownLatch(1);
      releaseOrdinaryAnalysisAction = new CountDownLatch(1);
      ordinaryAnalysisActionAfterRelease = afterRelease;
    }

    private void trackStatefulOrdinaryPairAdmission() {
      statefulOrdinaryPairAdmitted = new CountDownLatch(1);
    }

    private void blockAfterStatefulOrdinaryPairAdmission() {
      statefulOrdinaryPairAdmitted = new CountDownLatch(1);
      releaseStatefulOrdinaryPairAdmission = new CountDownLatch(1);
    }

    private void blockAfterNextCurrentStatefulAdmissionCheck() {
      statefulOrdinaryPublicationChecked = new CountDownLatch(1);
      releaseStatefulOrdinaryPublication = new CountDownLatch(1);
      blockNextStatefulOrdinaryPublication.set(true);
    }

    private void blockBeforeAnalysisPrimaryPublication() {
      analysisPrimaryPublicationEntered = new CountDownLatch(1);
      releaseAnalysisPrimaryPublication = new CountDownLatch(1);
    }

    private void blockBeforeReaderBindingPublication() {
      readerBindingPublicationEntered = new CountDownLatch(1);
      releaseReaderBindingPublication = new CountDownLatch(1);
    }

    @Override
    void beforeEngineGameBootstrapCommandsForTest(
        EngineManager.EngineGameTransaction transaction) {
      CountDownLatch entered = engineGameBootstrapEntered;
      CountDownLatch release = releaseEngineGameBootstrap;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      boolean interrupted = false;
      while (true) {
        try {
          release.await();
          break;
        } catch (InterruptedException waitInterrupted) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    void afterEngineGameBootstrapCommandsForTest(
        EngineManager.EngineGameTransaction transaction) {
      if (engineGameBootstrapCompleted != null) {
        engineGameBootstrapCompleted.countDown();
      }
    }

    private void blockForceQuit() {
      forceQuitEntered = new CountDownLatch(1);
      releaseForceQuit = new CountDownLatch(1);
      forceQuitFinished = new CountDownLatch(1);
    }

    @Override
    boolean forceQuitIfCurrentIncarnation(Object expectedIncarnation) {
      forceQuitAttempts.incrementAndGet();
      try {
        if (forceQuitEntered != null) {
          forceQuitEntered.countDown();
          await(releaseForceQuit);
        }
        if (forceQuitFailure != null) {
          throw forceQuitFailure;
        }
        boolean forced = super.forceQuitIfCurrentIncarnation(expectedIncarnation);
        if (forced) {
          successfulForceQuits.incrementAndGet();
        }
        return forced;
      } finally {
        if (forceQuitFinished != null) {
          forceQuitFinished.countDown();
        }
      }
    }

    @Override
    void afterEngineGameStartupResponseOwnershipCapturedForTest(
        EngineManager.EngineGameTransaction transaction) {
      if (startupResponseOwnerCaptured == null) {
        return;
      }
      startupResponseOwnerCaptured.countDown();
      await(releaseStartupResponseOwner);
    }

    @Override
    void afterAnalysisOutputRouteCapturedForTest(String route) {
      CountDownLatch entered = analysisRouteCaptured;
      CountDownLatch release = releaseAnalysisRoute;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      await(release);
    }

    @Override
    void afterAnalysisInfoAdmissionSnapshotCapturedForTest() {
      CountDownLatch entered = analysisInfoSnapshotCaptured;
      CountDownLatch release = releaseAnalysisInfoSnapshot;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      await(release);
    }

    @Override
    void beforeAnalysisPrimaryPublicationForTest() {
      CountDownLatch entered = analysisPrimaryPublicationEntered;
      CountDownLatch release = releaseAnalysisPrimaryPublication;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      await(release);
    }

    @Override
    void beforeAnalysisDisplayPublicationForTest() {
      RuntimeException failure = analysisDisplayPublicationFailure;
      analysisDisplayPublicationFailure = null;
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    void beforeReaderBindingPublicationForTest() {
      CountDownLatch entered = readerBindingPublicationEntered;
      CountDownLatch release = releaseReaderBindingPublication;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      await(release);
    }

    @Override
    void beforeOrdinaryAnalysisOutputAdmissionForTest() {
      CountDownLatch entered = ordinaryAnalysisAdmissionEntered;
      CountDownLatch release = releaseOrdinaryAnalysisAdmission;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      await(release);
    }

    @Override
    void beforeAnalysisOutputActionForTest(boolean exactEngineGame) {
      (exactEngineGame ? exactAnalysisActions : ordinaryAnalysisActions).incrementAndGet();
      if (exactEngineGame || ordinaryAnalysisActionEntered == null) {
        return;
      }
      ordinaryAnalysisActionEntered.countDown();
      await(releaseOrdinaryAnalysisAction);
      Runnable afterRelease = ordinaryAnalysisActionAfterRelease;
      if (afterRelease != null) {
        afterRelease.run();
      }
    }

    @Override
    void afterStatefulOrdinaryPairAdmissionForTest() {
      CountDownLatch admitted = statefulOrdinaryPairAdmitted;
      if (admitted != null) {
        admitted.countDown();
      }
      CountDownLatch release = releaseStatefulOrdinaryPairAdmission;
      if (release != null) {
        await(release);
      }
    }

    @Override
    void afterCurrentStatefulOrdinaryAdmissionCheckForTest(
        String mutationKind, long admissionGeneration) {
      if (!blockNextStatefulOrdinaryPublication.compareAndSet(true, false)) {
        return;
      }
      statefulOrdinaryPublicationChecked.countDown();
      await(releaseStatefulOrdinaryPublication);
    }

    @Override
    void afterClearStateCommandForTest() {
      Runnable action = afterClearStateCommand;
      if (action != null) {
        action.run();
      }
    }

    @Override
    void dispatchStartupPostActionWorker(Runnable worker) {
      if (runStartupPostActionsInline) {
        worker.run();
        return;
      }
      if (startupPostWorkerEntered == null && startupPostCommandsEntered == null) {
        super.dispatchStartupPostActionWorker(worker);
        return;
      }
      startupPostWorker =
          new Thread(
              () -> {
                if (startupPostWorkerEntered != null) {
                  startupPostWorkerEntered.countDown();
                  await(releaseStartupPostWorker);
                }
                try {
                  worker.run();
                } catch (Throwable failure) {
                  startupPostWorkerFailure.set(failure);
                }
              },
              "blocked-engine-game-startup-post-action");
      startupPostWorker.setDaemon(true);
      startupPostWorker.start();
    }

    @Override
    void afterStartupPostActionCommandsForTest() {
      if (startupPostCommandsEntered == null) {
        return;
      }
      startupPostCommandsEntered.countDown();
      await(releaseStartupPostCommands);
    }

    @Override
    public void notPondering() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {
      maybeAdjustPdaCalls.incrementAndGet();
    }

    @Override
    void afterEngineGameResponseSettledForTest() {
      CountDownLatch entered = responseSettled;
      CountDownLatch release = releaseSettledResponse;
      if (entered == null || release == null) {
        return;
      }
      entered.countDown();
      await(release);
    }

    @Override
    long engineTuningSynchronizationTimeoutMillis() {
      return tuningTimeoutMillis;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private AtomicInteger inputAttempts;
    private volatile boolean throwOnInputRestore;
    private volatile BoardHistoryNode displayNodeOverride;

    @Override
    public boolean isInputRoutingInitialized() {
      return true;
    }

    @Override
    public void addInput(boolean shouldAdd) {
      inputAttempts.incrementAndGet();
      if (throwOnInputRestore) {
        throw new AssertionError("controlled input restoration failure");
      }
    }

    @Override
    public void updateTitle() {}

    @Override
    public BoardHistoryNode getDisplayNode() {
      return displayNodeOverride != null
          ? displayNodeOverride
          : Lizzie.board.getHistory().getCurrentHistoryNode();
    }

    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestAnalysisTitleUpdate() {}

    @Override
    public void refresh() {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void updateEngineMenu() {}

    @Override
    public void applyEngineSwitchUiState(EngineManager.EngineSwitchUiSnapshot snapshot) {}

    @Override
    public void changeEngineIcon(int index, int mode) {}

    @Override
    public void changeEngineIcon2(int index, int mode) {}

    @Override
    public void changeicon(int index) {}

    @Override
    public void showPdaForEngine(Leelaz engine, boolean show) {}

    @Override
    public void showPdaForEngine(Leelaz engine, long primaryGeneration, boolean show) {}

    @Override
    public void setBtnRankMark() {}
  }

  private static final class TrackingToolbar extends BottomToolbar {
    private AtomicInteger enableAttempts;
    private volatile boolean controlsEnabled;

    @Override
    public void enableDisabelForEngineGame(boolean enable) {
      enableAttempts.incrementAndGet();
      controlsEnabled = enable;
    }
  }

  private static class RecordingBoard extends Board {
    AtomicInteger clearAfterMoveCalls;

    @Override
    public void clearAfterMove() {
      clearAfterMoveCalls.incrementAndGet();
    }
  }

  private static final class BlockingCommitBoard extends RecordingBoard {
    private CountDownLatch boardCommitted;
    private CountDownLatch releaseCommit;

    @Override
    public synchronized Board.EngineGameMoveCommit commitEngineGamePlace(
        BoardHistoryList expectedHistory,
        BoardHistoryNode expectedNode,
        boolean expectedBlackToPlay,
        int x,
        int y,
        Stone color,
        boolean noCapture,
        boolean canSuicidal,
        boolean newMoveNumberInBranch) {
      Board.EngineGameMoveCommit committed =
          super.commitEngineGamePlace(
              expectedHistory,
              expectedNode,
              expectedBlackToPlay,
              x,
              y,
              color,
              noCapture,
              canSuicidal,
              newMoveNumberInBranch);
      boardCommitted.countDown();
      await(releaseCommit);
      return committed;
    }
  }

  private static final class BlockingFirstWriteOutput extends ByteArrayOutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);

    @Override
    public void write(int value) {
      if (first.compareAndSet(true, false)) {
        writeEntered.countDown();
        await(releaseWrite);
      }
      synchronized (this) {
        super.write(value);
      }
    }

    private synchronized String text() {
      return toString();
    }
  }

  private static final class ArmableBlockingWriteOutput extends ByteArrayOutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean blocked = new AtomicBoolean();

    private void arm() {
      armed.set(true);
    }

    @Override
    public void write(int value) {
      if (armed.get() && blocked.compareAndSet(false, true)) {
        writeEntered.countDown();
        await(releaseWrite);
      }
      synchronized (this) {
        super.write(value);
      }
    }

    private synchronized String text() {
      return toString();
    }
  }

  private static final class FlushFailingOutput extends ByteArrayOutputStream {
    @Override
    public synchronized void flush() throws IOException {
      throw new IOException("controlled flush failure after delegated bytes");
    }

    private synchronized String text() {
      return toString();
    }
  }

  private static final class ShutdownAfterFirstFlushOutput extends ByteArrayOutputStream {
    private final AtomicInteger flushes = new AtomicInteger();
    private volatile StateMachineLeelaz engine;

    private void arm(StateMachineLeelaz engine) {
      this.engine = engine;
    }

    @Override
    public synchronized void flush() {
      StateMachineLeelaz currentEngine = engine;
      if (currentEngine != null && flushes.incrementAndGet() == 1) {
        currentEngine.requestCurrentReaderShutdownForTest();
      }
    }

    private synchronized String text() {
      return toString();
    }
  }

  private static final class CloseReleasedWriteOutput extends ByteArrayOutputStream {
    private final CountDownLatch writeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWrite = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean first =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    @Override
    public void write(int value) {
      blockFirstWrite();
      super.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      blockFirstWrite();
      super.write(bytes, offset, length);
    }

    private void blockFirstWrite() {
      if (first.compareAndSet(true, false)) {
        writeEntered.countDown();
        await(releaseWrite);
      }
    }

    @Override
    public void close() {
      releaseWrite.countDown();
    }
  }

  private static final class CloseReleasedTransport implements EngineTransport {
    private final CloseReleasedWriteOutput output;
    private final AtomicInteger closeCount = new AtomicInteger();

    private CloseReleasedTransport(CloseReleasedWriteOutput output) {
      this.output = output;
    }

    @Override
    public void start() {}

    @Override
    public InputStream stdout() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public OutputStream stdin() {
      return output;
    }

    @Override
    public InputStream stderr() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public boolean isOpen() {
      return closeCount.get() == 0;
    }

    @Override
    public String description() {
      return "controlled close-released transport";
    }

    @Override
    public void close() {
      if (closeCount.incrementAndGet() == 1) {
        output.close();
      }
    }

    // Matches the final exact non-protocol EngineTransport API; harmless as an extra method on
    // this branch's older interface.
    public void abort() {
      close();
    }
  }

  private static final class ImmediateGenmoveResponseOutput extends ByteArrayOutputStream {
    private final AtomicInteger responses = new AtomicInteger();
    private final AtomicReference<Throwable> responseFailure = new AtomicReference<>();
    private volatile StateMachineLeelaz engine;
    private volatile String response;

    private void arm(StateMachineLeelaz engine, String response) {
      this.engine = engine;
      this.response = response;
    }

    @Override
    public synchronized void write(int value) {
      StateMachineLeelaz currentEngine = engine;
      String currentResponse = response;
      if (currentEngine != null
          && currentResponse != null
          && responses.compareAndSet(0, 1)) {
        try {
          currentEngine.parseEngineGameLineForTest(currentResponse);
        } catch (Throwable failure) {
          responseFailure.set(failure);
        }
      }
      super.write(value);
    }
  }

  private static final class ImmediateAnalysisTailOutput extends ByteArrayOutputStream {
    private final AtomicInteger tails = new AtomicInteger();
    private final AtomicReference<Throwable> tailFailure = new AtomicReference<>();
    private volatile StateMachineLeelaz engine;
    private volatile String line;

    private void arm(StateMachineLeelaz engine, String line) {
      this.engine = engine;
      this.line = line;
    }

    @Override
    public synchronized void write(int value) {
      StateMachineLeelaz currentEngine = engine;
      String currentLine = line;
      if (currentEngine != null && currentLine != null && tails.compareAndSet(0, 1)) {
        try {
          currentEngine.parseAnalysisLineForTest(currentLine);
        } catch (Throwable failure) {
          tailFailure.set(failure);
        }
      }
      super.write(value);
    }
  }

  private static final class ImmediateQueuedAnalysisOutput extends ByteArrayOutputStream {
    private final AtomicInteger enqueues = new AtomicInteger();
    private final AtomicReference<Throwable> enqueueFailure = new AtomicReference<>();
    private volatile StateMachineLeelaz engine;
    private volatile String successorCommand;

    private void arm(StateMachineLeelaz engine, String successorCommand) {
      this.engine = engine;
      this.successorCommand = successorCommand;
    }

    @Override
    public synchronized void write(int value) {
      StateMachineLeelaz currentEngine = engine;
      String currentSuccessor = successorCommand;
      if (currentEngine != null
          && currentSuccessor != null
          && enqueues.compareAndSet(0, 1)) {
        try {
          currentEngine.sendOrdinaryAnalysisCommandForTest(currentSuccessor);
        } catch (Throwable failure) {
          enqueueFailure.set(failure);
        }
      }
      super.write(value);
    }
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}
  }
}
