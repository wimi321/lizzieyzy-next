package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class TopMenuRegressionTest {
  @Test
  void shortcutDoesNotOverlapTheHelpBadgeAtAnySupportedFontSize() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (int size : new int[] {12, 15, 18, 24}) {
            AtomicInteger help = new AtomicInteger();
            AtomicInteger normal = new AtomicInteger();
            Menu.InfoMenuItem item =
                new Menu.InfoMenuItem("Analyze-mode game (Alt+N)", help::incrementAndGet);
            item.setFont(new Font(Font.DIALOG, Font.PLAIN, size));
            item.addActionListener(event -> normal.incrementAndGet());
            JPopupMenu popup = new JPopupMenu();
            popup.add(item);
            TopMenuStyle.install(popup);
            item.setSize(item.getPreferredSize());
            TopMenuItemUI.Row row = TopMenuItemUI.Row.of(item);
            int end = row.hintRight(item.getWidth());
            int start = end - TopMenuItemUI.textWidth(item, row.hint());
            int y = item.getHeight() / 2;
            for (int x = start; x <= end; x++) {
              assertFalse(item.isInfoHit(x, y), "shortcut overlaps help at font " + size);
            }
            item.processMouseEvent(
                new MouseEvent(
                    item, MouseEvent.MOUSE_RELEASED, 0, 0, item.getWidth() - 16, y, 1, false));
            assertEquals(1, help.get());
            assertEquals(0, normal.get());
            item.doClick(0);
            assertEquals(1, normal.get());
          }
        });
  }

  @Test
  void realQuickLinksKeepUserNamesWithoutInventingShortcuts() throws Exception {
    Config previousConfig = Lizzie.config;
    JFontMenu previousLinks = Menu.quickLinks;
    try {
      Config config = allocate(Config.class);
      config.leelazConfig =
          new JSONObject()
              .put("program-command-list", new JSONArray().put("not-executed"))
              .put("program-name-list", new JSONArray().put("Editor (A)"));
      Lizzie.config = config;
      Menu menu = allocate(Menu.class);
      Field resources = Menu.class.getDeclaredField("resourceBundle");
      resources.setAccessible(true);
      resources.set(menu, Lizzie.resourceBundle);
      Method refresh = Menu.class.getDeclaredMethod("refreshQuickLinkItems");
      refresh.setAccessible(true);
      SwingUtilities.invokeAndWait(
          () -> {
            Menu.quickLinks = new JFontMenu("Quick links");
            try {
              refresh.invoke(menu);
            } catch (ReflectiveOperationException e) {
              throw new AssertionError(e);
            }
            JPopupMenu popup = Menu.quickLinks.getPopupMenu();
            TopMenuStyle.install(popup);
            JMenuItem item = (JMenuItem) popup.getComponent(0);
            assertEquals("Editor (A)", TopMenuItemUI.Row.of(item).label());
            assertNull(TopMenuItemUI.Row.of(item).hint());
            assertEquals("Editor (A)", item.getAccessibleContext().getAccessibleName());
          });
    } finally {
      Lizzie.config = previousConfig;
      Menu.quickLinks = previousLinks;
    }
  }

  @Test
  void compositeRowsKeepTheirFullAccessibleNamesAcrossReopening() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (String original :
              new String[] {"自定义布局1(Alt+8)", "自定义布局2(Alt+9)", "Custom layout 1 (Alt+8)"}) {
            JMenuItem item = new JMenuItem();
            JLabel label = new JFontLabel(original);
            item.add(label);
            item.add(new JButton("Set"));
            JPopupMenu popup = new JPopupMenu();
            popup.add(item);
            TopMenuStyle.install(popup);
            TopMenuStyle.install(popup);
            assertEquals(original, label.getAccessibleContext().getAccessibleName());
            assertEquals(original, item.getAccessibleContext().getAccessibleName());
            assertTrue(TopMenuItemUI.Row.of(item).hint().startsWith("Alt+"));
            assertFalse(label.getText().contains("Alt+"), "visual hint remains right aligned");
          }
        });
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
