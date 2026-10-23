package featurecat.lizzie.gui;

import featurecat.lizzie.logging.LogCategories;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 边下边提醒: judges each of the player's moves by the rank-aware win chance.
 *
 * <p>Whenever it is the player's turn, the coach engine reviews the position while the player
 * thinks. A move is then usually judged from that review as soon as it is played; a move the search
 * hardly looked at needs a second, shorter review of the position after it. Reviews run one at a
 * time on their own thread, in the order they were asked for. Everything else here belongs to the
 * event thread.
 */
final class MoveReviewer {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.APP);

  /** About a second on a mid-range GPU; the time limit keeps slow engines to the same budget. */
  static final int TURN_VISITS = 400;

  static final Duration TURN_TIME = Duration.ofMillis(1500);
  static final int AFTER_VISITS = 200;
  static final Duration AFTER_TIME = Duration.ofMillis(800);
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  /** Reviews one position on the coach engine; empty when the engine could not. */
  interface Engine {
    Optional<PositionReview> review(
        BoardHistoryNode position, int maxVisits, Duration maxTime, Duration timeout);
  }

  private record Judged(BoardHistoryNode after, MoveFeedback feedback) {}

  private final Engine engine;
  private final Predicate<BoardHistoryNode> inGame;
  private final Runnable changed;
  private final Consumer<Runnable> eventThread;
  private final ExecutorService worker;

  /** Finished reviews by position. Only the worker touches it. */
  private final Map<BoardHistoryNode, PositionReview> reviews = new IdentityHashMap<>();

  private final List<Judged> judged = new ArrayList<>();
  private BoardHistoryNode turn;
  private PositionReview turnReview;
  private BoardHistoryNode judging;
  private boolean closed;

  /**
   * @param inGame whether a position is still part of the game, so that feedback on a move taken
   *     back meanwhile is dropped
   * @param changed called on the event thread when there is something new to show
   */
  MoveReviewer(Engine engine, Predicate<BoardHistoryNode> inGame, Runnable changed) {
    this(engine, inGame, changed, SwingUtilities::invokeLater, newWorker());
  }

  MoveReviewer(
      Engine engine,
      Predicate<BoardHistoryNode> inGame,
      Runnable changed,
      Consumer<Runnable> eventThread,
      ExecutorService worker) {
    this.engine = engine;
    this.inGame = inGame;
    this.changed = changed;
    this.eventThread = eventThread;
    this.worker = worker;
  }

  private static ExecutorService newWorker() {
    return Executors.newSingleThreadExecutor(
        task -> {
          Thread thread = new Thread(task, "coach-move-review");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** It is the player's turn in {@code position}: review it while they think. */
  void playerToMove(BoardHistoryNode position) {
    turn = position;
    turnReview = null;
    submit(
        () -> {
          Optional<PositionReview> review = reviewOf(position, TURN_VISITS, TURN_TIME);
          eventThread.accept(
              () -> {
                if (!closed && turn == position) {
                  turnReview = review.orElse(null);
                  changed.run();
                }
              });
        });
  }

  /**
   * The player played {@code move} in {@code before}, which led to {@code after}.
   *
   * @return completes on the worker thread with the judgement, or empty when the engine could not
   *     judge the move
   */
  CompletableFuture<Optional<MoveFeedback>> judge(
      BoardHistoryNode before, BoardHistoryNode after, String move) {
    CompletableFuture<Optional<MoveFeedback>> result = new CompletableFuture<>();
    int moveNumber = after.getData().moveNumber;
    turn = null;
    turnReview = null;
    judging = after;
    boolean submitted =
        submit(
            () -> {
              Optional<MoveFeedback> feedback = Optional.empty();
              try {
                Optional<PositionReview> review = reviewOf(before, TURN_VISITS, TURN_TIME);
                if (review.isPresent()) {
                  PositionReview afterReview =
                      MoveFeedback.needsPositionAfter(review.get(), move)
                          ? reviewOf(after, AFTER_VISITS, AFTER_TIME).orElse(null)
                          : null;
                  feedback = MoveFeedback.judge(moveNumber, move, review.get(), afterReview);
                }
              } catch (RuntimeException failure) {
                LOG.warn("Could not judge the move {} {}", moveNumber, move, failure);
              } finally {
                Optional<MoveFeedback> done = feedback;
                eventThread.accept(() -> record(after, done));
                result.complete(done);
              }
            });
    if (!submitted) {
      judging = null;
      result.complete(Optional.empty());
    }
    return result;
  }

  private void record(BoardHistoryNode after, Optional<MoveFeedback> feedback) {
    if (closed) return;
    if (judging == after) {
      judging = null;
    }
    if (feedback.isPresent() && inGame.test(after)) {
      int moveNumber = feedback.get().moveNumber();
      judged.removeIf(entry -> entry.feedback().moveNumber() >= moveNumber);
      judged.add(new Judged(after, feedback.get()));
    }
    changed.run();
  }

  /** Moves were taken back down to {@code live}: forget what was said about the moves after it. */
  void takenBack(BoardHistoryNode live) {
    int moveNumber = live.getData().moveNumber;
    judged.removeIf(entry -> entry.feedback().moveNumber() > moveNumber);
    if (judging != null && judging.getData().moveNumber > moveNumber) {
      judging = null;
    }
  }

  /** The judged moves, in the order they were played. */
  List<MoveFeedback> feedback() {
    List<MoveFeedback> moves = new ArrayList<>(judged.size());
    for (Judged entry : judged) {
      moves.add(entry.feedback());
    }
    return moves;
  }

  Optional<MoveFeedback> latest() {
    return judged.isEmpty()
        ? Optional.empty()
        : Optional.of(judged.get(judged.size() - 1).feedback());
  }

  /** The review of {@code position} if the player is to move there and it is done, else null. */
  PositionReview currentReview(BoardHistoryNode position) {
    return turn == position ? turnReview : null;
  }

  /**
   * The moves that swung the game, its 恶手. 问题手 are counted in the history but are not key: kyu
   * players make five to fifteen a game.
   */
  int keyMistakes() {
    return count(MoveVerdict.BLUNDER);
  }

  /**
   * The game's {@code count} biggest mistakes, worst rating and largest drop first when choosing,
   * listed in the order they were played.
   */
  List<MoveFeedback> worstMistakes(int count) {
    return judged.stream()
        .map(Judged::feedback)
        .filter(feedback -> feedback.verdict().isMistake())
        .sorted(
            java.util.Comparator.comparing(MoveFeedback::verdict)
                .reversed()
                .thenComparingDouble(MoveFeedback::winChange))
        .limit(count)
        .sorted(java.util.Comparator.comparingInt(MoveFeedback::moveNumber))
        .toList();
  }

  /** True from the player's move until its judgement arrives. */
  boolean isJudging() {
    return judging != null;
  }

  int count(MoveVerdict verdict) {
    int count = 0;
    for (Judged entry : judged) {
      if (entry.feedback().verdict() == verdict) {
        count++;
      }
    }
    return count;
  }

  /**
   * The player's rank-aware win chance in percent, oldest first: before and after each judged move,
   * then in the position the player is to move in once it is reviewed.
   */
  double[] winChances() {
    List<Double> values = new ArrayList<>();
    for (Judged entry : judged) {
      values.add(entry.feedback().winBefore() * 100.0);
      values.add(entry.feedback().winAfter() * 100.0);
    }
    PositionReview current = turnReview;
    if (current != null && current.best().isPresent()) {
      values.add(
          RankAwareWinrate.of(current.best().get().scoreLead(), current.scoreStdev()) * 100.0);
    }
    double[] chances = new double[values.size()];
    for (int i = 0; i < chances.length; i++) {
      chances[i] = values.get(i);
    }
    return chances;
  }

  void close() {
    // The event-thread owner also rejects results already queued before worker interruption.
    closed = true;
    judging = null;
    worker.shutdownNow();
  }

  private boolean submit(Runnable task) {
    try {
      worker.execute(task);
      return true;
    } catch (RejectedExecutionException closed) {
      return false;
    }
  }

  private Optional<PositionReview> reviewOf(
      BoardHistoryNode position, int maxVisits, Duration maxTime) {
    PositionReview cached = reviews.get(position);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<PositionReview> review = engine.review(position, maxVisits, maxTime, TIMEOUT);
    review.ifPresent(found -> reviews.put(position, found));
    return review;
  }
}
