package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.JFontButton;
import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EngineStartupStatusTest {
  @Test
  void listenersReceiveCurrentAndFutureStates() {
    EngineStartupStatus status = new EngineStartupStatus();
    List<EngineStartupStatus.Snapshot> updates = new ArrayList<>();

    status.addListener(updates::add);
    status.checking("checking", "Checking");
    status.needsRepair("repair", "Repair", "Missing engine");
    status.failed("failed", "Failed", "Process error");
    status.ready();

    assertEquals(5, updates.size());
    assertEquals(EngineStartupStatus.State.READY, updates.get(0).state);
    assertEquals(EngineStartupStatus.State.CHECKING, updates.get(1).state);
    assertFalse(updates.get(1).isActionable());
    assertTrue(updates.get(2).isActionable());
    assertTrue(updates.get(3).isActionable());
    assertEquals(EngineStartupStatus.State.READY, status.snapshot().state);
  }

  @Test
  void readyStatusRepaintsTheBasePanelAfterHidingTheNotice() throws Exception {
    LizzieFrame frame = allocate(LizzieFrame.class);
    CountingLayeredPane basePanel = new CountingLayeredPane();
    JFontButton statusButton = new JFontButton();
    statusButton.setVisible(true);
    setField(frame, "basePanel", basePanel);
    setField(frame, "engineStartupStatusButton", statusButton);

    Method update =
        LizzieFrame.class.getDeclaredMethod(
            "updateEngineStartupStatus", EngineStartupStatus.Snapshot.class);
    update.setAccessible(true);
    update.invoke(frame, new EngineStartupStatus().snapshot());
    SwingUtilities.invokeAndWait(() -> {});

    assertFalse(statusButton.isVisible());
    assertEquals(1, basePanel.repaintCount);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class CountingLayeredPane extends JLayeredPane {
    private int repaintCount;

    @Override
    public void repaint() {
      repaintCount++;
    }
  }

  @Test
  void missingEngineRemainsActionableUnlessUserExplicitlySelectedNoEngine() {
    assertTrue(Lizzie.shouldOfferEngineRepair(false, false));
    assertFalse(Lizzie.shouldOfferEngineRepair(false, true));
    assertFalse(Lizzie.shouldOfferEngineRepair(true, false));
  }
}
