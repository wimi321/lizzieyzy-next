package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Frame;
import javax.swing.JFrame;
import org.junit.jupiter.api.Test;

class YikeLiveDialogWindowTest {

  @Test
  void liveCenterUsesMinimizableApplicationWindow() {
    assertTrue(JFrame.class.isAssignableFrom(YikeLiveDialog.class));
  }

  @Test
  void reopeningLiveCenterRestoresAnIconifiedWindow() {
    int state = Frame.ICONIFIED | Frame.MAXIMIZED_HORIZ;

    assertEquals(Frame.MAXIMIZED_HORIZ, YikeLiveDialog.restoredWindowState(state));
  }
}
