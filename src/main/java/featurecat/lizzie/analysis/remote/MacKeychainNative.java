package featurecat.lizzie.analysis.remote;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Length-delimited Keychain access; secrets never enter argv or a terminal prompt. */
final class MacKeychainNative {
  private static final int NOT_FOUND = -25300;
  private static final int DUPLICATE = -25299;

  interface InteractionControl {
    int SecKeychainGetUserInteractionAllowed(byte[] state);

    int SecKeychainSetUserInteractionAllowed(byte state);
  }

  interface Security extends Library, InteractionControl {
    int SecItemCopyMatching(Pointer query, PointerByReference result);

    int SecItemAdd(Pointer attributes, PointerByReference result);

    int SecItemUpdate(Pointer query, Pointer attributes);

    int SecItemDelete(Pointer query);
  }

  interface CF extends Library {
    Pointer CFDictionaryCreateMutable(
        Pointer allocator, NativeLong capacity, Pointer keys, Pointer values);

    void CFDictionarySetValue(Pointer dictionary, Pointer key, Pointer value);

    Pointer CFStringCreateWithBytes(
        Pointer allocator, byte[] bytes, NativeLong length, int encoding, byte external);

    Pointer CFDataCreate(Pointer allocator, byte[] bytes, NativeLong length);

    NativeLong CFDataGetLength(Pointer data);

    Pointer CFDataGetBytePtr(Pointer data);

    NativeLong CFGetTypeID(Pointer data);

    NativeLong CFDataGetTypeID();

    void CFRelease(Pointer value);
  }

  private static final class NativeApi {
    static final Security SECURITY = Native.load("Security", Security.class);
    static final CF CORE = Native.load("CoreFoundation", CF.class);
    static final NativeLibrary SYMBOLS = NativeLibrary.getInstance("Security");
    static final NativeLibrary CF_SYMBOLS = NativeLibrary.getInstance("CoreFoundation");
  }

  static boolean available() {
    try {
      return NativeApi.SECURITY != null && NativeApi.CORE != null;
    } catch (LinkageError | RuntimeException unavailable) {
      return false;
    }
  }

  static synchronized Optional<String> readWithoutPrompt(String service, String account)
      throws IOException {
    // SecItem's UI flag alone does not suppress prompts for macOS file-based keychains.
    // Serialize all our native operations and restore the process flag on every exit path.
    try (InteractionScope ignored = new InteractionScope(NativeApi.SECURITY)) {
      return read(service, account);
    }
  }

  static synchronized Optional<String> read(String service, String account) throws IOException {
    try (Dictionary query = query(service, account)) {
      query.set(
          "kSecReturnData",
          NativeApi.CF_SYMBOLS.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0));
      query.set("kSecMatchLimit", symbol("kSecMatchLimitOne"));
      PointerByReference result = new PointerByReference();
      int status = NativeApi.SECURITY.SecItemCopyMatching(query.pointer, result);
      if (status == NOT_FOUND) return Optional.empty();
      check(status);
      Pointer data = result.getValue();
      if (data == null) throw new IOException("Keychain returned no data.");
      try {
        if (!NativeApi.CORE.CFGetTypeID(data).equals(NativeApi.CORE.CFDataGetTypeID()))
          throw new IOException("Keychain returned an unexpected data type.");
        long length = NativeApi.CORE.CFDataGetLength(data).longValue();
        if (length < 0 || length > 1024 * 1024)
          throw new IOException("Invalid Keychain data length.");
        if (length == 0) return Optional.empty();
        byte[] bytes = NativeApi.CORE.CFDataGetBytePtr(data).getByteArray(0, (int) length);
        try {
          return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } finally {
          Arrays.fill(bytes, (byte) 0);
        }
      } finally {
        NativeApi.CORE.CFRelease(data);
      }
    }
  }

  static synchronized void write(String service, String account, String secret) throws IOException {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    try (Dictionary query = query(service, account);
        Dictionary update = new Dictionary()) {
      Pointer data =
          update.own(NativeApi.CORE.CFDataCreate(null, bytes, new NativeLong(bytes.length)));
      update.set("kSecValueData", data);
      int status = NativeApi.SECURITY.SecItemUpdate(query.pointer, update.pointer);
      if (status == NOT_FOUND) {
        query.set("kSecValueData", data);
        status = NativeApi.SECURITY.SecItemAdd(query.pointer, null);
        if (status == DUPLICATE) {
          // Another process may have created the same item after our first update.
          try (Dictionary retry = query(service, account)) {
            status = NativeApi.SECURITY.SecItemUpdate(retry.pointer, update.pointer);
          }
        }
      }
      check(status);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
    if (!read(service, account).filter(secret::equals).isPresent())
      throw new IOException("Keychain verification failed.");
  }

  static synchronized void delete(String service, String account) throws IOException {
    try (Dictionary query = query(service, account)) {
      int status = NativeApi.SECURITY.SecItemDelete(query.pointer);
      if (status != NOT_FOUND) check(status);
    }
  }

  private static Dictionary query(String service, String account) {
    Dictionary query = new Dictionary();
    try {
      query.set("kSecClass", symbol("kSecClassGenericPassword"));
      query.string("kSecAttrService", service);
      query.string("kSecAttrAccount", account);
      return query;
    } catch (RuntimeException | LinkageError failure) {
      query.close();
      throw failure;
    }
  }

  private static Pointer symbol(String name) {
    return NativeApi.SYMBOLS.getGlobalVariableAddress(name).getPointer(0);
  }

  private static void check(int status) throws IOException {
    if (status != 0) throw new IOException("Keychain operation failed (OSStatus " + status + ").");
  }

  static final class InteractionScope implements AutoCloseable {
    private final InteractionControl control;
    private final byte previous;

    InteractionScope(InteractionControl control) throws IOException {
      this.control = control;
      byte[] state = new byte[1];
      check(control.SecKeychainGetUserInteractionAllowed(state));
      previous = state[0];
      check(control.SecKeychainSetUserInteractionAllowed((byte) 0));
    }

    @Override
    public void close() throws IOException {
      check(control.SecKeychainSetUserInteractionAllowed(previous));
    }
  }

  private static final class Dictionary implements AutoCloseable {
    final Pointer pointer;
    final List<Pointer> owned = new ArrayList<>();

    Dictionary() {
      pointer = NativeApi.CORE.CFDictionaryCreateMutable(null, new NativeLong(0), null, null);
      if (pointer == null) throw new IllegalStateException("Keychain allocation failed.");
    }

    Pointer own(Pointer value) {
      if (value == null) throw new IllegalStateException("Keychain allocation failed.");
      owned.add(value);
      return value;
    }

    void set(String key, Pointer value) {
      NativeApi.CORE.CFDictionarySetValue(pointer, symbol(key), value);
    }

    void string(String key, String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      set(
          key,
          own(
              NativeApi.CORE.CFStringCreateWithBytes(
                  null, bytes, new NativeLong(bytes.length), 0x08000100, (byte) 0)));
    }

    @Override
    public void close() {
      NativeApi.CORE.CFRelease(pointer);
      for (Pointer value : owned) NativeApi.CORE.CFRelease(value);
    }
  }
}
