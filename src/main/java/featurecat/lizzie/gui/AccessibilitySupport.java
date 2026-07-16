package featurecat.lizzie.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Shared Swing accessibility helpers that do not alter the normal visual layout. */
public final class AccessibilitySupport {
  private static final String EXPLICIT_NAME = "lizzie.a11y.explicitName";
  private static final String EXPLICIT_DESCRIPTION = "lizzie.a11y.explicitDescription";
  private static final int LOCALIZED_CONTROL_WIDTH_PADDING = 12;

  private AccessibilitySupport() {}

  public static <T extends JComponent> T named(T component, String name, String description) {
    if (component == null) {
      return null;
    }
    AccessibleContext context = component.getAccessibleContext();
    String cleanName = clean(name);
    String cleanDescription = clean(description);
    component.putClientProperty(EXPLICIT_NAME, cleanName);
    component.putClientProperty(EXPLICIT_DESCRIPTION, cleanDescription);
    if (context != null) {
      context.setAccessibleName(cleanName);
      context.setAccessibleDescription(cleanDescription);
    }
    return component;
  }

  public static <T extends AbstractButton> T button(T button, String name, String description) {
    named(button, name, description);
    if (button != null) {
      button
          .getInputMap(JComponent.WHEN_FOCUSED)
          .put(KeyStroke.getKeyStroke("pressed ENTER"), "pressed");
      button
          .getInputMap(JComponent.WHEN_FOCUSED)
          .put(KeyStroke.getKeyStroke("released ENTER"), "released");
      if (button.getToolTipText() == null || button.getToolTipText().isBlank()) {
        button.setToolTipText(clean(description).isEmpty() ? clean(name) : clean(description));
      }
    }
    return button;
  }

  static int localizedControlWidth(AbstractButton button, int minimumWidth) {
    if (button == null) {
      return Math.max(0, minimumWidth);
    }
    return Math.max(
        minimumWidth, button.getPreferredSize().width + LOCALIZED_CONTROL_WIDTH_PADDING);
  }

  public static void labelFor(JLabel label, JComponent field, String description) {
    if (label == null || field == null) {
      return;
    }
    label.setLabelFor(field);
    named(label, label.getText(), description);
    named(field, label.getText(), description);
  }

  public static void progress(JProgressBar progressBar, String name, String description) {
    named(progressBar, name, description);
  }

