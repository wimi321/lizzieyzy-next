package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.EngineValidationResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.EngineValidationStatus;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.awt.Cursor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

class KataGoAutoSetupCatalogRegressionTest {
  @TempDir Path root;

  @Test
  void unavailableSavedEntryRefreshRestoresRetryWithoutUnlockingSidebar() throws Exception {
    // No native window is needed to exercise the real completion path. Missing/remote entries
    // have no later validation callback to repair these controls.
    HeadlessRefreshDialog dialog = unconstructedDialog();
    JFontButton refresh = new JFontButton();
    JFontButton close = new JFontButton();
    var sidebar = new KataGoAutoSetupDialog.ExactHitList<String>();
    set(dialog, "selectedBenchmarkEntryId", "saved-entry");
    set(dialog, "snapshot", null);
    set(dialog, "progressBar", new JProgressBar());
    set(dialog, "progressPanel", new JPanel());
    set(dialog, "footerPanel", new JPanel());
    set(dialog, "btnRefresh", refresh);
    set(dialog, "btnClose", close);
    set(dialog, "btnRemoteCompute", new JButton());
    set(dialog, "sectionNav", sidebar);
    Method complete = KataGoAutoSetupDialog.class.getDeclaredMethod("setStateRefreshPending", boolean.class);
    complete.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> {
      refresh.setEnabled(false);
      close.setEnabled(false);
      sidebar.setEnabled(false);
      try {
        complete.invoke(dialog, false);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
      assertTrue(refresh.isEnabled(), "unavailable entries must remain refreshable");
      assertTrue(close.isEnabled());
      assertFalse(sidebar.isEnabled(), "saved-entry scope must never unlock other setup sections");
    });
    set(dialog, "selectedBenchmarkEntryId", "");
    SwingUtilities.invokeAndWait(() -> {
      try {
        complete.invoke(dialog, false);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
      assertTrue(sidebar.isEnabled(), "normal setup navigation must still be restored");
    });
  }

  @Test
  void replacedTransformerOnUseIsRejectedBeforeAnyProfileWrite() throws Exception {
    String transformer = KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL;
    String cnn = "ordinary_" + "x".repeat(transformer.length() - "ordinary_".length());
    Path weight = root.resolve("selected.bin");
    writeHeader(weight, cnn);
    SetupSnapshot selected = snapshot(weight).scanWeightCatalog();
    var modified = Files.getLastModifiedTime(weight);
    long size = Files.size(weight);
    writeHeader(weight, transformer);
    Files.setLastModifiedTime(weight, modified);
    assertEquals(size, Files.size(weight));
    assertEquals(cnn, selected.weightCatalog.modelName(weight), "rendering still uses its snapshot");
    Path settings = root.resolve("config.txt");
    Files.writeString(settings, "original configuration");
    byte[] original = Files.readAllBytes(settings);
    AtomicBoolean persisted = new AtomicBoolean();
    var error = assertThrows(KataGoAutoSetupDialog.WeightPreflightException.class,
        () -> KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(selected, validation("1.16.0"),
            ignored -> {
              persisted.set(true);
              Files.writeString(settings, "changed configuration");
              return null;
            }));
    assertEquals(KataGoAutoSetupDialog.WeightSelectionCheck.INCOMPATIBLE, error.check);
    assertFalse(persisted.get());
    assertArrayEquals(original, Files.readAllBytes(settings));
  }

  @Test
  void compatibleReplacementStillRequiresFreshSelectionAndDeletedFileNeverWrites() throws Exception {
    Path weight = root.resolve("selected.bin");
    writeHeader(weight, "custom_first");
    SetupSnapshot selected = snapshot(weight).scanWeightCatalog();
    writeHeader(weight, "custom_other");
    var error = assertThrows(KataGoAutoSetupDialog.WeightPreflightException.class,
        () -> KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(selected, validation("1.18.2"),
            ignored -> { throw new AssertionError("stale selection must not persist"); }));
    assertEquals(KataGoAutoSetupDialog.WeightSelectionCheck.CHANGED, error.check);
    SetupSnapshot refreshed = selected.scanWeightCatalog();
    AtomicBoolean persisted = new AtomicBoolean();
    KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(refreshed, validation("1.18.2"),
        ignored -> { persisted.set(true); return null; });
    assertTrue(persisted.get());
    Files.delete(weight);
    var missing = assertThrows(KataGoAutoSetupDialog.WeightPreflightException.class,
        () -> KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(refreshed, validation("1.18.2"),
            ignored -> { throw new AssertionError("missing weight must not persist"); }));
    assertEquals(KataGoAutoSetupDialog.WeightSelectionCheck.MISSING, missing.check);
  }

  private SetupSnapshot snapshot(Path weight) {
    EngineData entry = new EngineData();
    entry.commands = "katago gtp -model \"" + weight + "\"";
    return KataGoAutoSetupHelper.inspectSavedEngine(entry);
  }

  private static void writeHeader(Path weight, String model) throws Exception {
    Files.writeString(weight, model + "\n15\n22\n19\n");
  }

  private static EngineValidationResult validation(String version) throws Exception {
    var constructor = EngineValidationResult.class.getDeclaredConstructor(EngineValidationStatus.class, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(EngineValidationStatus.VALID, "KataGo v" + version);
  }

  private static HeadlessRefreshDialog unconstructedDialog() throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    HeadlessRefreshDialog dialog =
        (HeadlessRefreshDialog) ((Unsafe) field.get(null)).allocateInstance(HeadlessRefreshDialog.class);
    // Keep actual button instances when another independently tested feature adds completion UI.
    // Only the top-level native window construction is bypassed by this headless fixture.
    for (Field button : KataGoAutoSetupDialog.class.getDeclaredFields()) {
      if (button.getType() == JFontButton.class) {
        button.setAccessible(true);
        button.set(dialog, new JFontButton());
      }
    }
    return dialog;
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class HeadlessRefreshDialog extends KataGoAutoSetupDialog {
    private HeadlessRefreshDialog() { super(null); }
    @Override public void revalidate() {}
    @Override public void repaint() {}
    @Override public void setCursor(Cursor cursor) {}
  }
}
