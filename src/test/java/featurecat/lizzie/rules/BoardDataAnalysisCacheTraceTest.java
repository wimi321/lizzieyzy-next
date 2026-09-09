package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class BoardDataAnalysisCacheTraceTest {
  private static final long BOARD_REVISION = 1842L;

  @TempDir Path tempDir;
  private Config previousConfig;
  private Board previousBoard;
  private Leelaz previousLeelaz;
  private LizzieFrame previousFrame;
  private ListAppender<ILoggingEvent> traceEvents;

  @BeforeEach
  void saveGlobals() {
    previousConfig = Lizzie.config;
    previousBoard = Lizzie.board;
    previousLeelaz = Lizzie.leelaz;
    previousFrame = Lizzie.frame;
  }

  @AfterEach
  void restoreGlobals() {
    LoggingRuntime.resetForTests();
    Lizzie.config = previousConfig;
    Lizzie.board = previousBoard;
    Lizzie.leelaz = previousLeelaz;
    Lizzie.frame = previousFrame;
  }

  @Test
  void ordinaryRootAdmissionComparesLegacyCountsAndKeepsSlotsIndependent() throws Exception {
    installBoardGlobals();
    BoardData node = primaryNode(1000, 40, 1);
    node.setPlayouts2(2000);
    node.rootVisits2 = 2000;
    Object source = new Object();
    var payload = featurecat.lizzie.analysis.KataGoAnalysisPayload.parse(
        "info move D4 visits 900 winrate 0.6 pv D4 rootInfo visits 1500");
    assertEquals(BoardData.AnalysisAdoption.REJECTED,
        node.adoptOrdinaryAnalysis(payload.moves, "KataGo", Lizzie.leelaz,
            payload.totalVisits(), payload.rootVisits, null, source, false, false));
    assertEquals(1000, node.getPlayouts());
    assertEquals(-1, node.rootVisits);
    payload = featurecat.lizzie.analysis.KataGoAnalysisPayload.parse(
        "info move D4 visits 1100 winrate 0.6 pv D4 rootInfo visits 100");
    assertEquals(BoardData.AnalysisAdoption.FULL,
        node.adoptOrdinaryAnalysis(payload.moves, "KataGo", Lizzie.leelaz,
            payload.totalVisits(), payload.rootVisits, null, source, false, false));
    assertEquals(100, node.getPlayouts());
    assertEquals(100, node.rootVisits);
    assertEquals(2000, node.rootVisits2);
    var lower = featurecat.lizzie.analysis.KataGoAnalysisPayload.parse(
        "info move C3 visits 50000 winrate 0.8 pv C3 rootInfo visits 50 ownership 0.5 -0.5");
    Object replacement = new Object();
    assertEquals(BoardData.AnalysisAdoption.OWNERSHIP_ONLY,
        node.adoptOrdinaryAnalysis(lower.moves, "KataGo", Lizzie.leelaz,
            lower.totalVisits(), lower.rootVisits, lower.ownership, replacement, false, false));
    assertEquals("D4", node.bestMoves.get(0).coordinate);
    assertEquals(100, node.rootVisits);
    assertEquals(List.of(0.5, -0.5), node.estimateArray);
    node.isChanged2 = true;
    assertEquals(BoardData.AnalysisAdoption.FULL,
        node.adoptOrdinaryAnalysis(lower.moves, "KataGo", Lizzie.leelaz,
            lower.totalVisits(), lower.rootVisits, null, replacement, true, false));
    assertEquals(50, node.rootVisits2);
    assertEquals(100, node.rootVisits);
    var unknown = featurecat.lizzie.analysis.KataGoAnalysisPayload.parse(
        "info move C3 visits 90000 winrate 0.9 pv C3");
    assertEquals(BoardData.AnalysisAdoption.REJECTED,
        node.adoptOrdinaryAnalysis(unknown.moves, "KataGo", Lizzie.leelaz,
            unknown.totalVisits(), -1, null, replacement, false, false));
    assertEquals(100, node.rootVisits);
  }

  @Test
  void primaryHigherVisitsAcceptsAndEmitsStructuredDecisionWhenTraceIsOn() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(10555, 0.12, -27.1);
    MoveData incoming = kataMove(10621, 99.87, 30.6);

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(incoming)), "KataGo", Lizzie.leelaz, 10621, null, false));

    assertEquals(10621, node.getPlayouts());
    assertEquals(99.87, node.winrate, 0.0001);
    assertDecision(
        "ACCEPT",
        "HIGHER_VISITS",
        10621,
        99.87,
        30.6,
        10555,
        0.12,
        -27.1);
  }

  @Test
  void primaryLowerVisitsRejectsAndEmitsStructuredDecisionWhenTraceIsOn() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(10555, 0.12, -27.1);
    MoveData incoming = kataMove(868, 99.91, 30.4);

    assertFalse(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(incoming)), "KataGo", Lizzie.leelaz, 868, null, false));

    assertEquals(10555, node.getPlayouts());
    assertEquals(0.12, node.winrate, 0.0001);
    assertDecision("REJECT", "LOWER_VISITS", 868, 99.91, 30.4, 10555, 0.12, -27.1);
  }

  @Test
  void primaryOwnershipBackfillAcceptsMapOnlyAndEmitsDistinctReason() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(10555, 0.12, -27.1);
    MoveData cached = kataMove(10555, 0.12, -27.1);
    cached.coordinate = "A1";
    node.bestMoves = new ArrayList<>(List.of(cached));
    MoveData incoming = kataMove(868, 99.91, 30.4);
    incoming.coordinate = "B1";
    List<Double> ownership = List.of(0.9, -0.8, 0.7);

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(incoming)),
            "KataGo",
            Lizzie.leelaz,
            868,
            ownership,
            false));

    assertEquals(10555, node.getPlayouts());
    assertEquals("A1", node.bestMoves.get(0).coordinate);
    assertEquals(0.12, node.winrate, 0.0001);
    assertEquals(ownership, node.estimateArray);
    assertDecision(
        "ACCEPT", "OWNERSHIP_BACKFILL", 868, 99.91, 30.4, 10555, 0.12, -27.1);
  }

  @Test
  void primaryEqualVisitsWithoutOwnershipFillRejects() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(868, 50.0, 1.5);

    assertFalse(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(kataMove(868, 12.0, 9.0))),
            "KataGo",
            Lizzie.leelaz,
            868,
            null,
            false));

    assertEquals(50.0, node.winrate, 0.0001);
    assertDecision("REJECT", "EQUAL_VISITS", 868, 12.0, 9.0, 868, 50.0, 1.5);
  }

  @Test
  void primaryForceOverrideAcceptsLowerVisits() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(10555, 0.12, -27.1);

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(kataMove(868, 99.91, 30.4))),
            "KataGo",
            Lizzie.leelaz,
            868,
            null,
            true));

    assertEquals(868, node.getPlayouts());
    assertDecision("ACCEPT", "FORCE_OVERRIDE", 868, 99.91, 30.4, 10555, 0.12, -27.1);
  }

  @Test
  void primaryChangedFlagAcceptsEqualVisits() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(868, 50.0, 1.5);
    node.isChanged = true;

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(kataMove(868, 12.0, 9.0))),
            "KataGo",
            Lizzie.leelaz,
            868,
            null,
            false));

    assertEquals(12.0, node.winrate, 0.0001);
    assertDecision("ACCEPT", "IS_CHANGED", 868, 12.0, 9.0, 868, 50.0, 1.5);
  }

  @Test
  void primaryExplicitInvalidationLetsLowerVisitsReplaceOnlyPrimarySlot() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(10555, 0.12, -27.1);
    MoveData cachedPrimary = kataMove(10555, 0.12, -27.1);
    cachedPrimary.coordinate = "A1";
    node.bestMoves = new ArrayList<>(List.of(cachedPrimary));
    node.setPlayouts2(12000);
    node.winrate2 = 55.5;
    node.scoreMean2 = 4.25;
    MoveData cachedSecondary = kataMove(12000, 55.5, 4.25);
    cachedSecondary.coordinate = "Q16";
    node.bestMoves2 = new ArrayList<>(List.of(cachedSecondary));
    List<Double> secondaryOwnership = List.of(0.4, -0.5);
    node.estimateArray2 = secondaryOwnership;
    node.isChanged = true;
    MoveData incoming = kataMove(868, 99.91, 30.4);
    incoming.coordinate = "B1";
    List<Double> incomingOwnership = List.of(0.9, -0.8, 0.7);

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(incoming)),
            "KataGo",
            Lizzie.leelaz,
            868,
            incomingOwnership,
            false));

    assertEquals(868, node.getPlayouts());
    assertEquals("B1", node.bestMoves.get(0).coordinate);
    assertEquals(868, node.bestMoves.get(0).playouts);
    assertEquals(99.91, node.winrate, 0.0001);
    assertEquals(30.4, node.scoreMean, 0.0001);
    assertEquals(incomingOwnership, node.estimateArray);
    assertEquals(12000, node.getPlayouts2());
    assertEquals("Q16", node.bestMoves2.get(0).coordinate);
    assertEquals(12000, node.bestMoves2.get(0).playouts);
    assertEquals(55.5, node.winrate2, 0.0001);
    assertEquals(4.25, node.scoreMean2, 0.0001);
    assertEquals(secondaryOwnership, node.estimateArray2);
  }

  @Test
  void primaryPdaChangeAcceptsEqualVisits() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(868, 50.0, 1.5);
    node.pda = 0;
    Lizzie.leelaz.pda = 1.25;

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(kataMove(868, 12.0, 9.0))),
            "KataGo",
            Lizzie.leelaz,
            868,
            null,
            false));

    assertEquals(12.0, node.winrate, 0.0001);
    assertDecision("ACCEPT", "PDA_CHANGED", 868, 12.0, 9.0, 868, 50.0, 1.5);
  }

  @Test
  void secondaryHigherVisitsAcceptsAndEmitsStructuredDecisionWhenTraceIsOn() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = secondaryNode(10555, 0.12, -27.1);

    node.tryToSetBestMoves2FromEngine(
        new ArrayList<>(List.of(kataMove(10621, 99.87, 30.6))),
        "KataGo-2",
        Lizzie.leelaz,
        10621,
        null);

    assertEquals(10621, node.getPlayouts2());
    assertDecision(
        "ACCEPT",
        "HIGHER_VISITS",
        10621,
        99.87,
        30.6,
        10555,
        0.12,
        -27.1,
        "KataGo-2");
  }

  @Test
  void secondaryLowerVisitsRejectsAndEmitsStructuredDecisionWhenTraceIsOn() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = secondaryNode(10555, 0.12, -27.1);

    node.tryToSetBestMoves2FromEngine(
        new ArrayList<>(List.of(kataMove(868, 99.91, 30.4))),
        "KataGo-2",
        Lizzie.leelaz,
        868,
        null);

    assertEquals(10555, node.getPlayouts2());
    assertDecision(
        "REJECT", "LOWER_VISITS", 868, 99.91, 30.4, 10555, 0.12, -27.1, "KataGo-2");
  }

  @Test
  void secondaryExplicitInvalidationLetsLowerVisitsReplaceOnlySecondarySlot() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = secondaryNode(10555, 0.12, -27.1);
    MoveData cachedSecondary = kataMove(10555, 0.12, -27.1);
    cachedSecondary.coordinate = "Q16";
    node.bestMoves2 = new ArrayList<>(List.of(cachedSecondary));
    node.setPlayouts(12000);
    node.winrate = 55.5;
    node.scoreMean = 4.25;
    MoveData cachedPrimary = kataMove(12000, 55.5, 4.25);
    cachedPrimary.coordinate = "A1";
    node.bestMoves = new ArrayList<>(List.of(cachedPrimary));
    List<Double> primaryOwnership = List.of(0.4, -0.5);
    node.estimateArray = primaryOwnership;
    node.isChanged2 = true;
    MoveData incoming = kataMove(868, 99.91, 30.4);
    incoming.coordinate = "B1";
    List<Double> incomingOwnership = List.of(0.9, -0.8, 0.7);

    node.tryToSetBestMoves2FromEngine(
        new ArrayList<>(List.of(incoming)), "KataGo-2", Lizzie.leelaz, 868, incomingOwnership);

    assertEquals(868, node.getPlayouts2());
    assertEquals("B1", node.bestMoves2.get(0).coordinate);
    assertEquals(868, node.bestMoves2.get(0).playouts);
    assertEquals(99.91, node.winrate2, 0.0001);
    assertEquals(30.4, node.scoreMean2, 0.0001);
    assertEquals(incomingOwnership, node.estimateArray2);
    assertEquals(12000, node.getPlayouts());
    assertEquals("A1", node.bestMoves.get(0).coordinate);
    assertEquals(12000, node.bestMoves.get(0).playouts);
    assertEquals(55.5, node.winrate, 0.0001);
    assertEquals(4.25, node.scoreMean, 0.0001);
    assertEquals(primaryOwnership, node.estimateArray);
  }

  @Test
  void secondaryOwnershipBackfillAcceptsMapOnlyAndEmitsDistinctReason() throws Exception {
    startFullTrace();
    installBoardGlobals();
    BoardData node = secondaryNode(10555, 0.12, -27.1);
    MoveData cached = kataMove(10555, 0.12, -27.1);
    cached.coordinate = "A1";
    node.bestMoves2 = new ArrayList<>(List.of(cached));
    MoveData incoming = kataMove(868, 99.91, 30.4);
    incoming.coordinate = "B1";
    List<Double> ownership = List.of(0.4, 0.5);

    node.tryToSetBestMoves2FromEngine(
        new ArrayList<>(List.of(incoming)), "KataGo-2", Lizzie.leelaz, 868, ownership);

    assertEquals(10555, node.getPlayouts2());
    assertEquals("A1", node.bestMoves2.get(0).coordinate);
    assertEquals(ownership, node.estimateArray2);
    assertDecision(
        "ACCEPT",
        "OWNERSHIP_BACKFILL",
        868,
        99.91,
        30.4,
        10555,
        0.12,
        -27.1,
        "KataGo-2");
  }

  @Test
  void traceOffDoesNotEmitAnalysisCacheLinesAndStillAcceptsHigherVisits() throws Exception {
    startRuntimeWithoutFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(100, 40.0, 2.0);

    assertTrue(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(kataMove(200, 55.0, 3.0))),
            "KataGo",
            Lizzie.leelaz,
            200,
            null,
            false));
    assertEquals(200, node.getPlayouts());
    assertFalse(hasAnalysisCacheLine(), formattedTrace());
  }

  @Test
  void traceOffDoesNotEmitAnalysisCacheLinesAndStillRejectsLowerVisits() throws Exception {
    startRuntimeWithoutFullTrace();
    installBoardGlobals();
    BoardData node = primaryNode(10555, 0.12, -27.1);

    assertFalse(
        node.tryToSetBestMovesFromEngine(
            new ArrayList<>(List.of(kataMove(868, 99.91, 30.4))),
            "KataGo",
            Lizzie.leelaz,
            868,
            null,
            false));

    assertEquals(10555, node.getPlayouts());
    assertFalse(hasAnalysisCacheLine(), formattedTrace());
  }

  private void startFullTrace() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    traceEvents = attach(trace);
  }

  private void startRuntimeWithoutFullTrace() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    trace.setLevel(Level.INFO);
    traceEvents = attach(trace);
  }

  private void installBoardGlobals() throws Exception {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir);
    Lizzie.config.enableLizzieCache = true;
    Lizzie.config.isAutoAna = false;
    Board board = allocate(Board.class);
    board.setHistory(new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
    Field revision = Board.class.getDeclaredField("contextRevision");
    revision.setAccessible(true);
    revision.setLong(board, BOARD_REVISION);
    Lizzie.board = board;
    Lizzie.leelaz = new Leelaz("");
    Lizzie.leelaz.pda = 0;
    Lizzie.frame = null;
  }

  private static BoardData primaryNode(int visits, double winrate, double scoreLead) {
    BoardData node = BoardData.empty(Board.boardWidth, Board.boardHeight);
    node.moveNumber = 206;
    node.blackToPlay = true;
    node.setPlayouts(visits);
    node.winrate = winrate;
    node.scoreMean = scoreLead;
    return node;
  }

  private static BoardData secondaryNode(int visits, double winrate, double scoreLead) {
    BoardData node = BoardData.empty(Board.boardWidth, Board.boardHeight);
    node.moveNumber = 206;
    node.blackToPlay = true;
    node.setPlayouts2(visits);
    node.winrate2 = winrate;
    node.scoreMean2 = scoreLead;
    return node;
  }

  private static MoveData kataMove(int visits, double winrate, double scoreLead) {
    MoveData move = new MoveData();
    move.coordinate = "D4";
    move.playouts = visits;
    move.winrate = winrate;
    move.scoreMean = scoreLead;
    move.isKataData = true;
    return move;
  }

  private void assertDecision(
      String decision,
      String reason,
      int incomingVisits,
      double incomingWinrate,
      double incomingScoreLead,
      int cachedVisits,
      double cachedWinrate,
      double cachedScoreLead) {
    assertDecision(
        decision,
        reason,
        incomingVisits,
        incomingWinrate,
        incomingScoreLead,
        cachedVisits,
        cachedWinrate,
        cachedScoreLead,
        "KataGo");
  }

  private void assertDecision(
      String decision,
      String reason,
      int incomingVisits,
      double incomingWinrate,
      double incomingScoreLead,
      int cachedVisits,
      double cachedWinrate,
      double cachedScoreLead,
      String engine) {
    List<String> lines = analysisCacheLines();
    assertEquals(1, lines.size(), formattedTrace());
    String line = lines.get(0);
    assertTrue(line.contains("analysis-cache "), line);
    assertTrue(line.contains("nodeMove=206"), line);
    assertTrue(line.contains("boardRevision=" + BOARD_REVISION), line);
    assertTrue(line.contains("blackToPlay=true"), line);
    assertTrue(line.contains("engine=" + engine), line);
    assertTrue(line.contains("incomingVisits=" + incomingVisits), line);
    assertTrue(line.contains("incomingWinrate=" + incomingWinrate), line);
    assertTrue(line.contains("incomingScoreLead=" + incomingScoreLead), line);
    assertTrue(line.contains("cachedVisits=" + cachedVisits), line);
    assertTrue(line.contains("cachedWinrate=" + cachedWinrate), line);
    assertTrue(line.contains("cachedScoreLead=" + cachedScoreLead), line);
    assertTrue(line.contains("decision=" + decision), line);
    assertTrue(line.contains("reason=" + reason), line);
  }

  private boolean hasAnalysisCacheLine() {
    return !analysisCacheLines().isEmpty();
  }

  private List<String> analysisCacheLines() {
    List<String> lines = new ArrayList<>();
    if (traceEvents == null) {
      return lines;
    }
    for (ILoggingEvent event : traceEvents.list) {
      String message = event.getFormattedMessage();
      if (message.contains("analysis-cache ")) {
        lines.add(message);
      }
    }
    return lines;
  }

  private String formattedTrace() {
    if (traceEvents == null) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : traceEvents.list) {
      text.append(event.getFormattedMessage()).append('\n');
    }
    return text.toString();
  }

  private static ListAppender<ILoggingEvent> attach(Logger logger) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
