package featurecat.lizzie.analysis;

import java.util.ArrayDeque;

/**
 * Runs one engine game's runtime search-state updates (komi, breadth) one at a time.
 *
 * <p>Each update stops both searches and then waits until it is the game's only operation in
 * flight. Two updates on separate workers would each wait for the other's operation lease, so this
 * runner is the single owner of those updates: it holds one lease and runs queued updates in
 * submission order. An update queued behind another keeps its own barrier (its pending state stops
 * the search from resuming in between).
 */
final class EngineGameSearchUpdates {
  private final EngineManager.EngineGameOwnerTransaction owner;

  /** Guarded by this. */
  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

  /** Guarded by this; true while a worker owns the queue. */
  private boolean running;

  EngineGameSearchUpdates(EngineManager.EngineGameOwnerTransaction owner) {
    this.owner = owner;
  }

  /** Queues one update; returns false when no worker could be scheduled for the game. */
  boolean submit(String name, Runnable update) {
    synchronized (this) {
      queue.add(update);
      if (running) return true;
      running = true;
    }
    if (owner.manager.dispatchEngineGameWorker(owner, name, this::drain)) {
      return true;
    }
    synchronized (this) {
      running = false;
      queue.clear();
    }
    return false;
  }

  private void drain() {
    boolean finished = false;
    try {
      while (true) {
        Runnable next;
        synchronized (this) {
          next = queue.poll();
          if (next == null) {
            running = false;
            finished = true;
            return;
          }
        }
        next.run();
      }
    } finally {
      if (!finished) {
        // A failed update fails the game; later updates belong to nobody.
        synchronized (this) {
          running = false;
          queue.clear();
        }
      }
    }
  }
}
