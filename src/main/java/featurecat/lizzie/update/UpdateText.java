package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import javax.swing.BorderFactory;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.UIManager;

final class UpdateText {
  private UpdateText() {}

  static String tr(String key, String chineseText, String englishText) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception ignored) {
    }
    return Lizzie.config != null && Lizzie.config.isChinese ? chineseText : englishText;
  }

  static JTextArea createStatusArea() {
    JTextArea status = new JTextArea(" ", 2, 1);
    status.setEditable(false);
    status.setFocusable(false);
    status.setLineWrap(true);
    status.setWrapStyleWord(true);
    status.setOpaque(false);
    status.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
    status.setFont(UIManager.getFont("Label.font"));
    status
        .getAccessibleContext()
        .setAccessibleName(
            tr("WindowsUpdate.accessibility.status", "更新下载状态", "Update download status"));
    return status;
  }

  static void configureProgressBar(JProgressBar progressBar) {
    progressBar
        .getAccessibleContext()
        .setAccessibleName(
            tr("WindowsUpdate.accessibility.progress", "更新下载进度", "Update download progress"));
  }

  static String userFacingError(Throwable error) {
    if (ResumableDownloader.isIntegrityFailure(error)) {
      return tr(
          "WindowsUpdate.error.integrity",
          "下载文件校验失败，未进行安装。请重试；若仍失败请从 GitHub Release 手动下载。",
          "The downloaded file failed integrity verification and was not installed. Retry, or download it manually from GitHub Releases.");
    }
    if (containsMessage(error, "all update download sources failed")
        || containsMessage(error, "no signed update source could be verified")
        || containsMessage(error, "http ")) {
      return tr(
          "WindowsUpdate.error.sources",
          "Cloudflare R2 与 GitHub 均无法完成下载，请检查网络后重试。",
          "Cloudflare R2 and GitHub could not complete the download. Check your network and retry.");
    }
    return tr(
        "WindowsUpdate.error.generic",
        "无法完成更新，请稍后重试或打开 Release 页面手动下载。",
        "The update could not be completed. Retry later or download it manually from the Release page.");
  }

  private static boolean containsMessage(Throwable error, String fragment) {
    if (error == null) {
      return false;
    }
    String message = error.getMessage();
    if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(fragment)) {
      return true;
    }
    if (containsMessage(error.getCause(), fragment)) {
      return true;
    }
    for (Throwable suppressed : error.getSuppressed()) {
      if (containsMessage(suppressed, fragment)) {
        return true;
      }
    }
    return false;
  }
}
