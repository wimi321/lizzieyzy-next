package featurecat.lizzie.gui;

import featurecat.lizzie.util.OgsGameClient;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ResourceBundle;
import javax.swing.JFrame;

/** OGS counterpart to the Fox and Tencent game-list windows. */
final class OgsKifuDownload extends JFrame {
  private final OgsKifuBrowser browser;

  OgsKifuDownload(LizzieFrame owner, ResourceBundle strings) {
    super(strings.getString("Menu.ogsKifu"));
    browser =
        new OgsKifuBrowser(
            strings,
            new OgsGameClient(),
            (sgf, done) -> OgsKifuImport.loadDownloaded(owner, sgf, done),
            action -> {
              if (action == 0) setExtendedState(Frame.ICONIFIED);
              else if (action == 1) dispose();
            });
    setContentPane(browser);
    setIconImages(owner.getIconImages());
    setAlwaysOnTop(owner.isAlwaysOnTop());
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setMinimumSize(new Dimension(760, 400));
    setSize(980, 680);
    setLocationRelativeTo(owner);
    LizzieFrame.constrainWindowToAvailableWorkArea(this);
    AccessibilitySupport.installEscapeToClose(getRootPane(), this);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            browser.cancelPending();
          }
        });
  }

  void presentWindow() {
    setVisible(true);
    setExtendedState(Frame.NORMAL);
    toFront();
    browser.query.requestFocusInWindow();
  }

  @Override
  public void dispose() {
    browser.cancelPending();
    super.dispose();
  }
}
