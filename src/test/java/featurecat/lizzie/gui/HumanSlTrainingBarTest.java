package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.Test;

class HumanSlTrainingBarTest {
  @Test
  void actionsKeepFinishButDoNotExposeDuplicateResign() {
    HumanSlTrainingBar bar = new HumanSlTrainingBar();
    List<String> buttonTexts = new ArrayList<>();
    collectButtonTexts(bar, buttonTexts);

    assertTrue(buttonTexts.contains(Lizzie.resourceBundle.getString("HumanSlTraining.finishReview")));
    assertFalse(buttonTexts.contains(Lizzie.resourceBundle.getString("HumanSlGame.btn.resign")));
  }

  private static void collectButtonTexts(Container container, List<String> buttonTexts) {
    for (Component component : container.getComponents()) {
      if (component instanceof AbstractButton) {
        buttonTexts.add(((AbstractButton) component).getText());
      }
      if (component instanceof Container) {
        collectButtonTexts((Container) component, buttonTexts);
      }
    }
  }
}
