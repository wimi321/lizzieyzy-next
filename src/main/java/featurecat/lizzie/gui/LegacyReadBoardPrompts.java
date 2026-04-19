package featurecat.lizzie.gui;

import java.net.URI;
import java.util.Objects;
import java.util.ResourceBundle;

final class LegacyReadBoardPrompts {
  static final String DOWNLOAD_URL = "https://github.com/qiyi71w/readboard/releases";

  private LegacyReadBoardPrompts() {}

  static boolean promptMissingLegacyReadBoardDownload(
      ResourceBundle resourceBundle,
      ConfirmationDialog confirmationDialog,
      BrowserOpener browserOpener,
      ErrorReporter errorReporter) {
    Objects.requireNonNull(resourceBundle, "resourceBundle");
    Objects.requireNonNull(confirmationDialog, "confirmationDialog");
    Objects.requireNonNull(browserOpener, "browserOpener");
    Objects.requireNonNull(errorReporter, "errorReporter");

    boolean confirmed =
        confirmationDialog.confirm(
            resourceBundle.getString("ReadBoard.missingLegacyTitle"),
            resourceBundle.getString("ReadBoard.missingLegacyDownload"),
            resourceBundle.getString("LizzieFrame.confirm"),
            resourceBundle.getString("LizzieFrame.cancel"));
    if (!confirmed) {
      return false;
    }

    try {
      browserOpener.browse(URI.create(DOWNLOAD_URL));
      return true;
    } catch (Exception e) {
      errorReporter.report(resolveErrorMessage(e));
      return false;
    }
  }

  private static String resolveErrorMessage(Exception e) {
    String message = e.getLocalizedMessage();
    if (message == null || message.isBlank()) {
      return e.toString();
    }
    return message;
  }

  @FunctionalInterface
  interface ConfirmationDialog {
    boolean confirm(String title, String message, String confirmLabel, String cancelLabel);
  }

  @FunctionalInterface
  interface BrowserOpener {
    void browse(URI uri) throws Exception;
  }

  @FunctionalInterface
  interface ErrorReporter {
    void report(String message);
  }
}
