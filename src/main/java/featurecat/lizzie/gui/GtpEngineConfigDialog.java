package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationSchema;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.json.JSONObject;

/** Schema-driven editor for optional, client-owned GTP engine configuration profiles. */
public final class GtpEngineConfigDialog {
  private GtpEngineConfigDialog() {}

  public static Optional<JSONObject> showDialog(
      Component parent, GtpConfigurationSchema schema, JSONObject savedProfile) {
    ResourceBundle bundle = Lizzie.resourceBundle;
    Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    JDialog dialog =
        new JDialog(
            owner,
            bundle.getString("GtpEngineConfig.title"),
            Dialog.ModalityType.APPLICATION_MODAL);
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

    EditorPanel editor = new EditorPanel(schema, savedProfile, bundle);
    AtomicReference<JSONObject> result = new AtomicReference<JSONObject>();
    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
    content.add(createHeader(bundle), BorderLayout.NORTH);
    JScrollPane scrollPane = new JScrollPane(editor);
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    scrollPane.getVerticalScrollBar().setUnitIncrement(16);
    content.add(scrollPane, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    JButton defaults = new JButton(bundle.getString("GtpEngineConfig.restoreDefaults"));
    JButton cancel = new JButton(bundle.getString("GtpEngineConfig.cancel"));
    JButton save = new JButton(bundle.getString("GtpEngineConfig.save"));
    buttons.add(defaults);
    buttons.add(Box.createHorizontalStrut(12));
    buttons.add(cancel);
    buttons.add(save);
    content.add(buttons, BorderLayout.SOUTH);

    defaults.addActionListener(event -> editor.restoreDefaults());
    cancel.addActionListener(event -> dialog.dispose());
    save.addActionListener(
        event -> {
          try {
            result.set(editor.profile());
            dialog.dispose();
          } catch (IllegalArgumentException error) {
            JOptionPane.showMessageDialog(
                dialog,
                error.getMessage(),
                bundle.getString("GtpEngineConfig.invalidValue"),
                JOptionPane.WARNING_MESSAGE);
          }
        });
    dialog.getRootPane().setDefaultButton(save);
    dialog
        .getRootPane()
        .registerKeyboardAction(
            event -> dialog.dispose(),
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

    AccessibilitySupport.button(save, save.getText(), bundle.getString("GtpEngineConfig.subtitle"));
    AccessibilitySupport.button(cancel, cancel.getText(), cancel.getText());
    AccessibilitySupport.button(defaults, defaults.getText(), defaults.getText());
    AccessibilitySupport.applyToTree(content);
    dialog.setContentPane(content);
    dialog.setMinimumSize(new Dimension(560, 480));
    dialog.setPreferredSize(new Dimension(640, 620));
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
    return Optional.ofNullable(result.get());
  }

  private static JPanel createHeader(ResourceBundle bundle) {
    JPanel header = new JPanel();
    header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
    JLabel title = new JLabel(bundle.getString("GtpEngineConfig.title"));
    title.setFont(
        new Font(Config.sysDefaultFontName, Font.BOLD, Math.max(20, Config.frameFontSize + 5)));
    JLabel subtitle =
        wrappedLabel(bundle.getString("GtpEngineConfig.subtitle"), secondaryTextColor(), 520);
    subtitle.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Math.max(13, Config.frameFontSize)));
    header.add(title);
    header.add(Box.createVerticalStrut(5));
    header.add(subtitle);
    return header;
  }

  static final class EditorPanel extends JPanel implements Scrollable {
    private final GtpConfigurationSchema schema;
    private final ResourceBundle bundle;
    private final Map<String, ValueEditor> editors = new LinkedHashMap<String, ValueEditor>();
    private final Map<String, JLabel> labels = new LinkedHashMap<String, JLabel>();
    private final JPanel advancedFields = new JPanel(new GridBagLayout());
    private final JButton advancedToggle = new JButton();
    private boolean advancedVisible;

