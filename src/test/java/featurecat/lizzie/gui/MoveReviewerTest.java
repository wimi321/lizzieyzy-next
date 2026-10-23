package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.training.MoveFeedback;
import featurecat.lizzie.training.MoveVerdict;
import featurecat.lizzie.training.PositionReview;
import featurecat.lizzie.training.RankAwareWinrate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MoveReviewerTest {
  private static final PositionReview TURN =
      new PositionReview(
          0.4,
          12.0,
          List.of(
              new PositionReview.Candidate("Q16", 300, 0.5),
              new PositionReview.Candidate("D4", 80, -3.5)),
          Map.of("D4", 0.55, "Q16", 0.2));

  private final ScriptedEngine engine = new ScriptedEngine();
  private final List<String> changes = new ArrayList<>();
  private boolean inGame = true;
  private final DirectExecutor worker = new DirectExecutor();
  private final MoveReviewer reviewer =
      new MoveReviewer(engine, node -> inGame, () -> changes.add("changed"), Runnable::run, worker);

  private static BoardHistoryNode node(int moveNumber) {
    BoardData data = BoardData.empty(19, 19);
    data.moveNumber = moveNumber;
    return new BoardHistoryNode(data);
  }

  @Test
  void theTurnIsReviewedWhileThePlayerThinks() {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);

    reviewer.playerToMove(turn);

    assertEquals(List.of("10@400"), engine.calls);
    assertEquals(Duration.ofMillis(1500), engine.lastTime);
    assertArrayEquals(
        new double[] {RankAwareWinrate.of(0.5, 12.0) * 100.0}, reviewer.winChances(), 1e-9);
  }

  @Test
  void aWellSearchedMoveIsJudgedFromTheTurnsReviewAlone() throws Exception {
    BoardHistoryNode turn = node(10);
    BoardHistoryNode after = node(11);
    engine.reviews.put(turn, TURN);
    reviewer.playerToMove(turn);

    Optional<MoveFeedback> feedback = reviewer.judge(turn, after, "D4").get();

    assertEquals(List.of("10@400"), engine.calls, "the turn's review is reused");
    assertEquals(MoveVerdict.PROBLEM, feedback.orElseThrow().verdict());
    assertEquals(11, feedback.get().moveNumber());
    assertEquals(feedback, reviewer.latest());
    assertFalse(reviewer.isJudging());
    assertEquals(1, reviewer.count(MoveVerdict.PROBLEM));
    assertArrayEquals(
        new double[] {feedback.get().winBefore() * 100.0, feedback.get().winAfter() * 100.0},
        reviewer.winChances(),
        1e-9);
  }

  @Test
  void aMoveTheSearchMissedNeedsAShortReviewOfThePositionAfter() throws Exception {
    BoardHistoryNode turn = node(10);
    BoardHistoryNode after = node(11);
    engine.reviews.put(turn, TURN);
    engine.reviews.put(after, new PositionReview(14.5, 11.0, List.of(), Map.of()));

    MoveFeedback feedback = reviewer.judge(turn, after, "A1").get().orElseThrow();

    assertEquals(List.of("10@400", "11@200"), engine.calls);
    assertEquals(Duration.ofMillis(800), engine.lastTime);
    assertEquals(MoveVerdict.BLUNDER, feedback.verdict());
  }

  @Test
  void feedbackOnAMoveTakenBackMeanwhileIsDropped() throws Exception {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);
    inGame = false;

    reviewer.judge(turn, node(11), "D4").get();

    assertTrue(reviewer.feedback().isEmpty());
    assertFalse(reviewer.isJudging());
  }

  @Test
  void takingBackForgetsWhatWasSaidAboutTheLaterMoves() throws Exception {
    BoardHistoryNode first = node(10);
    BoardHistoryNode second = node(12);
    engine.reviews.put(first, TURN);
    engine.reviews.put(second, TURN);
    reviewer.judge(first, node(11), "Q16").get();
    reviewer.judge(second, node(13), "D4").get();
    assertEquals(2, reviewer.feedback().size());

    reviewer.takenBack(node(12));

    assertEquals(1, reviewer.feedback().size());
    assertEquals(11, reviewer.latest().orElseThrow().moveNumber());
    assertEquals(0, reviewer.count(MoveVerdict.PROBLEM));
  }

  @Test
  void theWorstMistakesAreTheWorstRatingsFirstListedInPlayOrder() throws Exception {
    PositionReview good = TURN;
    PositionReview blunder =
        new PositionReview(
            0.4,
            12.0,
            List.of(
                new PositionReview.Candidate("Q16", 300, 0.5),
                new PositionReview.Candidate("A1", 60, -14.0)),
            Map.of("Q16", 0.5));
    BoardHistoryNode first = node(10);
    BoardHistoryNode second = node(12);
    BoardHistoryNode third = node(14);
    engine.reviews.put(first, blunder);
    engine.reviews.put(second, good);
    engine.reviews.put(third, good);
    reviewer.judge(first, node(11), "A1").get();
    reviewer.judge(second, node(13), "Q16").get();
    reviewer.judge(third, node(15), "D4").get();

    List<MoveFeedback> worst = reviewer.worstMistakes(3);

    assertEquals(List.of(11, 15), worst.stream().map(MoveFeedback::moveNumber).toList());
    assertEquals(1, reviewer.keyMistakes(), "the 恶手 only, not the 问题手");
    assertEquals(MoveVerdict.BLUNDER, worst.get(0).verdict());
    assertEquals(
        List.of(11), reviewer.worstMistakes(1).stream().map(MoveFeedback::moveNumber).toList());
  }

  @Test
  void anEngineFailureLeavesTheMoveUnrated() throws Exception {
    Optional<MoveFeedback> feedback = reviewer.judge(node(10), node(11), "D4").get();

    assertTrue(feedback.isEmpty());
    assertFalse(reviewer.isJudging());
    assertTrue(reviewer.feedback().isEmpty());
    assertEquals(List.of("changed"), changes, "the panel stops showing that it is rating");
  }

  @Test
  void afterClosingNothingMoreIsReviewed() throws Exception {
    reviewer.close();

    assertTrue(reviewer.judge(node(10), node(11), "D4").get().isEmpty());
    assertTrue(engine.calls.isEmpty());
    assertFalse(reviewer.isJudging());
  }

  @Test
  void closingDropsATurnReviewAlreadyQueuedForTheEventThread() {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);
    List<Runnable> queued = new ArrayList<>();
    MoveReviewer delayed =
        new MoveReviewer(
            engine, ignored -> true, () -> changes.add("changed"), queued::add, worker);
    delayed.playerToMove(turn);
    assertEquals(1, queued.size());
    assertNull(delayed.currentReview(turn), "the event owner has not accepted the result yet");

    delayed.close();
    queued.forEach(Runnable::run);

    assertTrue(changes.isEmpty(), "a closed coach must not notify a detached or replacement UI");
    assertNull(delayed.currentReview(turn));
    assertArrayEquals(new double[0], delayed.winChances());
  }

  @Test
  void closingDropsAJudgementAlreadyQueuedForTheEventThread() throws Exception {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);
    List<Runnable> queued = new ArrayList<>();
    MoveReviewer delayed =
        new MoveReviewer(
            engine, ignored -> true, () -> changes.add("changed"), queued::add, worker);
    var completed = delayed.judge(turn, node(11), "D4");
    assertTrue(completed.get().isPresent(), "the worker result still completes independently");
    assertEquals(1, queued.size());
    assertTrue(delayed.isJudging());

    delayed.close();
    queued.forEach(Runnable::run);

    assertTrue(changes.isEmpty(), "late judgement cannot republish closed-session state");
    assertTrue(delayed.feedback().isEmpty());
    assertFalse(delayed.isJudging());
  }

  @Test
  void queuedReviewsStillPublishWhileTheReviewerIsOpen() {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);
    List<Runnable> queued = new ArrayList<>();
    MoveReviewer delayed =
        new MoveReviewer(
            engine, ignored -> true, () -> changes.add("changed"), queued::add, worker);
    try {
      delayed.playerToMove(turn);
      assertTrue(changes.isEmpty());
      queued.forEach(Runnable::run);
      assertEquals(List.of("changed"), changes);
      assertEquals(TURN, delayed.currentReview(turn));
    } finally {
      delayed.close();
    }
  }

  @Test
  void closingKeepsFeedbackThatWasAlreadyPublished() throws Exception {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);
    MoveFeedback feedback = reviewer.judge(turn, node(11), "D4").get().orElseThrow();

    reviewer.close();

    assertEquals(List.of(feedback), reviewer.feedback());
    assertEquals(Optional.of(feedback), reviewer.latest());
  }

  @Test
  void closingKeepsTheTurnReviewThatWasAlreadyPublished() {
    BoardHistoryNode turn = node(10);
    engine.reviews.put(turn, TURN);
    reviewer.playerToMove(turn);
    double[] chances = reviewer.winChances();

    reviewer.close();

    assertEquals(TURN, reviewer.currentReview(turn));
    assertArrayEquals(chances, reviewer.winChances());
  }

  private static final class ScriptedEngine implements MoveReviewer.Engine {
    final Map<BoardHistoryNode, PositionReview> reviews = new IdentityHashMap<>();
    final List<String> calls = new ArrayList<>();
    Duration lastTime;

    @Override
    public Optional<PositionReview> review(
        BoardHistoryNode position, int maxVisits, Duration maxTime, Duration timeout) {
      calls.add(position.getData().moveNumber + "@" + maxVisits);
      lastTime = maxTime;
      return Optional.ofNullable(reviews.get(position));
    }
  }

  /** Runs each task at once on the caller's thread. */
  private static final class DirectExecutor extends AbstractExecutorService {
    private boolean shutdown;

    @Override
    public void execute(Runnable command) {
      if (shutdown) {
        throw new RejectedExecutionException("closed");
      }
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
