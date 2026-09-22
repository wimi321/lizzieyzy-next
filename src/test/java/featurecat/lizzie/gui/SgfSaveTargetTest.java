package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SgfSaveTargetTest {
  @Test
  void preservesExistingExtensionRegardlessOfCase() {
    for (String name : new String[] {"game.sgf", "game.SGF", "game.SgF", "中文棋谱.sgf"}) {
      assertEquals(new File(name), LizzieFrame.sgfSaveTarget(new File(name)));
    }
  }

  @Test
  void addsExtensionEvenWhenSgfAppearsElsewhereInTheName() {
    for (String name : new String[] {"game", "sgf-review", "review.sgf.backup", "中文棋谱"}) {
      assertEquals(new File(name + ".sgf"), LizzieFrame.sgfSaveTarget(new File(name)));
    }
  }

  @Test
  void overwriteCheckSeesTheFinalDestination(@TempDir Path directory) throws Exception {
    Path selected = directory.resolve("sgf-review");
    Path target = Files.writeString(directory.resolve("sgf-review.sgf"), "protected");
    assertFalse(Files.exists(selected));
    File resolved = LizzieFrame.sgfSaveTarget(selected.toFile());
    assertEquals(target.toFile(), resolved);
    assertTrue(resolved.exists());
    assertEquals("protected", Files.readString(target));
  }
}
