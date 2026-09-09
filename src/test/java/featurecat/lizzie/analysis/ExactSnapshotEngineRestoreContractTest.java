package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExactSnapshotEngineRestoreContractTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final String AUTO_ID_RESPONSE = "__auto-id-response__";

  @Test
  void exactSnapshotRestoreHistoryTargetReplaysOnlyRealActionsAfterNearestSnapshot()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));

      Leelaz engine = new Leelaz("");
      ScriptedResponseOutputStream output =
          new ScriptedResponseOutputStream(engine, null, null, AUTO_ID_RESPONSE);
      setOutputStream(engine, output);

      executeHistoryRestore(engine, history.getCurrentHistoryNode());

      assertTrue(isLoadSgfCommand(output.commands().get(0)));
      assertEquals(
          List.of("play B " + Board.convertCoordinatesToName(2, 2), "play W pass"),
          collectPlayCommands(output.commands()),
          "history restore should replay only real MOVE/PASS nodes after the nearest snapshot.");
    }
  }

  @Test
  void remoteComputeSnapshotAnchorRestoreDoesNotEmitHostLoadSgf() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());

      board.resendMoveToEngine(engine, false);

      List<String> commands = transport.commands();
      assertTrue(
          commands.stream().noneMatch(ExactSnapshotEngineRestoreContractTest::isHostLoadSgfCommand),
          "remote snapshot-anchor restore must not emit a host-only loadsgf path: " + commands);

      String setPosition =
          commands.stream()
              .filter(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand)
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "remote snapshot restore must represent setup stones in-band: "
                              + commands));
      Set<String> placed = setPositionPlacements(setPosition);
      assertTrue(
          placed.contains("B " + Board.convertCoordinatesToName(0, 0)),
          "black setup stone missing from in-band restore: " + setPosition);
      assertTrue(
          placed.contains("W " + Board.convertCoordinatesToName(1, 0)),
          "white setup stone missing from in-band restore: " + setPosition);
      assertTrue(
          commands.stream()
              .noneMatch(ExactSnapshotEngineRestoreContractTest::isGoguiSetupPlayerCommand),
          "remote restore must not emit gogui-setup_player: " + commands);
      assertEquals(
          List.of("play B " + Board.convertCoordinatesToName(2, 2)),
          collectPlayCommands(commands),
          "sequential tail after the snapshot must still be replayed with play");
    }
  }

  @Test
  void remoteComputeEmptyTailWhiteToPlayFailsClosedAndReleasesBoardSyncLease()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      Lizzie.setPrimaryEngine(engine);

      Object owner = new Object();
      Leelaz.LifecycleCompletionClaim claim = engine.tryBeginLifecycleCompletion(owner, null);
      assertNotNull(claim);
      Leelaz.ExactSnapshotRestoreAdmission admission =
          engine.captureBoardSyncExactSnapshotRestoreAdmission();
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          ExactSnapshotEngineRestore.prepareCurrentPosition(admission, snapshotRoot(false));
      AtomicInteger completionCount = new AtomicInteger();
      claim.completeSuccess(completionCount::incrementAndGet, detail -> {});
      assertEquals(0, completionCount.get(), "the live board-sync lease must defer completion.");

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(ExactSnapshotEngineRestore.Failure.class, preparedRestore::execute);

      assertEquals(
          ExactSnapshotEngineRestore.FailureCategory.UNSUPPORTED_REMOTE_POSITION,
          thrown.category());
      assertTrue(thrown.getMessage().contains("white-to-play"));
      assertTrue(
          transport.commands().isEmpty(),
          "unsupported remote state must fail before preclear or restore commands: "
              + transport.commands());
      assertEquals(1, completionCount.get(), "failure must release the board-sync lease.");
      Leelaz.LifecycleCompletionClaim nextClaim =
          engine.tryBeginLifecycleCompletion(new Object(), null);
      assertNotNull(nextClaim, "failure must release lifecycle-completion admission.");
      nextClaim.completeSuccess(() -> {}, detail -> {});
    }
  }

  @Test
  void remoteComputeEmptyTailBlackToPlaySnapshotRestoreSetsEngineSideToPlay() throws Exception {
    assertRemoteEmptyTailBlackToPlayRestore();
  }

  @Test
  void remoteComputeTailGtpErrorFailsRestoreInsteadOfReportingSuccess() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      String tailCommand = "play B " + Board.convertCoordinatesToName(2, 2);

      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.equals(tailCommand)
                      ? ExactSnapshotRestoreProtocolFixture.Response.error(
                          "controlled tail failure")
                      : ExactSnapshotRestoreProtocolFixture.Response.success());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(
              ExactSnapshotEngineRestore.Failure.class,
              () -> executeHistoryRestore(engine, history.getCurrentHistoryNode()));

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR, thrown.category());
      assertTrue(thrown.getMessage().contains(tailCommand));
      assertTrue(thrown.getMessage().contains("controlled tail failure"));
      assertEquals(List.of(tailCommand), collectPlayCommands(transport.commands()));
    }
  }

  @Test
  void remoteComputeTailResponseTimeoutFailsRestoreInsteadOfReportingSuccess() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      String tailCommand = "play B " + Board.convertCoordinatesToName(2, 2);

      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.equals(tailCommand)
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(
              ExactSnapshotEngineRestore.Failure.class,
              () -> executeHistoryRestore(engine, history.getCurrentHistoryNode()));

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.TIMEOUT, thrown.category());
      assertTrue(thrown.getMessage().contains(tailCommand));
      assertTrue(thrown.getMessage().contains("Timed out"));
      assertEquals(List.of(tailCommand), collectPlayCommands(transport.commands()));
    }
  }

  @Test
  void remoteComputeKomiErrorRemainsFailureAfterSetPositionSucceeds() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardData position = snapshotRoot(true);
      position.komi = 6.5;
      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      engine.komi = 7.5f;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("komi ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.error("illegal komi")
                      : ExactSnapshotRestoreProtocolFixture.Response.success());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(
              ExactSnapshotEngineRestore.Failure.class,
              () -> executePositionRestore(engine, position));

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR, thrown.category());
      assertTrue(thrown.getMessage().contains("komi 6.5"));
      assertTrue(thrown.getMessage().contains("illegal komi"));
      assertEquals("boardsize 3", transport.commands().get(0));
      assertEquals("komi 6.5", transport.commands().get(1));
      assertTrue(
          transport.commands().stream()
              .anyMatch(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand),
          "set_position success must not erase the earlier komi failure: " + transport.commands());
      assertTrue(
          transport.rawCommands().stream()
              .filter(command -> commandPayload(command).startsWith("komi "))
              .allMatch(ExactSnapshotEngineRestoreContractTest::hasNumericCommandId));
      assertTrue(
          transport.rawCommands().stream()
              .filter(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand)
              .allMatch(ExactSnapshotEngineRestoreContractTest::hasNumericCommandId));
      assertEquals(
          7.5f,
          engine.komi,
          0.0001f,
          "failed remote komi must not be published into the Java-side cache.");
    }
  }

  @Test
  void remoteComputeBoardSizeErrorStopsBeforeSetPositionAndPreservesCaches() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardData position = snapshotRoot(true);
      position.komi = 6.5;
      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      engine.width = 19;
      engine.height = 19;
      engine.komi = 7.5f;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("boardsize ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.error("unsupported size")
                      : ExactSnapshotRestoreProtocolFixture.Response.success());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(
              ExactSnapshotEngineRestore.Failure.class,
              () -> executePositionRestore(engine, position));

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR, thrown.category());
      assertTrue(thrown.getMessage().contains("boardsize 3"));
      assertTrue(thrown.getMessage().contains("unsupported size"));
      assertEquals(List.of("boardsize 3"), transport.commands());
      assertFalse(
          transport.commands().stream()
              .anyMatch(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand),
          "set_position must not be sent until board-size synchronization succeeds.");
      String rawBoardSize = transport.rawCommands().get(0);
      assertTrue(hasNumericCommandId(rawBoardSize));
      assertEquals(19, engine.width);
      assertEquals(19, engine.height);
      assertEquals(7.5f, engine.komi, 0.0001f);
    }
  }

  @Test
  void remoteComputeRectangularBoardSizeIsConfirmedBeforeCacheCommit() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board.boardWidth = 4;
      Board.boardHeight = 3;
      Zobrist.init();
      BoardData position = rectangularSnapshot(4, 3);
      position.komi = 6.5;
      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      engine.width = 19;
      engine.height = 19;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());

      executePositionRestore(engine, position);

      assertEquals("rectangular_boardsize 4 3", transport.commands().get(0));
      assertTrue(hasNumericCommandId(transport.rawCommands().get(0)));
      assertTrue(
          transport.commands().stream()
              .anyMatch(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand));
      assertEquals(4, engine.width);
      assertEquals(3, engine.height);
    }
  }

  @Test
  void remoteComputeKomiTimeoutFailsClosedAndReleasesBoardSyncLease() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardData position = snapshotRoot(true);
      position.komi = 6.5;
      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      engine.komi = 7.5f;
      engine.width = 19;
      engine.height = 19;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("komi ")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      Lizzie.setPrimaryEngine(engine);

      Object owner = new Object();
      Leelaz.LifecycleCompletionClaim claim = engine.tryBeginLifecycleCompletion(owner, null);
      assertNotNull(claim);
      Leelaz.ExactSnapshotRestoreAdmission admission =
          engine.captureBoardSyncExactSnapshotRestoreAdmission();
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          ExactSnapshotEngineRestore.prepareCurrentPosition(admission, position);
      AtomicInteger completionCount = new AtomicInteger();
      claim.completeSuccess(completionCount::incrementAndGet, detail -> {});
      assertEquals(0, completionCount.get());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(ExactSnapshotEngineRestore.Failure.class, preparedRestore::execute);

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.TIMEOUT, thrown.category());
      assertTrue(thrown.getMessage().contains("komi 6.5"));
      assertTrue(
          transport.commands().stream()
              .anyMatch(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand),
          "the successful set_position response must not mask a missing komi response.");
      assertEquals(7.5f, engine.komi, 0.0001f);
      assertEquals(1, completionCount.get(), "timeout must release the board-sync lease.");
      Leelaz.LifecycleCompletionClaim nextClaim =
          engine.tryBeginLifecycleCompletion(new Object(), null);
      assertNotNull(nextClaim, "timeout must release lifecycle-completion admission.");
      nextClaim.completeSuccess(() -> {}, detail -> {});
    }
  }

  @Test
  void remoteComputeBoardSizeKomiAndSetPositionCommitOnlyAfterAllNumberedResponses()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardData position = snapshotRoot(true);
      position.komi = 6.5;
      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      engine.komi = 7.5f;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);
      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  executePositionRestore(engine, position);
                } catch (Throwable failure) {
                  thrownRef.set(failure);
                }
              },
              "remote-komi-numbered-response-barrier");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(transport, 1);
      String rawBoardSize =
          transport.rawCommands().stream()
              .filter(command -> commandPayload(command).startsWith("boardsize "))
              .findFirst()
              .orElseThrow();

      try {
        assertTrue(hasNumericCommandId(rawBoardSize));
        assertEquals(
            List.of("boardsize 3"),
            transport.commands(),
            "komi and set_position must remain gated on board-size success.");
        assertTrue(restoreThread.isAlive(), "restore must wait for board-size confirmation.");
        assertEquals(7.5f, engine.komi, 0.0001f);
        assertEquals(19, engine.width);
        assertEquals(19, engine.height);

        engine.processCommandResponseLineForTest(buildSuccessResponseLine(rawBoardSize));
        waitForCommandCount(transport, 3);
        String rawKomi =
            transport.rawCommands().stream()
                .filter(command -> commandPayload(command).startsWith("komi "))
                .findFirst()
                .orElseThrow();
        String rawSetPosition =
            transport.rawCommands().stream()
                .filter(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand)
                .findFirst()
                .orElseThrow();
        assertTrue(hasNumericCommandId(rawKomi));
        assertTrue(hasNumericCommandId(rawSetPosition));
        assertFalse(
            commandId(rawKomi).equals(commandId(rawSetPosition)),
            "komi and set_position require distinct response bindings.");
        assertFalse(commandId(rawBoardSize).equals(commandId(rawKomi)));
        assertFalse(commandId(rawBoardSize).equals(commandId(rawSetPosition)));
        assertEquals(7.5f, engine.komi, 0.0001f);
        assertEquals(19, engine.width);
        assertEquals(19, engine.height);

        engine.processCommandResponseLineForTest(buildSuccessResponseLine(rawSetPosition));
        assertTrue(
            restoreThread.isAlive(),
            "out-of-order set_position success must still wait for the komi response.");
        assertEquals(
            7.5f,
            engine.komi,
            0.0001f,
            "set_position success alone must not publish the target komi cache.");

        engine.processCommandResponseLineForTest(buildSuccessResponseLine(rawKomi));
        restoreThread.join(2000L);
      } finally {
        if (restoreThread.isAlive()) {
          engine.processCommandResponseLineForTest(buildSuccessResponseLine(rawBoardSize));
          for (String command : transport.rawCommands()) {
            if (commandPayload(command).startsWith("komi ")
                || isSetPositionCommand(command)) {
              engine.processCommandResponseLineForTest(buildSuccessResponseLine(command));
            }
          }
          restoreThread.join(7000L);
        }
      }

      assertFalse(restoreThread.isAlive(), "restore should finish after all responses.");
      assertTrue(thrownRef.get() == null, "successful numbered responses must complete restore.");
      assertEquals(6.5f, engine.komi, 0.0001f);
      assertEquals(3, engine.width);
      assertEquals(3, engine.height);
    }
  }

  @Test
  void localTailMirrorIllegalMoveFailsRestoreAndPreservesBothKomiCaches() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;
      String tailCommand = "play B " + Board.convertCoordinatesToName(2, 2);
      Leelaz primary = new Leelaz("");
      Leelaz mirror = new Leelaz("");
      primary.komi = 7.5f;
      mirror.komi = 7.5f;
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = mirror;
      ExactSnapshotRestoreProtocolFixture.Transport primaryTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              primary, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      ExactSnapshotRestoreProtocolFixture.Transport mirrorTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              mirror,
              command ->
                  command.equals(tailCommand)
                      ? ExactSnapshotRestoreProtocolFixture.Response.error("illegal move")
                      : ExactSnapshotRestoreProtocolFixture.Response.success());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(
              ExactSnapshotEngineRestore.Failure.class,
              () -> executeHistoryRestore(primary, history.getCurrentHistoryNode()));

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR, thrown.category());
      assertTrue(thrown.getMessage().contains(tailCommand));
      assertTrue(thrown.getMessage().contains("illegal move"));
      assertEquals(List.of(tailCommand), collectPlayCommands(primaryTransport.commands()));
      assertEquals(List.of(tailCommand), collectPlayCommands(mirrorTransport.commands()));
      assertTrue(
          primaryTransport.rawCommands().stream()
              .filter(command -> commandPayload(command).startsWith("play "))
              .allMatch(ExactSnapshotEngineRestoreContractTest::hasNumericCommandId));
      assertTrue(
          mirrorTransport.rawCommands().stream()
              .filter(command -> commandPayload(command).startsWith("play "))
              .allMatch(ExactSnapshotEngineRestoreContractTest::hasNumericCommandId));
      assertEquals(7.5f, primary.komi, 0.0001f);
      assertEquals(7.5f, mirror.komi, 0.0001f);
      String loadCommand =
          primaryTransport.commands().stream()
              .filter(ExactSnapshotEngineRestoreContractTest::isLoadSgfCommand)
              .findFirst()
              .orElseThrow();
      assertEventuallyDeleted(extractLoadSgfPath(loadCommand));
    }
  }

  @Test
  void localTailTimeoutFailsClosedAndReleasesBoardSyncLease() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;
      String tailCommand = "play B " + Board.convertCoordinatesToName(2, 2);
      Leelaz engine = new Leelaz("");
      engine.komi = 7.5f;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.equals(tailCommand)
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      Lizzie.setPrimaryEngine(engine);

      Object owner = new Object();
      Leelaz.LifecycleCompletionClaim claim = engine.tryBeginLifecycleCompletion(owner, null);
      assertNotNull(claim);
      Leelaz.ExactSnapshotRestoreAdmission admission =
          engine.captureBoardSyncExactSnapshotRestoreAdmission();
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          ExactSnapshotEngineRestore.prepare(admission, history.getCurrentHistoryNode())
              .orElseThrow();
      AtomicInteger completionCount = new AtomicInteger();
      claim.completeSuccess(completionCount::incrementAndGet, detail -> {});
      assertEquals(0, completionCount.get());

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(ExactSnapshotEngineRestore.Failure.class, preparedRestore::execute);

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.TIMEOUT, thrown.category());
      assertTrue(thrown.getMessage().contains(tailCommand));
      assertEquals(List.of(tailCommand), collectPlayCommands(transport.commands()));
      assertEquals(7.5f, engine.komi, 0.0001f);
      assertEquals(1, completionCount.get(), "tail timeout must release the board-sync lease.");
      Leelaz.LifecycleCompletionClaim nextClaim =
          engine.tryBeginLifecycleCompletion(new Object(), null);
      assertNotNull(nextClaim, "tail timeout must release lifecycle-completion admission.");
      nextClaim.completeSuccess(() -> {}, detail -> {});
      String loadCommand =
          transport.commands().stream()
              .filter(ExactSnapshotEngineRestoreContractTest::isLoadSgfCommand)
              .findFirst()
              .orElseThrow();
      assertEventuallyDeleted(extractLoadSgfPath(loadCommand));
    }
  }

  @Test
  void localTailSuccessPublishesKomiOnlyAfterItsNumberedResponse() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;
      String tailCommand = "play B " + Board.convertCoordinatesToName(2, 2);
      Leelaz engine = new Leelaz("");
      engine.komi = 7.5f;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.equals(tailCommand)
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  executeHistoryRestore(engine, history.getCurrentHistoryNode());
                } catch (Throwable failure) {
                  thrownRef.set(failure);
                }
              },
              "local-tail-numbered-response-barrier");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(transport, 2);
      String rawTail =
          transport.rawCommands().stream()
              .filter(command -> commandPayload(command).equals(tailCommand))
              .findFirst()
              .orElseThrow();
      try {
        assertTrue(hasNumericCommandId(rawTail));
        assertTrue(restoreThread.isAlive(), "local restore must wait for the tail response.");
        assertEquals(7.5f, engine.komi, 0.0001f);
        engine.processCommandResponseLineForTest(buildSuccessResponseLine(rawTail));
        restoreThread.join(2000L);
      } finally {
        if (restoreThread.isAlive()) {
          engine.processCommandResponseLineForTest(buildSuccessResponseLine(rawTail));
          restoreThread.join(7000L);
        }
      }

      assertFalse(restoreThread.isAlive(), "local restore should finish after tail success.");
      assertTrue(thrownRef.get() == null, "successful tail response must complete restore.");
      assertEquals(6.5f, engine.komi, 0.0001f);
      String loadCommand =
          transport.commands().stream()
              .filter(ExactSnapshotEngineRestoreContractTest::isLoadSgfCommand)
              .findFirst()
              .orElseThrow();
      assertEventuallyDeleted(extractLoadSgfPath(loadCommand));
    }
  }

  @Test
  void emptyRootHistoryTargetDoesNotEnterExactRestore() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Leelaz engine = new Leelaz("");
      RecordingOutputStream output = new RecordingOutputStream(null);
      setOutputStream(engine, output);

      assertTrue(
          prepareHistoryRestore(engine, history.getCurrentHistoryNode()).isEmpty(),
          "an empty root is a history origin, not an exact snapshot anchor");
      assertTrue(
          output.commands().isEmpty(), "a root replay caller must receive no exact commands");
    }
  }

  @Test
  void currentHistoryRestoreReplaysRealTailToPreserveRemoteWhiteToPlay() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(2, 2, Stone.BLACK, false, 1));
      Lizzie.board.setHistory(history);

      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      Leelaz.ExactSnapshotRestoreAdmission admission =
          engine.captureExactSnapshotRestoreAdmission(
              Leelaz.ExactSnapshotRestoreOwner.ORDINARY,
              null,
              engine.resolveLoadSgfMirrorEngine());

      ExactSnapshotEngineRestore.prepareCurrentHistoryPosition(
              admission, history.getCurrentHistoryNode())
          .execute();

      assertTrue(
          transport.commands().stream()
              .anyMatch(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand));
      assertEquals(
          List.of("play B " + Board.convertCoordinatesToName(2, 2)),
          collectPlayCommands(transport.commands()),
          "remote restore must use the real move tail to establish white-to-play");
    }
  }

  @Test
  void historyTargetCaptureUsesCurrentGameKomi() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.komi = 7.5f;
      CommandMutationOutputStream output = new CommandMutationOutputStream(engine, null, null);
      setOutputStream(engine, output);

      prepareHistoryRestore(engine, history.getCurrentHistoryNode()).orElseThrow().execute();

      assertTrue(
          output.loadedSgf().contains("KM[6.5]"),
          "history-target capture must use the current game's komi instead of engine cache");
    }
  }

  @Test
  void currentPositionCaptureUsesSnapshotKomiInsteadOfLiveGameKomi() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;

      BoardData position = moveNode(2, 2, Stone.BLACK, true, 4);
      position.komi = 8.5;
      Leelaz engine = new Leelaz("");
      engine.komi = 7.5f;
      CommandMutationOutputStream output = new CommandMutationOutputStream(engine, null, null);
      setOutputStream(engine, output);

      prepareCurrentPositionRestore(engine, position).execute();

      assertTrue(
          output.loadedSgf().contains("KM[8.5]"),
          "current-position capture must use supplied snapshot data instead of live history");
    }
  }

  @Test
  void currentPositionRestoreMaterializesAndFreezesPlanBeforePreclear() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz mirror = new Leelaz("");
      Leelaz replacementMirror = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = mirror;

      CommandMutationOutputStream primaryOutput =
          new CommandMutationOutputStream(primary, null, null);
      CommandMutationOutputStream mirrorOutput =
          new CommandMutationOutputStream(mirror, null, null);
      RecordingOutputStream replacementOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(mirror, mirrorOutput);
      setOutputStream(replacementMirror, replacementOutput);

      BoardData position = moveNode(2, 2, Stone.BLACK, true, 4);
      position.komi = 8.5;
      Leelaz.ExactSnapshotRestoreAdmission admission =
          primary.captureBoardSyncExactSnapshotRestoreAdmission();
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          ExactSnapshotEngineRestore.prepareCurrentPosition(admission, position);

      position.stones[Board.getIndex(0, 0)] = Stone.EMPTY;
      position.komi = 1.5;
      Board.boardWidth = 5;
      Board.boardHeight = 5;
      Lizzie.leelaz2 = replacementMirror;

      preparedRestore.execute();

      assertEquals("clear_board", commandPayload(primaryOutput.commands().get(0)));
      assertEquals("clear_board", commandPayload(mirrorOutput.commands().get(0)));
      assertTrue(primaryOutput.loadedSgf().contains("SZ[3]"));
      assertTrue(primaryOutput.loadedSgf().contains("AB[aa]"));
      assertTrue(primaryOutput.loadedSgf().contains("AB[cc]"));
      assertTrue(primaryOutput.loadedSgf().contains("KM[8.5]"));
      assertEquals(1, primaryOutput.loadSgfCommandCount());
      assertEquals(1, mirrorOutput.loadSgfCommandCount());
      assertEquals(0, replacementOutput.loadSgfCommandCount());
    }
  }

  @Test
  void invalidCurrentPositionFailsBeforeAnyRestoreSideEffect() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Leelaz engine = new Leelaz("");
      RecordingOutputStream output = new RecordingOutputStream(null);
      setOutputStream(engine, output);
      BoardData invalidPosition =
          BoardData.snapshot(
              null, java.util.Optional.empty(), Stone.EMPTY, true, null, 0, null, 0, 0, 50, 0);

      assertThrows(
          IllegalArgumentException.class,
          () -> prepareCurrentPositionRestore(engine, invalidPosition));
      assertTrue(output.commands().isEmpty());
    }
  }

  @Test
  void preclearFailureInvalidatesAnalysisForEveryAttemptedTarget() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Lizzie.board.setHistory(new BoardHistoryList(snapshotRoot()));
      Leelaz primary = new Leelaz("");
      Leelaz mirror = new Leelaz("");
      primary.isKatago = true;
      mirror.isKatago = true;
      primary.setBestMovesForEngineGameTest(List.of(new MoveData()));
      mirror.setBestMovesForEngineGameTest(List.of(new MoveData()));
      primary.scoreMean = 12.0;
      mirror.scoreMean = 34.0;
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = mirror;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream mirrorOutput = new RecordingOutputStream("clear_board");
      setOutputStream(primary, primaryOutput);
      setOutputStream(mirror, mirrorOutput);

      Leelaz.ExactSnapshotRestoreAdmission admission =
          primary.captureBoardSyncExactSnapshotRestoreAdmission();
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          ExactSnapshotEngineRestore.prepareCurrentPosition(admission, snapshotRoot());

      assertThrows(RuntimeException.class, preparedRestore::execute);

      assertEquals(
          List.of("clear_board"),
          primaryOutput.commands().stream()
              .map(ExactSnapshotEngineRestoreContractTest::commandPayload)
              .toList());
      assertEquals(
          List.of("clear_board"),
          mirrorOutput.commands().stream()
              .map(ExactSnapshotEngineRestoreContractTest::commandPayload)
              .toList());
      assertTrue(primary.getBestMoves().isEmpty());
      assertEquals(0.0, primary.scoreMean);
      assertTrue(
          mirror.getBestMoves().isEmpty(),
          "state invalidation precedes the first command byte and therefore survives flush"
              + " failure");
      assertEquals(0.0, mirror.scoreMean);
      assertEquals(0, primaryOutput.loadSgfCommandCount());
      assertEquals(0, mirrorOutput.loadSgfCommandCount());
    }
  }

  @Test
  void preparedRestoreCanExecuteOnlyOnce() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      Leelaz engine = new Leelaz("");
      ScriptedResponseOutputStream output =
          new ScriptedResponseOutputStream(engine, null, null, AUTO_ID_RESPONSE);
      setOutputStream(engine, output);
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          prepareHistoryRestore(engine, history.getCurrentHistoryNode()).orElseThrow();

      preparedRestore.execute();
      int commandCount = output.commands().size();

        assertThrows(IllegalStateException.class, preparedRestore::execute);
      assertEquals(
          commandCount,
          output.commands().size(),
          "a repeated execute must fail before issuing another command");
        }
      }

  @Test
  void preparedBoardRestoreDoesNotReadLiveMoveListAfterPlanCapture() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      ThrowingMoveListBoard board = allocate(ThrowingMoveListBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      setOutputStream(
          engine, new ScriptedResponseOutputStream(engine, null, null, AUTO_ID_RESPONSE));
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          prepareHistoryRestore(engine, history.getCurrentHistoryNode()).orElseThrow();

      board.resendMoveToEngine(engine, false, preparedRestore);
      }
  }

  @Test
  void preparedRestoreSynchronizesTargetKomiCacheAfterSuccess() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;
      Leelaz engine = new Leelaz("");
      engine.komi = 7.5f;
      ScriptedResponseOutputStream output =
          new ScriptedResponseOutputStream(engine, null, null, AUTO_ID_RESPONSE);
      setOutputStream(engine, output);

      prepareHistoryRestore(engine, history.getCurrentHistoryNode()).orElseThrow().execute();

      assertEquals(6.5f, engine.komi, 0.0001f);
    }
  }

  @Test
  void exactSnapshotRestoreFreezesTailAndMirrorBeforeLoadResponse() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Leelaz replacementSecondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream replacementOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);
      setOutputStream(replacementSecondary, replacementOutput);

      AtomicReference<ExactSnapshotEngineRestore.Completion> completionRef =
          new AtomicReference<>();
      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  completionRef.set(
                      executeHistoryRestore(primary, history.getCurrentHistoryNode()));
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "immutable-snapshot-restore-plan");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(primaryOutput, 1);
      waitForCommandCount(secondaryOutput, 1);

      history.getData().lastMove = java.util.Optional.of(new int[] {0, 2});
      Lizzie.leelaz2 = replacementSecondary;

      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(0)));

      waitForCommandCount(primaryOutput, 2);
      waitForCommandCount(secondaryOutput, 2);
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(1)));
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(1)));

      restoreThread.join(2000L);
      assertFalse(restoreThread.isAlive(), "restore should finish after captured targets respond.");
      assertTrue(thrownRef.get() == null, "captured restore plan should complete successfully.");
      assertNotNull(completionRef.get());

      String capturedMove = "play B " + Board.convertCoordinatesToName(2, 2);
      assertEquals(List.of(capturedMove), collectPlayCommands(primaryOutput.commands()));
      assertEquals(
          List.of(capturedMove),
          collectPlayCommands(secondaryOutput.commands()),
          "the originally captured mirror should receive the frozen tail.");
      assertEquals(
          0,
          replacementOutput.commands().size(),
          "callbacks must not re-read the replacement global mirror.");
    }
  }

  @Test
  void preparedRestoreDoesNotClearAnExecutionTimeGlobalMirrorThatWasNotCaptured() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;
      Leelaz target = new Leelaz("");
      Leelaz executionTimeMirror = new Leelaz("");
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          prepareHistoryRestore(target, history.getCurrentHistoryNode()).orElseThrow();
      ExactSnapshotRestoreProtocolFixture.Transport targetTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              target, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      ExactSnapshotRestoreProtocolFixture.Transport mirrorTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              executionTimeMirror,
              command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      Lizzie.leelaz = target;
      Lizzie.leelaz2 = executionTimeMirror;

      board.resendMoveToEngine(target, false, preparedRestore);

      assertTrue(
          targetTransport.commands().stream().anyMatch(command -> command.startsWith("loadsgf ")));
      assertTrue(
          mirrorTransport.commands().isEmpty(),
          "prepared restore precommands must not re-resolve an execution-time mirror");
    }
  }

  @Test
  void productionResyncFreezesHistoryPlanBeforeOwnerPrecommands() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.getGameInfo().setKomiNoMenu(6.5);
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz mirror = new Leelaz("");
      Leelaz replacementMirror = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = mirror;

      CommandMutationOutputStream primaryOutput =
          new CommandMutationOutputStream(
              primary,
              "name",
              () -> {
                history.getStart().getData().stones[Board.getIndex(0, 0)] = Stone.EMPTY;
                history.getData().lastMove = java.util.Optional.of(new int[] {0, 2});
                Lizzie.leelaz2 = replacementMirror;
              });
      CommandMutationOutputStream mirrorOutput =
          new CommandMutationOutputStream(mirror, null, null);
      RecordingOutputStream replacementOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(mirror, mirrorOutput);
      setOutputStream(replacementMirror, replacementOutput);

      new LeelazEngineCommandSink().resyncFromCurrentHistory(history.getCurrentHistoryNode());

      assertEquals(1, primaryOutput.matchingCommandCount());
      assertTrue(primaryOutput.loadedSgf().contains("AB[aa]"));
      assertTrue(primaryOutput.loadedSgf().contains("KM[6.5]"));
      String capturedMove = "play B " + Board.convertCoordinatesToName(2, 2);
      assertEquals(List.of(capturedMove), collectPlayCommands(primaryOutput.commands()));
      assertEquals(List.of(capturedMove), collectPlayCommands(mirrorOutput.commands()));
      assertTrue(
          replacementOutput.commands().isEmpty(),
          "replacement mirror must receive no commands: " + replacementOutput.commands());
    }
  }

  @Test
  void productionResyncRestoresPonderOnCapturedPrimary() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      ProductionResyncPonderLeelaz original = new ProductionResyncPonderLeelaz();
      ProductionResyncPonderLeelaz replacement = new ProductionResyncPonderLeelaz();
      original.replacePrimaryOnNotPondering(replacement);
      Lizzie.leelaz = original;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());

      new LeelazEngineCommandSink().resyncFromCurrentHistory(history.getCurrentHistoryNode());

      assertEquals(1, original.ponderCalls);
      assertTrue(original.commands().stream().anyMatch(command -> command.equals("name")));
      assertTrue(
          original.commands().stream()
              .anyMatch(ExactSnapshotEngineRestoreContractTest::isLoadSgfCommand));
      assertEquals(0, replacement.ponderCalls);
      assertTrue(replacement.commands().isEmpty());
    }
  }

  @Test
  void exactSnapshotRestoreFailsWhenEngineArbitrationRejectsTailCommand() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));

      Leelaz engine = new Leelaz("");
      TailRejectingOutputStream output = new TailRejectingOutputStream(engine);
      setOutputStream(engine, output);

      ExactSnapshotEngineRestore.Failure thrown =
          assertThrows(
              ExactSnapshotEngineRestore.Failure.class,
              () -> executeHistoryRestore(engine, history.getCurrentHistoryNode()));

      assertEquals(ExactSnapshotEngineRestore.FailureCategory.ADMISSION_STALE, thrown.category());
      assertEquals(1, output.commands().size());
      assertTrue(isLoadSgfCommand(output.commands().get(0)));
      assertTrue(
          collectPlayCommands(output.commands()).isEmpty(),
          "a rejected tail command must not be reported as a completed exact restore.");
    }
  }
  @Test
  void discardedPreparedRestoreReleasesLifecycleCompletionLeaseWithoutSendingCommands()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Leelaz engine = new Leelaz("");
      RecordingOutputStream output = new RecordingOutputStream(null);
      setOutputStream(engine, output);
      Lizzie.setPrimaryEngine(engine);
      Object owner = new Object();
      Leelaz.LifecycleCompletionClaim claim = engine.tryBeginLifecycleCompletion(owner, null);
      assertNotNull(claim);
      Leelaz.ExactSnapshotRestoreAdmission admission =
          engine.captureBoardSyncExactSnapshotRestoreAdmission();
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          ExactSnapshotEngineRestore.prepareCurrentPosition(admission, snapshotRoot());
      AtomicInteger completionCount = new AtomicInteger();
      claim.completeSuccess(completionCount::incrementAndGet, detail -> {});
      assertEquals(0, completionCount.get(), "the live board-sync lease must defer completion.");

      preparedRestore.discard();

      assertEquals(1, completionCount.get(), "discard must release the live completion lease.");
      assertTrue(output.commands().isEmpty());
      IllegalStateException repeatedDiscard =
          assertThrows(IllegalStateException.class, preparedRestore::discard);
      assertEquals(
          "Exact snapshot restore has already been executed", repeatedDiscard.getMessage());
      IllegalStateException executeAfterDiscard =
          assertThrows(IllegalStateException.class, preparedRestore::execute);
      assertEquals(
          "Exact snapshot restore has already been executed", executeAfterDiscard.getMessage());
      assertNotNull(engine.tryBeginLifecycleCompletion(new Object(), null));
    }
  }


  @Test
  void exactSnapshotRestoreDeletesSgfWhenAdmissionRejectsBeforeLoadDispatch() throws Exception {
    String previousTempDirectory = System.getProperty("java.io.tmpdir");
    Path tempDirectory =
        Path.of(previousTempDirectory, "exact-restore-admission-cleanup-" + System.nanoTime());
    Files.createDirectory(tempDirectory);
    try (TestHarness harness = TestHarness.open(false)) {
      System.setProperty("java.io.tmpdir", tempDirectory.toString());
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Leelaz engine = new Leelaz("");
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          prepareHistoryRestore(engine, history.getCurrentHistoryNode()).orElseThrow();
      Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
      assertNotNull(reservation);
      try {
        assertThrows(IllegalStateException.class, preparedRestore::execute);
      } finally {
        reservation.close();
      }
      try (var files = Files.list(tempDirectory)) {
        assertTrue(
            files.findAny().isEmpty(),
            "an admission rejection before loadsgf dispatch must delete the temporary SGF");
      }
    } finally {
      if (previousTempDirectory == null) {
        System.clearProperty("java.io.tmpdir");
      } else {
        System.setProperty("java.io.tmpdir", previousTempDirectory);
      }
      Files.deleteIfExists(tempDirectory);
    }
  }

  @Test
  void exactSnapshotRestoreDeletesSgfWhenSnapshotSerializationFails() throws Exception {
    Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
    List<Path> existingSnapshotFiles = snapshotSgfFiles(tempDirectory);
    try (TestHarness harness = TestHarness.open(false)) {
      BoardData malformedSnapshot =
          BoardData.snapshot(
              null,
              java.util.Optional.empty(),
              Stone.EMPTY,
              false,
              new Zobrist(),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      malformedSnapshot.addProperty("SZ", String.valueOf(BOARD_SIZE));
      BoardHistoryList history = new BoardHistoryList(malformedSnapshot);
      Leelaz engine = new Leelaz("");
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          prepareHistoryRestore(engine, history.getCurrentHistoryNode()).orElseThrow();

      assertThrows(RuntimeException.class, preparedRestore::execute);
      assertEquals(
          existingSnapshotFiles,
          snapshotSgfFiles(tempDirectory),
          "snapshot serialization failure must delete the partially-created temporary SGF");
    }
  }

  @Test
  void mirrorLoadSgfEnqueueRejectionFailsImmediatelyAfterPrimaryWasAccepted() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz mirror = new Leelaz("");
      AtomicReference<Leelaz.EngineModeReservation> mirrorReservation = new AtomicReference<>();
      CommandMutationOutputStream primaryOutput =
          new CommandMutationOutputStream(
              primary,
              "loadsgf ",
              () -> {
                Thread reservationThread =
                    new Thread(
                        () -> mirrorReservation.set(mirror.beginEngineModeReservation()),
                        "mirror-loadsgf-conflict-owner");
                reservationThread.start();
                try {
                  reservationThread.join();
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(ex);
                }
              });
      RecordingOutputStream mirrorOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(mirror, mirrorOutput);
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = mirror;

      long startedAt = System.nanoTime();
      try {
        IllegalStateException thrown =
            assertThrows(
                IllegalStateException.class, () -> board.resendMoveToEngine(primary, false));
        long elapsedMillis =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(thrown.getMessage().contains("rejected"), thrown.getMessage());
        assertTrue(
            elapsedMillis < 2000L,
            "mirror admission rejection must not wait for the no-response timeout");
        assertNotNull(mirrorReservation.get());
        assertEquals(1, primaryOutput.loadSgfCommandCount());
        assertEquals(0, mirrorOutput.loadSgfCommandCount());
        assertTrue(collectPlayCommands(primaryOutput.commands()).isEmpty());
        assertTrue(collectPlayCommands(mirrorOutput.commands()).isEmpty());

        assertEventuallyDeleted(
            extractLoadSgfPath(
                primaryOutput.commands().stream()
                    .filter(ExactSnapshotEngineRestoreContractTest::isLoadSgfCommand)
                    .findFirst()
                    .orElseThrow()));
      } finally {
        if (mirrorReservation.get() != null) {
          mirrorReservation.get().close();
        }
      }
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenLoadsgfFlushFailsAndStopsRealReplay() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      RecordingOutputStream output = new RecordingOutputStream("loadsgf ");
      setOutputStream(engine, output);

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, () -> board.resendMoveToEngine(engine, false));

      assertTrue(
          thrown.getMessage().contains("loadsgf"),
          "loadsgf send failures should be exposed as restore failures.");
      assertEquals("clear_board", commandPayload(output.commands().get(0)));
      assertTrue(isLoadSgfCommand(output.commands().get(1)));
      assertEquals(2, output.commands().size(), "restore should stop before replaying real moves.");
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenLoadsgfReturnsErrorResponseAndStopsRealReplay()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      ScriptedResponseOutputStream output =
          new ScriptedResponseOutputStream(engine, null, "=", "? cannot loadsgf");
      setOutputStream(engine, output);

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, () -> board.resendMoveToEngine(engine, false));

      assertTrue(
          thrown.getMessage().contains("cannot loadsgf"),
          "loadsgf GTP error responses should be exposed as restore failures.");
      assertEquals("clear_board", commandPayload(output.commands().get(0)));
      assertTrue(isLoadSgfCommand(output.commands().get(1)));
      assertEquals(2, output.commands().size(), "restore should stop before replaying real moves.");
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenQueuedLoadsgfFlushFailsAndCleansTempSgf() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream output = new RecordingOutputStream("loadsgf ");
      setOutputStream(engine, output);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  board.resendMoveToEngine(engine, false);
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "queued-loadsgf-failure");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(output, 1);

      assertEquals("clear_board", commandPayload(output.commands().get(0)));

      triggerQueuedSend(engine, output.commands().get(0));

      restoreThread.join(2000L);
      assertFalse(restoreThread.isAlive(), "queued loadsgf send failure should not hang restore.");

      Throwable thrown = thrownRef.get();
      assertTrue(
          thrown instanceof IllegalStateException,
          "queued loadsgf send failures should surface as restore failures.");
      assertTrue(
          thrown.getMessage().contains("loadsgf"),
          "queued loadsgf send failures should keep loadsgf context.");

      List<String> commands = output.commands();
      assertEquals(2, commands.size(), "restore should stop before replaying real moves.");
      assertEquals("clear_board", commandPayload(commands.get(0)));
      assertTrue(isLoadSgfCommand(commands.get(1)));

      Path tempSgf = extractLoadSgfPath(commands.get(1));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void resendMoveToEngineThrowsWhenQueuedLoadsgfOutputStreamUnavailableAndCleansTempSgf()
      throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream output = new RecordingOutputStream(null);
      setOutputStream(engine, output);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  board.resendMoveToEngine(engine, false);
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "queued-loadsgf-outputstream-unavailable");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(output, 1);

      assertEquals("clear_board", commandPayload(output.commands().get(0)));

      setOutputStream(engine, null);
      triggerQueuedSend(engine, output.commands().get(0));

      restoreThread.join(2000L);
      assertFalse(restoreThread.isAlive(), "queued outputStream failure should not hang restore.");

      Throwable thrown = thrownRef.get();
      assertTrue(
          thrown instanceof IllegalStateException,
          "queued outputStream failures should surface as restore failures.");
      assertTrue(
          thrown.getMessage().contains("outputStream unavailable"),
          "queued send failures should expose outputStream unavailable.");

      List<String> commands = output.commands();
      assertEquals(1, commands.size(), "loadsgf should not replay real moves after send failure.");
      assertEquals("clear_board", commandPayload(commands.get(0)));

      Path tempSgf = extractLoadSgfPathFromFailure(thrown.getMessage());
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreKeepsTempFileUntilPrimaryConsumerFinishesAfterMirrorSendFailure()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, null, null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      secondaryOutput.failOnCommand("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class, () -> executePositionRestore(primary, snapshotRoot()));

      assertTrue(
          thrown.getMessage().contains("loadsgf"),
          "mirror send failures should be exposed as restore failures.");
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(1, secondaryOutput.commands().size());
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));
      assertTrue(isLoadSgfCommand(secondaryOutput.commands().get(0)));

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      assertTrue(
          Files.exists(tempSgf),
          "temporary SGF should survive until the already-dispatched primary consumer finishes.");

      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreMirrorsLoadSgfWhenStartedFromSecondaryEngine() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, "=", AUTO_ID_RESPONSE);
      ScriptedResponseOutputStream secondaryOutput =
          new ScriptedResponseOutputStream(secondary, null, "=", AUTO_ID_RESPONSE);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      executePositionRestore(secondary, snapshotRoot());
      assertEquals(1, secondaryOutput.commands().size());
      assertEquals(1, primaryOutput.commands().size());
      assertTrue(isLoadSgfCommand(secondaryOutput.commands().get(0)));
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));

      Path primaryTempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      Path secondaryTempSgf = extractLoadSgfPath(secondaryOutput.commands().get(0));
      assertEquals(primaryTempSgf, secondaryTempSgf, "mirrored restore should share one temp SGF.");
      assertEventuallyDeleted(primaryTempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreFromThirdEngineDoesNotMirrorToPrimaryOrSecondary() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Leelaz third = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      ScriptedResponseOutputStream thirdOutput =
          new ScriptedResponseOutputStream(third, null, null, AUTO_ID_RESPONSE);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);
      setOutputStream(third, thirdOutput);

      executePositionRestore(third, snapshotRoot());

      assertEquals(1, thirdOutput.commands().size(), "third engine should send one loadsgf.");
      assertTrue(
          isLoadSgfCommand(thirdOutput.commands().get(0)), "third engine should send loadsgf.");
      assertEquals(
          0, primaryOutput.commands().size(), "third engine restore should not mirror to primary.");
      assertEquals(
          0,
          secondaryOutput.commands().size(),
          "third engine restore should not mirror to secondary.");

      Path tempSgf = extractLoadSgfPath(thirdOutput.commands().get(0));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void thirdEngineResendReplaysTrailingRealActionsOnlyToItself() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Leelaz third = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      ScriptedResponseOutputStream thirdOutput =
          new ScriptedResponseOutputStream(third, null, "=", AUTO_ID_RESPONSE);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);
      setOutputStream(third, thirdOutput);

      board.resendMoveToEngine(third, false);

      List<String> thirdCommands = thirdOutput.commands();
      assertEquals("clear_board", commandPayload(thirdCommands.get(0)));
      assertTrue(isLoadSgfCommand(thirdCommands.get(1)));

      List<String> expectedReplay =
          List.of("play B " + Board.convertCoordinatesToName(2, 2), "play W pass");
      assertEquals(expectedReplay, collectPlayCommands(thirdCommands));
      assertEquals(
          0,
          collectPlayCommands(primaryOutput.commands()).size(),
          "third engine trailing replay should not mirror plays to primary.");
      assertEquals(
          0,
          collectPlayCommands(secondaryOutput.commands()).size(),
          "third engine trailing replay should not mirror plays to secondary.");

      Path tempSgf = extractLoadSgfPath(thirdCommands.get(1));
      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void secondaryEntryResendMirrorsTrailingRealActionsAfterSnapshotRestore() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  board.resendMoveToEngine(secondary, false);
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "secondary-resend-mirror");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(secondaryOutput, 2);
      waitForCommandCount(primaryOutput, 2);
      assertEquals("clear_board", commandPayload(secondaryOutput.commands().get(0)));
      assertEquals("clear_board", commandPayload(primaryOutput.commands().get(0)));

      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(0)));
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(1)));
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(1)));

      waitForCommandCount(secondaryOutput, 3);
      waitForCommandCount(primaryOutput, 3);
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(2)));
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(2)));

      waitForCommandCount(secondaryOutput, 4);
      waitForCommandCount(primaryOutput, 4);
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(3)));
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(3)));

      waitForCommandCount(secondaryOutput, 5);
      waitForCommandCount(primaryOutput, 5);
      assertEquals("name", commandPayload(secondaryOutput.commands().get(4)));
      assertEquals("name", commandPayload(primaryOutput.commands().get(4)));
      invokeResponseHandlerForLine(
          secondary, buildSuccessResponseLine(secondaryOutput.commands().get(4)));
      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(4)));

      restoreThread.join(2000L);
      assertFalse(
          restoreThread.isAlive(), "secondary restore entry should finish after responses.");
      assertTrue(thrownRef.get() == null, "secondary restore entry should not fail.");

      List<String> expectedReplay =
          List.of("play B " + Board.convertCoordinatesToName(2, 2), "play W pass");
      assertEquals(
          expectedReplay,
          collectPlayCommands(secondaryOutput.commands()),
          "secondary restore entry should replay trailing real actions in order.");
      assertEquals(
          expectedReplay,
          collectPlayCommands(primaryOutput.commands()),
          "secondary restore entry should mirror trailing real actions to primary engine.");
    }
  }

  @Test
  void secondaryEntryResendKeepsTempSgfAliveUntilTrailingReplayCommandsAreSent() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot());
      history.add(moveNode(2, 2, Stone.BLACK, true, 4));
      Stone[] passStones = history.getData().stones.clone();
      history.add(
          BoardData.pass(
              passStones,
              Stone.WHITE,
              false,
              zobrist(passStones),
              5,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      TailReplayAwareOutputStream primaryOutput = new TailReplayAwareOutputStream(primary);
      TailReplayAwareOutputStream secondaryOutput = new TailReplayAwareOutputStream(secondary);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      board.resendMoveToEngine(secondary, false);

      assertTrue(
          secondaryOutput.tempFileExistedDuringReplay(),
          "secondary replay should see temporary SGF while trailing real moves are being sent.");
      assertTrue(
          primaryOutput.tempFileExistedDuringReplay(),
          "mirrored primary replay should see temporary SGF while trailing real moves are being"
              + " sent.");
      assertEventuallyDeleted(secondaryOutput.loadSgfPath());
    }
  }

  @Test
  void exactSnapshotRestoreFallbackCleansPrimaryHandlerWhenMirrorFailsAndPrimaryNeverResponds()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class, () -> executePositionRestore(primary, snapshotRoot()));
      assertTrue(thrown.getMessage().contains("loadsgf"));
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(1, secondaryOutput.commands().size());

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      assertTrue(Files.exists(tempSgf), "temporary SGF should exist before fallback cleanup.");

      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreFailsAndCleansWhenPrimaryReturnsErrorAndMirrorStaysSilent()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  executePositionRestore(primary, snapshotRoot());
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "error-response-with-silent-mirror");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(primaryOutput, 1);
      waitForCommandCount(secondaryOutput, 1);
      invokeResponseHandlerForLine(
          primary, buildResponseLine(primaryOutput.commands().get(0), "? cannot loadsgf"));

      restoreThread.join(7000L);
      assertFalse(restoreThread.isAlive(), "? + silent mirror should still return a failure.");

      Throwable thrown = thrownRef.get();
      assertTrue(thrown instanceof IllegalStateException, "restore should fail on ? responses.");
      assertTrue(
          thrown.getMessage().contains("cannot loadsgf"),
          "restore failure should preserve the GTP error detail.");
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(1, secondaryOutput.commands().size());

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));

      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreStillDispatchesMirrorWhenPrimaryReturnsImmediateError()
      throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, null, "? cannot loadsgf");
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class, () -> executePositionRestore(primary, snapshotRoot()));

      assertTrue(
          thrown.getMessage().contains("cannot loadsgf"),
          "immediate ? failures should preserve the GTP error detail.");
      assertEquals(1, primaryOutput.commands().size());
      assertEquals(
          1,
          secondaryOutput.commands().size(),
          "mirror loadsgf should still dispatch when primary fails immediately.");
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));
      assertTrue(isLoadSgfCommand(secondaryOutput.commands().get(0)));

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));

      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void exactSnapshotRestoreFailsAndCleansWhenAllDispatchedEnginesStaySilent() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream(null);
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      AtomicReference<Throwable> thrownRef = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  executePositionRestore(primary, snapshotRoot());
                } catch (Throwable ex) {
                  thrownRef.set(ex);
                }
              },
              "silent-success-all-engines");
      restoreThread.setDaemon(true);
      restoreThread.start();

      waitForCommandCount(primaryOutput, 1);
      waitForCommandCount(secondaryOutput, 1);
      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));

      restoreThread.join(7000L);
      assertFalse(
          restoreThread.isAlive(), "silent-success dispatch should fail instead of hanging.");

      Throwable thrown = thrownRef.get();
      assertTrue(thrown instanceof IllegalStateException, "silent-success should surface failure.");
      assertTrue(thrown.getMessage().contains("loadsgf"), "failure should keep loadsgf context.");

      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void
      exactSnapshotRestoreKeepsTempFileForSlowPrimaryConsumerBeyondCurrentGraceAfterMirrorSendFailure()
          throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;

      ScriptedResponseOutputStream primaryOutput =
          new ScriptedResponseOutputStream(primary, null, null, null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class, () -> executePositionRestore(primary, snapshotRoot()));
      assertTrue(
          thrown.getMessage().contains("loadsgf"), "mirror send failures should still be exposed.");
      assertEquals(1, primaryOutput.commands().size());
      assertTrue(isLoadSgfCommand(primaryOutput.commands().get(0)));

      Path tempSgf = extractLoadSgfPath(primaryOutput.commands().get(0));
      Thread.sleep(4300L);
      assertTrue(
          Files.exists(tempSgf),
          "slow primary consumers beyond current grace should keep temp SGF until real"
              + " consumption.");

      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(0)));

      assertEventuallyDeleted(tempSgf);
    }
  }

  @Test
  void lateLoadSgfResponseAfterFailureCleanupDoesNotConsumeNextCommandHandler() throws Exception {
    try (TestHarness harness = TestHarness.open(true)) {
      Leelaz primary = new Leelaz("");
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      RecordingOutputStream primaryOutput = new RecordingOutputStream(null);
      RecordingOutputStream secondaryOutput = new RecordingOutputStream("loadsgf ");
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);

      assertThrows(
          IllegalStateException.class, () -> executePositionRestore(primary, snapshotRoot()));

      String loadSgfCommand = primaryOutput.commands().get(0);
      String lateLoadSgfResponse = buildSuccessResponseLine(loadSgfCommand);

      AtomicInteger callbackCount = new AtomicInteger(0);
      sendCommandWithResponse(primary, "name", callbackCount::incrementAndGet);

      invokeResponseHandlerForLine(primary, "? late loadsgf response");
      assertEquals(
          0, callbackCount.get(), "late loadsgf response should not consume next command handler.");

      invokeResponseHandlerForLine(primary, lateLoadSgfResponse);
      assertEquals(
          0,
          callbackCount.get(),
          "late numbered loadsgf response should not consume next command handler.");

      invokeResponseHandlerForLine(
          primary, buildSuccessResponseLine(primaryOutput.commands().get(1)));
      assertEquals(1, callbackCount.get(), "next command handler should run on its own response.");
    }
  }

  private static ExactSnapshotEngineRestore.Completion executeHistoryRestore(
      Leelaz engine, BoardHistoryNode target) {
    return prepareHistoryRestore(engine, target).orElseThrow().execute();
  }

  private static java.util.Optional<ExactSnapshotEngineRestore.PreparedRestore>
      prepareHistoryRestore(Leelaz engine, BoardHistoryNode target) {
    Leelaz.ExactSnapshotRestoreAdmission admission =
        engine.captureExactSnapshotRestoreAdmission(
            Leelaz.ExactSnapshotRestoreOwner.ORDINARY, null, engine.resolveLoadSgfMirrorEngine());
    return ExactSnapshotEngineRestore.prepare(admission, target);
  }

  private static ExactSnapshotEngineRestore.PreparedRestore prepareCurrentPositionRestore(
      Leelaz engine, BoardData positionData) {
    Leelaz.ExactSnapshotRestoreAdmission admission =
        engine.captureExactSnapshotRestoreAdmission(
            Leelaz.ExactSnapshotRestoreOwner.ORDINARY, null, engine.resolveLoadSgfMirrorEngine());
    return ExactSnapshotEngineRestore.prepareCurrentPosition(admission, positionData);
  }

  private static ExactSnapshotEngineRestore.Completion executePositionRestore(
      Leelaz engine, BoardData positionData) {
    return prepareCurrentPositionRestore(engine, positionData).execute();
  }

  private static void assertRemoteEmptyTailBlackToPlayRestore() throws Exception {
    try (TestHarness harness = TestHarness.open(false)) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(snapshotRoot(true));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.useRemoteCompute = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());

      board.resendMoveToEngine(engine, false);

      List<String> commands = transport.commands();
      assertTrue(
          commands.stream().noneMatch(ExactSnapshotEngineRestoreContractTest::isHostLoadSgfCommand),
          "remote snapshot-anchor restore must not emit a host-only loadsgf path: " + commands);
      assertTrue(
          commands.stream()
              .noneMatch(ExactSnapshotEngineRestoreContractTest::isGoguiSetupPlayerCommand),
          "remote restore must not emit gogui-setup_player: " + commands);
      assertEquals(
          List.of(),
          collectPlayCommands(commands),
          "B-to-play empty-tail must not invent a play command: " + commands);

      String setPosition =
          commands.stream()
              .filter(ExactSnapshotEngineRestoreContractTest::isSetPositionCommand)
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "remote snapshot restore must represent setup stones in-band: "
                              + commands));
      Set<String> placed = setPositionPlacements(setPosition);
      assertTrue(
          placed.contains("B " + Board.convertCoordinatesToName(0, 0)),
          "black setup stone missing from in-band restore: " + setPosition);
      assertTrue(
          placed.contains("W " + Board.convertCoordinatesToName(1, 0)),
          "white setup stone missing from in-band restore: " + setPosition);
      assertTrue(
          replayRemoteEngineBlackToPlay(commands),
          "empty-tail remote restore must leave engine side-to-play matching snapshot PL: "
              + commands);
    }
  }

  private static boolean replayRemoteEngineBlackToPlay(List<String> commands) {
    boolean engineBlackToPlay = true;
    for (String rawCommand : commands) {
      String command = commandPayload(rawCommand);
      if (command.isEmpty()) {
        continue;
      }
      if (command.equals("clear_board")
          || command.startsWith("boardsize ")
          || command.startsWith("rectangular_boardsize ")) {
        engineBlackToPlay = true;
      } else if (isSetPositionCommand(command)) {
        engineBlackToPlay = true;
      } else if (command.startsWith("play ")) {
        String[] parts = command.split("\\s+");
        if (parts.length >= 2) {
          engineBlackToPlay = "W".equalsIgnoreCase(parts[1]);
        }
      }
    }
    return engineBlackToPlay;
  }

  private static List<Path> snapshotSgfFiles(Path tempDirectory) throws IOException {
    try (var files = Files.list(tempDirectory)) {
      return files
          .filter(path -> path.getFileName().toString().startsWith("lizzie-snapshot-"))
          .sorted()
          .toList();
    }
  }

  private static BoardData snapshotRoot() {
    return snapshotRoot(false);
  }

  private static BoardData snapshotRoot(boolean blackToPlay) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(0, 0)] = 1;
    moveNumberList[Board.getIndex(1, 0)] = 2;
    return BoardData.snapshot(
        stones,
        java.util.Optional.of(new int[] {1, 0}),
        Stone.WHITE,
        blackToPlay,
        zobrist(stones),
        3,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData rectangularSnapshot(int width, int height) {
    Stone[] stones = new Stone[width * height];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            java.util.Optional.empty(),
            Stone.EMPTY,
            true,
            new Zobrist(),
            0,
            new int[stones.length],
            0,
            0,
            50,
            0);
    snapshot.addProperty("SZ", width + ":" + height);
    return snapshot;
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = snapshotRoot().stones.clone();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static void setOutputStream(Leelaz engine, OutputStream stream) {
    engine.installCommandOutputForTest(stream);
  }

  private static void invokeResponseHandlerForLine(Leelaz engine, String line) {
    engine.runPendingResponseHandlerForTest(line);
  }

  private static void triggerQueuedSend(Leelaz engine, String pendingCommand) {
    engine.setResponseUpToDate();
    engine.processCommandResponseLineForTest(buildSuccessResponseLine(pendingCommand));
  }

  private static void waitForCommandCount(RecordingOutputStream output, int expectedCount)
      throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      if (output.commands().size() >= expectedCount) {
        return;
      }
      Thread.sleep(25L);
    }
    assertEquals(expectedCount, output.commands().size(), "expected queued command count.");
  }

  private static void waitForCommandCount(
      ExactSnapshotRestoreProtocolFixture.Transport transport, int expectedCount) throws Exception {
    for (int attempt = 0; attempt < 80; attempt++) {
      if (transport.commands().size() >= expectedCount) {
        return;
      }
      Thread.sleep(25L);
    }
    assertEquals(expectedCount, transport.commands().size(), "expected transport command count.");
  }

  private static boolean isLoadSgfCommand(String command) {
    return command != null && command.contains("loadsgf ");
  }

  private static boolean isHostLoadSgfCommand(String command) {
    if (!isLoadSgfCommand(command)) {
      return false;
    }
    String argument = command.substring(command.indexOf("loadsgf ") + "loadsgf ".length()).trim();
    return argument.contains("/") || argument.contains("\\") || argument.contains(":");
  }

  private static boolean isSetPositionCommand(String command) {
    String payload = commandPayload(command);
    return payload.equals("set_position") || payload.startsWith("set_position ");
  }

  private static boolean isGoguiSetupPlayerCommand(String command) {
    String payload = commandPayload(command);
    return payload.equals("gogui-setup_player") || payload.startsWith("gogui-setup_player ");
  }

  private static Set<String> setPositionPlacements(String command) {
    command = commandPayload(command);
    String payload =
        command.startsWith("set_position") ? command.substring("set_position".length()).trim() : "";
    String[] tokens = payload.isEmpty() ? new String[0] : payload.split("\\s+");
    Set<String> placements = new LinkedHashSet<>();
    for (int index = 0; index + 1 < tokens.length; index += 2) {
      placements.add(tokens[index] + " " + tokens[index + 1]);
    }
    return placements;
  }

  private static List<String> collectPlayCommands(List<String> commands) {
    List<String> replay = new ArrayList<>();
    for (String rawCommand : commands) {
      String command = commandPayload(rawCommand);
      if (command.startsWith("play ")) {
        replay.add(command);
      }
    }
    return replay;
  }

  private static String commandPayload(String commandLine) {
    if (commandLine == null) {
      return "";
    }
    String trimmed = commandLine.trim();
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace <= 0) {
      return trimmed;
    }
    String firstToken = trimmed.substring(0, firstSpace);
    for (int index = 0; index < firstToken.length(); index++) {
      if (!Character.isDigit(firstToken.charAt(index))) {
        return trimmed;
      }
    }
    return trimmed.substring(firstSpace + 1);
  }

  private static boolean hasNumericCommandId(String commandLine) {
    return !commandId(commandLine).isEmpty();
  }

  private static String commandId(String commandLine) {
    if (commandLine == null) {
      return "";
    }
    String trimmed = commandLine.trim();
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace <= 0) {
      return "";
    }
    String firstToken = trimmed.substring(0, firstSpace);
    for (int index = 0; index < firstToken.length(); index++) {
      if (!Character.isDigit(firstToken.charAt(index))) {
        return "";
      }
    }
    return firstToken;
  }

  private static boolean matchesCommandPrefix(String command, String commandPrefix) {
    if (commandPrefix == null || command == null) {
      return false;
    }
    return command.startsWith(commandPrefix) || command.contains(" " + commandPrefix);
  }

  private static Path extractLoadSgfPath(String command) {
    String marker = "loadsgf ";
    int start = command.indexOf(marker);
    if (start < 0) {
      throw new IllegalStateException("Cannot extract loadsgf temp file from command: " + command);
    }
    return Path.of(command.substring(start + marker.length()).trim());
  }

  private static Path extractLoadSgfPathFromFailure(String message) {
    String marker = "loadsgf ";
    int start = message.indexOf(marker);
    int end = message.indexOf(".sgf", start);
    if (start < 0 || end < 0) {
      throw new IllegalStateException("Cannot extract loadsgf temp file from message: " + message);
    }
    return Path.of(message.substring(start + marker.length(), end + 4).trim());
  }

  private static void assertEventuallyDeleted(Path path) throws InterruptedException {
    for (int attempt = 0; attempt < 160; attempt++) {
      if (!Files.exists(path)) {
        return;
      }
      Thread.sleep(50L);
    }
    assertFalse(Files.exists(path), "temporary SGF should be deleted after both consumers finish.");
  }

  private static Config minimalConfig(boolean doubleEngine) throws Exception {
    Config config = allocate(Config.class);
    config.extraMode = doubleEngine ? ExtraMode.Double_Engine : ExtraMode.Normal;
    config.alwaysGtp = false;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static String buildSuccessResponseLine(String command) {
    String trimmed = command.trim();
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace <= 0) {
      return "=";
    }
    String firstToken = trimmed.substring(0, firstSpace);
    for (int index = 0; index < firstToken.length(); index++) {
      if (!Character.isDigit(firstToken.charAt(index))) {
        return "=";
      }
    }
    return "=" + firstToken;
  }

  private static String buildResponseLine(String command, String response) {
    String trimmed = command.trim();
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace <= 0) {
      return response;
    }
    String firstToken = trimmed.substring(0, firstSpace);
    for (int index = 0; index < firstToken.length(); index++) {
      if (!Character.isDigit(firstToken.charAt(index))) {
        return response;
      }
    }
    if (!response.isEmpty()
        && (response.charAt(0) == '=' || response.charAt(0) == '?')
        && (response.length() == 1 || !Character.isDigit(response.charAt(1)))) {
      return response.charAt(0) + firstToken + response.substring(1);
    }
    return response;
  }

  private static void sendCommandWithResponse(Leelaz engine, String command, Runnable onResponse) {
    engine.sendCommandWithResponseForTest(command, onResponse);
  }

  private abstract static class RecordedCommandOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    @Override
    public final void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public final void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty()) {
        return;
      }
      commands.add(command);
      onCommand(command);
    }

    protected abstract void onCommand(String command) throws IOException;

    protected final List<String> commands() {
      return commands;
    }
  }

  private static final class RecordingOutputStream extends RecordedCommandOutputStream {
    private String failCommandPrefix;

    private RecordingOutputStream(String failCommandPrefix) {
      this.failCommandPrefix = failCommandPrefix;
    }

    @Override
    protected void onCommand(String command) throws IOException {
      if (matchesCommandPrefix(command, failCommandPrefix)) {
        throw new IOException("simulated flush failure: " + command);
      }
    }

    private void failOnCommand(String commandPrefix) {
      this.failCommandPrefix = commandPrefix;
    }

    private int loadSgfCommandCount() {
      int count = 0;
      for (String command : commands()) {
        if (isLoadSgfCommand(command)) {
          count++;
        }
      }
      return count;
    }
  }

  private static final class ProductionResyncPonderLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();
    private int ponderCalls;
    private Leelaz replacementPrimary;

    private ProductionResyncPonderLeelaz() throws IOException {
      super("");
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            commands.add(command);
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    public boolean isPondering() {
      return true;
    }

    @Override
    public void notPondering() {
      if (replacementPrimary != null) {
        Lizzie.leelaz = replacementPrimary;
      }
    }

    @Override
    public void ponder() {
      ponderCalls++;
    }

    private void replacePrimaryOnNotPondering(Leelaz replacement) {
      replacementPrimary = replacement;
    }

    private List<String> commands() {
      return commands;
    }
  }

  private static final class CommandMutationOutputStream extends RecordedCommandOutputStream {
    private final Leelaz engine;
    private final String mutationCommand;
    private final Runnable mutation;
    private int matchingCommandCount;
    private String loadedSgf = "";

    private CommandMutationOutputStream(
        Leelaz engine, String mutationCommand, Runnable mutation) {
      this.engine = engine;
      this.mutationCommand = mutationCommand;
      this.mutation = mutation;
    }

    @Override
    protected void onCommand(String command) throws IOException {
      if (matchesCommandPrefix(command, mutationCommand)) {
        matchingCommandCount++;
        if (matchingCommandCount == 1) {
          mutation.run();
        }
      }
      if (!isLoadSgfCommand(command)) {
        String payload = commandPayload(command);
        if (payload.equals("clear_board")
            || payload.startsWith("play ")
            || payload.equals("name")) {
          invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
        }
        return;
      }
      try {
        loadedSgf = Files.readString(extractLoadSgfPath(command));
        invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
      } catch (Exception ex) {
        throw new IOException("failed to consume snapshot SGF: " + command, ex);
      }
    }

    private int loadSgfCommandCount() {
      int count = 0;
      for (String command : commands()) {
        if (isLoadSgfCommand(command)) {
          count++;
        }
      }
      return count;
    }

    private String loadedSgf() {
      return loadedSgf;
    }

    private int matchingCommandCount() {
      return matchingCommandCount;
    }
  }

  private static final class ScriptedResponseOutputStream extends RecordedCommandOutputStream {
    private final Leelaz engine;
    private final String failCommandPrefix;
    private final String clearBoardResponse;
    private final String loadSgfResponse;

    private ScriptedResponseOutputStream(
        Leelaz engine,
        String failCommandPrefix,
        String clearBoardResponse,
        String loadSgfResponse) {
      this.engine = engine;
      this.failCommandPrefix = failCommandPrefix;
      this.clearBoardResponse = clearBoardResponse;
      this.loadSgfResponse = loadSgfResponse;
    }

    @Override
    protected void onCommand(String command) throws IOException {
      if (matchesCommandPrefix(command, failCommandPrefix)) {
        throw new IOException("simulated flush failure: " + command);
      }
      String responseLine = responseFor(command);
      if (responseLine == null) {
        return;
      }
      try {
        invokeResponseHandlerForLine(engine, responseLine);
      } catch (Exception ex) {
        throw new IOException("failed to simulate loadsgf response: " + responseLine, ex);
      }
    }

    private String responseFor(String command) {
      String payload = commandPayload(command);
      if (payload.equals("clear_board")) {
        return clearBoardResponse == null ? null : buildResponseLine(command, clearBoardResponse);
      }
      if (isLoadSgfCommand(command)) {
        if (loadSgfResponse == null) {
          return null;
        }
        if (AUTO_ID_RESPONSE.equals(loadSgfResponse)) {
          return buildSuccessResponseLine(command);
        }
        return buildResponseLine(command, loadSgfResponse);
      }
      if ((payload.startsWith("play ") || payload.equals("name"))
          && AUTO_ID_RESPONSE.equals(loadSgfResponse)) {
        return buildSuccessResponseLine(command);
      }
      return null;
    }

  }

  private static final class TailReplayAwareOutputStream extends RecordedCommandOutputStream {
    private final Leelaz engine;
    private Path loadSgfPath;
    private boolean tempFileExistedDuringReplay;

    private TailReplayAwareOutputStream(Leelaz engine) {
      this.engine = engine;
    }

    @Override
    protected void onCommand(String command) throws IOException {
      String payload = commandPayload(command);
      if (isLoadSgfCommand(command)) {
        loadSgfPath = extractLoadSgfPath(command);
        try {
          invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
        } catch (Exception ex) {
          throw new IOException("failed to simulate loadsgf response: " + command, ex);
        }
        return;
      }
      if (payload.startsWith("play ") && loadSgfPath != null) {
        tempFileExistedDuringReplay = tempFileExistedDuringReplay || Files.exists(loadSgfPath);
        invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
        return;
      }
      if (payload.equals("clear_board") || payload.equals("name")) {
        invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
      }
    }

    private boolean tempFileExistedDuringReplay() {
      return tempFileExistedDuringReplay;
    }

    private Path loadSgfPath() {
      return loadSgfPath;
    }
  }

  private static final class TailRejectingOutputStream extends RecordedCommandOutputStream {
    private final Leelaz engine;

    private TailRejectingOutputStream(Leelaz engine) {
      this.engine = engine;
    }

    @Override
    protected void onCommand(String command) throws IOException {
      if (!isLoadSgfCommand(command)) {
        return;
      }
      invokeResponseHandlerForLine(engine, buildSuccessResponseLine(command));
      engine.beginForegroundRestoreForTest();
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

  private static final class ThrowingMoveListBoard extends Board {
    @Override
    public ArrayList<Movelist> getMoveList() {
      throw new AssertionError("prepared exact restore must not read live move list");
    }
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private TestHarness() {
      this.previousConfig = Lizzie.config;
      this.previousBoard = Lizzie.board;
      this.previousFrame = Lizzie.frame;
      this.previousGtpConsole = Lizzie.gtpConsole;
      this.previousLeelaz = Lizzie.leelaz;
      this.previousLeelaz2 = Lizzie.leelaz2;
      this.previousBoardWidth = Board.boardWidth;
      this.previousBoardHeight = Board.boardHeight;
    }

    private static TestHarness open(boolean doubleEngine) throws Exception {
      TestHarness harness = new TestHarness();
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Lizzie.config = minimalConfig(doubleEngine);
      Lizzie.board = allocate(Board.class);
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      return harness;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
    }
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
