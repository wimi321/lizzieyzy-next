package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.ZhiziEngineConnections.State;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ZhiziConnectionControlsTest {
  @Test
  void disconnectedClientCanCancelItsPendingStart() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.backend.state = State.DISCONNECTED;
          f.pending = true;
          f.controls.refresh();
          assertTrue(f.button().isEnabled());
          f.button().doClick(0);
          assertFalse(f.pending);
          assertEquals(1, f.cancels);
          f.finish(State.DISCONNECTED);
          assertFalse(f.button().isEnabled());
        });
  }

  @Test
  void repeatedDisconnectIsSingleFlightAndBusyIsNotSuccess() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.button().doClick(0);
          f.button().doClick(0);
          assertEquals(1, f.completions.size());
          assertEquals(List.of(true), f.busy);
          f.finish(State.BUSY);
          assertTrue(f.label().getText().contains("Finish the active task"));
          assertTrue(f.button().isEnabled());
          assertEquals(List.of(true, false), f.busy);
        });
  }

  @Test
  void dismissedDialogDoesNotPublishLateResultOrCancelAcceptedDisconnect() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.button().doClick(0);
          String before = f.label().getText();
          f.controls.close();
          f.finish(State.DISCONNECTED);
          assertEquals(before, f.label().getText());
          assertEquals(List.of(true), f.busy);
          assertEquals(1, f.backend.closes);
        });
  }

  @Test
  void stateRefreshReflectsRetainedConnectionWithoutCurrentEngineOrLogin() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          assertTrue(f.label().getText().contains("connection retained"));
          f.backend.state = State.BUSY;
          f.controls.refresh();
          assertTrue(f.label().getText().contains("task or connection change"));
          f.backend.state = State.DISCONNECTED;
          f.controls.refresh();
          assertTrue(f.label().getText().contains("engines: disconnected"));
          assertFalse(f.button().isEnabled());
        });
  }

  @Test
  void failedCloseDoesNotClaimDisconnected() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.button().doClick(0);
          f.completions.get(0).accept(null, new IllegalStateException("fixture failure"));
          assertTrue(f.label().getText().contains("Disconnect failed"));
          assertTrue(f.button().isEnabled());
        });
  }

  private static class Fixture {
    final FakeBackend backend = new FakeBackend();
    final List<BiConsumer<State, Throwable>> completions = new ArrayList<>();
    final List<Boolean> busy = new ArrayList<>();
    boolean pending;
    int cancels;
    final ZhiziConnectionControls controls =
        new ZhiziConnectionControls(
            () -> backend,
            () -> {
              cancels++;
              pending = false;
            },
            () -> pending,
            busy::add,
            (key, fallback) -> fallback,
            (target, completion) -> completions.add(completion));

    JButton button() {
      return find(controls, JButton.class);
    }

    JLabel label() {
      return find(controls, JLabel.class);
    }

    void finish(State result) {
      backend.state = result;
      completions.get(0).accept(backend.disconnect(), null);
    }
  }

  private static class FakeBackend implements ZhiziConnectionControls.Backend {
    State state = State.CONNECTED;
    int closes;

    public State state() {
      return state;
    }

    public State disconnect() {
      closes++;
      return state;
    }
  }

  private static <T> T find(Container root, Class<T> type) {
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) return type.cast(child);
      if (child instanceof Container container) {
        T found = find(container, type);
        if (found != null) return found;
      }
    }
    return null;
  }
}
