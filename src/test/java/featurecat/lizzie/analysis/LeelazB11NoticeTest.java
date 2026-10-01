package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.util.B11ModelNotice;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class LeelazB11NoticeTest {
  @Test
  void readerReplacementAndRetirementCannotKeepOrAdoptOldIdentity() throws Exception {
    Leelaz engine = new Leelaz("katago gtp -model default.bin.gz");
    assertFalse(engine.usesB11ForSpeedNotice());
    installReader(engine);
    Object old = engine.currentEngineIncarnation();
    set(old, "speedModelLookup", B11ModelNotice.known(true));
    assertTrue(engine.usesB11ForSpeedNotice());
    installReader(engine);
    assertFalse(engine.usesB11ForSpeedNotice());
    set(old, "speedModelLookup", B11ModelNotice.known(true));
    assertFalse(engine.usesB11ForSpeedNotice(), "old asynchronous completion must remain isolated");
    Object current = engine.currentEngineIncarnation();
    set(current, "speedModelLookup", B11ModelNotice.known(true));
    assertTrue(engine.usesB11ForSpeedNotice());
    set(current, "terminated", true);
    assertFalse(engine.usesB11ForSpeedNotice());
  }

  @Test
  void auxiliaryEngineCannotChangeAnotherEnginesNotice() throws Exception {
    Leelaz main = new Leelaz("katago gtp");
    Leelaz auxiliary = new Leelaz("katago analysis");
    installReader(main);
    installReader(auxiliary);
    set(main.currentEngineIncarnation(), "speedModelLookup", B11ModelNotice.known(false));
    set(auxiliary.currentEngineIncarnation(), "speedModelLookup", B11ModelNotice.known(true));
    assertFalse(main.usesB11ForSpeedNotice());
    assertTrue(auxiliary.usesB11ForSpeedNotice());
  }

  private static void installReader(Leelaz engine) {
    engine.installFreshCommandStreamsForTest(
        new ByteArrayInputStream(new byte[0]),
        new ByteArrayOutputStream(),
        new ByteArrayInputStream(new byte[0]));
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}
