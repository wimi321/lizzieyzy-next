package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Checks the real search window, whose keyboard policy is independent of the main board. */
public final class FoxKifuDownloadAccessibilityTest {
  @Test
  void searchChoicesRemainInTheKeyboardTraversalCycle() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            FoxKifuDownloadAccessibilityTest.class, "fox-keyboard", List.of(), List.of());
    assertTrue(Files.readString(result).contains("search-combos=2 keyboard-reachable=true"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    int exitCode = 1;
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            FoxKifuDownload window = new FoxKifuDownload();
            try {
              window.setVisible(true);
              List<JComboBox<?>> choices = new ArrayList<>();
              collectChoices(window.getContentPane(), choices);
              if (choices.size() != 2) throw new AssertionError("Expected two search choices");
              for (JComboBox<?> choice : choices) {
                if (!choice.isFocusable()) {
                  throw new AssertionError("Search choice is excluded from keyboard focus");
                }
                String name = choice.getAccessibleContext().getAccessibleName();
                if (name == null || name.isBlank()) {
                  throw new AssertionError("Search choice has no accessible name");
                }
                Component next = window.getFocusTraversalPolicy().getComponentAfter(window, choice);
                if (next == null
                    || window.getFocusTraversalPolicy().getComponentBefore(window, next) != choice) {
                  throw new AssertionError("Tab/Shift+Tab traversal skips the search choice");
                }
              }
            } finally {
              window.dispose();
            }
          });
      Files.writeString(result, "search-combos=2 keyboard-reachable=true\n");
      exitCode = 0;
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, "failure=" + failure);
    } finally {
      System.exit(exitCode);
    }
  }

  private static void collectChoices(Component component, List<JComboBox<?>> choices) {
    if (component instanceof JComboBox<?> combo) {
      choices.add(combo);
    } else if (component instanceof Container container) {
      for (Component child : container.getComponents()) collectChoices(child, choices);
    }
  }
}
