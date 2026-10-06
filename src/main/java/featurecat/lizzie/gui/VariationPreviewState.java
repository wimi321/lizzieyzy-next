package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.Branch;
import java.util.Objects;

/**
 * Selection truth and whole preview result publication on the EDT.
 */
final class VariationPreviewState {

  record Selection(
      BranchInputCapture source,
      String coordinate,
      Branch.Input input,
      int displayedLength) {
    Selection {
      Objects.requireNonNull(input, "input");
    }

    Selection withSimulation(boolean removeDeadChains, boolean recordPvVisits) {
      if (input.removeDeadChains == removeDeadChains && input.recordPvVisits == recordPvVisits) {
        return this;
      }
      return new Selection(source, coordinate,
          new Branch.Input(input.position, input.variation, input.pvVisits, input.maxLength,
              removeDeadChains, recordPvVisits), displayedLength);
    }
  }

  private Selection selection;
  private VariationPreviewGenerator.Result published;
  private Selection publishedSelection;
  private Request publishedRequest;
  private VariationPreviewScheduler scheduler;
  private Request requested;
  private long generation;
  private long replayTarget;

  record Mode(featurecat.lizzie.ExtraMode mode, boolean frozen, boolean autoReplay) {}

  private record Request(Selection selection, VariationPreviewGenerator.Geometry geometry,
      VariationPreviewGenerator.Style style, Mode mode) {
    boolean sameContext(Request other) {
      Branch.Input input = selection.input();
      Branch.Input prior = other.selection.input();
      BranchInputCapture source = selection.source();
      BranchInputCapture oldSource = other.selection.source();
      return Objects.equals(selection.coordinate(), other.selection.coordinate())
          && selection.displayedLength() == other.selection.displayedLength()
          && (source == oldSource || source != null && oldSource != null
              && source.sourceData == oldSource.sourceData
              && source.analysisData == oldSource.analysisData && oldSource.isCurrent())
          && input.maxLength == prior.maxLength
          && input.removeDeadChains == prior.removeDeadChains
          && input.recordPvVisits == prior.recordPvVisits
          && geometry.equals(other.geometry) && style.sameRendering(other.style)
          && mode.equals(other.mode);
    }

    boolean sameSurface(Request other) {
      BranchInputCapture source = selection.source();
      BranchInputCapture prior = other.selection.source();
      return (source == prior || source != null && prior != null
              && source.sourceData == prior.sourceData && source.analysisData == prior.analysisData
              && prior.isCurrent())
          && selection.input().removeDeadChains == other.selection.input().removeDeadChains
          && selection.input().recordPvVisits == other.selection.input().recordPvVisits
          && geometry.sameSurface(other.geometry) && style.sameSurface(other.style)
          && mode.equals(other.mode);
    }

    boolean sameContent(Request other) {
      return sameContext(other)
          && selection.input().variation.equals(other.selection.input().variation)
          && Objects.equals(selection.input().pvVisits, other.selection.input().pvVisits);
    }
  }

  VariationPreviewState() {}

  VariationPreviewState(VariationPreviewScheduler scheduler) {
    this.scheduler = scheduler;
  }

  /** Capture/validation may acquire Board and must never run under this monitor. */
  void request(long capturedGeneration, Selection next, VariationPreviewGenerator.Geometry geometry,
      VariationPreviewGenerator.Style style, Mode mode, Runnable validate,
      java.util.function.Consumer<VariationPreviewGenerator.Result> onPublished) {
    Request priorRequest;
    Request priorPublished;
    synchronized (this) {
      if (generation != capturedGeneration || selection == null) return;
      priorRequest = requested;
      priorPublished = publishedRequest;
    }
    Request request = new Request(next, geometry, style, mode);
    boolean unchanged = priorRequest != null && request.sameContent(priorRequest);
    boolean retire = priorRequest != null && !request.sameContext(priorRequest);
    boolean clearImage = priorPublished != null && !request.sameSurface(priorPublished);
    final long expectedGeneration;
    final VariationPreviewScheduler target;
    synchronized (this) {
      if (generation != capturedGeneration || selection == null || unchanged) return;
      if (retire) retireWork();
      if (clearImage) clearPublished();
      requested = request;
      expectedGeneration = generation;
      if (scheduler == null) scheduler = VariationPreviewScheduler.shared();
      target = scheduler;
    }
    // Submission can use an inline executor in tests; it must not capture Board under our lock.
    target.submit(this, next.input(), geometry, style, result -> {
      synchronized (this) {
        if (generation != expectedGeneration || selection == null) return;
      }
      validate.run();
      if (next.source() != null && !next.source().isCurrent()) return;
      synchronized (this) {
        if (generation != expectedGeneration || !publish(selection, next, result)) return;
        publishedRequest = request;
        // Only install immutable renderer fields/repaint here: no Board/engine/Swing waits.
        onPublished.accept(result);
      }
    });
    synchronized (this) {
      // A clear can retire this request before submit reaches the scheduler.
      if (generation != expectedGeneration && requested == null) target.cancel(this);
    }
  }

  synchronized long generation() {
    return generation;
  }

  synchronized long replayTarget() {
    return selection == null ? -1 : replayTarget;
  }

  synchronized void stopReplay() {
    replayTarget++;
  }

  /** Commit an EDT capture only if no engine clear or explicit step crossed it. */
  synchronized long selectIfCurrent(long capturedGeneration, Selection next) {
    if (generation != capturedGeneration) return -1;
    if (selection == null || !Objects.equals(selection.coordinate(), next.coordinate())) select(next);
    return generation;
  }

  synchronized Selection selected() {
    return selection;
  }

  synchronized Selection applicationSelection() {
    return publishedSelection != null ? publishedSelection : selection;
  }

  synchronized Selection publishedSelection() {
    return publishedSelection;
  }

  synchronized VariationPreviewGenerator.Result published() {
    return published;
  }

  synchronized boolean isPending() {
    return requested != null && published == null;
  }

  synchronized void select(Selection next) {
    stopReplay();
    retireWork();
    this.selection = next;
  }

  synchronized void clear() {
    stopReplay();
    cancelPreview();
    this.selection = null;
  }

  synchronized void cancelPreview() {
    retireWork();
    clearPublished();
  }

  private void retireWork() {
    generation++;
    if (scheduler != null) scheduler.cancel(this);
    requested = null;
  }

  private void clearPublished() {
    published = null;
    publishedSelection = null;
    publishedRequest = null;
  }

  synchronized boolean publish(
      Selection expected,
      Selection replacement,
      VariationPreviewGenerator.Result result) {
    Objects.requireNonNull(replacement, "replacement");
    Objects.requireNonNull(result, "result");
    if (this.selection == null || this.selection != expected) {
      return false;
    }
    this.selection = replacement;
    this.published = result;
    this.publishedSelection = replacement;
    return true;
  }

  synchronized boolean setDisplayedLength(int n) {
    if (selection == null || selection.displayedLength() == n) {
      return false;
    }
    Branch.Input oldInput = selection.input();
    Branch.Input newInput =
        new Branch.Input(
            oldInput.position,
            oldInput.variation,
            oldInput.pvVisits,
            n > 0 ? n : 199,
            oldInput.removeDeadChains,
            oldInput.recordPvVisits);
    this.selection =
        new Selection(selection.source(), selection.coordinate(), newInput, n);
    retireWork();
    return true;
  }
}
