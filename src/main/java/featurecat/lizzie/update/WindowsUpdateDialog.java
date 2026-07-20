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
  private final JProgressBar progressBar = new JProgressBar();
  private final JLabel statusLabel = new JLabel(" ");
  private final JFontButton updateButton =
      new JFontButton(tr("WindowsUpdate.btnUpdate", "立即更新", "Update now"));
  private final JFontButton laterButton =
      new JFontButton(tr("WindowsUpdate.btnLater", "关闭", "Close"));
  private final JFontButton releaseButton =
      new JFontButton(tr("WindowsUpdate.btnRelease", "查看 Release", "View release"));

  /**
   * Resource lookup with a built-in bilingual fallback so a missing key can never throw on the
   * updater path (same contract as LizzieFrame.kifuLoadText).
   */
  private static String tr(String key, String chineseText, String englishText) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception ignored) {
    }
    return Lizzie.config != null && Lizzie.config.isChinese ? chineseText : englishText;
  }

  public WindowsUpdateDialog(
      Component parent, WindowsUpdateService service, WindowsUpdatePlan plan) {
    super(
        parent == null ? null : SwingUtilities.getWindowAncestor(parent),
        tr("WindowsUpdate.title", "发现新版本", "New version available"),
        ModalityType.MODELESS);
    this.service = service;
    this.plan = plan;
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
                + "</b><br>"
                + tr("WindowsUpdate.currentVersion", "当前版本", "Current version")
                + ": "
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
    buttons.add(laterButton);
    buttons.add(updateButton);
    footer.add(buttons, BorderLayout.SOUTH);
    root.add(footer, BorderLayout.SOUTH);

    releaseButton.addActionListener(e -> openRelease());
    laterButton.addActionListener(e -> dispose());
    updateButton.addActionListener(e -> startUpdate());
    pack();
    setLocationRelativeTo(parent == null ? Lizzie.frame : parent);
  }

  private String updateSummary() {
    StringBuilder text = new StringBuilder();
    text.append(
            tr(
                "WindowsUpdate.summary.intro",
                "这次会先下载并校验更新文件，然后关闭当前程序，由独立更新器替换文件并重新打开。",
                "The update is downloaded and verified first; the app then closes and a standalone"
                    + " updater replaces the files and restarts it."))
        .append("\n\n");
    text.append(tr("WindowsUpdate.summary.coreSize", "本次主程序小更新", "Core update download") + ": ")
        .append(formatBytes(plan.coreSizeBytes()))
        .append('\n');
    if (plan.resourceSizeBytes() > 0L) {
      text.append(
              tr("WindowsUpdate.summary.resourceSize", "本次需要更新的大资源", "Large resources to update")
                  + ": ")
          .append(formatBytes(plan.resourceSizeBytes()))
          .append('\n');
    } else {
      text.append(
              tr(
                  "WindowsUpdate.summary.noResource",
                  "大资源本次无需下载: KataGo、权重、运行环境、同步工具和浏览器组件都会保留。",
                  "No large resources to download this time: KataGo, weights, the runtime, the sync"
                      + " tool and the browser component are all kept."))
          .append('\n');
    }
    text.append(tr("WindowsUpdate.summary.totalDownload", "总下载", "Total download") + ": ")
        .append(formatBytes(plan.selectedSizeBytes()))
        .append("\n\n");
    text.append(tr("WindowsUpdate.summary.components", "将更新的组件:", "Components to update:"))
        .append('\n');
    for (WindowsUpdatePlan.Item item : plan.selectedItems()) {
      text.append("- ").append(displayName(item.component)).append("  ");
      text.append(formatBytes(item.component.sizeBytes)).append('\n');
    }
    text.append('\n')
        .append(
            tr(
                "WindowsUpdate.summary.preserved",
                "未变化的大资源不会重复下载。用户数据、棋谱、设置、下载权重和 TensorRT 会保留。",
                "Unchanged large resources are not downloaded again. User data, game records,"
                    + " settings, downloaded weights and TensorRT are preserved."));
    return text.toString();
  }

  private String displayName(UpdateManifest.Component component) {
    switch (component.id) {
      case "core":
        return tr("WindowsUpdate.component.core", "主程序小更新", "Core update");
      case "katago-cpu":
        return tr("WindowsUpdate.component.katagoCpu", "KataGo CPU 引擎", "KataGo CPU engine");
      case "katago-opencl":
        return tr(
            "WindowsUpdate.component.katagoOpencl", "KataGo OpenCL 引擎", "KataGo OpenCL engine");
      case "katago-nvidia":
        return tr(
            "WindowsUpdate.component.katagoNvidia", "KataGo NVIDIA 引擎", "KataGo NVIDIA engine");
      case "katago-nvidia50-cuda":
        return tr(
            "WindowsUpdate.component.katagoNvidia50",
            "KataGo RTX 50 CUDA 引擎",
            "KataGo RTX 50 CUDA engine");
      case "weight-default":
        return tr("WindowsUpdate.component.weightDefault", "默认权重", "Default weights");
      case "readboard":
        return tr("WindowsUpdate.component.readboard", "棋盘同步工具", "Board sync tool");
      case "jcef":
        return tr("WindowsUpdate.component.jcef", "内置浏览器运行时", "Embedded browser runtime");
      case "java-runtime":
        return tr("WindowsUpdate.component.javaRuntime", "Java 运行时", "Java runtime");
      default:
        return component.id;
    }
  }

  private void startUpdate() {
    setButtonsEnabled(false);
    progressBar.setVisible(true);
    statusLabel.setText(tr("WindowsUpdate.status.preparing", "准备下载...", "Preparing download..."));
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
                      String failed =
                          tr("WindowsUpdate.status.failed", "更新失败", "Update failed") + ": ";
                      statusLabel.setText(failed + e.getLocalizedMessage());
                      Utils.showMsg(failed + e.getLocalizedMessage());
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
      statusLabel.setText(
          tr(
              "WindowsUpdate.status.launching",
              "下载完成，正在启动更新器...",
              "Download complete, launching the updater..."));
      service.launchHelper(requestPath);
      dispose();
      Lizzie.shutdown();
    } catch (IOException e) {
      e.printStackTrace();
      setButtonsEnabled(true);
      String launchFailed =
          tr("WindowsUpdate.status.launchFailed", "启动更新器失败", "Failed to launch the updater") + ": ";
      statusLabel.setText(launchFailed + e.getLocalizedMessage());
      Utils.showMsg(launchFailed + e.getLocalizedMessage());
    }
  }

  private void openRelease() {
    try {
      Desktop.getDesktop().browse(new URI(plan.manifest.notesUrl));
    } catch (Exception e) {
      Utils.showMsg(
          tr(
                  "WindowsUpdate.status.openReleaseFailed",
                  "无法打开 Release 页面",
                  "Could not open the release page")
              + ": "
              + e.getLocalizedMessage());
    }
  }

  private void setButtonsEnabled(boolean enabled) {
    updateButton.setEnabled(enabled);
    laterButton.setEnabled(enabled);
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
