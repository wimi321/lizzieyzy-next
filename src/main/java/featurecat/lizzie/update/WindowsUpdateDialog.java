package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.AppleStyleSupport;
import featurecat.lizzie.gui.JFontButton;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

public final class WindowsUpdateDialog extends JDialog {
  private final WindowsUpdateService service;
  private final WindowsUpdatePlan plan;
  private final String ignoreVersionKey;
  private final JProgressBar progressBar = new JProgressBar();
  private final JLabel statusLabel = new JLabel(" ");
  private final JFontButton updateButton = new JFontButton("立即更新");
  private final JFontButton laterButton = new JFontButton("稍后提醒");
  private final JFontButton ignoreButton = new JFontButton("忽略此版本");
  private final JFontButton releaseButton = new JFontButton("查看 Release");

  public WindowsUpdateDialog(
      Component parent, WindowsUpdateService service, WindowsUpdatePlan plan, String ignoreVersionKey) {
    super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "发现新版本", ModalityType.MODELESS);
    this.service = service;
    this.plan = plan;
    this.ignoreVersionKey = ignoreVersionKey;
    buildUi(parent);
  }

  private void buildUi(Component parent) {
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setMinimumSize(new Dimension(560, 360));
    setLayout(new BorderLayout(12, 12));
    JPanel root = new JPanel(new BorderLayout(12, 12));
    root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
    root.setBackground(new Color(246, 247, 249));
    setContentPane(root);

    JLabel title =
        new JLabel(
            "<html><b>LizzieYzy Next "
                + plan.manifest.releaseTag
                + "</b><br>当前版本: "
                + plan.currentVersion
                + "</html>");
    title.setFont(title.getFont().deriveFont(Font.PLAIN, 15f));
    root.add(title, BorderLayout.NORTH);

    JTextArea detail = new JTextArea(updateSummary());
    detail.setEditable(false);
    detail.setLineWrap(true);
    detail.setWrapStyleWord(true);
    detail.setBackground(AppleStyleSupport.validFieldBackground());
    detail.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    root.add(new JScrollPane(detail), BorderLayout.CENTER);

    JPanel footer = new JPanel(new BorderLayout(8, 8));
    footer.setOpaque(false);
    progressBar.setStringPainted(true);
    progressBar.setMinimum(0);
    progressBar.setMaximum(1000);
    progressBar.setValue(0);
    progressBar.setVisible(false);
    footer.add(progressBar, BorderLayout.NORTH);
    footer.add(statusLabel, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    buttons.add(releaseButton);
    buttons.add(ignoreButton);
    buttons.add(laterButton);
    buttons.add(updateButton);
    footer.add(buttons, BorderLayout.SOUTH);
    root.add(footer, BorderLayout.SOUTH);

    releaseButton.addActionListener(e -> openRelease());
    laterButton.addActionListener(e -> dispose());
    ignoreButton.addActionListener(e -> ignoreVersion());
    updateButton.addActionListener(e -> startUpdate());
    pack();
    setLocationRelativeTo(parent == null ? Lizzie.frame : parent);
  }

  private String updateSummary() {
    StringBuilder text = new StringBuilder();
    text.append("这次会先下载并校验更新文件，然后关闭当前程序，由独立更新器替换文件并重新打开。\n\n");
    text.append("核心更新: ").append(formatBytes(plan.coreSizeBytes())).append('\n');
    text.append("大资源更新: ").append(formatBytes(plan.resourceSizeBytes())).append('\n');
    text.append("总下载: ").append(formatBytes(plan.selectedSizeBytes())).append("\n\n");
    text.append("将更新的组件:\n");
    for (WindowsUpdatePlan.Item item : plan.selectedItems()) {
      text.append("- ").append(displayName(item.component)).append("  ");
      text.append(formatBytes(item.component.sizeBytes)).append('\n');
    }
    text.append("\n未变化的大资源不会重复下载。用户数据、棋谱、设置和下载的模型会保留。");
    return text.toString();
  }

  private String displayName(UpdateManifest.Component component) {
    switch (component.id) {
      case "core":
        return "主程序核心";
      case "katago-cpu":
        return "KataGo CPU 引擎";
      case "katago-opencl":
        return "KataGo OpenCL 引擎";
      case "katago-nvidia":
        return "KataGo NVIDIA 引擎";
      case "katago-nvidia50-cuda":
        return "KataGo RTX 50 CUDA 引擎";
      case "weight-default":
        return "默认权重";
      case "readboard":
        return "棋盘同步工具";
      case "jcef":
        return "内置浏览器运行时";
      case "java-runtime":
        return "Java 运行时";
      default:
        return component.id;
    }
  }

  private void startUpdate() {
    setButtonsEnabled(false);
    progressBar.setVisible(true);
    statusLabel.setText("准备下载...");
    Thread worker =
        new Thread(
            () -> {
              try {
                Path request =
                    service.downloadAndPrepare(
                        plan,
                        (status, completed, total) ->
                            SwingUtilities.invokeLater(
                                () -> updateProgress(status, completed, total)));
                SwingUtilities.invokeLater(() -> applyUpdate(request));
              } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(
                    () -> {
                      setButtonsEnabled(true);
                      statusLabel.setText("更新失败: " + e.getLocalizedMessage());
                      Utils.showMsg("更新失败: " + e.getLocalizedMessage());
                    });
              }
            },
            "lizzie-windows-update-download");
    worker.setDaemon(true);
    worker.start();
  }

  private void updateProgress(String status, long completed, long total) {
    statusLabel.setText(status + "  " + formatBytes(completed) + " / " + formatBytes(total));
    int value = total <= 0L ? 0 : (int) Math.max(0L, Math.min(1000L, completed * 1000L / total));
    progressBar.setValue(value);
    progressBar.setString(value / 10 + "%");
  }

  private void applyUpdate(Path requestPath) {
    try {
      statusLabel.setText("下载完成，正在启动更新器...");
      service.launchHelper(requestPath);
      dispose();
      Lizzie.shutdown();
    } catch (IOException e) {
      e.printStackTrace();
      setButtonsEnabled(true);
      statusLabel.setText("启动更新器失败: " + e.getLocalizedMessage());
      Utils.showMsg("启动更新器失败: " + e.getLocalizedMessage());
    }
  }

  private void openRelease() {
    try {
      Desktop.getDesktop().browse(new URI(plan.manifest.notesUrl));
    } catch (Exception e) {
      Utils.showMsg("无法打开 Release 页面: " + e.getLocalizedMessage());
    }
  }

  private void ignoreVersion() {
    Lizzie.config.uiConfig.put(ignoreVersionKey, plan.manifest.releaseTag);
    try {
      Lizzie.config.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
    dispose();
  }

  private void setButtonsEnabled(boolean enabled) {
    updateButton.setEnabled(enabled);
    laterButton.setEnabled(enabled);
    ignoreButton.setEnabled(enabled);
    releaseButton.setEnabled(enabled);
  }

  static String formatBytes(long bytes) {
    if (bytes < 1024L) {
      return bytes + " B";
    }
    double value = bytes;
    String[] units = {"KB", "MB", "GB"};
    int unit = -1;
    do {
      value /= 1024.0;
      unit++;
    } while (value >= 1024.0 && unit < units.length - 1);
    return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
  }
}
