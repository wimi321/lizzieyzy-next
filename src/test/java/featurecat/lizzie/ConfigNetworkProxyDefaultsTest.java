package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.util.NetworkProxy;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigNetworkProxyDefaultsTest {
  @TempDir Path tempRoot;

  @Test
  void defaultConfigUsesDirectProxy() throws Exception {
    Config config = ConfigTestHelper.createForTests(tempRoot);
    Method createDefaultConfig = Config.class.getDeclaredMethod("createDefaultConfig");
    createDefaultConfig.setAccessible(true);
    JSONObject defaults = (JSONObject) createDefaultConfig.invoke(config);

    assertEquals(
        NetworkProxy.MODE_DIRECT,
        defaults.getJSONObject("ui").getString(NetworkProxy.KEY_PROXY_MODE));
  }
}
