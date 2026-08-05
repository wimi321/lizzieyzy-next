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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Download-and-open flow for signed macOS DMGs and Linux archives. */
public final class PackageUpdateDialog extends JDialog {
  private final PlatformUpdateService service;
  private final PackageUpdatePlan plan;
  private ResumableDownloader.Control control = new ResumableDownloader.Control();
  private final JProgressBar progressBar = new JProgressBar(0, 1000);
  private final JTextArea statusLabel = UpdateText.createStatusArea();
  private final JFontButton downloadButton =
      new JFontButton(UpdateText.tr("WindowsUpdate.btnDownload", "下载新版", "Download update"));
  private final JFontButton pauseButton =
      new JFontButton(UpdateText.tr("WindowsUpdate.btnPause", "暂停", "Pause"));
  private final JFontButton cancelButton =
      new JFontButton(UpdateText.tr("WindowsUpdate.btnCancel", "取消", "Cancel"));
  private final JFontButton releaseButton =
      new JFontButton(UpdateText.tr("WindowsUpdate.btnRelease", "查看 Release", "View release"));
  private volatile boolean downloading;

  public PackageUpdateDialog(
      Component parent, PlatformUpdateService service, PackageUpdatePlan plan) {
    super(
        parent == null ? null : SwingUtilities.getWindowAncestor(parent),
        UpdateText.tr("WindowsUpdate.title", "发现新版本", "New version available"),
        ModalityType.MODELESS);
    this.service = service;
    this.plan = plan;
    buildUi(parent);
  }

