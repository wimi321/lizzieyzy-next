package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.RulePreset;
import java.util.Optional;
import java.util.ResourceBundle;
import javax.swing.JPopupMenu;

/**
 * The menu the rules caption at the top left of the main window opens: one entry per rule set with
 * the current one ticked, a note in a handicap game, and the way to the full rules dialog.
 */
final class RulesQuickMenu {
  /** What the entries do. */
  interface Actions {
    void apply(RulePreset preset);

    void openRulesDialog();
  }

  private RulesQuickMenu() {}

  static JPopupMenu build(
      ResourceBundle strings, Optional<RulePreset> current, boolean handicap, Actions actions) {
    JPopupMenu menu = new JPopupMenu();
    for (RulePreset preset : RulePreset.values()) {
      JFontCheckBoxMenuItem item = new JFontCheckBoxMenuItem(strings.getString(preset.labelKey()));
      item.setSelected(current.isPresent() && current.get() == preset);
      item.addActionListener(event -> actions.apply(preset));
      menu.add(item);
    }
    if (handicap) {
      JFontMenuItem note = new JFontMenuItem(strings.getString("RulesQuickMenu.handicapKomi"));
      note.setEnabled(false);
      menu.add(note);
    }
    menu.add(BoardMenuStyle.separator());
    JFontMenuItem more = new JFontMenuItem(strings.getString("RulesQuickMenu.more"));
    more.addActionListener(event -> actions.openRulesDialog());
    menu.add(more);
    BoardMenuStyle.install(menu);
    return menu;
  }
}