    EditorPanel(GtpConfigurationSchema schema, JSONObject savedProfile, ResourceBundle bundle) {
      this.schema = schema;
      this.bundle = bundle;
      setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
      JSONObject values = schema.valuesWithSavedProfile(savedProfile);
      JPanel basicFields = new JPanel(new GridBagLayout());
      int basicRow = 0;
      int advancedRow = 0;
      for (GtpConfigurationSchema.Field field : schema.fields()) {
        ValueEditor editor = createEditor(field, values.opt(field.name()));
        editors.put(field.name(), editor);
        JPanel target = field.isBasic() ? basicFields : advancedFields;
        int row = field.isBasic() ? basicRow++ : advancedRow++;
        addFieldRow(target, row, field, editor.component());
      }

      add(sectionTitle(bundle.getString("GtpEngineConfig.basic")));
      add(Box.createVerticalStrut(6));
      add(basicFields);
      add(Box.createVerticalStrut(10));
      advancedToggle.setHorizontalAlignment(SwingConstants.LEFT);
      advancedToggle.setBorderPainted(false);
      advancedToggle.setContentAreaFilled(false);
      advancedToggle.setFocusPainted(true);
      advancedToggle.setMargin(new Insets(4, 0, 4, 0));
      advancedToggle.addActionListener(event -> setAdvancedVisible(!advancedVisible));
      add(advancedToggle);
      add(advancedFields);
      add(Box.createVerticalStrut(10));
      JLabel applyHint =
          wrappedLabel(bundle.getString("GtpEngineConfig.nextSearch"), secondaryTextColor(), 500);
      add(applyHint);
      JLabel effectiveHint = effectiveHint();
      if (effectiveHint != null) {
        add(Box.createVerticalStrut(4));
        add(effectiveHint);
      }
      setAdvancedVisible(false);
      updateActiveFields();
    }

    JSONObject profile() {
      JSONObject values = new JSONObject();
      for (GtpConfigurationSchema.Field field : schema.fields()) {
        Object value = editors.get(field.name()).value();
        if (!field.accepts(value)) {
          throw new IllegalArgumentException(
              bundle.getString("GtpEngineConfig.invalidField") + " " + fieldLabel(field.name()));
        }
        values.put(field.name(), value);
      }
      return values;
    }

    boolean isFieldEnabled(String name) {
      ValueEditor editor = editors.get(name);
      return editor != null && editor.component().isEnabled();
    }

    void setFieldValue(String name, Object value) {
      ValueEditor editor = editors.get(name);
      if (editor == null) {
        throw new IllegalArgumentException("Unknown field: " + name);
      }
      editor.setValue(value);
      updateActiveFields();
    }

    boolean hasAccessibleEditorNames() {
      for (ValueEditor editor : editors.values()) {
        String name = editor.component().getAccessibleContext().getAccessibleName();
        if (name == null || name.isBlank()) {
          return false;
        }
      }
      return true;
    }

    boolean hasReadableNumericEditors() {
      for (ValueEditor editor : editors.values()) {
        if (editor instanceof SpinnerEditor
            && !((SpinnerEditor) editor).hasReadableText()) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
        Rectangle visibleRect, int orientation, int direction) {
      return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
        Rectangle visibleRect, int orientation, int direction) {
      return 64;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }

    void restoreDefaults() {
      for (GtpConfigurationSchema.Field field : schema.fields()) {
        editors.get(field.name()).setValue(field.defaultValue());
      }
      updateActiveFields();
    }

    private void setAdvancedVisible(boolean visible) {
      advancedVisible = visible;
      advancedFields.setVisible(visible);
      advancedToggle.setText(
          bundle.getString(
              visible ? "GtpEngineConfig.hideAdvanced" : "GtpEngineConfig.showAdvanced"));
      revalidate();
    }

    private void updateActiveFields() {
      JSONObject values = rawValues();
      for (GtpConfigurationSchema.Field field : schema.fields()) {
        boolean enabled = field.isActive(values);
        editors.get(field.name()).component().setEnabled(enabled);
        JLabel label = labels.get(field.name());
        if (label != null) {
          label.setEnabled(enabled);
        }
      }
    }

    private JSONObject rawValues() {
      JSONObject values = new JSONObject();
      for (Map.Entry<String, ValueEditor> entry : editors.entrySet()) {
        values.put(entry.getKey(), entry.getValue().value());
      }
      return values;
    }

    private ValueEditor createEditor(GtpConfigurationSchema.Field field, Object value) {
      if ("string".equals(field.type()) && !field.enumValues().isEmpty()) {
        JComboBox<Choice> combo = new JComboBox<Choice>();
        for (String option : field.enumValues()) {
          combo.addItem(new Choice(option, enumLabel(field.name(), option)));
        }
        ValueEditor editor = new ComboEditor(combo);
        editor.setValue(value);
        combo.addActionListener(event -> updateActiveFields());
        return editor;
      }
      if ("integer".equals(field.type()) || "number".equals(field.type())) {
        boolean integer = "integer".equals(field.type());
        Number initial = numberValue(value, field.defaultValue(), integer);
        Comparable<?> minimum =
            field.minimum() == null ? null : integer ? field.minimum().intValue() : field.minimum();
        Comparable<?> maximum =
            field.maximum() == null ? null : integer ? field.maximum().intValue() : field.maximum();
        Number step = integer ? 1 : numericStep(field);
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, minimum, maximum, step));
        return new SpinnerEditor(spinner, integer);
      }
      JTextField fieldControl = new JTextField(value == null ? "" : value.toString());
      return new TextEditor(fieldControl);
    }

