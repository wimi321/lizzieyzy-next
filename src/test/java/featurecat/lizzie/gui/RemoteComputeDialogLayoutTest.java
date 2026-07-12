package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.AccessibleContext;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class RemoteComputeDialogLayoutTest {
  @Test
  void localizedButtonUsesItsFullPreferredWidth() {
    JButton button = new JButton("Open the Zhizi website for account top-up");

    assertEquals(
        button.getPreferredSize().width,
        RemoteComputeDialog.localizedButtonWidth(button, 40));
  }

  @Test
  void localizedTabsExpandPastLegacyFixedWidth() {
    JButton first = new JButton("Zhizi Cloud");
    JButton second = new JButton("User-provided remote computing service");

    int width = RemoteComputeDialog.localizedButtonGroupWidth(292, 8, 12, first, second);

    assertTrue(width > 292);
    assertTrue(width >= first.getPreferredSize().width + second.getPreferredSize().width + 20);
  }

  @Test
  void localizedTabsKeepEachButtonsPreferredWidth() {
    JButton first = new JButton("Zhizi Cloud");
    JButton second = new JButton("User-provided remote computing service");
    JPanel group = RemoteComputeDialog.createLocalizedTabGroup(first, second);

    group.setSize(group.getPreferredSize());
    group.doLayout();

    assertTrue(first.getWidth() >= first.getPreferredSize().width);
    assertTrue(second.getWidth() >= second.getPreferredSize().width);
  }

  @Test
  void statusChangesRemainAvailableAfterTheLiveAnnouncement() {
    JLabel status = new JLabel("Custom compute enabled");
    JPanel indicator = new JPanel();
    AtomicReference<Object> announcedValue = new AtomicReference<>();
    status
        .getAccessibleContext()
        .addPropertyChangeListener(
            event -> {
              if (AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY.equals(
                  event.getPropertyName())) {
                announcedValue.set(event.getNewValue());
              }
            });

    RemoteComputeDialog.updateStatusAccessibility(
        status,
        indicator,
        "Connection status",
        "Connection indicator",
        "Connecting to custom compute");

    assertEquals("Connection status", status.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Custom compute enabled", status.getAccessibleContext().getAccessibleDescription());
    assertEquals(
        "Custom compute enabled", indicator.getAccessibleContext().getAccessibleDescription());
    assertEquals("Custom compute enabled", announcedValue.get());
  }
}
