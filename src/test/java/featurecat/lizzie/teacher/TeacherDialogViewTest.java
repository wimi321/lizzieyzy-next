package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class TeacherDialogViewTest {
  @Test
  void minimumDialogSizeKeepsModeRailReaderAndComposerUsable() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          TeacherDialogView view = new TeacherDialogView();
          view.setSize(760, 540);
          layoutTree(view);

          JComponent rail = find(view, "teacherModeRail");
          JComponent reader = find(view, "teacherReader");
          JComponent cards = find(view, "teacherContentCards");
          JComponent composer = find(view, "teacherComposer");

          assertNotNull(rail);
          assertNotNull(reader);
          assertNotNull(cards);
          assertNotNull(composer);
          assertEquals(94, rail.getWidth());
          assertTrue(reader.getWidth() >= 560, "the commentary reader remains the dominant region");
          assertTrue(cards.getHeight() >= 220, "commentary keeps meaningful reading height");
          assertFalse(overlaps(boundsIn(view, rail), boundsIn(view, reader)));
          assertFalse(overlaps(boundsIn(view, reader), boundsIn(view, composer)));
          assertTrue(view.explainNext().getY() < view.explainRange().getY());
          assertTrue(view.explainRange().getY() < view.explainWhole().getY());
        });
  }

  @Test
  void switchesBetweenIntentionalEmptyLoadingAndOutputStates() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          TeacherDialogView view = new TeacherDialogView();
          JComponent empty = find(view, "teacherEmptyState");
          JComponent output = find(view, "teacherOutputScroll");

          assertNotNull(empty);
          assertNotNull(output);
          assertTrue(empty.isVisible());
          assertFalse(output.isVisible());

          view.showLoading("Building a grounded explanation");
          assertTrue(empty.isVisible());
          assertTrue(view.emptyDetail().getText().contains("grounded"));

          view.showOutput();
          assertFalse(empty.isVisible());
          assertTrue(output.isVisible());

          view.resetEmptyTitle();
          view.showEmpty();
          assertTrue(empty.isVisible());
          assertFalse(output.isVisible());
        });
  }

  @Test
  void modeAndComposerControlsExposeStableKeyboardAndReaderNames() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          TeacherDialogView view = new TeacherDialogView();

          assertTrue(view.explainNext().isSelected());
          assertAccessible(view.explainNext());
          assertAccessible(view.explainRange());
          assertAccessible(view.explainWhole());
          assertAccessible(view.settingsButton());
          assertAccessible(view.stop());
          assertAccessible(view.ask());
          assertAccessible(view.followUp());
          assertAccessibleName(view.rangeStart());
          assertAccessibleName(view.rangeEnd());

          view.selectMode(TeacherDialogView.Mode.WHOLE);
          assertTrue(view.explainWhole().isSelected());
          assertFalse(view.explainNext().isSelected());

          view.setRunning(true);
          assertTrue(view.progressBar().isVisible());
          view.setRunning(false);
          assertFalse(view.progressBar().isVisible());
        });
  }

  @Test
  void longStatusAndModelTextCanShrinkWithoutExpandingTheReader() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          TeacherDialogView view = new TeacherDialogView();
          view.setStatus(
              "A deliberately long localized status message that must clip instead of resizing",
              TeacherDialogView.StatusTone.WARNING);
          view.setModelStatus(
              "Model: an-unusually-long-provider-and-model-identifier-for-layout-testing");
          view.setSize(760, 540);
          layoutTree(view);

          JComponent reader = find(view, "teacherReader");
          assertNotNull(reader);
          assertTrue(reader.getX() + reader.getWidth() <= view.getWidth() - view.getInsets().right);
          assertEquals(view.status().getText(), view.status().getToolTipText());
          assertEquals(view.modelStatus().getText(), view.modelStatus().getToolTipText());
        });
  }

  private static void assertAccessible(JComponent component) {
    assertAccessibleName(component);
    assertNotNull(component.getAccessibleContext().getAccessibleDescription());
    assertFalse(component.getAccessibleContext().getAccessibleDescription().isBlank());
  }

  private static void assertAccessibleName(JComponent component) {
    assertNotNull(component.getAccessibleContext().getAccessibleName());
    assertFalse(component.getAccessibleContext().getAccessibleName().isBlank());
  }

  private static JComponent find(Container root, String name) {
    for (Component component : root.getComponents()) {
      if (component instanceof JComponent && name.equals(component.getName())) {
        return (JComponent) component;
      }
      if (component instanceof Container) {
        JComponent nested = find((Container) component, name);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  private static void layoutTree(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container) {
        layoutTree((Container) child);
      }
    }
  }

  private static boolean overlaps(Rectangle first, Rectangle second) {
    return first.intersects(second) && !first.isEmpty() && !second.isEmpty();
  }

  private static Rectangle boundsIn(Container ancestor, Component component) {
    return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
  }
}
