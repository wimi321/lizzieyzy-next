package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.SyncDiagnosticsEnvironment;
import featurecat.lizzie.analysis.SyncDiagnosticsExporter;
import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;

public class SyncDiagnosticsDialog extends JDialog {
  private static final long serialVersionUID = -5489515334083853604L;
  private static final int DEFAULT_WIDTH = 760;
  private static final int DEFAULT_HEIGHT = 520;
  private static final Insets FOOTER_BUTTON_INSETS = new Insets(4, 14, 4, 14);
  private static final int FOOTER_BUTTON_EXTRA_WIDTH = 16;
  private static final int FOOTER_BUTTON_EXTRA_HEIGHT = 6;

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
    configureFooterButton(refreshButton);
    configureFooterButton(copyButton);
    configureFooterButton(exportButton);
    configureFooterButton(closeButton);
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
      appendStatus(exportSuccessStatus(text("SyncDiagnostics.exportSuccess", "Exported to:"), zip));
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
    return SyncDiagnosticsEnvironment.sanitizePath(message);
  }

  static String exportSuccessStatus(String label, Path zip) {
    return label + " " + displayExportPath(zip);
  }

  static void configureFooterButton(JButton button) {
    button.setUI(new StableFooterButtonUI());
    button.setFocusable(true);
    button.setFocusPainted(true);
    button.setRolloverEnabled(false);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setOpaque(false);
    button.setMargin(FOOTER_BUTTON_INSETS);
    button.setBorder(new EmptyBorder(FOOTER_BUTTON_INSETS));
    Dimension size = footerButtonSize(button);
    button.setMinimumSize(size);
    button.setPreferredSize(size);
  }

  private static Dimension footerButtonSize(JButton button) {
    FontMetrics metrics = button.getFontMetrics(button.getFont());
    Insets margin = button.getMargin();
    Dimension preferred = button.getPreferredSize();
    int width =
        metrics.stringWidth(button.getText())
            + margin.left
            + margin.right
            + FOOTER_BUTTON_EXTRA_WIDTH;
    int height =
        metrics.getHeight()
            + margin.top
            + margin.bottom
            + FOOTER_BUTTON_EXTRA_HEIGHT;
    return new Dimension(Math.max(preferred.width, width), Math.max(preferred.height, height));
  }

  static final class StableFooterButtonUI extends BasicButtonUI {
    @Override
    public void update(Graphics graphics, JComponent component) {
      paint(graphics, component);
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
      g.setColor(buttonBackground(button));
      g.fillRect(0, 0, component.getWidth(), component.getHeight());
      g.setColor(buttonBorder(button));
      g.drawRect(0, 0, component.getWidth() - 1, component.getHeight() - 1);
      if (button.isFocusPainted() && button.hasFocus()) {
        g.setColor(focusBorder());
        g.drawRect(2, 2, component.getWidth() - 5, component.getHeight() - 5);
      }
      g.dispose();
      super.paint(graphics, component);
    }

    @Override
    protected void paintButtonPressed(Graphics graphics, AbstractButton button) {}

    private Color buttonBackground(AbstractButton button) {
      Color base = UIManager.getColor("Button.background");
      if (base == null) {
        base = new Color(240, 240, 240);
      }
      if (!button.isEnabled()) {
        return blend(base, Color.WHITE, 0.45f);
      }
      if (button.getModel().isArmed() || button.getModel().isPressed()) {
        return blend(base, Color.GRAY, 0.16f);
      }
      return base;
    }

    private Color buttonBorder(AbstractButton button) {
      Color border = UIManager.getColor("Button.shadow");
      if (border == null) {
        border = new Color(150, 150, 150);
      }
      return button.isEnabled() ? border : blend(border, Color.WHITE, 0.45f);
    }

    private Color focusBorder() {
      Color focus = UIManager.getColor("Focus.color");
      return focus == null ? new Color(80, 130, 210) : focus;
    }

    private Color blend(Color a, Color b, float ratio) {
      float inverse = 1.0f - ratio;
      return new Color(
          Math.round(a.getRed() * inverse + b.getRed() * ratio),
          Math.round(a.getGreen() * inverse + b.getGreen() * ratio),
          Math.round(a.getBlue() * inverse + b.getBlue() * ratio));
    }
  }

  private static String displayExportPath(Path zip) {
    String projectRelative = projectRelativeExportPath(zip);
    return projectRelative == null ? sanitizeExportPath(zip) : projectRelative;
  }

  private static String projectRelativeExportPath(Path zip) {
    if (zip == null) {
      return null;
    }
    Path normalized = zip.toAbsolutePath().normalize();
    int nameCount = normalized.getNameCount();
    for (int i = 0; i < nameCount; i++) {
      String name = normalized.getName(i).toString();
      if ("lizzieyzy-next".equalsIgnoreCase(name)) {
        return normalized.subpath(i, nameCount).toString();
      }
      if ("sync-diagnostics".equalsIgnoreCase(name) && i > 0) {
        String parentName = normalized.getName(i - 1).toString();
        if (parentName.toLowerCase().startsWith("lizzieyzy-next")) {
          return normalized.subpath(i - 1, nameCount).toString();
        }
      }
    }
    return null;
  }

  private static String sanitizeExportPath(Path zip) {
    if (zip == null) {
      return "unknown";
    }
    String sanitized = SyncDiagnosticsEnvironment.sanitizePath(zip.toString());
    Path fileName = zip.getFileName();
    if (fileName == null
        || fileName.toString().isEmpty()
        || sanitized.endsWith(fileName.toString())) {
      return sanitized;
    }
    String separator = sanitized.contains("\\") ? "\\" : "/";
    return sanitized + separator + fileName;
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
