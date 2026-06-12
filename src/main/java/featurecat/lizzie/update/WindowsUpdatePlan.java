package featurecat.lizzie.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WindowsUpdatePlan {
  public final UpdateManifest manifest;
  public final InstalledUpdateState installedState;
  public final String currentVersion;
  public final String currentFlavor;
  public final List<Item> items;

  private WindowsUpdatePlan(
      UpdateManifest manifest,
      InstalledUpdateState installedState,
      String currentVersion,
      String currentFlavor,
      List<Item> items) {
    this.manifest = manifest;
    this.installedState = installedState;
    this.currentVersion = currentVersion;
    this.currentFlavor = currentFlavor;
    this.items = Collections.unmodifiableList(new ArrayList<>(items));
  }

  public static WindowsUpdatePlan create(
      UpdateManifest manifest,
      InstalledUpdateState installedState,
      String currentVersion,
      String currentFlavor) {
    if (manifest == null) {
      throw new IllegalArgumentException("Update manifest is required.");
    }
    if (UpdateVersion.shouldSkipAutomaticCheck(currentVersion)
        || !UpdateVersion.isNewerThan(manifest.releaseTag, currentVersion)) {
      return new WindowsUpdatePlan(
          manifest, installedState, currentVersion, currentFlavor, Collections.emptyList());
    }

    String normalizedFlavor = InstalledUpdateState.normalize(currentFlavor);
    InstalledUpdateState state =
        installedState != null
            ? installedState
            : InstalledUpdateState.empty(currentVersion, "windows", normalizedFlavor);
    List<Item> items = new ArrayList<>();
    boolean hasCore = false;
    for (UpdateManifest.Component component : manifest.components) {
      if (!component.matches("windows", normalizedFlavor)) {
        continue;
      }
      InstalledUpdateState.ComponentState installed = state.component(component.id);
      boolean changed = installed == null || !installed.matches(component);
      if (!changed) {
        continue;
      }
      boolean selected = component.defaultSelectedIfChanged || "core".equals(component.id);
      items.add(new Item(component, selected, installed == null));
      if ("core".equals(component.id)) {
        hasCore = true;
      }
    }
    if (!hasCore) {
      throw new IllegalArgumentException("Windows update manifest has no matching core component.");
    }
    return new WindowsUpdatePlan(manifest, state, currentVersion, normalizedFlavor, items);
  }

  public boolean hasUpdate() {
    return !items.isEmpty();
  }

  public List<Item> selectedItems() {
    List<Item> selected = new ArrayList<>();
    for (Item item : items) {
      if (item.selected) {
        selected.add(item);
      }
    }
    return selected;
  }

  public long selectedSizeBytes() {
    long total = 0L;
    for (Item item : selectedItems()) {
      total += item.component.sizeBytes;
    }
    return total;
  }

  public long coreSizeBytes() {
    long total = 0L;
    for (Item item : selectedItems()) {
      if ("core".equals(item.component.id)) {
        total += item.component.sizeBytes;
      }
    }
    return total;
  }

  public long resourceSizeBytes() {
    return Math.max(0L, selectedSizeBytes() - coreSizeBytes());
  }

  public static final class Item {
    public final UpdateManifest.Component component;
    public final boolean selected;
    public final boolean missingInstalledState;

    Item(UpdateManifest.Component component, boolean selected, boolean missingInstalledState) {
      this.component = component;
      this.selected = selected;
      this.missingInstalledState = missingInstalledState;
    }
  }
}
