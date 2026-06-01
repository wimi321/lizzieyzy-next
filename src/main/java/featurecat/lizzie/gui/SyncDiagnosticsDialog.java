package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.SyncDiagnosticsEnvironment;
import featurecat.lizzie.analysis.SyncDiagnosticsExporter;
import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class SyncDiagnosticsDialog extends JDialog {
  private static final long serialVersionUID = -5489515334083853604L;
  private static final int DEFAULT_WIDTH = 760;
  private static final int DEFAULT_HEIGHT = 520;

  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private final JTextArea summaryTextArea = new JTextArea();

  public SyncDiagnosticsDialog(Window owner) {
    super(owner);
    setTitle(text("SyncDiagnostics.title", "Sync diagnostics"));
    setModalityType(ModalityType.MODELESS);
    if (Lizzie.frame != null) {
      setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    }
    setDefaultCloseOperation(HIDE_ON_CLOSE);

    summaryTextArea.setEditable(false);
    summaryTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    summaryTextArea.setLineWrap(false);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
    JButton refreshButton = new JButton(text("SyncDiagnostics.refresh", "Refresh"));
    JButton copyButton = new JButton(text("SyncDiagnostics.copy", "Copy summary"));
    JButton exportButton = new JButton(text("SyncDiagnostics.export", "Export package"));
    JButton closeButton = new JButton(text("SyncDiagnostics.close", "Close"));
    buttonPanel.add(refreshButton);
    buttonPanel.add(copyButton);
    buttonPanel.add(exportButton);
    buttonPanel.add(closeButton);

    refreshButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            refreshSummary();
          }
        });
    copyButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            copySummary();
          }
        });
    exportButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            exportDiagnosticsPackage();
          }
        });
    closeButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });

    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    content.add(new JScrollPane(summaryTextArea), BorderLayout.CENTER);
    content.add(buttonPanel, BorderLayout.SOUTH);
    setContentPane(content);

    refreshSummary();
    setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    setLocationRelativeTo(owner);
  }

  private void refreshSummary() {
    summaryTextArea.setText(SyncDiagnosticsRecorder.getDefault().snapshot().toSummaryText());
    summaryTextArea.setCaretPosition(0);
  }

  private void copySummary() {
    String summary = summaryTextArea.getText();
    Toolkit.getDefaultToolkit()
        .getSystemClipboard()
        .setContents(new StringSelection(summary), null);
  }

  private void exportDiagnosticsPackage() {
    try {
      Path zip =
          new SyncDiagnosticsExporter(SyncDiagnosticsExporter.defaultOutputDirectory())
              .export(SyncDiagnosticsRecorder.getDefault().exportSnapshot());
      refreshSummary();
      appendStatus(text("SyncDiagnostics.exportSuccess", "Exported to:") + " " + zip);
    } catch (IOException | RuntimeException ex) {
      appendStatus(
          text("SyncDiagnostics.exportFailure", "Export failed:")
              + " "
              + sanitizeFailureMessage(ex.getMessage()));
    }
  }

  private void appendStatus(String status) {
    summaryTextArea.append("\n\n" + status);
  }

  private static String sanitizeFailureMessage(String message) {
    try {
      Method sanitizePath =
          SyncDiagnosticsEnvironment.class.getDeclaredMethod("sanitizePath", String.class);
      sanitizePath.setAccessible(true);
      return (String) sanitizePath.invoke(null, message);
    } catch (ReflectiveOperationException | SecurityException ignored) {
      return "<redacted-path>";
    }
  }

  private String text(String key, String fallback) {
    try {
      if (resourceBundle != null) {
        return resourceBundle.getString(key);
      }
    } catch (MissingResourceException ignored) {
    }
    return fallback;
  }
}
