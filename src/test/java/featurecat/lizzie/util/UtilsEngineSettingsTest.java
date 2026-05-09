package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.EngineData;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

public class UtilsEngineSettingsTest {
  @Test
  void normalizesMovedDefaultEngineIndex() {
    ArrayList<EngineData> engines = new ArrayList<EngineData>();
    engines.add(engine("first", false));
    engines.add(engine("preferred", true));
    engines.add(engine("duplicate", true));

    EngineData moved = engines.remove(1);
    engines.add(0, moved);
    int defaultIndex = Utils.normalizeDefaultEngineFlags(engines);

    assertEquals(0, defaultIndex);
    assertTrue(engines.get(0).isDefault);
    assertFalse(engines.get(1).isDefault);
    assertFalse(engines.get(2).isDefault);
    assertEquals(0, engines.get(0).index);
    assertEquals(1, engines.get(1).index);
    assertEquals(2, engines.get(2).index);
  }

  private static EngineData engine(String name, boolean isDefault) {
    EngineData engineData = new EngineData();
    engineData.name = name;
    engineData.commands = "katago";
    engineData.isDefault = isDefault;
    engineData.width = 19;
    engineData.height = 19;
    engineData.komi = 7.5F;
    return engineData;
  }
}
