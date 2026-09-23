package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SgfUiFilenameFieldTest {
  @Test
  void findsOnlyTheCurrentChoosersVisiblePresetFilename() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JFileChooser chooser = new JFileChooser();
          File selected = new File("中文-default.SGF");
          chooser.setSelectedFile(selected);
          JPanel panel = new JPanel();
          JTextField name = showingField(selected.getName());
          panel.add(name);
          panel.add(showingField("unrelated directory"));
          chooser.add(panel);
          assertSame(name, SgfUiAcceptanceIT.saveFileNameField(chooser));
          name.setText(selected.getAbsolutePath());
          assertSame(name, SgfUiAcceptanceIT.saveFileNameField(chooser));
          JFileChooser another = new JFileChooser();
          another.setSelectedFile(selected);
          another.add(showingField(selected.getName()));
          assertSame(name, SgfUiAcceptanceIT.saveFileNameField(chooser));
        });
  }

  @Test
  void refusesHiddenDisabledReadOnlyOrAmbiguousFields() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setSelectedFile(new File("preset.sgf"));
          chooser.add(new JTextField("preset.sgf"));
          assertNull(SgfUiAcceptanceIT.saveFileNameField(chooser));
          JTextField field = showingField("preset.sgf");
          chooser.add(field);
          field.setEnabled(false);
          assertNull(SgfUiAcceptanceIT.saveFileNameField(chooser));
          field.setEnabled(true);
          field.setEditable(false);
          assertNull(SgfUiAcceptanceIT.saveFileNameField(chooser));
          field.setEditable(true);
          chooser.add(showingField("preset.sgf"));
          assertNull(SgfUiAcceptanceIT.saveFileNameField(chooser));
          chooser.setSelectedFile(null);
          assertNull(SgfUiAcceptanceIT.saveFileNameField(chooser));
        });
  }

  private static JTextField showingField(String text) {
    return new JTextField(text) {
      @Override
      public boolean isShowing() {
        return true;
      }
    };
  }
}