    private void addFieldRow(
        JPanel panel, int row, GtpConfigurationSchema.Field field, JComponent component) {
      GridBagConstraints labelConstraints = new GridBagConstraints();
      labelConstraints.gridx = 0;
      labelConstraints.gridy = row;
      labelConstraints.weightx = 0;
      labelConstraints.anchor = GridBagConstraints.WEST;
      labelConstraints.insets = new Insets(5, 0, 5, 16);
      JLabel label = new JLabel(fieldLabel(field.name()));
      labels.put(field.name(), label);
      panel.add(label, labelConstraints);

      GridBagConstraints controlConstraints = new GridBagConstraints();
      controlConstraints.gridx = 1;
      controlConstraints.gridy = row;
      controlConstraints.weightx = 1;
      controlConstraints.fill = GridBagConstraints.HORIZONTAL;
      controlConstraints.insets = new Insets(5, 0, 5, 0);
      component.setPreferredSize(
          new Dimension(280, Math.max(30, component.getPreferredSize().height)));
      panel.add(component, controlConstraints);
      AccessibilitySupport.labelFor(label, component, label.getText());
    }

    private String fieldLabel(String name) {
      return localized("GtpEngineConfig.field." + name, name);
    }

    private String enumLabel(String fieldName, String value) {
      String key = "GtpEngineConfig.value." + fieldName + "." + value;
      if (bundle.containsKey(key)) {
        return bundle.getString(key);
      }
      if ("rankPreset".equals(fieldName) && value.length() > 1) {
        String suffix = value.substring(value.length() - 1);
        String number = value.substring(0, value.length() - 1);
        String rankKey =
            "k".equals(suffix) ? "GtpEngineConfig.rank.kyu" : "GtpEngineConfig.rank.dan";
        if (bundle.containsKey(rankKey)) {
          return String.format(bundle.getString(rankKey), number);
        }
      }
      return value;
    }

    private String localized(String key, String fallback) {
      return bundle.containsKey(key) ? bundle.getString(key) : fallback;
    }

    private JLabel effectiveHint() {
      JSONObject selected = schema.selected();
      JSONObject effective = schema.effective();
      for (GtpConfigurationSchema.Field field : schema.fields()) {
        Object selectedValue = selected.opt(field.name());
        Object effectiveValue = effective.opt(field.name());
        if (selectedValue != null
            && effectiveValue != null
            && !sameValue(selectedValue, effectiveValue)) {
          String selectedText = displayValue(field, selectedValue);
          String effectiveText = displayValue(field, effectiveValue);
          return wrappedLabel(
              String.format(
                  bundle.getString("GtpEngineConfig.effectiveDifference"),
                  fieldLabel(field.name()),
                  selectedText,
                  effectiveText),
              secondaryTextColor(),
              500);
        }
      }
      return null;
    }

    private String displayValue(GtpConfigurationSchema.Field field, Object value) {
      return value instanceof String ? enumLabel(field.name(), value.toString()) : value.toString();
    }

