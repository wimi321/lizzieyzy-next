package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.Test;

class WindowMenuStripTest {
  @Test
  void secondPressOnOpenTopMenuClosesInsteadOfReopening() {
    JMenuBar menuBar = new JMenuBar();
    JMenu view = new JMenu("显示");
    view.add(new JMenuItem("面板"));
    menuBar.add(view);

    WindowMenuStrip strip = new WindowMenuStrip(menuBar);
    Component component = strip.getComponent(0);
    assertTrue(component instanceof JButton);

    JButton button = (JButton) component;
    JPopupMenu popup = view.getPopupMenu();
    popup.setInvoker(button);
    popup.setVisible(true);
    assertTrue(popup.isVisible());

    MouseEvent press =
        new MouseEvent(
            button,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            0,
            4,
            4,
            1,
            false,
            MouseEvent.BUTTON1);
    for (MouseListener listener : button.getMouseListeners()) {
      listener.mousePressed(press);
    }
    button.doClick(0);

    assertFalse(popup.isVisible());
  }
}
