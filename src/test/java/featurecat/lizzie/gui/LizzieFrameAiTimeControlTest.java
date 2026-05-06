package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LizzieFrameAiTimeControlTest {
  @Test
  void defaultAiMoveTimeUsesOneStoneByoyomi() {
    assertEquals("time_settings 0 4 1", LizzieFrame.buildDefaultAiMoveTimeSettings(4));
  }

  @Test
  void defaultAiMoveTimeNeverWritesNegativeSeconds() {
    assertEquals("time_settings 0 0 1", LizzieFrame.buildDefaultAiMoveTimeSettings(-1));
  }

  @Test
  void kataGoFixedMoveTimeUsesMaxTime() {
    assertEquals("kata-set-param maxTime 4", LizzieFrame.buildKataGoFixedMoveTimeCommand(4));
  }

  @Test
  void kataGoUnlimitedMoveTimeClearsMaxTimeLimit() {
    assertEquals("kata-set-param maxTime 1e20", LizzieFrame.buildKataGoFixedMoveTimeCommand(0));
  }

  @Test
  void kataGoDefaultAiTimeUsesFixedMaxTimeInsteadOfGtpTimeManagement() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-ai-time"));
      Lizzie.config.advanceTimeSettings = false;
      Lizzie.config.kataTimeSettings = false;
      Lizzie.config.maxGameThinkingTimeSeconds = 4;
      RecordingLeelaz engine = new RecordingLeelaz();
      engine.isKatago = true;

      LizzieFrame.sendAiTime(false, engine, false);

      assertEquals(List.of("kata-time_settings none", "kata-set-param maxTime 4"), engine.commands);
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void nonKataGoDefaultAiTimeKeepsStandardGtpTimeSettings() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Lizzie.config =
          ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-ai-time-non-katago"));
      Lizzie.config.advanceTimeSettings = false;
      Lizzie.config.kataTimeSettings = false;
      Lizzie.config.maxGameThinkingTimeSeconds = 4;
      RecordingLeelaz engine = new RecordingLeelaz();
      engine.isKatago = false;

      LizzieFrame.sendAiTime(false, engine, false);

      assertEquals(List.of("time_settings 0 4 1"), engine.commands);
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  private static final class RecordingLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();

    private RecordingLeelaz() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }
  }
}
