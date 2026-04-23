package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.io.IOException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

public class TrackingConsolePane extends JDialog {
  private JIMSendTextPane console;
  private JScrollPane scrollPane;
  private Font consoleFont;
  private static final int MAX_LENGTH = 15000;

  public TrackingConsolePane() {
    super();
    setModalityType(ModalityType.MODELESS);
    setTitle(Lizzie.resourceBundle.getString("TrackingConsolePane.title"));
    try (java.io.InputStream fontStream =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("fonts/SourceCodePro-Regular.ttf")) {
      consoleFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
      consoleFont = consoleFont.deriveFont(Font.PLAIN, Config.frameFontSize);
    } catch (IOException | FontFormatException e) {
      consoleFont = new Font(Font.MONOSPACED, Font.PLAIN, Config.frameFontSize);
    }

    Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
    setBounds((int) screensize.getWidth() - 450, (int) screensize.getHeight() - 400, 450, 350);

    console = new JIMSendTextPane(false);
    console.setBorder(BorderFactory.createEmptyBorder());
    console.setEditable(false);
    console.setBackground(Color.BLACK);
    console.setForeground(Color.LIGHT_GRAY);
    console.setFont(consoleFont);

    scrollPane = new JScrollPane();
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    scrollPane.setViewportView(console);
    getContentPane().add(scrollPane, BorderLayout.CENTER);

    JPanel bottomPane = new JPanel(new BorderLayout());
    JButton btnShutdown =
        new JFontButton(Lizzie.resourceBundle.getString("TrackingConsolePane.shutdown"));
    btnShutdown.setMargin(new Insets(0, 5, 0, 5));
    btnShutdown.setFocusable(false);
    btnShutdown.addActionListener(
        (ActionEvent e) -> {
          if (Lizzie.frame == null) return;
          Lizzie.frame.clearTrackedCoords();
          Lizzie.frame.destroyTrackingEngine();
        });
    JButton btnClear = new JFontButton(Lizzie.resourceBundle.getString("GtpConsolePane.clear"));
    btnClear.setMargin(new Insets(0, 5, 0, 5));
    btnClear.setFocusable(false);
    btnClear.addActionListener((ActionEvent e) -> console.setText(""));
    JPanel rightButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 5, 0));
    rightButtons.add(btnClear);
    rightButtons.add(btnShutdown);
    bottomPane.add(rightButtons, BorderLayout.EAST);
    getContentPane().add(bottomPane, BorderLayout.SOUTH);

    getRootPane().setBorder(BorderFactory.createEmptyBorder());
  }

  private final StringBuilder pendingBuffer = new StringBuilder();
  private boolean flushScheduled = false;
  private static final int MAX_BUFFER_BYTES = 8192;

  public void addLine(String line) {
    if (line == null || line.isEmpty()) return;
    synchronized (pendingBuffer) {
      if (pendingBuffer.length() >= MAX_BUFFER_BYTES) return;
      pendingBuffer.append(line).append("\n");
      if (flushScheduled) return;
      flushScheduled = true;
    }
    SwingUtilities.invokeLater(this::flushPending);
  }

  private void flushPending() {
    String text;
    synchronized (pendingBuffer) {
      text = pendingBuffer.toString();
      pendingBuffer.setLength(0);
      flushScheduled = false;
    }
    if (text.isEmpty()) return;
    SimpleAttributeSet attr = new SimpleAttributeSet();
    StyleConstants.setForeground(attr, Color.GREEN);
    StyleConstants.setFontSize(attr, Config.frameFontSize);
    Document doc = console.getDocument();
    try {
      if (doc.getLength() > MAX_LENGTH) {
        doc.remove(0, doc.getLength() - MAX_LENGTH / 2);
      }
      doc.insertString(doc.getLength(), text, attr);
      console.setCaretPosition(doc.getLength());
    } catch (BadLocationException e) {
      // ignore
    }
  }
}
