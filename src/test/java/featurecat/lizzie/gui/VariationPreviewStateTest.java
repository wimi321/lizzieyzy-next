package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.BoardData;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariationPreviewStateTest {

  @Test
  void pendingSelectionAccessibleWithoutImage() {
    VariationPreviewState state = new VariationPreviewState();
    assertNull(state.selected());
    assertNull(state.published());

    Branch.Input input = sampleInput(199);
    VariationPreviewState.Selection selection =
        new VariationPreviewState.Selection(null, "D4", input, -2);

    state.select(selection);

    assertSame(selection, state.selected());
    assertNull(state.published());
  }

  @Test
  void cancelRetainsSameImmutableSnapshot() {
    VariationPreviewState state = new VariationPreviewState();
    Branch.Input input = sampleInput(199);
    VariationPreviewState.Selection selection =
        new VariationPreviewState.Selection(null, "D4", input, -2);
    VariationPreviewGenerator.Result result = sampleResult(input);

    state.select(selection);
    assertTrue(state.publish(selection, selection, result));
    assertSame(result, state.published());

    state.cancelPreview();

    assertNull(state.published());
    assertSame(selection, state.selected());
  }

  @Test
  void stalePublishAfterSelectClearAndSetLengthRejected() {
    VariationPreviewState state = new VariationPreviewState();
    Branch.Input input1 = sampleInput(199);
    Branch.Input input2 = sampleInput(199);
    VariationPreviewState.Selection s1 =
        new VariationPreviewState.Selection(null, "D4", input1, -2);
    VariationPreviewState.Selection s2 =
        new VariationPreviewState.Selection(null, "Q16", input2, -2);
    VariationPreviewGenerator.Result r1 = sampleResult(input1);

    // Stale publish after select(s2)
    state.select(s1);
    state.select(s2);
    assertFalse(state.publish(s1, s1, r1));
    assertSame(s2, state.selected());
    assertNull(state.published());

    // Stale publish after clear()
    state.select(s1);
    state.clear();
    assertFalse(state.publish(s1, s1, r1));
    assertNull(state.selected());
    assertNull(state.published());

    // Stale publish after setDisplayedLength(5)
    state.select(s1);
    assertTrue(state.setDisplayedLength(5));
    assertFalse(state.publish(s1, s1, r1));
    assertEquals(5, state.selected().displayedLength());
    assertNull(state.published());
  }

  @Test
  void refreshReplacementAtomicallyChangesSelectedAndPublished() {
    VariationPreviewState state = new VariationPreviewState();
    Branch.Input input1 = sampleInput(199);
    Branch.Input input2 = sampleInput(199);
    VariationPreviewState.Selection s1 =
        new VariationPreviewState.Selection(null, "D4", input1, -2);
    VariationPreviewState.Selection s2 =
        new VariationPreviewState.Selection(null, "D4", input2, -2);
    VariationPreviewGenerator.Result r1 = sampleResult(input1);
    VariationPreviewGenerator.Result r2 = sampleResult(input2);

    state.select(s1);
    assertTrue(state.publish(s1, s1, r1));
    assertSame(s1, state.selected());
    assertSame(r1, state.published());

    assertTrue(state.publish(s1, s2, r2));
    assertSame(s2, state.selected());
    assertSame(r2, state.published());
  }


  @Test
  void sameDisplayedLengthRetainsPublishedAndReturnsFalse() {
    VariationPreviewState state = new VariationPreviewState();
    Branch.Input input = sampleInput(3);
    VariationPreviewState.Selection s1 =
        new VariationPreviewState.Selection(null, "D4", input, 3);
    VariationPreviewGenerator.Result r1 = sampleResult(input);

    state.select(s1);
    assertTrue(state.publish(s1, s1, r1));

    assertFalse(state.setDisplayedLength(3));
    assertSame(s1, state.selected());
    assertSame(r1, state.published());
  }


  private static Branch.Input sampleInput(int maxLength) {
    BoardData data = BoardData.empty(19, 19);
    Branch.Position position =
        new Branch.Position(19, 19, data, data.stones.clone(), true);
    return new Branch.Input(
        position,
        List.of("D4", "Q16"),
        List.of("100", "200"),
        maxLength,
        false,
        true);
  }

  private static VariationPreviewGenerator.Result sampleResult(Branch.Input input) {
    Branch branch = new Branch(input);
    BufferedImage img1 = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    BufferedImage img2 = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    BufferedImage img3 = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    return new VariationPreviewGenerator.Result(branch, img1, img2, img3, false, true);
  }
}
