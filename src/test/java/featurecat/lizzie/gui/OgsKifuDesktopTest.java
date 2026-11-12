package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.search.FunctionCatalog;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class OgsKifuDesktopTest {
  @Test
  void toolbarAndMenuOpenTheSameOgsGameBrowser() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(OgsKifuDesktopTest.class, "ogs-browser", List.of(), List.of());
    assertTrue(Files.readString(result).contains("ogs-toolbar-menu=passed"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    int exit = 1;
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            List<AbstractButton> buttons = new ArrayList<>();
            collect(Lizzie.frame.topPanel.leftArea, buttons);
            int fox = index(buttons, "menu.foxKifu");
            int tencent = index(buttons, "menu.tencentKifu");
            int ogs = index(buttons, "menu.ogsKifu");
            if (fox < 0 || tencent != fox + 1 || ogs != tencent + 1)
              throw new AssertionError("OGS is not beside Fox and Tencent");
            buttons.get(ogs).doClick(0);
            OgsKifuDownload browser = window();
            if (!browser.isShowing()) throw new AssertionError("Browser not visible");
            if (!(browser.getContentPane() instanceof OgsKifuBrowser))
              throw new AssertionError("Missing game-list controls");
            List<AbstractButton> menu = new ArrayList<>();
            collect(Lizzie.frame.menu, menu);
            int item = index(menu, "menu.ogsKifu");
            if (item < 0) throw new AssertionError("Missing OGS menu entry");
            menu.get(item).doClick(0);
            if (window() != browser) throw new AssertionError("Menu created a duplicate browser");
            if (FunctionCatalog.entry("menu.ogsKifu") == null)
              throw new AssertionError("Missing searchable action");
            browser.validate();
            browser.getRootPane().paintImmediately(browser.getRootPane().getBounds());
          });
      Robot robot = new Robot();
      robot.waitForIdle();
      ImageIO.write(
          robot.createScreenCapture(new Rectangle(0, 0, 1600, 1000)),
          "png",
          result.resolveSibling("ogs-desktop.png").toFile());
      Files.writeString(result, "ogs-toolbar-menu=passed\n");
      exit = 0;
    } catch (Throwable error) {
      error.printStackTrace();
      Files.writeString(result, "failure=" + error);
    } finally {
      System.exit(exit);
    }
  }

  private static OgsKifuDownload window() {
    for (Window window : Window.getWindows()) {
      if (window instanceof OgsKifuDownload ogs && ogs.isDisplayable()) return ogs;
    }
    throw new AssertionError("No OGS browser");
  }

  private static int index(List<AbstractButton> buttons, String action) {
    for (int i = 0; i < buttons.size(); i++)
      if (action.equals(buttons.get(i).getClientProperty(AutoAnalyzeMenu.ACTION_PROPERTY)))
        return i;
    return -1;
  }

  private static void collect(Component component, List<AbstractButton> buttons) {
    if (component instanceof AbstractButton button) buttons.add(button);
    if (component instanceof JMenu menu) collect(menu.getPopupMenu(), buttons);
    if (component instanceof Container container)
      for (Component child : container.getComponents()) collect(child, buttons);
  }
}
