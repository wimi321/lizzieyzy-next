package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LizzieFrameRulesCaptionTest {
  @Test
  void clickingTheRulesCaptionOutsideAnEngineGameOpensTheRulesMenu() throws Exception {
    Board previousBoard = Lizzie.board;
    Lizzie.board = null; // no game: nothing recorded to inspect
    try {
      RecordingFrame frame = allocate(RecordingFrame.class);
      Rectangle caption = new Rectangle(40, 30, 60, 14);
      Field bounds = LizzieFrame.class.getDeclaredField("matchRulesCaptionBounds");
      bounds.setAccessible(true);
      bounds.set(frame, caption);

      assertFalse(frame.tryInspectMatchRulesAt(10, 10));
      assertTrue(RecordingFrame.opened.isEmpty());

      assertTrue(frame.tryInspectMatchRulesAt(45, 35));
      assertEquals(List.of(caption), RecordingFrame.opened);
    } finally {
      Lizzie.board = previousBoard;
      RecordingFrame.opened.clear();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  /** Allocated without a constructor, so its record is static. */
  private static final class RecordingFrame extends LizzieFrame {
    static final List<Rectangle> opened = new ArrayList<>();

    @Override
    void showRulesQuickMenu(Rectangle caption) {
      opened.add(caption);
    }
  }
}
