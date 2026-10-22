package featurecat.lizzie.analysis;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Sends one confirmed command to each listed engine-game participant and waits for all of them. */
final class EngineGameParticipantCommands {
  private EngineGameParticipantCommands() {}

  /**
   * One participant command. A null command is a stop fence; {@code check} receives the response
   * payload and returns a failure detail, or null to accept it.
   */
  record Leg(
      String label,
      Leelaz engine,
      Object incarnation,
      String command,
      Function<String, String> check) {}

  /** Confirms every leg; throws the first failure. */
  static void confirmAll(EngineManager.EngineGameOwnerTransaction owner, List<Leg> legs)
      throws InterruptedException {
    for (String failure : confirm(owner, legs, true)) {
      if (failure != null) {
        throw new IllegalStateException(failure);
      }
    }
  }

  /**
   * Sends every leg at once and returns each leg's failure detail (null when confirmed). With
   * {@code failFast} the first failure ends the wait and unsettled legs report nothing. An owner
   * that stops being the current transaction ends the wait with an exception; ownerless legs (batch
   * restore) are bounded by each command's own response timeout.
   */
  static String[] confirm(
      EngineManager.EngineGameOwnerTransaction owner, List<Leg> legs, boolean failFast)
      throws InterruptedException {
    String[] failures = new String[legs.size()];
    CountDownLatch completed = new CountDownLatch(legs.size());
    AtomicBoolean failed = new AtomicBoolean();
    for (int i = 0; i < legs.size(); i++) {
      int index = i;
      Leg leg = legs.get(i);
      leg.engine()
          .confirmEngineGameCommand(
              owner,
              leg.incarnation(),
              leg.command(),
              leg.check(),
              completed::countDown,
              detail -> {
                failures[index] = leg.label() + ": " + detail;
                failed.set(true);
                completed.countDown();
              });
    }
    while (!completed.await(25L, TimeUnit.MILLISECONDS)) {
      if (failFast && failed.get()) {
        break;
      }
      if (owner != null && !EngineManager.isCurrentEngineGameTransaction(owner)) {
        throw new IllegalStateException(
            EngineGameParameterSession.text("EngineGameParameters.transactionLost"));
      }
    }
    return failures;
  }
}
