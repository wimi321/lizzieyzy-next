package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.Leelaz;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineCountDownFixedTimeTest {
  @Test
  void fixedPerMoveTimeSendsFullTimeLeftForEveryAiMove() throws Exception {
    RecordingLeelaz engine = new RecordingLeelaz();
    EngineCountDown countDown = new EngineCountDown();

    assertTrue(countDown.setEngineCountDown("time_settings 0 4 1", engine));
    countDown.initialize(false);
    countDown.sendTimeLeft(false);
    countDown.sendTimeLeft(false);

    assertEquals(List.of("time_left W 4 1", "time_left W 4 1"), engine.commands);
  }

  @Test
  void fixedPerMoveTimeUsesBlackColorWhenAiPlaysBlack() throws Exception {
    RecordingLeelaz engine = new RecordingLeelaz();
    EngineCountDown countDown = new EngineCountDown();

    assertTrue(countDown.setEngineCountDown("time_settings 0 4 1", engine));
    countDown.initialize(true);
    countDown.sendTimeLeft(false);

    assertEquals(List.of("time_left B 4 1"), engine.commands);
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
