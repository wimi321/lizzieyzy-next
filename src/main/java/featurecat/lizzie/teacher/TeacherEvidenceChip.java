package featurecat.lizzie.teacher;

/**
 * 对齐 GoAgent 的 TeacherEvidenceChip：从 KataGo 分析派生出的可视化证据标签。 kinds: move / coordinate / candidate /
 * loss / pv / knowledge / confidence
 */
public class TeacherEvidenceChip {
  public enum Kind {
    MOVE,
    COORDINATE,
    CANDIDATE,
    LOSS,
    PV,
    KNOWLEDGE,
    CONFIDENCE
  }

  public final String id;
  public final Kind kind;
  public final String label;
  public final String detail;
  public final Integer moveNumber;
  public final String point;

  public TeacherEvidenceChip(
      String id, Kind kind, String label, String detail, Integer moveNumber, String point) {
    this.id = id;
    this.kind = kind;
    this.label = label;
    this.detail = detail;
    this.moveNumber = moveNumber;
    this.point = point;
  }

  @Override
  public String toString() {
    return label + (detail == null || detail.isEmpty() ? "" : " — " + detail);
  }
}
