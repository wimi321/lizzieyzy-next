package featurecat.lizzie;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Observable startup state used by the main window instead of modal engine progress dialogs. */
public final class EngineStartupStatus {
  public enum State {
    READY,
    CHECKING,
    NEEDS_REPAIR,
    START_FAILED
  }

  public static final class Snapshot {
    public final State state;
    public final String messageKey;
    public final String fallback;
    public final String detail;

    private Snapshot(State state, String messageKey, String fallback, String detail) {
      this.state = state;
      this.messageKey = messageKey == null ? "" : messageKey;
      this.fallback = fallback == null ? "" : fallback;
      this.detail = detail == null ? "" : detail;
    }

    public boolean isActionable() {
      return state == State.NEEDS_REPAIR || state == State.START_FAILED;
    }
  }

  private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners =
      new CopyOnWriteArrayList<>();
  private volatile Snapshot snapshot = new Snapshot(State.READY, "", "", "");

  public Snapshot snapshot() {
    return snapshot;
  }

  public void addListener(Consumer<Snapshot> listener) {
    Consumer<Snapshot> safeListener = Objects.requireNonNull(listener, "listener");
    listeners.add(safeListener);
    safeListener.accept(snapshot);
  }

  public void removeListener(Consumer<Snapshot> listener) {
    listeners.remove(listener);
  }

  public void ready() {
    update(State.READY, "", "", "");
  }

  public void checking(String messageKey, String fallback) {
    update(State.CHECKING, messageKey, fallback, "");
  }

  public void needsRepair(String messageKey, String fallback, String detail) {
    update(State.NEEDS_REPAIR, messageKey, fallback, detail);
  }

  public void failed(String messageKey, String fallback, String detail) {
    update(State.START_FAILED, messageKey, fallback, detail);
  }

  private void update(State state, String messageKey, String fallback, String detail) {
    Snapshot next = new Snapshot(state, messageKey, fallback, detail);
    snapshot = next;
    for (Consumer<Snapshot> listener : listeners) {
      listener.accept(next);
    }
  }
}