  private void buildUi(Component parent) {
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            closeRequested();
          }
        });
    setMinimumSize(new Dimension(560, 330));
    JPanel root = new JPanel(new BorderLayout(12, 12));
    root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
    root.setBackground(new Color(246, 247, 249));
    setContentPane(root);

    JLabel title =
        new JLabel(
            "<html><b>LizzieYzy Next "
                + plan.manifest.releaseTag
                + "</b><br>"
                + UpdateText.tr("WindowsUpdate.currentVersion", "当前版本", "Current version")
                + ": "
                + plan.currentVersion
                + "</html>");
    title.setFont(title.getFont().deriveFont(Font.PLAIN, 15f));
    root.add(title, BorderLayout.NORTH);

    JTextArea summary = new JTextArea(summaryText());
    summary.setEditable(false);
    summary.setLineWrap(true);
    summary.setWrapStyleWord(true);
    summary.setBackground(AppleStyleSupport.validFieldBackground());
    summary.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    root.add(summary, BorderLayout.CENTER);

    JPanel footer = new JPanel(new BorderLayout(8, 8));
    footer.setOpaque(false);
    progressBar.setStringPainted(true);
    progressBar.setVisible(false);
    UpdateText.configureProgressBar(progressBar);
    footer.add(progressBar, BorderLayout.NORTH);
    footer.add(statusLabel, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    pauseButton.setEnabled(false);
    cancelButton.setEnabled(false);
    buttons.add(releaseButton);
    buttons.add(cancelButton);
    buttons.add(pauseButton);
    buttons.add(downloadButton);
    footer.add(buttons, BorderLayout.SOUTH);
    root.add(footer, BorderLayout.SOUTH);

    releaseButton.addActionListener(event -> openRelease());
    downloadButton.addActionListener(event -> startDownload());
    pauseButton.addActionListener(event -> togglePause());
    cancelButton.addActionListener(event -> control.cancel());
    pack();
    setLocationRelativeTo(parent == null ? Lizzie.frame : parent);
  }

  private String summaryText() {
    String action =
        "macos".equals(plan.platform)
            ? UpdateText.tr(
                "WindowsUpdate.summary.macInstall",
                "下载完成并校验后会自动打开 DMG。请把应用拖到“应用程序”，再从“应用程序”启动。",
                "After verification, the DMG opens automatically. Drag the app to Applications, then launch it from Applications.")
            : UpdateText.tr(
                "WindowsUpdate.summary.linuxInstall",
                "下载完成并校验后会打开所在文件夹。Linux 更新不会自动覆盖当前目录。",
                "After verification, the download folder opens. Linux updates never overwrite the current folder automatically.");
    return action
        + "\n\n"
        + UpdateText.tr("WindowsUpdate.summary.package", "下载文件", "Package")
        + ": "
        + plan.packageAsset.assetName
        + "\n"
        + UpdateText.tr("WindowsUpdate.summary.totalDownload", "总下载", "Total download")
        + ": "
        + WindowsUpdateDialog.formatBytes(plan.packageAsset.sizeBytes)
        + "\n\n"
        + UpdateText.tr(
            "WindowsUpdate.summary.fallback",
            "优先从 Cloudflare R2 下载；连接失败会自动切换 GitHub。下载支持断点续传，并始终校验签名清单中的大小和 SHA-256。",
            "Cloudflare R2 is preferred and GitHub is used automatically if needed. Downloads resume safely and are always checked against the signed size and SHA-256.");
  }

  private void startDownload() {
    if (downloading) {
      return;
    }
    if (control.isCancelled()) {
      control = new ResumableDownloader.Control();
    }
    downloading = true;
    progressBar.setVisible(true);
    downloadButton.setEnabled(false);
    pauseButton.setEnabled(true);
    cancelButton.setEnabled(true);
    statusLabel.setText(
        UpdateText.tr("WindowsUpdate.status.preparing", "准备下载...", "Preparing download..."));
    Thread worker =
        new Thread(
            () -> {
              try {
                Path downloaded =
                    service.download(
                        plan,
                        control,
                        progress -> SwingUtilities.invokeLater(() -> updateProgress(progress)));
                SwingUtilities.invokeLater(() -> downloadComplete(downloaded));
              } catch (ResumableDownloader.DownloadCancelledException e) {
                SwingUtilities.invokeLater(this::downloadCancelled);
              } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> downloadFailed(e));
              }
            },
            "lizzie-platform-update-download");
    worker.setDaemon(true);
    worker.start();
  }

  private void togglePause() {
    if (control.isPaused()) {
      control.resume();
      pauseButton.setText(UpdateText.tr("WindowsUpdate.btnPause", "暂停", "Pause"));
    } else {
      control.pause();
      pauseButton.setText(UpdateText.tr("WindowsUpdate.btnResume", "继续", "Resume"));
      statusLabel.setText(
          UpdateText.tr(
              "WindowsUpdate.status.paused",
              "下载已暂停，进度已保留。",
              "Download paused. Progress is preserved."));
    }
  }

  private void updateProgress(ResumableDownloader.Progress progress) {
    int value =
        (int) Math.max(0L, Math.min(1000L, progress.completedBytes * 1000L / progress.totalBytes));
    progressBar.setValue(value);
    progressBar.setString(value / 10 + "%");
    if (progress.state == ResumableDownloader.State.PAUSED) {
      statusLabel.setText(
          UpdateText.tr(
              "WindowsUpdate.status.paused",
              "下载已暂停，进度已保留。",
              "Download paused. Progress is preserved."));
      return;
    }
    if (progress.state == ResumableDownloader.State.VERIFYING) {
      statusLabel.setText(
          UpdateText.tr(
              "WindowsUpdate.status.verifying",
              "下载完成，正在校验文件...",
              "Download complete. Verifying file..."));
      return;
    }
    if (progress.state == ResumableDownloader.State.RETRYING) {
      statusLabel.setText(
          java.text.MessageFormat.format(
              UpdateText.tr(
                  "WindowsUpdate.status.fallback",
                  "主下载源不可用，正在从 {0} 继续下载...",
                  "The primary source is unavailable. Continuing from {0}..."),
              progress.sourceName));
      return;
    }
    String source =
        UpdateText.tr("WindowsUpdate.status.source", "来源", "Source") + ": " + progress.sourceName;
    String speed =
        progress.bytesPerSecond <= 0L
            ? ""
            : " · "
                + WindowsUpdateDialog.formatBytes(progress.bytesPerSecond)
                + "/s · "
                + UpdateText.tr("WindowsUpdate.status.eta", "剩余", "Remaining")
                + " "
                + ResumableDownloader.formatDuration(progress.estimatedSeconds);
    statusLabel.setText(
        source
            + " · "
            + WindowsUpdateDialog.formatBytes(progress.completedBytes)
            + " / "
            + WindowsUpdateDialog.formatBytes(progress.totalBytes)
            + speed);
  }

  private void downloadComplete(Path downloaded) {
    downloading = false;
    pauseButton.setEnabled(false);
    cancelButton.setEnabled(false);
    statusLabel.setText(
        UpdateText.tr(
            "WindowsUpdate.status.verified", "下载和校验完成，正在打开...", "Download verified; opening..."));
    try {
      service.openDownloadedPackage(plan, downloaded);
      downloadButton.setText(UpdateText.tr("WindowsUpdate.btnDownloaded", "已下载", "Downloaded"));
    } catch (Exception e) {
      downloadFailed(e);
    }
  }

  private void downloadCancelled() {
    downloading = false;
    pauseButton.setEnabled(false);
    cancelButton.setEnabled(false);
    downloadButton.setEnabled(true);
    downloadButton.setText(UpdateText.tr("WindowsUpdate.btnResume", "继续", "Resume"));
    statusLabel.setText(
        UpdateText.tr(
            "WindowsUpdate.status.cancelled",
            "下载已停止，已保留进度，下次可继续。",
            "Download stopped. Progress was kept so it can resume later."));
  }

  private void downloadFailed(Exception error) {
    downloading = false;
    pauseButton.setEnabled(false);
    cancelButton.setEnabled(false);
    downloadButton.setEnabled(true);
    String message =
        UpdateText.tr("WindowsUpdate.status.failed", "更新失败", "Update failed")
            + ": "
            + UpdateText.userFacingError(error);
    statusLabel.setText(message);
    Utils.showMsg(message);
  }

  private void openRelease() {
    try {
      if (!Desktop.isDesktopSupported()) {
        throw new IllegalStateException("Desktop integration is unavailable.");
      }
      Desktop.getDesktop().browse(new URI(plan.manifest.notesUrl));
    } catch (Exception e) {
      Utils.showMsg(
          UpdateText.tr(
                  "WindowsUpdate.status.openReleaseFailed",
                  "无法打开 Release 页面",
                  "Could not open the release page")
              + ": "
              + e.getLocalizedMessage());
    }
  }

  private void closeRequested() {
    if (downloading) {
      control.cancel();
    }
    dispose();
  }
}
