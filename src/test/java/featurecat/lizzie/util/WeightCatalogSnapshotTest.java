package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.gui.EngineData;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeightCatalogSnapshotTest {
  @TempDir Path root;

  @Test
  void largeCatalogReadsEachNormalizedCandidateOnceAndAllLookupsArePure() throws Exception {
    List<Path> candidates = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      Path path = root.resolve("weight-" + i + ".bin");
      Files.writeString(path, "custom_" + i + "\n15\n22\n19\n");
      candidates.add(path);
      candidates.add(path.getParent().resolve(".").resolve(path.getFileName()));
    }
    AtomicInteger reads = new AtomicInteger();
    Path active = candidates.get(80);
    WeightCatalogSnapshot catalog =
        WeightCatalogSnapshot.scan(
            active,
            candidates,
            path -> {
              assertFalse(SwingUtilities.isEventDispatchThread());
              reads.incrementAndGet();
              return KataGoAutoSetupHelper.readWeightModelName(path);
            });
    assertEquals(500, reads.get());
    assertEquals(500, catalog.entries().size());
    assertEquals(active, catalog.entries().get(0).path());
    for (WeightCatalogSnapshot.Entry entry : catalog.entries()) Files.delete(entry.path());
    SwingUtilities.invokeAndWait(
        () -> {
          for (int repeat = 0; repeat < 4; repeat++) {
            for (WeightCatalogSnapshot.Entry entry : catalog.entries()) {
              assertEquals(entry.modelName(), catalog.modelName(entry.path()));
              assertEquals(entry.path(), catalog.findModel(entry.modelName(), active));
              assertEquals(entry.modelName(), catalog.displayName(entry.path()));
              assertTrue(entry.regularFile());
              assertNotNull(entry.modified());
            }
          }
        });
    assertEquals(500, reads.get());
    assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
  }

  @Test
  void refreshReadsSameNameSameSizeSameMtimeReplacementButOldSnapshotIsConsistent()
      throws Exception {
    Path path = root.resolve("default.bin");
    Files.writeString(path, "custom_first\n15\n22\n19\n");
    FileTime modified = Files.getLastModifiedTime(path);
    long size = Files.size(path);
    WeightCatalogSnapshot original = WeightCatalogSnapshot.scan(path, List.of(path));
    Files.writeString(path, "custom_other\n15\n22\n19\n");
    Files.setLastModifiedTime(path, modified);
    assertEquals(size, Files.size(path));
    WeightCatalogSnapshot refreshed = WeightCatalogSnapshot.scan(path, List.of(path));
    assertEquals("custom_first", original.modelName(path));
    assertEquals("custom_other", refreshed.modelName(path));
    assertEquals(original.entry(path).modified(), refreshed.entry(path).modified());
    assertNull(refreshed.findModel("custom_first", path));
  }

  @Test
  void compressedDamagedMissingAndDuplicateModelsRetainSafeFallbacks() throws Exception {
    Path first = root.resolve("first.bin.gz");
    Path active = root.resolve("active.bin.gz");
    for (Path path : List.of(first, active)) {
      try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
        out.write("same_model\n15\n22\n19\n".getBytes(StandardCharsets.US_ASCII));
      }
    }
    Path broken = root.resolve("broken.bin.gz");
    Files.writeString(broken, "not a gzip");
    Path missing = root.resolve("missing.bin.gz");
    WeightCatalogSnapshot catalog =
        WeightCatalogSnapshot.scan(active, List.of(first, broken, missing, active));
    assertEquals(active, catalog.findModel("SAME_MODEL", active));
    assertEquals(first, catalog.findModel("same_model", first));
    assertEquals("broken.bin.gz", catalog.displayName(broken));
    assertEquals("", catalog.modelName(broken));
    assertFalse(catalog.entry(missing).regularFile());
    assertNull(catalog.entry(missing).modified());
  }

  @Test
  void changingSelectionReusesScanAndDoesNotTouchModelAgain() throws Exception {
    Path model = root.resolve("default.bin");
    Files.writeString(model, "custom_model\n15\n22\n19\n");
    EngineData entry = new EngineData();
    entry.commands = "katago gtp -model \"" + model + "\"";
    var scanned = KataGoAutoSetupHelper.inspectSavedEngine(entry).scanWeightCatalog();
    Files.delete(model);
    SwingUtilities.invokeAndWait(
        () -> {
          var switched = scanned.withActiveWeight(model).withEnginePath(root.resolve("katago"));
          assertSame(scanned.weightCatalog, switched.weightCatalog);
          assertEquals("custom_model", switched.weightCatalog.modelName(model));
          assertTrue(switched.hasWeight());
        });
    assertFalse(scanned.scanWeightCatalog().hasWeight());
    Path added = root.resolve("added.bin");
    Files.writeString(added, "added_model\n15\n22\n19\n");
    var changed = scanned.withActiveWeight(added);
    assertNull(changed.weightCatalog, "a new path requires a new background scan");
    assertEquals("added_model", changed.scanWeightCatalog().weightCatalog.modelName(added));
  }
}
