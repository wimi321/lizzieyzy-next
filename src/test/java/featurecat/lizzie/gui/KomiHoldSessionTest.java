package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class KomiHoldSessionTest {
  private static final int INITIAL_DELAY_MS = 40;
  private static final int REPEAT_DELAY_MS = 20;

  @Test
  void shortClickLeavesHoldIdleSoActionListenerIsTheOnlyStep() throws Exception {
    AtomicInteger clicks = new AtomicInteger();
    AtomicInteger holdSteps = new AtomicInteger();
    JButton button = new JButton();
    button.addActionListener(event -> clicks.incrementAndGet());
    KomiHoldSession.attach(button, holdSteps::incrementAndGet, INITIAL_DELAY_MS, REPEAT_DELAY_MS);

    SwingUtilities.invokeAndWait(
        () -> {
          button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED));
          button.dispatchEvent(mouse(button, MouseEvent.MOUSE_RELEASED));
          button.doClick(0);
        });
    Thread.sleep(INITIAL_DELAY_MS + REPEAT_DELAY_MS * 3L);
    flushEdt();

    assertEquals(1, clicks.get(), "one click must change komi exactly once via ActionListener");
    assertEquals(0, holdSteps.get(), "a short click must not start automatic hold steps");
  }

  @Test
  void missedMouseReleasedStopsWhenPointerLeaves() throws Exception {
    AtomicInteger holdSteps = new AtomicInteger();
    JButton button = new JButton();
    KomiHoldSession session =
        KomiHoldSession.attach(
            button, holdSteps::incrementAndGet, INITIAL_DELAY_MS, REPEAT_DELAY_MS);

    SwingUtilities.invokeAndWait(
        () -> button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED)));
    awaitAtLeast(holdSteps, 1);
    assertTrue(session.isHolding(), "hold must be active before the missed-release fallback");

    SwingUtilities.invokeAndWait(
        () -> button.dispatchEvent(mouse(button, MouseEvent.MOUSE_EXITED)));
    flushEdt();
    int frozen = holdSteps.get();
    assertFalse(session.isHolding(), "pointer leave must stop a hold that never saw mouseReleased");

    Thread.sleep(REPEAT_DELAY_MS * 4L);
    flushEdt();
    assertEquals(
        frozen,
        holdSteps.get(),
        "missed mouseReleased must not leave automatic komi steps running");
  }

  @Test
  void disableDuringHoldStopsRepeatingWithoutMouseReleased() throws Exception {
    AtomicInteger holdSteps = new AtomicInteger();
    JButton button = new JButton();
    KomiHoldSession session =
        KomiHoldSession.attach(
            button, holdSteps::incrementAndGet, INITIAL_DELAY_MS, REPEAT_DELAY_MS);

    SwingUtilities.invokeAndWait(
        () -> button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED)));
    awaitAtLeast(holdSteps, 1);
    assertTrue(session.isHolding());

    SwingUtilities.invokeAndWait(() -> button.setEnabled(false));
    flushEdt();
    int frozen = holdSteps.get();
    assertFalse(session.isHolding(), "disable during hold must stop the session");

    Thread.sleep(REPEAT_DELAY_MS * 4L);
    flushEdt();
    assertEquals(
        frozen, holdSteps.get(), "a disabled control must not keep stepping komi by itself");
  }

  @Test
  void focusLostStopsHoldWhenMouseReleasedIsMissed() throws Exception {
    AtomicInteger holdSteps = new AtomicInteger();
    JButton button = new JButton();
    KomiHoldSession session =
        KomiHoldSession.attach(
            button, holdSteps::incrementAndGet, INITIAL_DELAY_MS, REPEAT_DELAY_MS);

    SwingUtilities.invokeAndWait(
        () -> button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED)));
    awaitAtLeast(holdSteps, 1);

    SwingUtilities.invokeAndWait(
        () -> {
          FocusEvent lost = new FocusEvent(button, FocusEvent.FOCUS_LOST);
          for (FocusListener listener : button.getFocusListeners()) {
            listener.focusLost(lost);
          }
        });
    flushEdt();
    int frozen = holdSteps.get();
    assertFalse(session.isHolding());

    Thread.sleep(REPEAT_DELAY_MS * 4L);
    flushEdt();
    assertEquals(frozen, holdSteps.get(), "focus loss must not leave automatic hold steps running");
  }

  @Test
  void aLaterPressDoesNotLeaveTwoRepeaters() throws Exception {
    AtomicInteger holdSteps = new AtomicInteger();
    JButton button = new JButton();
    KomiHoldSession session =
        KomiHoldSession.attach(
            button, holdSteps::incrementAndGet, INITIAL_DELAY_MS, REPEAT_DELAY_MS);

    SwingUtilities.invokeAndWait(
        () -> {
          button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED));
          button.dispatchEvent(mouse(button, MouseEvent.MOUSE_PRESSED));
        });
    awaitAtLeast(holdSteps, 1);
    assertTrue(session.isHolding());

    SwingUtilities.invokeAndWait(
        () -> button.dispatchEvent(mouse(button, MouseEvent.MOUSE_RELEASED)));
    flushEdt();
    int frozen = holdSteps.get();
    assertFalse(session.isHolding());

    Thread.sleep(REPEAT_DELAY_MS * 4L);
    flushEdt();
    assertEquals(frozen, holdSteps.get(), "only one hold task may remain after release");
  }

  private static MouseEvent mouse(JButton button, int id) {
    return new MouseEvent(
        button, id, System.currentTimeMillis(), 0, 0, 0, 1, false, MouseEvent.BUTTON1);
  }

  private static void awaitAtLeast(AtomicInteger value, int minimum) throws Exception {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (value.get() < minimum) {
      if (System.nanoTime() > deadline) {
        fail("timed out waiting for " + minimum + " hold steps, had " + value.get());
      }
      Thread.sleep(5L);
    }
  }

  private static void flushEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }
}
