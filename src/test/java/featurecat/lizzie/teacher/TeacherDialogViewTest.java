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
  void commentaryUsesTheFullReaderWidthAtSmallAndLargeSizes() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var view = new TeacherDialogView();
          view.showOutput();
          for (var size :
              new java.awt.Dimension[] {
                new java.awt.Dimension(760, 540), new java.awt.Dimension(1120, 760)
              }) {
            view.setSize(size);
            for (var mode : TeacherDialogView.Mode.values()) {
              view.selectMode(mode);
              layoutTree(view);
              assertTextOnlyReader(view);
            }
          }
        });
  }

  static void assertTextOnlyReader(TeacherDialogView view) {
    var tabs = (javax.swing.JTabbedPane) find(view, "teacherReaderTabs");
    var reader = find(view, "teacherReader");
    var cards = find(view, "teacherContentCards");
    assertNotNull(tabs);
    assertNotNull(reader);
    assertNotNull(cards);
    assertEquals(2, tabs.getTabCount(), "only commentary and its evidence, no duplicate board");
    assertEquals(
        reader.getWidth() - reader.getInsets().left - reader.getInsets().right, tabs.getWidth());
    assertTrue(cards.getWidth() >= tabs.getWidth() - 24, "no sidebar reduces the reading width");
  }

  @Test
  void evidenceIncludesEverySelectedPositionAndClearsWithTheGame() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var view = new TeacherDialogView();
          var node = TeacherBoardContextTest.position();
          var first = TeacherEvidence.current(node).orElseThrow();
          node.getData().moveNumber = 20;
          var second = TeacherEvidence.current(node).orElseThrow();
          assertFalse(
              TeacherPromptBuilder.formatPosition(first)
                  .equals(TeacherPromptBuilder.formatPosition(second)));
          view.setEvidence(java.util.List.of(first, second));
          var evidence = (javax.swing.JTextArea) find(view, "teacherEvidenceText");
          assertNotNull(evidence);
          assertEquals(
              TeacherPromptBuilder.formatPosition(first)
                  + "\n\n"
                  + TeacherPromptBuilder.formatPosition(second),
              evidence.getText());
          assertEquals(0, evidence.getCaretPosition());
          assertFalse(evidence.isEditable());
          assertTrue(evidence.getLineWrap());
          assertAccessibleName(evidence);
          view.setEvidence(java.util.List.of());
          assertEquals("", evidence.getText());
        });
  }

  @Test
  void initialBoardIsARealPositionNotAMissingGame() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var view = new TeacherDialogView();
          view.setCurrentMove(0);
          assertEquals(
              TeacherStrings.format("Teacher.position.move", "Current move {0}", 0),
              view.currentMove().getText());
          view.setCurrentMove(-1);
          assertEquals(
              TeacherStrings.get("Teacher.position.none", "No active position"),
              view.currentMove().getText());
        });
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void localizedControlsRenderTheirActualGlyphs() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var previous = featurecat.lizzie.Lizzie.resourceBundle;
          try {
            for (String tag :
                new String[] {"zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH"}) {
              featurecat.lizzie.Lizzie.resourceBundle =
                  java.util.ResourceBundle.getBundle(
                      "l10n.DisplayStrings", java.util.Locale.forLanguageTag(tag));
              TeacherDialogView view = new TeacherDialogView();
              view.setChatGptUsageVisible(true);
              assertGlyphs(view);
            }
          } finally {
            featurecat.lizzie.Lizzie.resourceBundle = previous;
          }
        });
  }

  static void assertGlyphs(Component component) {
    String text = null;
    if (component instanceof javax.swing.AbstractButton)
      text = ((javax.swing.AbstractButton) component).getText();
    else if (component instanceof javax.swing.JLabel)
      text = ((javax.swing.JLabel) component).getText();
    else if (component instanceof javax.swing.text.JTextComponent)
      text = ((javax.swing.text.JTextComponent) component).getText();
    if (text != null && !text.isBlank())
      assertEquals(
          -1,
          component.getFont().canDisplayUpTo(text),
          component.getClass().getSimpleName() + ": " + text);
    if (component instanceof Container)
      for (Component child : ((Container) component).getComponents()) assertGlyphs(child);
  }

  @Test
  void localizedModeLabelsFitWithoutEllipsis() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          java.util.ResourceBundle previous = featurecat.lizzie.Lizzie.resourceBundle;
          try {
            for (String tag : new String[] {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko-KR", "th-TH"}) {
              featurecat.lizzie.Lizzie.resourceBundle =
                  java.util.ResourceBundle.getBundle(
                      "l10n.DisplayStrings", java.util.Locale.forLanguageTag(tag));
              TeacherDialogView view = new TeacherDialogView();
              view.setSize(760, 540);
              layoutTree(view);
              for (javax.swing.JToggleButton button :
                  new javax.swing.JToggleButton[] {
                    view.explainNext(), view.explainRange(), view.explainWhole()
                  }) {
                int available =
                    button.getWidth() - button.getInsets().left - button.getInsets().right;
                assertTrue(
                    available
                        >= button.getFontMetrics(button.getFont()).stringWidth(button.getText()),
                    tag);
              }
            }
          } finally {
            featurecat.lizzie.Lizzie.resourceBundle = previous;
          }
        });
  }

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
          assertTrue(rail.getWidth() >= 94);
          assertTrue(reader.getWidth() >= 500, "the commentary reader remains the dominant region");
          assertTrue(
              cards.getHeight() >= 220,
              "commentary keeps meaningful reading height: "
                  + cards.getBounds()
                  + "; reader="
                  + reader.getBounds()
                  + "; modes="
                  + rail.getBounds()
                  + "; composer="
                  + composer.getBounds());
          assertFalse(overlaps(boundsIn(view, rail), boundsIn(view, reader)));
          assertFalse(overlaps(boundsIn(view, reader), boundsIn(view, composer)));
          assertEquals(view.explainNext().getY(), view.explainRange().getY());
          assertTrue(view.explainNext().getX() < view.explainRange().getX());
          assertTrue(view.explainRange().getX() < view.explainWhole().getX());
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
          for (javax.swing.JSpinner spinner :
              new javax.swing.JSpinner[] {view.rangeStart(), view.rangeEnd()}) {
            spinner.setModel(new javax.swing.SpinnerNumberModel(1, 1, 100, 1));
            TeacherDialogStyle.styleSpinner(spinner);
            assertEquals(
                spinner.getAccessibleContext().getAccessibleName(),
                ((javax.swing.JSpinner.DefaultEditor) spinner.getEditor())
                    .getTextField()
                    .getAccessibleContext()
                    .getAccessibleName());
          }

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

  @Test
  void chatGptPlanUsageHasVisibleActionWithoutHidingTheComposer() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          java.util.ResourceBundle previous = featurecat.lizzie.Lizzie.resourceBundle;
          try {
            for (String tag : new String[] {"zh-CN", "zh-TW", "en-US", "ja-JP", "ko", "th-TH"}) {
              featurecat.lizzie.Lizzie.resourceBundle =
                  java.util.ResourceBundle.getBundle(
                      "l10n.DisplayStrings", java.util.Locale.forLanguageTag(tag));
              TeacherDialogView view = new TeacherDialogView();
              view.setChatGptUsageVisible(true);
              view.setSize(760, 540);
              layoutTree(view);
              var button = view.manageChatGptUsage();
              assertAccessible(button);
              assertTrue(
                  button.getWidth() - button.getInsets().left - button.getInsets().right
                      >= button.getFontMetrics(button.getFont()).stringWidth(button.getText()),
                  tag);
              var composer = find(view, "teacherComposer");
              assertNotNull(composer);
              assertFalse(overlaps(boundsIn(view, button), boundsIn(view, composer)), tag);
              assertTrue(boundsIn(view, button).y + button.getHeight() <= view.getHeight(), tag);
            }
          } finally {
            featurecat.lizzie.Lizzie.resourceBundle = previous;
          }
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
