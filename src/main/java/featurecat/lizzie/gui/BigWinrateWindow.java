package featurecat.lizzie.gui;

import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import org.json.JSONObject;

/**
 * 黑白胜率大字: a window of its own showing Black's and White's win rates in large figures, for reading
 * across the room or for a stream. It follows the position on the main board four times a second
 * while it is open; a right click picks the style and whether it stays on top, and it remembers
 * both and where it was.
 */
final class BigWinrateWindow extends JDialog {
  private static final int REFRESH_MILLIS = 250;

  private final Supplier<BoardHistoryNode> position;
  private final JSONObject settings;
  private final BigWinratePanel panel;
  private final Timer timer;

  /**
   * @param position the position on the main board
   * @param settings where the window keeps its style, on-top choice and bounds
   */
  BigWinrateWindow(
      Window owner,
      Supplier<BoardHistoryNode> position,
      JSONObject settings,
      ResourceBundle strings,
      Font numbers,
      Font labels) {
    super(owner);
    this.position = position;
    this.settings = settings;
    setTitle(text(strings, "BigWinrateWindow.title", "Win rates"));
    setModal(false);
    setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
    panel =
        new BigWinratePanel(
            text(strings, "BigWinrateWindow.black", "Black"),
            text(strings, "BigWinrateWindow.white", "White"),
            numbers,
            labels);
    panel.setStyle(BigWinrateSettings.style(settings));
    setContentPane(panel);
    setAlwaysOnTop(BigWinrateSettings.onTop(settings));
    panel.setComponentPopupMenu(menu(strings));
    Rectangle saved = BigWinrateSettings.bounds(settings);
    if (saved != null && onScreen(saved)) {
      setBounds(saved);
    } else {
      setSize(520, 260);
      setLocationRelativeTo(owner);
    }
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentMoved(ComponentEvent e) {
            BigWinrateSettings.setBounds(settings, getBounds());
          }

          @Override
          public void componentResized(ComponentEvent e) {
            BigWinrateSettings.setBounds(settings, getBounds());
          }
        });
    timer = new Timer(REFRESH_MILLIS, e -> refresh());
  }

  @Override
  public void setVisible(boolean visible) {
    if (visible) {
      refresh();
      timer.start();
    } else {
      timer.stop();
    }
    super.setVisible(visible);
  }

  private void refresh() {
    BoardHistoryNode node = position.get();
    panel.setReading(node == null ? WinrateReading.UNKNOWN : WinrateReading.of(node));
  }

  private JPopupMenu menu(ResourceBundle strings) {
    JPopupMenu menu = new JPopupMenu();
    ButtonGroup styles = new ButtonGroup();
    addStyle(
        menu,
        styles,
        text(strings, "BigWinrateWindow.plain", "Plain"),
        BigWinratePanel.Style.PLAIN);
    addStyle(
        menu,
        styles,
        text(strings, "BigWinrateWindow.led", "LED digits"),
        BigWinratePanel.Style.LED);
    menu.addSeparator();
    JCheckBoxMenuItem onTop =
        new JCheckBoxMenuItem(
            text(strings, "BigWinrateWindow.onTop", "Keep on top"), isAlwaysOnTop());
    onTop.addActionListener(
        e -> {
          setAlwaysOnTop(onTop.isSelected());
          BigWinrateSettings.setOnTop(settings, onTop.isSelected());
        });
    menu.add(onTop);
    return menu;
  }

  private void addStyle(
      JPopupMenu menu, ButtonGroup group, String label, BigWinratePanel.Style style) {
    JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, panel.style() == style);
    item.addActionListener(
        e -> {
          panel.setStyle(style);
          BigWinrateSettings.setStyle(settings, style);
        });
    group.add(item);
    menu.add(item);
  }

  /** Whether enough of the saved bounds lies on a screen that is still there. */
  private static boolean onScreen(Rectangle bounds) {
    for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
      Rectangle screen = device.getDefaultConfiguration().getBounds();
      Rectangle visible = screen.intersection(bounds);
      if (!visible.isEmpty() && visible.width >= 80 && visible.height >= 40) return true;
    }
    return false;
  }

  private static String text(ResourceBundle strings, String key, String fallback) {
    try {
      return strings == null ? fallback : strings.getString(key);
    } catch (RuntimeException missing) {
      return fallback;
    }
  }
}
