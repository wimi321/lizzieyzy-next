package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class PerformanceProbeEngineAccessTest {
  @Test
  void acceptsOnlySuccessfulAcknowledgement() throws Exception {
    PerformanceProbeEngineAccess.barrier(new RespondingEngine("=17"), "clear_cache");
  }

  @Test
  void errorResponseDoesNotPretendThatCacheWasCleared() throws Exception {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                PerformanceProbeEngineAccess.barrier(
                    new RespondingEngine("?17 unsupported"), "clear_cache"));
    assertTrue(failure.getMessage().contains("unsupported"));
  }

  @Test
  void callbackWithoutScopedResponseFailsClosed() throws Exception {
    assertThrows(
        AssertionError.class,
        () -> PerformanceProbeEngineAccess.barrier(new RespondingEngine(""), "stop"));
  }

  private static final class RespondingEngine extends Leelaz {
    private final String response;

    RespondingEngine(String response) throws java.io.IOException {
      super("");
      this.response = response;
    }

    @Override
    void sendCommandWithResponseForTest(String command, Runnable callback) {
      try {
        Field error = Leelaz.class.getDeclaredField("currentCommandResponseError");
        Field line = Leelaz.class.getDeclaredField("currentCommandResponseLine");
        error.setAccessible(true);
        line.setAccessible(true);
        error.setBoolean(this, response.startsWith("?"));
        line.set(this, response);
        try {
          callback.run();
        } finally {
          error.setBoolean(this, false);
          line.set(this, "");
        }
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    }
  }
}
