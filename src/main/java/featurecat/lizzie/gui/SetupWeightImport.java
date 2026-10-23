package featurecat.lizzie.gui;

import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.io.IOException;
import java.nio.file.Path;

/** A local import and its immutable directory view, collected outside the event thread. */
record SetupWeightImport(Path path, SetupSnapshot snapshot) {
  static SetupWeightImport read(Path source, boolean humanSl) throws IOException {
    Path imported =
        humanSl
            ? KataGoAutoSetupHelper.importHumanSlModel(source)
            : KataGoAutoSetupHelper.importWeight(source);
    return new SetupWeightImport(
        imported, KataGoAutoSetupHelper.inspectLocalSetup().scanWeightCatalog());
  }
}
