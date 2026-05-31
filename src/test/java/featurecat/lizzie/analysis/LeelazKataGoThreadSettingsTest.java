package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class LeelazKataGoThreadSettingsTest {
  @Test
  void enabledManualThreadSettingIsAppliedWhenEngineReloads() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      harness.keepAlive();
      SnapshotTrackingLeelaz primaryEngine = SnapshotTrackingLeelaz.create();
      SnapshotTrackingLeelaz reloadedEngine = SnapshotTrackingLeelaz.create();
      Lizzie.leelaz = primaryEngine;
      Lizzie.config.chkKataEngineThreads = true;
      Lizzie.config.autoLoadKataEngineThreads = false;
      Lizzie.config.txtKataEngineThreads = "6";

      invokeKataEngineParameterLoad(reloadedEngine);

      assertEquals(0, primaryEngine.sentCommands.size());
      assertEquals(1, reloadedEngine.sentCommands.size());
      assertEquals("kata-set-param numSearchThreads 6", reloadedEngine.sentCommands.get(0));
    }
  }

  @Test
  void autoloadThreadSettingTargetsTheEngineBeingInitialized() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      harness.keepAlive();
      SnapshotTrackingLeelaz primaryEngine = SnapshotTrackingLeelaz.create();
      SnapshotTrackingLeelaz preloadedEngine = SnapshotTrackingLeelaz.create();
      Lizzie.leelaz = primaryEngine;
      Lizzie.config.chkKataEngineThreads = true;
      Lizzie.config.autoLoadKataEngineThreads = true;
      Lizzie.config.txtKataEngineThreads = "8";

      invokeKataEngineParameterLoad(preloadedEngine);

      assertEquals(0, primaryEngine.sentCommands.size());
      assertEquals(1, preloadedEngine.sentCommands.size());
      assertEquals("kata-set-param numSearchThreads 8", preloadedEngine.sentCommands.get(0));
    }
  }

  private static void invokeKataEngineParameterLoad(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("setKataEnginePara");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Leelaz previousLeelaz;

    private TestHarness(Config previousConfig, Leelaz previousLeelaz) {
      this.previousConfig = previousConfig;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestHarness open() throws Exception {
      Config previousConfig = Lizzie.config;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Path tempRoot = Files.createTempDirectory("katago-thread-settings");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot);
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.config = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      return new TestHarness(previousConfig, previousLeelaz);
    }

    private void keepAlive() {}

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
    }
  }
}
