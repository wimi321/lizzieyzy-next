package featurecat.lizzie.gui;

import java.awt.IllegalComponentStateException;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Single repeating hold session for toolbar komi +/-.
 *
 * <p>Runs on the Swing event thread so a missed {@code mouseReleased} cannot leave a background
 * {@code while (pressed)} loop stepping komi. At most one timer is active; it is cancelled before
 * another press starts, and it stops on release, pointer leave, focus loss, or disable.
 */
final class KomiHoldSession {
  static final int INITIAL_DELAY_MS = 400;
  static final int REPEAT_DELAY_MS = 150;

  private final AbstractButton control;
  private final Runnable holdStep;
  private final int initialDelayMs;
  private final int repeatDelayMs;
  private volatile boolean holding;
  private boolean focusManagerBound;
  private Timer timer;
  private Window trackedWindow;
  private final MouseAdapter mouseAdapter =
      new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent event) {
          if (!SwingUtilities.isLeftMouseButton(event)) {
            return;
          }
          startHold();
        }

        @Override
        public void mouseReleased(MouseEvent event) {
          stopHold();
        }

        @Override
        public void mouseExited(MouseEvent event) {
          if (pointerStillOverControl(event)) {
            return;
          }
          stopHold();
        }
      };
  private final FocusAdapter focusAdapter =
      new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
          stopHold();
        }
      };
  private final PropertyChangeListener enabledListener =
      event -> {
        if (!Boolean.TRUE.equals(event.getNewValue())) {
          stopHold();
        }
      };
  private final PropertyChangeListener windowEnabledListener =
      event -> {
        if (!Boolean.TRUE.equals(event.getNewValue())) {
          stopHold();
        }
      };
  private final WindowAdapter windowFocusAdapter =
      new WindowAdapter() {
        @Override
        public void windowLostFocus(WindowEvent event) {
          stopHold();
        }

        @Override
        public void windowDeactivated(WindowEvent event) {
          stopHold();
        }
      };
  private final PropertyChangeListener focusedWindowListener = this::onFocusedWindowChanged;

  static KomiHoldSession attach(AbstractButton control, Runnable holdStep) {
    return attach(control, holdStep, INITIAL_DELAY_MS, REPEAT_DELAY_MS);
  }

  static KomiHoldSession attach(
      AbstractButton control, Runnable holdStep, int initialDelayMs, int repeatDelayMs) {
    KomiHoldSession session =
        new KomiHoldSession(control, holdStep, initialDelayMs, repeatDelayMs);
    session.bind();
    return session;
  }

  private KomiHoldSession(
      AbstractButton control, Runnable holdStep, int initialDelayMs, int repeatDelayMs) {
    if (initialDelayMs < 0 || repeatDelayMs < 0) {
      throw new IllegalArgumentException("hold delays must not be negative");
    }
    this.control = Objects.requireNonNull(control, "control");
    this.holdStep = Objects.requireNonNull(holdStep, "holdStep");
    this.initialDelayMs = initialDelayMs;
    this.repeatDelayMs = repeatDelayMs;
  }

  boolean isHolding() {
    return holding;
  }

  private void bind() {
    control.addMouseListener(mouseAdapter);
    control.addFocusListener(focusAdapter);
    control.addPropertyChangeListener("enabled", enabledListener);
    control.addHierarchyListener(this::onHierarchyChanged);
    rebindWindow();
  }

  private void onHierarchyChanged(HierarchyEvent event) {
    long flags = event.getChangeFlags();
    if ((flags & (HierarchyEvent.PARENT_CHANGED | HierarchyEvent.SHOWING_CHANGED)) != 0) {
      rebindWindow();
    }
  }

  private void startHold() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::startHold);
      return;
    }
    stopHold();
    if (!canRepeat()) {
      return;
    }
    holding = true;
    bindFocusManager();
    timer =
        new Timer(
            repeatDelayMs,
            event -> {
              if (!holding || !canRepeat()) {
                stopHold();
                return;
              }
              holdStep.run();
            });
    timer.setInitialDelay(initialDelayMs);
    timer.setRepeats(true);
    timer.start();
  }

  private void stopHold() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::stopHold);
      return;
    }
    holding = false;
    if (timer != null) {
      timer.stop();
      timer = null;
    }
    unbindFocusManager();
  }

  private boolean canRepeat() {
    if (!control.isEnabled()) {
      return false;
    }
    Window window = SwingUtilities.getWindowAncestor(control);
    return window == null || window.isEnabled();
  }

  private boolean pointerStillOverControl(MouseEvent event) {
    if (control.contains(event.getPoint())) {
      return true;
    }
    if (!control.isShowing()) {
      return false;
    }
    try {
      Point screen = event.getLocationOnScreen();
      Point loc = control.getLocationOnScreen();
      Rectangle bounds = new Rectangle(loc.x, loc.y, control.getWidth(), control.getHeight());
      return bounds.contains(screen);
    } catch (IllegalComponentStateException ignored) {
      return false;
    }
  }

  private void rebindWindow() {
    Window window = SwingUtilities.getWindowAncestor(control);
    if (window == trackedWindow) {
      return;
    }
    if (trackedWindow != null) {
      trackedWindow.removePropertyChangeListener("enabled", windowEnabledListener);
      trackedWindow.removeWindowListener(windowFocusAdapter);
      trackedWindow.removeWindowFocusListener(windowFocusAdapter);
    }
    trackedWindow = window;
    if (trackedWindow != null) {
      trackedWindow.addPropertyChangeListener("enabled", windowEnabledListener);
      trackedWindow.addWindowListener(windowFocusAdapter);
      trackedWindow.addWindowFocusListener(windowFocusAdapter);
    }
  }

  private void onFocusedWindowChanged(PropertyChangeEvent event) {
    if (!holding) {
      return;
    }
    Window ancestor = SwingUtilities.getWindowAncestor(control);
    if (ancestor == null) {
      return;
    }
    Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
    if (focused == null || focused != ancestor) {
      stopHold();
    }
  }

  private void bindFocusManager() {
    if (focusManagerBound) {
      return;
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addPropertyChangeListener("focusedWindow", focusedWindowListener);
    focusManagerBound = true;
  }

  private void unbindFocusManager() {
    if (!focusManagerBound) {
      return;
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .removePropertyChangeListener("focusedWindow", focusedWindowListener);
    focusManagerBound = false;
  }
}
