package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MacKeychainInteractionTest {
  @Test
  void restoresOriginalPolicyAfterReadFailure() {
    FakeControl control = new FakeControl();
    assertThrows(
        IOException.class,
        () -> {
          try (var ignored = new MacKeychainNative.InteractionScope(control)) {
            assertEquals(0, control.state);
            throw new IOException("simulated access refusal");
          }
        });
    assertEquals(1, control.state);
    assertEquals(List.of((byte) 0, (byte) 1), control.changes);
  }

  @Test
  void preservesAlreadyDisabledPolicyAcrossNestedReads() throws Exception {
    FakeControl control = new FakeControl();
    control.state = 0;
    try (var outer = new MacKeychainNative.InteractionScope(control)) {
      try (var inner = new MacKeychainNative.InteractionScope(control)) {
        assertEquals(0, control.state);
      }
      assertEquals(0, control.state);
    }
    assertEquals(0, control.state);
  }

  @Test
  void cannotReadWhenPolicyCannotBeInspectedOrDisabled() {
    FakeControl control = new FakeControl();
    control.getStatus = -1;
    assertThrows(IOException.class, () -> new MacKeychainNative.InteractionScope(control));
    assertEquals(List.of(), control.changes);
    control.getStatus = 0;
    control.setStatus = -1;
    assertThrows(IOException.class, () -> new MacKeychainNative.InteractionScope(control));
    assertEquals(1, control.state);
  }

  private static final class FakeControl implements MacKeychainNative.InteractionControl {
    byte state = 1;
    int getStatus;
    int setStatus;
    final List<Byte> changes = new ArrayList<>();

    @Override
    public int SecKeychainGetUserInteractionAllowed(byte[] result) {
      result[0] = state;
      return getStatus;
    }

    @Override
    public int SecKeychainSetUserInteractionAllowed(byte value) {
      if (setStatus != 0) return setStatus;
      changes.add(value);
      state = value;
      return 0;
    }
  }
}
