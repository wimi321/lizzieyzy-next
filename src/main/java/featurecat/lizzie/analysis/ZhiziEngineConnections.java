package featurecat.lizzie.analysis;

import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Zhizi-only client shutdown. The manager's selection owner serializes preparation. */
public final class ZhiziEngineConnections {
  public enum State {
    DISCONNECTED,
    CONNECTED,
    BUSY
  }

  private ZhiziEngineConnections() {}

  static boolean includesZhizi(Leelaz... engines) {
    for (Leelaz engine : engines)
      if (engine != null && RemoteComputeConfig.isZhiziEngineCommand(engine.engineCommand))
        return true;
    return false;
  }

  static List<Leelaz> targets(List<Leelaz> catalog, Leelaz... selected) {
    LinkedHashSet<Leelaz> candidates = new LinkedHashSet<>();
    if (catalog != null) candidates.addAll(catalog);
    for (Leelaz engine : selected) candidates.add(engine);
    List<Leelaz> result = new ArrayList<>();
    for (Leelaz engine : candidates) {
      if (engine != null && RemoteComputeConfig.isZhiziEngineCommand(engine.engineCommand))
        result.add(engine);
    }
    return result;
  }

  static State state(List<Leelaz> targets, boolean transition) {
    if (targets.isEmpty()) return State.DISCONNECTED;
    if (transition || targets.stream().anyMatch(Leelaz::zhiziConnectionBusy)) return State.BUSY;
    return targets.stream().anyMatch(engine -> !engine.isProcessDead())
        ? State.CONNECTED
        : State.DISCONNECTED;
  }

  static Shutdown prepare(List<Leelaz> targets, boolean transition, Consumer<Leelaz> deselect) {
    if (state(targets, transition) == State.BUSY) return null;
    Shutdown shutdown = new Shutdown();
    try {
      for (Leelaz engine : targets) {
        Leelaz.ExclusiveGtpLifecycleReservation reservation = engine.reserveZhiziDisconnect();
        if (reservation == null) {
          shutdown.result = State.BUSY;
          return shutdown;
        }
        shutdown.reservations.add(() -> engine.releaseZhiziDisconnect(reservation));
      }
      for (Leelaz engine : targets) {
        engine.markZhiziDisconnected();
        Object incarnation = engine.captureEngineIncarnationFence();
        if (incarnation != null) {
          Leelaz.ExactNormalQuitClaim claim =
              engine.claimNormalQuitIfCurrentIncarnation(incarnation);
          if (claim != null) shutdown.closes.add(claim::finish);
        }
        deselect.accept(engine);
      }
      return shutdown;
    } catch (RuntimeException | Error failure) {
      shutdown.preparationFailure = failure;
      return shutdown;
    }
  }

  static final class Shutdown {
    State result = State.DISCONNECTED;
    private Throwable preparationFailure;
    private final List<Runnable> reservations = new ArrayList<>();
    private final List<Runnable> closes = new ArrayList<>();

    void finish() {
      Throwable failure = preparationFailure;
      try {
        for (Runnable close : closes) {
          try {
            close.run();
          } catch (RuntimeException | Error problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
          }
        }
      } finally {
        failure = closeReservations(failure);
      }
      if (failure instanceof RuntimeException problem) throw problem;
      if (failure instanceof Error problem) throw problem;
    }

    private Throwable closeReservations(Throwable failure) {
      for (int i = reservations.size() - 1; i >= 0; i--) {
        try {
          reservations.get(i).run();
        } catch (RuntimeException | Error problem) {
          if (failure == null) failure = problem;
          else if (failure != problem) failure.addSuppressed(problem);
        }
      }
      reservations.clear();
      return failure;
    }
  }
}
