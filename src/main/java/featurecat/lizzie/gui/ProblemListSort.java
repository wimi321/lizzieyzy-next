package featurecat.lizzie.gui;

import java.util.Comparator;

enum ProblemListSort {
  LOSS_DESC("loss-desc", "BlunderListPanel.sort.loss"),
  MOVE_ASC("move-asc", "BlunderListPanel.sort.earliest"),
  MOVE_DESC("move-desc", "BlunderListPanel.sort.latest");

  final String configValue;
  final String labelKey;

  ProblemListSort(String configValue, String labelKey) {
    this.configValue = configValue;
    this.labelKey = labelKey;
  }

  static ProblemListSort fromConfigValue(String value) {
    for (ProblemListSort sort : values()) {
      if (sort.configValue.equals(value)) return sort;
    }
    return LOSS_DESC;
  }

  Comparator<ProblemMoveEntry> comparator() {
    Comparator<ProblemMoveEntry> byMove = Comparator.comparingInt(entry -> entry.moveNumber);
    if (this == MOVE_ASC) return byMove;
    if (this == MOVE_DESC) return byMove.reversed();
    return Comparator.comparingDouble((ProblemMoveEntry entry) -> entry.winrateLossAbs)
        .reversed()
        .thenComparing(byMove);
  }

  @Override
  public String toString() {
    return BlunderListPanel.text(labelKey);
  }
}
