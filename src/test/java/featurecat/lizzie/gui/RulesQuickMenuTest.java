package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.RulePreset;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.Test;

class RulesQuickMenuTest {
  private static final ResourceBundle STRINGS =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

  @Test
  void listsEveryRuleSetWithTheCurrentOneTickedAndEndsWithTheRulesDialog() {
    Recorder actions = new Recorder();
    JPopupMenu menu =
        RulesQuickMenu.build(STRINGS, Optional.of(RulePreset.CHINESE_2027), false, actions);

    List<JMenuItem> items = items(menu);
    assertEquals(RulePreset.values().length + 1, items.size());
    for (int i = 0; i < RulePreset.values().length; i++) {
      RulePreset preset = RulePreset.values()[i];
      JCheckBoxMenuItem item = (JCheckBoxMenuItem) items.get(i);
      assertEquals(STRINGS.getString(preset.labelKey()), item.getText());
      assertEquals(preset == RulePreset.CHINESE_2027, item.isSelected(), preset.name());
    }
    assertTrue(items.get(0).getText().contains("3.5"));
    assertEquals(STRINGS.getString("RulesQuickMenu.more"), items.get(items.size() - 1).getText());
  }

  @Test
  void anEntryAppliesItsRuleSetAndTheLastOneOpensTheRulesDialog() {
    Recorder actions = new Recorder();
    List<JMenuItem> items = items(RulesQuickMenu.build(STRINGS, Optional.empty(), false, actions));

    items.get(RulePreset.JAPANESE.ordinal()).doClick();
    items.get(items.size() - 1).doClick();

    assertEquals(List.of(RulePreset.JAPANESE), actions.applied);
    assertEquals(1, actions.dialogs);
  }

  @Test
  void aHandicapGameSaysTheKomiStays() {
    List<JMenuItem> plain =
        items(RulesQuickMenu.build(STRINGS, Optional.empty(), false, new Recorder()));
    List<JMenuItem> handicap =
        items(RulesQuickMenu.build(STRINGS, Optional.empty(), true, new Recorder()));

    assertEquals(plain.size() + 1, handicap.size());
    JMenuItem note = handicap.get(RulePreset.values().length);
    assertEquals(STRINGS.getString("RulesQuickMenu.handicapKomi"), note.getText());
    assertFalse(note.isEnabled());
  }

  private static List<JMenuItem> items(JPopupMenu menu) {
    List<JMenuItem> items = new ArrayList<>();
    for (Component component : menu.getComponents()) {
      if (component instanceof JMenuItem item) items.add(item);
    }
    return items;
  }

  private static final class Recorder implements RulesQuickMenu.Actions {
    final List<RulePreset> applied = new ArrayList<>();
    int dialogs;

    @Override
    public void apply(RulePreset preset) {
      applied.add(preset);
    }

    @Override
    public void openRulesDialog() {
      dialogs++;
    }
  }
}
