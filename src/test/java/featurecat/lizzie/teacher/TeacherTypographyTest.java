package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.AppleStyleSupport;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.SwingUtilities;
import javax.swing.text.GlyphView;
import javax.swing.text.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherTypographyTest {
  @TempDir Path temporary;

  @Test
  void controlsAndRenderedHtmlUseTheWorkspaceFontAndReadableSizes() throws Exception {
    withConfig(
        () -> {
          var view = new TeacherDialogView();
          String expected = AppleStyleSupport.workspaceFont(Font.PLAIN, 16).getFamily();
          assertEquals(expected, view.output().getFont().getFamily());
          assertEquals(expected, view.start().getFont().getFamily());
          assertTrue(view.start().getFont().getSize() >= 13);
          assertTrue(view.emptyDetail().getFont().getSize() >= 13);
          assertEquals(Font.PLAIN, view.settingsButton().getFont().getStyle());
          view.output().setText(TeacherDialog.markdownToHtml("Read clearly. **One key idea.**"));
          view.output().setSize(640, 400);
          view.output().getPreferredSize();
          var fonts = renderedFonts(view.output().getUI().getRootView(view.output()));
          assertFalse(fonts.isEmpty());
          for (Font font : fonts) {
            assertEquals(expected, font.getFamily(), "HTML must not fall back to a serif face");
            assertEquals(16, font.getSize(), "Swing HTML must not shrink the reading font");
          }
          assertTrue(fonts.stream().anyMatch(Font::isBold));
          assertTrue(fonts.stream().anyMatch(font -> !font.isBold()));
        });
  }

  @Test
  void customFontAndLargerUserTextRemainInEffect() throws Exception {
    withConfig(
        () -> {
          Lizzie.config.uiFontName = Font.MONOSPACED;
          Config.frameFontSize = 22;
          Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
          var view = new TeacherDialogView();
          view.output().setText(TeacherDialog.markdownToHtml("Custom reading font"));
          view.output().setSize(800, 500);
          view.output().getPreferredSize();
          assertEquals(Font.MONOSPACED, view.followUp().getFont().getFamily());
          assertEquals(22, view.followUp().getFont().getSize());
          for (Font font : renderedFonts(view.output().getUI().getRootView(view.output()))) {
            assertEquals(Font.MONOSPACED, font.getFamily());
            assertEquals(22, font.getSize());
          }
        });
  }

  private void withConfig(Runnable check) throws Exception {
    Config previous = Lizzie.config;
    int previousSize = Config.frameFontSize;
    var previousBundle = Lizzie.resourceBundle;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(temporary);
      Lizzie.config.uiFontName = Config.sysDefaultFontName;
      Lizzie.config.uiConfig = new org.json.JSONObject();
      Lizzie.config.theme = null;
      Lizzie.config.useLanguage = 1;
      Config.frameFontSize = 12;
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
      SwingUtilities.invokeAndWait(check);
    } finally {
      Lizzie.config = previous;
      Config.frameFontSize = previousSize;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  private static List<Font> renderedFonts(View view) {
    List<Font> fonts = new ArrayList<>();
    if (view instanceof GlyphView) fonts.add(((GlyphView) view).getFont());
    for (int i = 0; i < view.getViewCount(); i++) fonts.addAll(renderedFonts(view.getView(i)));
    return fonts;
  }
}
