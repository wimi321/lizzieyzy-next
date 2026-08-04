package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WholeGameAnalysisDialogLayoutTest {
  @Test
  void runningMetadataFitsTheInitialDialogWithoutAnOrdinaryScrollbar() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            WholeGameAnalysisDialog dialog = null;
            try {
              dialog = new WholeGameAnalysisDialog(null);
              JLabel progress =
                  (JLabel) findByName(dialog.getContentPane(), "wholeGameProgressText");
              JLabel mode = (JLabel) findByName(dialog.getContentPane(), "wholeGameMode");
              JLabel remaining = (JLabel) findByName(dialog.getContentPane(), "wholeGameRemaining");
              JScrollPane scrollPane =
                  (JScrollPane) findByName(dialog.getContentPane(), "wholeGameScrollPane");
              progress.setText("已完成 1234 / 2345 个局面 · 当前计算量 5000");
              mode.setText(Lizzie.resourceBundle.getString("WholeGameAnalysis.mode.local"));
              remaining.setText(
                  java.text.MessageFormat.format(
                      Lizzie.resourceBundle.getString("WholeGameAnalysis.remaining"), "12:34"));
              layoutTree(dialog.getContentPane());
              scrollPane.doLayout();

              assertFalse(scrollPane.getVerticalScrollBar().isVisible());
              assertTextFits(progress, Locale.SIMPLIFIED_CHINESE);
              assertTextFits(mode, Locale.SIMPLIFIED_CHINESE);
              assertTextFits(remaining, Locale.SIMPLIFIED_CHINESE);
            } catch (Throwable throwable) {
              failure.set(throwable);
            } finally {
              if (dialog != null) {
                dialog.dispose();
              }
            }
          });
      if (failure.get() != null) {
        throw new AssertionError("Running-state layout failed", failure.get());
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void localizedProgressAndControlsRemainVisibleWithEnlargedFonts() throws Exception {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      for (Locale locale :
          List.of(
              Locale.SIMPLIFIED_CHINESE,
              Locale.TRADITIONAL_CHINESE,
              Locale.US,
              Locale.JAPAN,
              Locale.KOREA,
              Locale.forLanguageTag("th-TH"))) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
            () -> {
              WholeGameAnalysisDialog dialog = null;
              try {
                ResourceBundle resources = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
                Lizzie.resourceBundle = resources;
                dialog = new WholeGameAnalysisDialog(null);
                JLabel progress =
                    (JLabel) findByName(dialog.getContentPane(), "wholeGameProgressText");
                JLabel mode = (JLabel) findByName(dialog.getContentPane(), "wholeGameMode");
                JLabel remaining =
                    (JLabel) findByName(dialog.getContentPane(), "wholeGameRemaining");
                assertNotNull(progress, locale.toString());
                assertNotNull(mode, locale.toString());
                assertNotNull(remaining, locale.toString());
                progress.setText(
                    java.text.MessageFormat.format(
                        resources.getString("WholeGameAnalysis.progress"), 1234, 2345, 5000));
                mode.setText(resources.getString("WholeGameAnalysis.mode.local"));
                remaining.setText(
                    java.text.MessageFormat.format(
                        resources.getString("WholeGameAnalysis.remaining"), "12:34"));
                scaleFonts(dialog.getContentPane(), 1.5f);
                dialog.pack();
                layoutTree(dialog.getContentPane());

                assertVisibleSize(progress, locale);
                assertVisibleSize(mode, locale);
                assertVisibleSize(remaining, locale);
                assertTextFits(progress, locale);
                assertTextFits(mode, locale);
                assertTextFits(remaining, locale);
                assertButtonFits(
                    (JButton) findByName(dialog.getContentPane(), "wholeGameStart"), locale);
                assertButtonFits(
                    (JButton) findByName(dialog.getContentPane(), "wholeGamePause"), locale);
                assertButtonFits(
                    (JButton) findByName(dialog.getContentPane(), "wholeGameStop"), locale);
              } catch (Throwable throwable) {
                failure.set(throwable);
              } finally {
                if (dialog != null) {
                  dialog.dispose();
                }
              }
            });
        if (failure.get() != null) {
          throw new AssertionError("Layout failed for " + locale, failure.get());
        }
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  private static void assertVisibleSize(Component component, Locale locale) {
    assertNotNull(component, locale.toString());
    assertTrue(component.isVisible(), locale.toString());
    assertTrue(component.getWidth() > 0, locale + " width");
    assertTrue(component.getHeight() > 0, locale + " height");
  }

  private static void assertTextFits(JLabel label, Locale locale) {
    assertTrue(
        label.getWidth() >= label.getPreferredSize().width,
        locale + " label width: " + label.getText());
    assertTrue(
        label.getHeight() >= label.getPreferredSize().height,
        locale + " label height: " + label.getText());
  }

  private static void assertButtonFits(JButton button, Locale locale) {
    assertVisibleSize(button, locale);
    assertTrue(
        button.getWidth() >= button.getPreferredSize().width,
        locale + " button width: " + button.getText());
    assertTrue(
        button.getHeight() >= button.getPreferredSize().height,
        locale + " button height: " + button.getText());
  }

  private static void scaleFonts(Component component, float scale) {
    Font font = component.getFont();
    if (font != null) {
      component.setFont(font.deriveFont(font.getSize2D() * scale));
    }
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        scaleFonts(child, scale);
      }
    }
  }

  private static void layoutTree(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container) {
        layoutTree((Container) child);
      }
    }
  }

  private static Component findByName(Container container, String name) {
    for (Component child : container.getComponents()) {
      if (name.equals(child.getName())) {
        return child;
      }
      if (child instanceof Container) {
        Component match = findByName((Container) child, name);
        if (match != null) {
          return match;
        }
      }
    }
    return null;
  }
}