    private static Number numberValue(Object value, Object fallback, boolean integer) {
      Object candidate = value instanceof Number ? value : fallback;
      Number number = candidate instanceof Number ? (Number) candidate : 0;
      return integer ? number.intValue() : number.doubleValue();
    }

    private static double numericStep(GtpConfigurationSchema.Field field) {
      return field.maximum() != null && field.maximum() <= 1.0 ? 0.01 : 0.5;
    }

    private static JLabel sectionTitle(String text) {
      JLabel label = new JLabel(text);
      label.setFont(
          new Font(Config.sysDefaultFontName, Font.BOLD, Math.max(14, Config.frameFontSize + 1)));
      return label;
    }
  }

  private static Color secondaryTextColor() {
    Color color = UIManager.getColor("Label.disabledForeground");
    return color == null ? Color.GRAY : color;
  }

  static boolean sameValue(Object left, Object right) {
    if (left instanceof Number && right instanceof Number) {
      return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
    }
    return left == null ? right == null : left.equals(right);
  }

  private static JLabel wrappedLabel(String text, Color color, int width) {
    String escaped =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    JLabel label =
        new JLabel("<html><div style='width:" + width + "px'>" + escaped + "</div></html>");
    label.setForeground(color);
    label.getAccessibleContext().setAccessibleName(text);
    return label;
  }

  private interface ValueEditor {
    JComponent component();

    Object value();

    void setValue(Object value);
  }

  private static final class Choice {
    private final String value;
    private final String label;

    private Choice(String value, String label) {
      this.value = value;
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private static final class ComboEditor implements ValueEditor {
    private final JComboBox<Choice> component;

    private ComboEditor(JComboBox<Choice> component) {
      this.component = component;
    }

    @Override
    public JComponent component() {
      return component;
    }

    @Override
    public Object value() {
      Choice choice = (Choice) component.getSelectedItem();
      return choice == null ? "" : choice.value;
    }

    @Override
    public void setValue(Object value) {
      for (int i = 0; i < component.getItemCount(); i++) {
        Choice choice = component.getItemAt(i);
        if (choice.value.equals(value)) {
          component.setSelectedIndex(i);
          return;
        }
      }
    }
  }

  private static final class SpinnerEditor implements ValueEditor {
    private final JSpinner component;
    private final boolean integer;

    private SpinnerEditor(JSpinner component, boolean integer) {
      this.component = component;
      this.integer = integer;
      if (component.getEditor() instanceof JSpinner.DefaultEditor) {
        javax.swing.JFormattedTextField textField =
            ((JSpinner.DefaultEditor) component.getEditor()).getTextField();
        Color foreground = UIManager.getColor("TextField.foreground");
        Color background = UIManager.getColor("TextField.background");
        Color caret = UIManager.getColor("TextField.caretForeground");
        if (foreground != null) {
          textField.setForeground(foreground);
        }
        if (background != null) {
          textField.setBackground(background);
        }
        if (caret != null) {
          textField.setCaretColor(caret);
        }
        textField.setHorizontalAlignment(JTextField.LEADING);
      }
    }

    private boolean hasReadableText() {
      if (!(component.getEditor() instanceof JSpinner.DefaultEditor)) {
        return true;
      }
      javax.swing.JFormattedTextField textField =
          ((JSpinner.DefaultEditor) component.getEditor()).getTextField();
      return !textField.getForeground().equals(textField.getBackground());
    }

    @Override
    public JComponent component() {
      return component;
    }

    @Override
    public Object value() {
      Number value = (Number) component.getValue();
      return integer ? value.intValue() : value.doubleValue();
    }

    @Override
    public void setValue(Object value) {
      if (value instanceof Number) {
        component.setValue(integer ? ((Number) value).intValue() : ((Number) value).doubleValue());
      }
    }
  }

  private static final class TextEditor implements ValueEditor {
    private final JTextField component;

    private TextEditor(JTextField component) {
      this.component = component;
    }

    @Override
    public JComponent component() {
      return component;
    }

    @Override
    public Object value() {
      return component.getText();
    }

    @Override
    public void setValue(Object value) {
      component.setText(value == null ? "" : value.toString());
    }
  }
}
