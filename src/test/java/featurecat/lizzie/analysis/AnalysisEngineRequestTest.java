package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class AnalysisEngineRequestTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void sendRequestExpressesSnapshotRootPositionWithInitialStonesAndPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertTrue(request.has("initialStones"), "snapshot-root request should send initial stones.");
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "snapshot-root request should serialize the current board as initial stones.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void foxHandicapSgfAnalysisRequestKeepsSetupStonesAndZeroKomi() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Board parseBoard =
          boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      parseBoard.isLoadingFile = true;
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]KM[0]HA[2]AB[aa]AB[ca];W[ba])", true);
      boardWithHistory(history);
      history.next();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "B:C3"),
          stoneSet(request.getJSONArray("initialStones")),
          "Fox handicap imports must send root handicap stones to KataGo analysis.");
      assertEquals(List.of(List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(0.0, request.getDouble("komi"), 0.0001);
      assertEquals(2, history.getGameInfo().getHandicap());
    }
  }

  @Test
  void startRequestSkipsSnapshotRootOnlyHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(
          0,
          engine.requestCount(),
          "snapshot-root-only history should not emit batch requests from startRequest.");
    }
  }

  @Test
  void startRequestMissingMainlineAnalyzesOnlyBelowTargetMainlineMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(AnalysisEngine.targetAnalysisVisits());
      analyzed.engineName = "cached-analysis";
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(1, requested);
      assertEquals(1, engine.requestCount());
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")),
          request.getJSONArray("moves").toList(),
          "Yike curve completion should request only the missing current mainline node.");
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestMissingMainlineStillSkipsExistingAnalysisWhenOverrideIsEnabled()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.engineName = "cached-analysis";
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(
          1,
          requested,
          "missing-mainline completion is a preservation path and should not reanalyze cached nodes.");
      assertEquals(1, engine.requestCount());
      assertEquals(List.of(2), engine.singleRequest().getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestMissingMainlineSkipsLowVisitExistingAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData lowVisits =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      lowVisits.setPlayouts(AnalysisEngine.targetAnalysisVisits() - 1);
      lowVisits.engineName = "partial-analysis";
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(0, requested);
      assertEquals(0, engine.requestCount());
    }
  }

  @Test
  void startRequestReanalyzesExistingMovesWhenAlwaysOverrideIsEnabled() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(
          2,
          engine.requestCount(),
          "explicit override should still allow users to recalculate the whole selected range.");
      assertEquals(List.of(1), engine.requestAt(0).getJSONArray("analyzeTurns").toList());
      assertEquals(List.of(2), engine.requestAt(1).getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void parseResultHonorsAlwaysOverrideEvenWhenNewVisitsAreLower() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      Lizzie.config.enableLizzieCache = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.winrate = 42.0;
      analyzed.engineName = "cached-analysis";
      history.add(analyzed);
      BoardHistoryNode analyzedNode = history.getCurrentHistoryNode();
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.trackPending(1, analyzedNode);
      engine.trackPending(2, analyzedNode);

      engine.parseResult(analysisResponse(1, 1, 0.77).toString());

      assertEquals(
          1,
          analyzed.getPlayouts(),
          "always override should bypass cache guards even when the new flash result has fewer visits.");
      assertEquals(77.0, analyzed.winrate, 0.0001);
      assertFalse("cached-analysis".equals(analyzed.engineName));
    }
  }

  @Test
  void startRequestMissingMainlineReportsDispatchFailure() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;
      engine.setCompletionCallback(() -> {});

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(-1, requested);
      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
      assertNull(engine.completionCallback());
    }
  }

  @Test
  void failedSendRequestDoesNotLeavePendingAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;

      engine.sendRequest(history.getCurrentHistoryNode());

      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void failedBatchRequestDoesNotAdvanceToNextFile() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      frame.isBatchAnalysisMode = true;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;

      engine.startRequest(1, -1, false);

      assertEquals(0, frame.flashAutoAnaSaveAndLoadCalls);
      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void startRequestCountsOnlyMoveAndPassNodesWhenSelectingRange() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          snapshotNode(
              stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 1));
      history.add(passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 2));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK)),
              new int[] {1, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(2, 3, false);

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3"),
          stoneSet(request.getJSONArray("initialStones")),
          "range analysis should rebuild from the nearest SNAPSHOT anchor.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(
          List.of(List.of("W", "pass")),
          request.getJSONArray("moves").toList(),
          "range analysis should treat SNAPSHOT as a history boundary.");
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestIgnoresDummyPassWhenCollectingMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK));
      Stone[] finalStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 1));
      BoardData dummyPass = passNode(snapshotStones, Stone.WHITE, true, 2);
      dummyPass.dummy = true;
      history.add(dummyPass);
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.BLACK, false, 3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "B3")), request.getJSONArray("moves").toList());
      assertEquals(
          List.of(1),
          request.getJSONArray("analyzeTurns").toList(),
          "dummy PASS should stay out of request turn counting.");
    }
  }

  @Test
  void startRequestAllBranchesSkipsDummyPassNodesAndAnalyzesOnlyRealActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK));
      Stone[] finalStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 1));
      BoardData dummyPass = passNode(snapshotStones, Stone.WHITE, true, 2);
      dummyPass.dummy = true;
      history.add(dummyPass);
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.BLACK, false, 3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "branch analysis should skip dummy PASS nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestAllBranchesSkipsSnapshotNodesAndAnalyzesOnlyRealActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 59));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "all-branches scan should skip SNAPSHOT nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3"),
          stoneSet(request.getJSONArray("initialStones")),
          "all-branches scan should seed analysis from the snapshot board.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(List.of("W", "pass")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestSkipsMovesAtTargetVisitsButContinuesLowerVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      int targetVisits = AnalysisEngine.targetAnalysisVisits();
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(targetVisits);
      history.add(complete);
      BoardData lowVisits =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      lowVisits.setPlayouts(targetVisits - 1);
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(1, engine.requestCount(), "flash analysis should continue partial results.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")),
          request.getJSONArray("moves").toList());
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
      assertEquals(targetVisits, request.getInt("maxVisits"));
    }
  }

  @Test
  void startRequestOverrideReanalyzesMovesAtTargetVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(AnalysisEngine.targetAnalysisVisits());
      history.add(complete);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(1, engine.requestCount(), "explicit override should still reanalyze.");
      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "A3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void silentStartRequestDoesNotPauseForegroundPonder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(0, leelaz.togglePonderCount);
      assertTrue(leelaz.isPondering());
      assertEquals(1, engine.requestCount());
    }
  }

  @Test
  void startRequestAllBranchesSkipsOnlyBranchNodesAtTargetVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      int targetVisits = AnalysisEngine.targetAnalysisVisits();
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(targetVisits);
      history.add(complete);
      BoardData lowVisits =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      lowVisits.setPlayouts(targetVisits - 1);
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "branch flash analysis should continue partial nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")),
          request.getJSONArray("moves").toList());
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestAllBranchesSkipsSnapshotRootOnlyHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(
          0,
          engine.requestCount(),
          "snapshot-root-only history should not emit branch scan requests.");
    }
  }

  @Test
  void silentParseResultKeepsForegroundCurrentNodeAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData current =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      current.winrate = 41.0;
      current.setPlayouts(12);
      history.add(current);
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.startRequest(-1, -1, false);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(12, current.getPlayouts());
      assertEquals(41.0, current.winrate, 0.0001);
      assertTrue(current.bestMoves.isEmpty());
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void silentParseResultUpdatesNonCurrentNodesWhileForegroundAnalyzes() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData current =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      history.add(current);
      BoardHistoryNode currentNode = history.getCurrentHistoryNode();
      BoardData next =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      next.winrate = 39.0;
      next.setPlayouts(8);
      history.add(next);
      history.setHead(currentNode);
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.sendRequest(history.getCurrentHistoryNode().next().get());

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(200, next.getPlayouts());
      assertEquals(62.0, next.winrate, 0.0001);
      assertFalse(next.bestMoves.isEmpty());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void sendRequestSetupOnlyStartStonePositionIncludesCurrentPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "setup-only analysis should send configured start stones.");
      assertEquals(
          "W",
          request.getString("initialPlayer"),
          "setup-only analysis should use the current position side to move.");
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestRootSnapshotPrefersSnapshotStonesOverConfiguredStartStones() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(2, 2, true));
      Lizzie.board.startStonelist.add(startStone(1, 1, false));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "root SNAPSHOT should stay the request anchor even when start stones are configured.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestStartStonePositionWithMovesKeepsRootInitialPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] afterFirstMove =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      history.add(moveNode(afterFirstMove, new int[] {2, 0}, Stone.WHITE, true, 1));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "start-stone analysis with moves should still send configured start stones.");
      assertEquals(
          "W",
          request.getString("initialPlayer"),
          "start-stone analysis should keep root side-to-play even after real moves.");
      assertEquals(List.of(List.of("W", "C3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestWithStartStoneAndMidSnapshotUsesNearestSnapshotAsSharedAnchor() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] afterRootMove =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.WHITE));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      history.add(moveNode(afterRootMove, new int[] {2, 0}, Stone.WHITE, true, 1));
      history.add(snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, true, 1));
      history.add(passNode(snapshotStones, Stone.BLACK, false, 2));
      history.add(moveNode(finalStones, new int[] {1, 1}, Stone.WHITE, true, 3));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:C3"),
          stoneSet(request.getJSONArray("initialStones")),
          "start-stone history with a nearer snapshot should use that snapshot board as anchor.");
      assertEquals(
          "B",
          request.getString("initialPlayer"),
          "start-stone history with a nearer snapshot should use that snapshot side-to-play.");
      assertEquals(
          List.of(List.of("B", "pass"), List.of("W", "B2")),
          request.getJSONArray("moves").toList(),
          "analysis moves should stay scoped to actions after the same snapshot anchor.");
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void restoreClosedEngineBoardStateReplaysFromLatestSnapshotBoundary() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(snapshotNode(emptyStones(), Optional.empty(), Stone.EMPTY, false, 1));
      history.add(
          moveNode(stones(placement(1, 0, Stone.WHITE)), new int[] {1, 0}, Stone.WHITE, true, 2));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();

      leelaz.restoreClosedEngineBoardState(false);

      assertEquals("clear_board", leelaz.sentCommands.get(0));
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "closed-engine restore should land the snapshot board exactly before later actions.");
      assertEquals(
          List.of("WHITE:B3"),
          leelaz.playedMoves,
          "closed-engine restore should replay only later real actions after the snapshot anchor.");
      assertArrayEquals(
          history.getCurrentHistoryNode().getData().stones,
          leelaz.copyStones(),
          "closed-engine restore should match the current board after the exact snapshot restore.");
    }
  }

  @Test
  void restoreClosedEngineBoardStateUsesLoadsgfForDeadSnapshotThenReplaysRealActions()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.BLACK),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(1, 2, Stone.BLACK));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.BLACK),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(1, 2, Stone.BLACK),
              placement(2, 2, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(snapshotStones, Stone.WHITE, true, 59));
      history.add(moveNode(finalStones, new int[] {2, 2}, Stone.BLACK, false, 60));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();

      leelaz.restoreClosedEngineBoardState(false);

      assertEquals(
          "clear_board",
          leelaz.sentCommands.get(0),
          "closed-engine restore should always reset the engine first.");
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "dead snapshot anchors should restore through loadsgf before replaying actions.");
      assertEquals(
          List.of("WHITE:pass", "BLACK:C1"),
          leelaz.playedMoves,
          "after the static snapshot is restored, only real PASS/MOVE actions should be replayed.");
      assertArrayEquals(
          finalStones,
          leelaz.copyStones(),
          "loadsgf plus real actions should reproduce the final board exactly.");
      assertFalse(
          leelaz.isBlackToPlay(),
          "replaying the real actions after loadsgf should preserve the final side to play.");
    }
  }

  @Test
  void restoreClosedEngineBoardStateForRemovedStoneHistoryStillStartsFromSnapshotBoundary()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.BLACK));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.getCurrentHistoryNode().setRemovedStone();
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.WHITE, true, 59));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      Leelaz previousLeelaz = Lizzie.leelaz;
      Lizzie.leelaz = leelaz;
      try {
        leelaz.restoreClosedEngineBoardState(false);
      } finally {
        Lizzie.leelaz = previousLeelaz;
      }

      assertEquals(
          "clear_board",
          leelaz.sentCommands.get(0),
          "closed-engine restore should still clear the engine before restoring removed-stone history.");
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "removed-stone history should restore from the latest snapshot boundary through loadsgf.");
      assertEquals(
          List.of("WHITE:B3"),
          leelaz.playedMoves,
          "removed-stone history should replay only the real actions after the snapshot boundary.");
      assertArrayEquals(
          finalStones,
          leelaz.copyStones(),
          "closed-engine restore should keep the final board exact after a removed-stone boundary.");
    }
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    Lizzie.board = board;
    return board;
  }

  private static BoardData moveNode(
      Stone[] stones, int[] lastMove, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.move(
        stones,
        lastMove,
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

  private static BoardData passNode(
      Stone[] stones, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones, color, blackToPlay, zobrist(stones), moveNumber, new int[BOARD_AREA], 0, 0, 50, 0);
  }

  private static BoardData snapshotNode(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber) {
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
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

  private static Set<String> stoneSet(JSONArray stones) {
    Set<String> result = new LinkedHashSet<>();
    for (Object entry : stones.toList()) {
      List<?> pair = (List<?>) entry;
      result.add(pair.get(0) + ":" + pair.get(1));
    }
    return result;
  }

  private static JSONObject analysisResponse(int id, int visits, double winrate) {
    JSONObject moveInfo = new JSONObject();
    moveInfo.put("order", 0);
    moveInfo.put("move", "C3");
    moveInfo.put("visits", visits);
    moveInfo.put("winrate", winrate);
    moveInfo.put("lcb", winrate);
    moveInfo.put("prior", 0.25);
    moveInfo.put("scoreLead", 1.5);
    moveInfo.put("scoreStdev", 0.3);
    moveInfo.put("pv", new JSONArray().put("C3"));

    JSONObject response = new JSONObject();
    response.put("id", String.valueOf(id));
    response.put("moveInfos", new JSONArray().put(moveInfo));
    return response;
  }

  private static String analysisResult(int id, int visits, double winrate) {
    JSONObject moveInfo = new JSONObject();
    moveInfo.put("move", "B2");
    moveInfo.put("visits", visits);
    moveInfo.put("winrate", winrate / 100.0);
    moveInfo.put("lcb", winrate / 100.0);
    moveInfo.put("prior", 0.25);
    moveInfo.put("scoreLead", 1.5);
    moveInfo.put("scoreStdev", 0.2);
    moveInfo.put("order", 0);
    moveInfo.put("pv", new JSONArray(List.of("B2")));
    JSONObject result = new JSONObject();
    result.put("id", String.valueOf(id));
    result.put("moveInfos", new JSONArray(List.of(moveInfo)));
    return result.toString();
  }

  private static void waitForMovelistRefreshThreads() throws InterruptedException {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if ("lizzie-movelist-refresh".equals(thread.getName()) && thread.isAlive()) {
        thread.join(1000);
      }
    }
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  private static Movelist startStone(int x, int y, boolean isBlack) {
    Movelist move = new Movelist();
    move.x = x;
    move.y = y;
    move.isblack = isBlack;
    move.ispass = false;
    return move;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setIntField(Class<?> owner, Object target, String name, int value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Leelaz previousLeelaz = Lizzie.leelaz;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.analysisMaxVisits = 32;
      config.batchAnalysisPlayouts = 64;
      config.showPvVisits = false;
      config.showKataGoEstimate = false;
      config.useMovesOwnership = false;
      config.analysisUseCurrentRules = false;
      config.analysisSpecificRules = "";
      config.currentKataGoRules = "";
      config.autoLoadKataRules = false;
      config.kataRules = "";
      config.analysisEnginePreLoad = false;
      config.readKomi = true;
      Lizzie.config = config;

      TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
      frame.isBatchAnalysisMode = false;
      frame.priorityMoveCoords = new ArrayList<>();
      frame.clickOrder = -1;
      frame.selectedorder = -1;
      frame.currentRow = -1;
      frame.clickbadmove = LizzieFrame.outOfBoundCoordinate;
      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame = frame;
      Lizzie.leelaz = allocate(Leelaz.class);

      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
          previousFrame,
          previousLeelaz);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
    }
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private int flashAutoAnaSaveAndLoadCalls;

    private TrackingLizzieFrame() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void flashAutoAnaSaveAndLoad() {
      flashAutoAnaSaveAndLoadCalls++;
    }

    @Override
    public void clearKataEstimate() {}
  }

  private static final class TrackingAnalysisEngine extends AnalysisEngine {
    private List<String> sentCommands;
    private boolean failSends;

    private TrackingAnalysisEngine() throws IOException {
      super(true);
    }

    private static TrackingAnalysisEngine create() throws Exception {
      TrackingAnalysisEngine engine = allocate(TrackingAnalysisEngine.class);
      engine.sentCommands = new ArrayList<>();
      setField(
          AnalysisEngine.class, engine, "analyzeMap", new java.util.HashMap<Integer, Object>());
      setIntField(AnalysisEngine.class, engine, "globalID", 1);
      setField(AnalysisEngine.class, engine, "waitFrame", null);
      setField(AnalysisEngine.class, engine, "silentProgress", false);
      setField(AnalysisEngine.class, engine, "shouldRePonder", false);
      setField(AnalysisEngine.class, engine, "isLoaded", true);
      setField(AnalysisEngine.class, engine, "resourceBundle", Lizzie.resourceBundle);
      return engine;
    }

    @Override
    public boolean sendCommand(String command) {
      if (failSends) {
        return false;
      }
      sentCommands.add(command);
      return true;
    }

    private JSONObject singleRequest() {
      assertEquals(1, sentCommands.size(), "test should capture exactly one analysis request.");
      return new JSONObject(sentCommands.get(0));
    }

    private int requestCount() {
      return sentCommands.size();
    }

    private JSONObject requestAt(int index) {
      return new JSONObject(sentCommands.get(index));
    }

    @SuppressWarnings("unchecked")
    private void trackPending(int id, BoardHistoryNode node) throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      ((java.util.Map<Integer, BoardHistoryNode>) field.get(this)).put(id, node);
    }

    private int pendingRequestCount() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      return ((java.util.Map<?, ?>) field.get(this)).size();
    }

    private Runnable completionCallback() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("completionCallback");
      field.setAccessible(true);
      return (Runnable) field.get(this);
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE;

    static {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        UNSAFE = (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
