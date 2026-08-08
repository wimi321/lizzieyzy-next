package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.KataGoCandidate;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.PvReport;
import featurecat.lizzie.teacher.knowledge.MotifRecognizer.RecognizedTeachingMotif;
import java.util.List;

/**
 * 单手分析载体：KataGo 证据（候选/PV/分类）+ 实战手 + 知识匹配结果。
 *
 * <p>教学校验链（TeachingEvidenceBuilder / QualityGate，供主程序棋力估计模块使用）以此为输入；
 * AI 讲解（TeacherDialog）本身使用不可变快照 {@link TeacherEvidence}，不依赖本类。
 */
public class MoveAnalysis {
  public int moveNumber;
  public String gameId;
  public String actualMove;
  public KataGoCandidate best;
  public MoveClassification classification;
  public PvReport pv;
  public List<TeacherEvidenceChip> chips;
  public List<RecognizedTeachingMotif> knowledge;
  public TeachingEvidenceBuilder.TeachingEvidence teachingEvidence;
  public String artifactHtml;
  public double actualWinrate;
  public double actualScoreLead;
  public double beforeWinrate;
  public double afterWinrate;
  public double beforeScoreLead;
  public double afterScoreLead;
  public double[] ownership; // KataGo ownership 数组（来自 BoardData.estimateArray）
}
