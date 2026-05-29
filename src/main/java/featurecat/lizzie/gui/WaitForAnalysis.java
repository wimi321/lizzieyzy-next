package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

public class WaitForAnalysis extends JDialog {
  private JLabel lblAnalsisProgress;
  private boolean disabledOwnerFrame;

  public WaitForAnalysis() {
    this.setModal(false);
    setResizable(false);
    this.setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    setTitle(Lizzie.resourceBundle.getString("WaitForAnalysis.title")); // ("分析中,请等待");
    // setSize(378, 93);
    Lizzie.setFrameSize(this, 378, 93);
    getContentPane().setLayout(null);

    lblAnalsisProgress =
        new JFontLabel(
            Lizzie.resourceBundle.getString(
                "WaitForAnalysis.lblAnalsisProgress")); // ("进度: 引擎加载中...");
    lblAnalsisProgress.setHorizontalAlignment(SwingConstants.CENTER);
    lblAnalsisProgress.setBounds(10, 2, 335, 25);
    getContentPane().add(lblAnalsisProgress);

    //    JLabel lblNotice = new JLabel("注: 如分析速度过慢,可在设置中降低每步计算量");
    //    lblNotice.setBounds(10, 30, 289, 15);
    //    getContentPane().add(lblNotice);

    JButton btnGtpConsole =
        new JButton(Lizzie.resourceBundle.getString("WaitForAnalysis.btnGtpConsole"));
    btnGtpConsole.setFocusable(false);
    btnGtpConsole.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Lizzie.frame.toggleGtpConsole();
          }
        });
    btnGtpConsole.setMargin(new Insets(0, 0, 0, 0));
    btnGtpConsole.setBounds(225, 29, 73, 22);
    getContentPane().add(btnGtpConsole);

    JButton btnHide =
        new JButton(Lizzie.resourceBundle.getString("WaitForAnalysis.btnHide")); // ("隐藏界面");
    btnHide.setFocusable(false);
    btnHide.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });
    btnHide.setMargin(new Insets(0, 0, 0, 0));
    btnHide.setBounds(148, 29, 73, 22);
    getContentPane().add(btnHide);

    JButton btnCancel =
        new JButton(Lizzie.resourceBundle.getString("WaitForAnalysis.btnCancel")); // ("取消分析");
    btnCancel.setFocusable(false);
    btnCancel.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Lizzie.frame.isBatchAnalysisMode = false;
            Lizzie.frame.destroyAnalysisEngine();
            setVisible(false);
          }
        });
    btnCancel.setMargin(new Insets(0, 0, 0, 0));
    btnCancel.setBounds(71, 29, 73, 22);
    getContentPane().add(btnCancel);
  }

  @Override
  public void setVisible(boolean visible) {
    if (visible) {
      disableOwnerFrame();
    } else {
      restoreOwnerFrame();
    }
    super.setVisible(visible);
  }

  @Override
  public void dispose() {
    restoreOwnerFrame();
    super.dispose();
  }

  private void disableOwnerFrame() {
    if (!disabledOwnerFrame && Lizzie.frame != null && Lizzie.frame.isEnabled()) {
      Lizzie.frame.setEnabled(false);
      disabledOwnerFrame = true;
    }
  }

  private void restoreOwnerFrame() {
    if (disabledOwnerFrame && Lizzie.frame != null) {
      Lizzie.frame.setEnabled(true);
      disabledOwnerFrame = false;
    }
  }

  public void setLoadingProgress() {
    if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
      javax.swing.SwingUtilities.invokeLater(this::setLoadingProgress);
      return;
    }
    lblAnalsisProgress.setText(
        Lizzie.resourceBundle.getString("WaitForAnalysis.lblAnalsisProgress"));
  }

  public void setProgress(int curMove, int allMove) {
    if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
      javax.swing.SwingUtilities.invokeLater(() -> setProgress(curMove, allMove));
      return;
    }
    if (curMove == allMove) {
      setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
      lblAnalsisProgress.setText(
          Lizzie.resourceBundle.getString("AnalysisEngine.analyzeComplete") + "!");
      setTitle(Lizzie.resourceBundle.getString("AnalysisEngine.analyzeComplete"));
      if (Lizzie.frame.isBatchAnalysisMode) {
        setVisible(false);
        javax.swing.Timer batchTimer =
            new javax.swing.Timer(300, e -> Lizzie.frame.flashAutoAnaSaveAndLoad());
        batchTimer.setRepeats(false);
        batchTimer.start();
      } else {
        javax.swing.Timer closeTimer = new javax.swing.Timer(1000, e -> setVisible(false));
        closeTimer.setRepeats(false);
        closeTimer.start();
      }
    } else {
      lblAnalsisProgress.setText(
          Lizzie.resourceBundle.getString("WaitForAnalysis.progress") + curMove + "/" + allMove);
    }
  }
}