  public static void applyToTree(Component root) {
    if (root == null) {
      return;
    }
    if (root instanceof AbstractButton) {
      AbstractButton button = (AbstractButton) root;
      AccessibleContext context = button.getAccessibleContext();
      String visibleText = clean(button.getText());
      String inferredName =
          isSymbolOnly(visibleText)
              ? firstNonBlank(button.getToolTipText(), visibleText)
              : firstNonBlank(visibleText, button.getToolTipText());
      String name =
          firstNonBlank(
              (String) button.getClientProperty(EXPLICIT_NAME),
              isSymbolOnly(visibleText)
                  ? inferredName
                  : firstNonBlank(
                      context == null ? null : context.getAccessibleName(), inferredName));
      String description =
          firstNonBlank(
              (String) button.getClientProperty(EXPLICIT_DESCRIPTION),
              firstNonBlank(
                  context == null ? null : context.getAccessibleDescription(),
                  firstNonBlank(button.getToolTipText(), name)));
      if (!name.isBlank()) {
        button(button, name, description);
      }
    } else if (root instanceof JProgressBar) {
      JProgressBar progressBar = (JProgressBar) root;
      String name = firstNonBlank(progressBar.getString(), progressBar.getToolTipText());
      if (!name.isBlank()) {
        progress(progressBar, name, firstNonBlank(progressBar.getToolTipText(), name));
      }
    } else if (root instanceof JComponent) {
      JComponent component = (JComponent) root;
      AccessibleContext context = component.getAccessibleContext();
      if (context != null
          && (context.getAccessibleName() == null || context.getAccessibleName().isBlank())
          && component.getToolTipText() != null
          && !component.getToolTipText().isBlank()) {
        named(component, component.getToolTipText(), component.getToolTipText());
      }
    }
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        applyToTree(child);
      }
    }
  }

  public static void installWindowFocusCycling(
      Window window, JComponent board, JComponent... focusZones) {
    if (window == null || board == null || focusZones == null || focusZones.length == 0) {
      return;
    }
    List<JComponent> zones = new ArrayList<>(Arrays.asList(focusZones));
    if (!zones.contains(board)) {
      zones.add(Math.min(2, zones.size()), board);
    }
    KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    KeyEventDispatcher dispatcher =
        event -> {
              if (event.getID() != KeyEvent.KEY_PRESSED || !window.isDisplayable()) {
                return false;
              }
              Component focusOwner =
                  KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
              Window activeWindow =
                  focusOwner == null
                      ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()
                      : SwingUtilities.getWindowAncestor(focusOwner);
              if (activeWindow != window) {
                return false;
              }
              if (event.getKeyCode() == KeyEvent.VK_F6) {
                moveToZone(
                    zones,
                    focusOwner,
                    event.isShiftDown() ? -1 : 1,
                    event.isShiftDown());
                event.consume();
                return true;
              }
              if (event.getKeyCode() == KeyEvent.VK_ESCAPE
                  && focusOwner instanceof JComponent
                  && Boolean.TRUE.equals(
                      ((JComponent) focusOwner).getClientProperty("lizzie.a11y.dynamicFocus"))) {
                releaseDynamicFocus(focusOwner);
                board.setFocusable(true);
                board.requestFocusInWindow();
                event.consume();
                return true;
              }
              if (event.getKeyCode() == KeyEvent.VK_TAB
                  && focusOwner instanceof JComponent
                  && Boolean.TRUE.equals(
                      ((JComponent) focusOwner).getClientProperty("lizzie.a11y.dynamicFocus"))) {
                cycleWithinZone(zones, focusOwner, event.isShiftDown() ? -1 : 1);
                event.consume();
                return true;
              }
              return false;
            };
    focusManager.addKeyEventDispatcher(dispatcher);
    window.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            focusManager.removeKeyEventDispatcher(dispatcher);
          }
        });
  }

  public static void installEscapeToClose(JRootPane rootPane, Window window) {
    installEscapeAction(rootPane, window, () -> window.setVisible(false));
  }

  public static void installEscapeAction(JRootPane rootPane, Window window, Runnable action) {
    if (rootPane == null || window == null) {
      return;
    }
    rootPane
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke("ESCAPE"), "accessible-close-window");
    rootPane
        .getActionMap()
        .put(
            "accessible-close-window",
            new javax.swing.AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent event) {
                if (action != null) {
                  action.run();
                }
              }
            });
  }

  public static void announce(JComponent component, String oldText, String newText) {
    if (component == null || oldText == null || newText == null || oldText.equals(newText)) {
      return;
    }
    AccessibleContext context = component.getAccessibleContext();
    if (context != null) {
      context.firePropertyChange(
          AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY, oldText, newText);
    }
  }

  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replaceAll("(?i)<br\\s*/?>", " ")
        .replaceAll("<[^>]+>", "")
        .replace('&', ' ')
        .trim();
  }

  private static String firstNonBlank(String first, String second) {
    String cleanFirst = clean(first);
    return cleanFirst.isBlank() ? clean(second) : cleanFirst;
  }

  private static boolean isSymbolOnly(String value) {
    String cleaned = clean(value);
    return !cleaned.isEmpty()
        && cleaned.codePoints().noneMatch(codePoint -> Character.isLetterOrDigit(codePoint));
  }

  private static void moveToZone(
      List<JComponent> zones, Component focusOwner, int direction, boolean reverseWithinZone) {
    int current = zoneIndex(zones, focusOwner);
    if (current < 0) {
      current = direction > 0 ? -1 : 0;
    }
    for (int offset = 1; offset <= zones.size(); offset++) {
      int next = Math.floorMod(current + direction * offset, zones.size());
      List<JComponent> candidates = focusCandidates(zones.get(next));
      if (candidates.isEmpty()) {
        continue;
      }
      JComponent target =
          reverseWithinZone ? candidates.get(candidates.size() - 1) : candidates.get(0);
      requestDynamicFocus(focusOwner, target);
      return;
    }
  }

  private static void cycleWithinZone(
      List<JComponent> zones, Component focusOwner, int direction) {
    int zoneIndex = zoneIndex(zones, focusOwner);
    if (zoneIndex < 0) {
      return;
    }
    List<JComponent> candidates = focusCandidates(zones.get(zoneIndex));
    int current = candidates.indexOf(focusOwner);
    if (current < 0 || candidates.isEmpty()) {
      return;
    }
    JComponent target = candidates.get(Math.floorMod(current + direction, candidates.size()));
    requestDynamicFocus(focusOwner, target);
  }

  private static int zoneIndex(List<JComponent> zones, Component component) {
    if (component != null) {
      for (int index = 0; index < zones.size(); index++) {
        JComponent zone = zones.get(index);
        if (component == zone || SwingUtilities.isDescendingFrom(component, zone)) {
          return index;
        }
      }
    }
    return -1;
  }

  private static List<JComponent> focusCandidates(JComponent zone) {
    List<JComponent> result = new ArrayList<>();
    collectFocusCandidates(zone, result);
    if (result.isEmpty() && zone.isVisible() && zone.isEnabled()) {
      result.add(zone);
    }
    return result;
  }

  private static void collectFocusCandidates(Component component, List<JComponent> result) {
    if (component == null || !component.isVisible() || !component.isEnabled()) {
      return;
    }
    if (component instanceof AbstractButton
        || component instanceof JTextComponent
        || component instanceof JComboBox
        || component instanceof JTable) {
      result.add((JComponent) component);
      return;
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        collectFocusCandidates(child, result);
      }
    }
  }

  private static void requestDynamicFocus(Component previous, JComponent target) {
    releaseDynamicFocus(previous);
    target.putClientProperty("lizzie.a11y.originalFocusable", target.isFocusable());
    if (target instanceof AbstractButton) {
      AbstractButton button = (AbstractButton) target;
      target.putClientProperty("lizzie.a11y.originalFocusPainted", button.isFocusPainted());
      button.setFocusPainted(true);
    }
    target.putClientProperty("lizzie.a11y.dynamicFocus", Boolean.TRUE);
    target.setFocusable(true);
    target.requestFocusInWindow();
  }

  private static void releaseDynamicFocus(Component component) {
    if (!(component instanceof JComponent)) {
      return;
    }
    JComponent managed = (JComponent) component;
    if (!Boolean.TRUE.equals(managed.getClientProperty("lizzie.a11y.dynamicFocus"))) {
      return;
    }
    Object originalFocusable = managed.getClientProperty("lizzie.a11y.originalFocusable");
    if (originalFocusable instanceof Boolean) {
      managed.setFocusable((Boolean) originalFocusable);
    }
    if (managed instanceof AbstractButton) {
      Object originalFocusPainted = managed.getClientProperty("lizzie.a11y.originalFocusPainted");
      if (originalFocusPainted instanceof Boolean) {
        ((AbstractButton) managed).setFocusPainted((Boolean) originalFocusPainted);
      }
    }
    managed.putClientProperty("lizzie.a11y.dynamicFocus", null);
  }
}
