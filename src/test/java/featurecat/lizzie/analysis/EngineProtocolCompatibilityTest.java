package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.gtp.GtpEngineProtocol;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineProtocolCompatibilityTest {
  @Test
  void realNameUsesAdvertisedKataAnalysisButDoesNotInventOtherExtensions() throws Exception {
    RecordingEngine engine = new RecordingEngine();
    engine.currentEnginename = "TestEngine";
    engine.advertiseCommandsForTest(List.of("kata-analyze", "play", "undo"));
    assertTrue(engine.usesKataAnalysis());
    assertTrue(engine.namesSideToPlayInCommands());
    assertFalse(engine.isKatago, "protocol support must not relabel the engine");
    assertFalse(engine.supportsGtpCommand("kata-genmove_analyze", false));
    engine.nameCmdfornoponder();
    engine.clearCacheIfSupported();
    assertEquals(List.of("name"), engine.commands);
    engine.advertiseCommandsForTest(List.of("kata-analyze", "stop", "clear_cache"));
    engine.nameCmdfornoponder();
    engine.clearCacheIfSupported();
    assertEquals(List.of("name", "stop", "clear_cache"), engine.commands);
  }

  @Test
  void completedDiscoveryOverridesNameFallbackAndRestartDoesNotReuseOldList() throws Exception {
    RecordingEngine engine = new RecordingEngine();
    engine.isKatago = true;
    assertTrue(engine.usesKataAnalysis());
    engine.advertiseCommandsForTest(List.of("lz-analyze"));
    assertFalse(engine.usesKataAnalysis());
    engine.clearCacheIfSupported();
    assertTrue(engine.commands.isEmpty());
    engine.installFreshCommandOutputForTest(new java.io.ByteArrayOutputStream());
    assertTrue(engine.usesKataAnalysis(), "a replaced reader must use its own discovery");
  }

  @Test
  void compatibleEngineAnalysisUsesDecimalWinrates() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Lizzie.config = allocate(Config.class);
      RecordingEngine engine = new RecordingEngine();
      engine.advertiseCommandsForTest(List.of("kata-analyze"));
      Lizzie.config.showKataGoEstimate = true;
      Lizzie.config.showPvVisits = true;
      assertEquals("", engine.addKataTag(), "optional KataGo fields require separate support");
      Method parser = Leelaz.class.getDeclaredMethod("parseAnalysisInfoPayload", String.class);
      parser.setAccessible(true);
      Object parsed =
          parser.invoke(engine, "info move D4 visits 30 winrate 0.61 scoreLead 2.5 pv D4 Q16");
      Field moves = parsed.getClass().getDeclaredField("moves");
      moves.setAccessible(true);
      MoveData move = ((List<MoveData>) moves.get(parsed)).get(0);
      assertEquals(61.0, move.winrate, 0.00001);
      assertEquals(2.5, move.scoreMean);
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void genericAndSaiEnginesReceiveThePassAlreadyRecordedOnTheBoard() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.leelaz = null;
      Lizzie.board = allocate(SilentBoard.class);
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(19, 19)));
      Lizzie.board.getHistory().pass(Stone.BLACK);
      for (boolean sai : List.of(false, true)) {
        RecordingEngine engine = new RecordingEngine();
        engine.isSai = sai;
        engine.playMove(Stone.BLACK, "pass");
        assertEquals(List.of("play B pass"), engine.commands);
      }
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void vendorVersionDoesNotMasqueradeAsAKataVersion() {
    assertFalse(GtpEngineProtocol.kataVersion("TestEngine-0.1.0").rootInfo());
    assertFalse(GtpEngineProtocol.kataVersion("1.16.3").rootInfo());
    assertTrue(GtpEngineProtocol.kataVersion("1.16.4").rootInfo());
    assertTrue(GtpEngineProtocol.kataVersion("1.17.1-custom").movesOwnership());
    assertTrue(GtpEngineProtocol.kataVersion("1.17-custom").rootInfo());
  }

  private static final class RecordingEngine extends Leelaz {
    final List<String> commands = new ArrayList<>();

    RecordingEngine() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }
  }

  private static final class SilentBoard extends Board {
    @Override
    public void clearAfterMove() {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
