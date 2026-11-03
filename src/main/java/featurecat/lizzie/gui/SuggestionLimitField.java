package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javax.swing.JToolBar;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The candidate limit field of the top toolbar's 限制 group: how many candidates the board shows, 0
 * for all, the setting the settings dialog calls 候选点上限. 自定义工具栏 shows or hides it, and it is hidden
 * by default. An edit takes effect at once and is saved like the dialog's.
 */
final class SuggestionLimitField {
  static final String SHOWN_KEY = "show-suggestion-limit-in-menu";

  private final Config config;
  private final Runnable repaint;
  final JFontCheckBoxMenuItem toggle;
  final JFontLabel label;
  final JFontTextField field;
  private boolean syncing;

  /**
   * @param relayout rebuilds the toolbar after the field is shown or hidden
   * @param repaint redraws the board after the limit changed
   */
  SuggestionLimitField(Config config, ResourceBundle strings, Runnable relayout, Runnable repaint) {
    this.config = config;
    this.repaint = repaint;
    toggle = new JFontCheckBoxMenuItem(strings.getString("Menu.showSuggestionLimit"));
    toggle.addActionListener(
        e -> {
          config.uiConfig.put(SHOWN_KEY, toggle.isSelected());
          relayout.run();
        });
    label = new JFontLabel(strings.getString("Menu.lblSuggestionLimit"));
    field = new JFontTextField();
    field.setDocument(new IntDocument());
    field.setColumns(2);
    field.setMaximumSize(new Dimension(40, Config.menuHeight));
    field.setPreferredSize(new Dimension(40, Config.menuHeight - 2));
    field.setToolTipText(strings.getString("Menu.txtSuggestionLimit.toolTip"));
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                apply();
              }

              public void removeUpdate(DocumentEvent e) {
                apply();
              }

              public void changedUpdate(DocumentEvent e) {
                apply();
              }
            });
  }

  boolean shown() {
    return config.uiConfig != null && config.uiConfig.optBoolean(SHOWN_KEY, false);
  }

  /** Shows the current choice and limit, which the settings dialog may have changed. */
  void sync() {
    toggle.setSelected(shown());
    String limit = String.valueOf(config.limitMaxSuggestion);
    if (limit.equals(field.getText())) return;
    syncing = true;
    try {
      field.setText(limit);
    } finally {
      syncing = false;
    }
  }

  void addTo(TopHeaderPanel toolbar) {
    addTo(toolbar::add);
  }

  void addTo(JToolBar toolbar) {
    addTo(toolbar::add);
  }

  private void addTo(Consumer<Component> add) {
    sync();
    add.accept(label);
    add.accept(field);
  }

  private void apply() {
    if (syncing) return;
    // An empty field, while a new number is being typed, keeps the limit.
    int limit = Utils.parseTextToInt(field, config.limitMaxSuggestion);
    if (limit == config.limitMaxSuggestion) return;
    config.limitMaxSuggestion = limit;
    config.leelazConfig.put("limit-max-suggestion", limit);
    repaint.run();
  }
}
