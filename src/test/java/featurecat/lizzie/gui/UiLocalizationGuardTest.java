package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UiLocalizationGuardTest {
  private static final Path GUI_ROOT = Path.of("src/main/java/featurecat/lizzie/gui");
  private static final List<String> PRIMARY_UI_FILES =
      List.of(
          "BottomToolbar.java",
          "ConfigDialog2.java",
          "KataGoAutoSetupDialog.java",
          "RemoteComputeDialog.java",
          "LoadEngine.java",
          "FoxKifuDownload.java",
          "TencentKifuDownload.java",
          "LizzieFrame.java",
          "SidebarHeaderPanel.java");
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("//.*");
  private static final Pattern DIRECT_UI_LITERAL =
      Pattern.compile(
          "(?:new\\s+(?:JFont)?(?:Label|Button|CheckBox|RadioButton|MenuItem)"
              + "|\\.set(?:Text|ToolTipText|Title)"
              + "|showMsg(?:NoModalForTime)?|showMessageDialog)"
              + "\\s*\\(\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"",
          Pattern.DOTALL);
  private static final Set<String> DIRECT_LITERAL_WHITELIST =
      Set.of(
          "",
          "<",
          ">",
          "<<",
          ">>",
          "|<",
          ">|",
          "《",
          "》",
          "0",
          "1",
          "2",
          "1/1",
          "<html>",
          "/assets/background.jpg",
          "/assets/board.png",
          "/assets/black0.png",
          "/assets/white0.png");

  @Test
  void primaryUiDirectLiteralsStayOnTheExplicitNonLanguageWhitelist() throws IOException {
    for (String name : PRIMARY_UI_FILES) {
      String source = Files.readString(GUI_ROOT.resolve(name), StandardCharsets.UTF_8);
      source = BLOCK_COMMENT.matcher(source).replaceAll("");
      source = LINE_COMMENT.matcher(source).replaceAll("");
      Matcher matcher = DIRECT_UI_LITERAL.matcher(source);
      while (matcher.find()) {
        String literal = matcher.group(1).trim();
        assertTrue(
            DIRECT_LITERAL_WHITELIST.contains(literal),
            name + " contains a direct user-visible literal outside the whitelist: " + literal);
      }
    }
  }
}
