package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.gui.LizzieFrame.HtmlKit;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JTextPane;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.StyleSheet;

public class HtmlMessage extends JDialog {

  public HtmlMessage(String title, String content, Window owner) {
    super(owner);
    this.setResizable(false);
    // setType(Type.POPUP);
    setTitle(title);
    setAlwaysOnTop(true);
    JTextPane lblMessage = new JTextPane();
    getContentPane().setBackground(MessageTheme.background());
    MessageTheme.apply(lblMessage);
    HTMLDocument htmlDoc;
    HtmlKit htmlKit;
    StyleSheet htmlStyle;

    htmlKit = new HtmlKit();
    htmlDoc = (HTMLDocument) htmlKit.createDefaultDocument();
    htmlStyle = htmlKit.getStyleSheet();
    String background = MessageTheme.cssColor(MessageTheme.background());
    String foreground = MessageTheme.cssColor(MessageTheme.foreground());
    String style =
        "body {background:#"
            + background
            + "; color:#"
            + foreground
            + "; font-family:"
            + Config.sysDefaultFontName
            + ", 'Microsoft YaHei', 'PingFang SC', Consolas, Menlo, Monaco, 'Ubuntu Mono', monospace;"
            + "}";
    htmlStyle.addRule(style);

    lblMessage.setEditorKit(htmlKit);
    lblMessage.setDocument(htmlDoc);
    lblMessage.setText(content);
    lblMessage.setEditable(false);
    lblMessage.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    lblMessage.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    lblMessage.addHyperlinkListener(
        new HyperlinkListener() {
          public void hyperlinkUpdate(HyperlinkEvent e) {
            if (HyperlinkEvent.EventType.ACTIVATED.equals(e.getEventType())) {
              if (Desktop.isDesktopSupported()) {
                try {
                  Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (Exception ex) {
                }
              }
            }
          }
        });
    this.add(lblMessage);
    lblMessage.setBorder(new EmptyBorder(12, 12, 12, 12));
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    pack();
    setLocationRelativeTo(owner);
  }
}
