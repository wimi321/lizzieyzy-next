package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class LizzieFrameNameWinratesTest {
  @Test
  void clickingThePlayersNamesShowsAndHidesTheirWinRates() throws Exception {
    Config previousConfig = Lizzie.config;
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("name-winrates"));
      Lizzie.config.uiConfig = new JSONObject();
      BoardRenderer renderer = allocate(BoardRenderer.class);
      Field bounds = BoardRenderer.class.getDeclaredField("playerNameBounds");
      bounds.setAccessible(true);
      bounds.set(renderer, new Rectangle(100, 600, 300, 24));
      LizzieFrame.boardRenderer = renderer;
      QuietFrame frame = allocate(QuietFrame.class);

      frame.onClicked(150, 610);
      assertTrue(NameWinrates.shown(Lizzie.config.uiConfig));
      assertTrue(QuietFrame.refreshes > 0, "the board is redrawn");

      frame.onClicked(380, 612);
      assertFalse(NameWinrates.shown(Lizzie.config.uiConfig));
    } finally {
      Lizzie.config = previousConfig;
      LizzieFrame.boardRenderer = previousRenderer;
      QuietFrame.refreshes = 0;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  /** Allocated without a constructor, so it keeps its count in a static. */
  private static final class QuietFrame extends LizzieFrame {
    static int refreshes;

    @Override
    public void refresh() {
      refreshes++;
    }
  }
}
