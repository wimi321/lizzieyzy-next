package featurecat.lizzie.theme;

import java.awt.Color;

public class MorandiPalette {
  public static final Color WARM_GRAY = new Color(189, 183, 172);
  public static final Color COOL_GRAY = new Color(176, 178, 180);
  public static final Color CREAM_WHITE = new Color(240, 235, 228);
  public static final Color CHARCOAL = new Color(62, 58, 57);

  public static final Color MUDED_RED = new Color(176, 112, 112);
  public static final Color MUDED_ORANGE = new Color(192, 155, 120);
  public static final Color MUDED_YELLOW = new Color(198, 186, 138);
  public static final Color MUDED_GREEN = new Color(142, 172, 130);
  public static final Color MUDED_TEAL = new Color(120, 168, 168);
  public static final Color MUDED_PURPLE = new Color(148, 126, 158);

  public static final Color BOARD_WOOD = new Color(198, 178, 148);
  public static final Color BOARD_LINE = new Color(88, 76, 66);
  public static final Color BG_PRIMARY = new Color(168, 162, 155);
  public static final Color BG_SECONDARY = new Color(182, 177, 170);

  public static final Color GLASS_OVERLAY = new Color(230, 225, 218, 40);
  public static final Color GLASS_BORDER = new Color(255, 255, 255, 55);
  public static final Color GLASS_HIGHLIGHT = new Color(255, 255, 255, 75);

  public static final Color WINRATE_BLACK = new Color(82, 78, 76);
  public static final Color WINRATE_WHITE = new Color(218, 213, 206);
  public static final Color SCOREMEAN_LINE = new Color(148, 126, 158);

  public static final Color SUGGESTION_BLUNDER = MUDED_PURPLE;
  public static final Color SUGGESTION_MISTAKE = MUDED_RED;
  public static final Color SUGGESTION_SLOW = MUDED_ORANGE;
  public static final Color SUGGESTION_CAUTION = MUDED_YELLOW;
  public static final Color SUGGESTION_GOOD = MUDED_GREEN;
  public static final Color SUGGESTION_BEST = MUDED_TEAL;

  public static final Color SUGGESTION_BLUNDER_ALPHA = new Color(148, 126, 158, 120);
  public static final Color SUGGESTION_MISTAKE_ALPHA = new Color(176, 112, 112, 100);
  public static final Color SUGGESTION_SLOW_ALPHA = new Color(192, 155, 120, 100);
  public static final Color SUGGESTION_CAUTION_ALPHA = new Color(198, 186, 138, 100);
  public static final Color SUGGESTION_GOOD_ALPHA = new Color(142, 172, 130, 100);
  public static final Color SUGGESTION_BEST_ALPHA = new Color(120, 168, 168, 100);

  public static final Color SUGGESTION_BLUNDER_LIGHT = new Color(148, 126, 158, 70);
  public static final Color SUGGESTION_MISTAKE_LIGHT = new Color(176, 112, 112, 50);
  public static final Color SUGGESTION_SLOW_LIGHT = new Color(192, 155, 120, 50);
  public static final Color SUGGESTION_CAUTION_LIGHT = new Color(198, 186, 138, 50);
  public static final Color SUGGESTION_GOOD_LIGHT = new Color(142, 172, 130, 50);
  public static final Color SUGGESTION_BEST_LIGHT = new Color(120, 168, 168, 60);

  public static final Color PANEL_BACKGROUND = new Color(168, 162, 155, 200);
  public static final Color PANEL_BACKGROUND_LIGHT = new Color(182, 177, 170, 180);
  public static final Color TABLE_ROW_EVEN = new Color(230, 225, 218, 25);
  public static final Color TABLE_ROW_ODD = new Color(210, 205, 198, 35);
  public static final Color TABLE_HEADER = new Color(148, 143, 136, 180);
  public static final Color SCROLLBAR_TRACK = new Color(168, 162, 155, 40);
  public static final Color SCROLLBAR_THUMB = new Color(148, 143, 136, 120);

  // Toolbar & Menu colors
  public static final Color TOOLBAR_BG = new Color(225, 220, 213);
  public static final Color TOOLBAR_BORDER = new Color(195, 190, 183);
  public static final Color TOOLBAR_BUTTON_BG = new Color(238, 233, 226);
  public static final Color TOOLBAR_BUTTON_HOVER = new Color(218, 213, 206);
  public static final Color TOOLBAR_BUTTON_PRESSED = new Color(205, 200, 193);
  public static final Color TOOLBAR_BUTTON_BORDER = new Color(200, 195, 188);
  public static final Color TOOLBAR_TEXT = new Color(72, 68, 65);
  public static final Color MENU_ITEM_HOVER = new Color(210, 205, 198);
  public static final Color MENU_ITEM_SELECTED = new Color(195, 190, 183);
  public static final Color MENU_ITEM_TEXT = new Color(62, 58, 57);
  public static final Color TOOLBAR_SEPARATOR = new Color(205, 200, 193);

  public static final Color TEXT_PRIMARY = CHARCOAL;
  public static final Color TEXT_SECONDARY = new Color(120, 115, 108);
  public static final Color TEXT_ON_DARK = CREAM_WHITE;

  public static final Color CONTROLS_OVERLAY = new Color(62, 58, 57, 160);
  public static final Color CONTROLS_BORDER = new Color(120, 115, 108, 80);

  public static Color getSuggestionColor(int level) {
    switch (level) {
      case 0:
        return SUGGESTION_BEST;
      case 1:
        return SUGGESTION_GOOD;
      case 2:
        return SUGGESTION_CAUTION;
      case 3:
        return SUGGESTION_SLOW;
      case 4:
        return SUGGESTION_MISTAKE;
      case 5:
        return SUGGESTION_BLUNDER;
      default:
        return SUGGESTION_BEST;
    }
  }

  public static Color getSuggestionAlphaColor(int level) {
    switch (level) {
      case 0:
        return SUGGESTION_BEST_ALPHA;
      case 1:
        return SUGGESTION_GOOD_ALPHA;
      case 2:
        return SUGGESTION_CAUTION_ALPHA;
      case 3:
        return SUGGESTION_SLOW_ALPHA;
      case 4:
        return SUGGESTION_MISTAKE_ALPHA;
      case 5:
        return SUGGESTION_BLUNDER_ALPHA;
      default:
        return SUGGESTION_BEST_ALPHA;
    }
  }

  public static Color getSuggestionLightColor(int level) {
    switch (level) {
      case 0:
        return SUGGESTION_BEST_LIGHT;
      case 1:
        return SUGGESTION_GOOD_LIGHT;
      case 2:
        return SUGGESTION_CAUTION_LIGHT;
      case 3:
        return SUGGESTION_SLOW_LIGHT;
      case 4:
        return SUGGESTION_MISTAKE_LIGHT;
      case 5:
        return SUGGESTION_BLUNDER_LIGHT;
      default:
        return SUGGESTION_BEST_LIGHT;
    }
  }
}
