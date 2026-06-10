package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ResourceBundle;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/** Floating controls shown during a HumanSL human-vs-AI game. */
public final class HumanSlGameControlPanel extends JFrame {
  private static final long serialVersionUID = 1L;

  private final transient HumanSlGameController controller;

  public HumanSlGameControlPanel(HumanSlGameController controller) {
    this.controller = controller;
    ResourceBundle resourceBundle = Lizzie.resourceBundle;
    setTitle(resourceBundle.getString("HumanSlGame.dialog.title"));
    setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    if (Lizzie.frame != null) {
      setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    }

    JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
    panel.setBorder(new EmptyBorder(6, 6, 6, 6));

    JFontButton passButton = new JFontButton(resourceBundle.getString("HumanSlGame.btn.pass"));
    passButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            controller.humanPass();
          }
        });
    JFontButton resignButton = new JFontButton(resourceBundle.getString("HumanSlGame.btn.resign"));
    resignButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            controller.humanResign();
          }
        });
    JFontButton countButton = new JFontButton(resourceBundle.getString("HumanSlGame.btn.count"));
    countButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            controller.countAndFinish();
          }
        });
    JFontButton saveButton = new JFontButton(resourceBundle.getString("HumanSlGame.btn.save"));
    saveButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            controller.saveKifu();
          }
        });

    panel.add(passButton);
    panel.add(resignButton);
    panel.add(countButton);
    panel.add(saveButton);

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            setVisible(false);
          }
        });

    getContentPane().add(panel);
    pack();
    if (Lizzie.frame != null) {
      java.awt.Rectangle bounds = Lizzie.frame.getBounds();
      int x = bounds.x + (bounds.width - getWidth()) / 2;
      int y = bounds.y + bounds.height - getHeight() - 60;
      setLocation(Math.max(0, x), Math.max(0, y));
    }
  }
}
