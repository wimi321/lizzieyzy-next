package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.border.EmptyBorder;

public class BrowserInitializing extends JDialog {

  public BrowserInitializing(Window owner) {
    super(owner);
    this.setModal(false);
    this.setResizable(false);
    this.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    this.setMinimumSize(new Dimension(Utils.zoomOut(380), Utils.zoomOut(118)));
    this.setTitle(Lizzie.resourceBundle.getString("Message.title"));

    JPanel content = new JPanel(new BorderLayout(0, Utils.zoomOut(14)));
    content.setBorder(new EmptyBorder(18, 22, 18, 22));
    JLabel tip = new JFontLabel(Lizzie.resourceBundle.getString("BrowserFrame.initializing"));
    tip.setHorizontalAlignment(JLabel.CENTER);
    tip.getAccessibleContext().setAccessibleName(tip.getText());
    content.add(tip, BorderLayout.NORTH);

    JProgressBar progress = new JProgressBar();
    progress.setIndeterminate(true);
    progress.setBorder(BorderFactory.createEmptyBorder());
    progress.getAccessibleContext().setAccessibleName(tip.getText());
    content.add(progress, BorderLayout.CENTER);

    setContentPane(content);
    pack();
    setLocationRelativeTo(owner);
  }
}
