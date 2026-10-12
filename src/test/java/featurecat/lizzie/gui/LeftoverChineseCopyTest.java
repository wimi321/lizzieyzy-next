package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import java.util.ResourceBundle;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LeftoverChineseCopyTest {
  private static final Pattern HAN_CHARACTER = Pattern.compile("[\\p{IsHan}]");

  @Test
  void englishHelpClearPersonalDataKeepsFourCategoriesWithoutHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();
    String menu = english.getString("Menu.clearAllPersonalData");
    String title = english.getString("Menu.clearAllPersonalData.confirmTitle");
    String confirm = english.getString("Menu.clearAllPersonalData.confirmMessage");
    String doneTitle = english.getString("Menu.clearAllPersonalData.doneTitle");
    String doneMessage = english.getString("Menu.clearAllPersonalData.doneMessage");

    assertEquals("Clear all personal data", menu);
    assertEquals("Clear all personal data", title);
    assertTrue(confirm.contains("Fox account search history"));
    assertTrue(confirm.contains("Recently opened game records"));
    assertTrue(confirm.contains("Batch analysis history"));
    assertTrue(confirm.contains("Shared game history"));
    assertTrue(confirm.contains("cannot be undone"));
    assertEquals("Done", doneTitle);
    assertEquals("Cleared.", doneMessage);
    assertNoHan(menu);
    assertNoHan(title);
    assertNoHan(confirm);
    assertNoHan(doneTitle);
    assertNoHan(doneMessage);
  }

  @Test
  void simplifiedChineseHelpClearPersonalDataKeepsOriginalMeaning() {
    ResourceBundle chinese = AppLocale.SIMPLIFIED_CHINESE.loadBundle();

    assertEquals("清除所有个人数据", chinese.getString("Menu.clearAllPersonalData"));
    assertEquals("清除所有个人数据", chinese.getString("Menu.clearAllPersonalData.confirmTitle"));
    assertEquals(
        "将清除以下个人数据:\n  • 野狐账号搜索记录\n  • 最近打开的棋谱列表\n  • 批量分析记录\n  • 分享棋谱历史\n\n" + "该操作不可撤销，是否继续?",
        chinese.getString("Menu.clearAllPersonalData.confirmMessage"));
    assertEquals("完成", chinese.getString("Menu.clearAllPersonalData.doneTitle"));
    assertEquals("已清除。", chinese.getString("Menu.clearAllPersonalData.doneMessage"));
  }

  @Test
  void englishProblemMovesEmptyStateHasNoHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    assertEquals("No problem moves", BlunderListPanel.emptyStatePrimary(false, english));
    assertEquals("Organizing problem moves...", BlunderListPanel.emptyStatePrimary(true, english));
    assertEquals(
        "After whole-game analysis, moves with larger winrate drops appear here.",
        BlunderListPanel.emptyStateHint(english));
    assertNoHan(BlunderListPanel.emptyStatePrimary(false, english));
    assertNoHan(BlunderListPanel.emptyStatePrimary(true, english).replace("⏳", ""));
    assertNoHan(BlunderListPanel.emptyStateHint(english));
  }

  @Test
  void simplifiedChineseProblemMovesEmptyStateKeepsOriginalMeaning() {
    ResourceBundle chinese = AppLocale.SIMPLIFIED_CHINESE.loadBundle();

    assertEquals("当前无问题手", BlunderListPanel.emptyStatePrimary(false, chinese));
    assertEquals("正在整理问题手...", BlunderListPanel.emptyStatePrimary(true, chinese));
    assertEquals("全盘分析后，这里会列出掉胜率较多的问题手", BlunderListPanel.emptyStateHint(chinese));
  }

  private static void assertNoHan(String value) {
    assertFalse(HAN_CHARACTER.matcher(value).find(), "English copy contains Han: " + value);
  }
}
