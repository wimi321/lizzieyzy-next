package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class LeelazStartupTuningTest {
  @Test
  void quarantinedStderrStillReportsProgressAndReplacementDoesNotInheritIt() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    engine.suppressGlobalEnginePresentationForTest();
    engine.parseAnalysisErrorLineForTest("Tuning transformer attention kernel");
    assertTrue(engine.isTuning);
    long now = System.nanoTime();
    assertTrue(engine.extendStartupTuningDeadline(now, now) > now);
    Object previous = engine.analysisReaderBindingForTest();

    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    engine.suppressGlobalEnginePresentationForTest();
    assertEquals(now, engine.extendStartupTuningDeadline(now, now));
    Method observe =
        Leelaz.class.getDeclaredMethod("observeStartupTuning", String.class, previous.getClass());
    observe.setAccessible(true);
    observe.invoke(engine, "Tuning 99/100 ...", previous);
    assertEquals(now, engine.extendStartupTuningDeadline(now, now));
  }

  @Test
  void analysisOutputCannotExtendStartupAfterTheEngineIsLoaded() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
    engine.suppressGlobalEnginePresentationForTest();
    engine.isLoaded = true;
    engine.parseAnalysisErrorLineForTest("Tuning transformer attention kernel");
    assertFalse(engine.isTuning);
    assertEquals(1, engine.extendStartupTuningDeadline(1, System.nanoTime()));
  }
}
