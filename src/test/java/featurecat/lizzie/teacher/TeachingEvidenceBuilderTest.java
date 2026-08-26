package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.teacher.TeachingEvidenceBuilder.KnowledgeReference;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.MarkdownVerification;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.RecognizedMotifView;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.TeachingConfidence;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.TeachingEvidence;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.TeachingEvidenceCandidate;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.TeachingPhase;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder.TeachingSeverity;
import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import featurecat.lizzie.teacher.knowledge.MotifRecognizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingEvidenceBuilderTest {
  private static final double EPS = 0.0001;
  private static final List<String> LONG_PV =
      List.of("Q16", "D16", "Q4", "D4", "C3", "R16", "F3", "C14", "E4", "K10");

  @Test
  void mapsMoveAnalysisToRoundedEvidenceAndCurrentLimits() {
    MoveAnalysis analysis =
        analysis(
            12,
            "D4",
            AnalysisBrain.Severity.MISTAKE,
            8.12,
            4.56,
            pvCandidate("Q16", 1, 220, 62.345, 2.344, LONG_PV),
            pvCandidate("D4", 2, 180, 53.119, -1.049, List.of("D4", "C3")));

    TeachingEvidence evidence =
        TeachingEvidenceBuilder.buildTeachingEvidence(analysis, "why D4", List.of(), List.of(), List.of());

    assertEquals(12, evidence.moveNumber);
    assertEquals(TeachingPhase.opening, evidence.phase);
    assertEquals("D4", evidence.actualMove);
    assertEquals("game-1", evidence.gameId);
    assertEquals(19, evidence.boardSize);
    assertEquals("why D4", evidence.userPrompt);

    assertEquals(61.24, evidence.before.winrate, EPS);
    assertEquals(3.46, evidence.before.scoreLead, EPS);
    assertEquals(61.24, evidence.before.blackWinrate, EPS);
    assertEquals(3.46, evidence.before.blackScoreLead, EPS);
    assertEquals(53.11, evidence.afterActual.winrate, EPS);
    assertEquals(-1.04, evidence.afterActual.scoreLead, EPS);
    assertEquals(53.12, evidence.playedMove.winrate, EPS);
    assertEquals(-1.05, evidence.playedMove.scoreLead, EPS);
    assertEquals("D4", evidence.playedMove.move);

    assertEquals(8.12, evidence.loss.winrateLoss, EPS);
    assertEquals(4.56, evidence.loss.scoreLoss, EPS);
    assertEquals(TeachingSeverity.mistake, evidence.loss.severity);
    assertEquals(TeachingConfidence.high, evidence.loss.confidence);

    assertEquals(2, evidence.bestCandidates.size());
    TeachingEvidenceCandidate best = evidence.bestCandidates.get(0);
    assertEquals("Q16", best.move);
    assertEquals(1, best.rank);
    assertEquals(220, best.visits);
    assertEquals(62.35, best.winrate, EPS);
    assertEquals(2.34, best.scoreLead, EPS);
    assertEquals(8, best.pv.size());
    assertEquals(List.of("Q16", "D16", "Q4", "D4", "C3", "R16", "F3", "C14"), best.pv);
    assertFalse(best.pv.contains("E4"));
    assertFalse(best.pv.contains("K10"));

    assertTrue(hasConstraint(evidence, "coordinates"));
    assertTrue(hasConstraint(evidence, "Do not invent winrate"));
    assertTrue(hasConstraint(evidence, "joseki:*"));
    assertTrue(hasConstraint(evidence, "sourceRefs"));
    assertTrue(hasConstraint(evidence, "medium/low"));
  }

  @Test
  void infersPhaseSeverityAndConfidenceFromCurrentThresholds() {
    assertEquals(TeachingPhase.opening, evidenceForMoveNumber(50).phase);
    assertEquals(TeachingPhase.middle, evidenceForMoveNumber(51).phase);
    assertEquals(TeachingPhase.middle, evidenceForMoveNumber(160).phase);
    assertEquals(TeachingPhase.endgame, evidenceForMoveNumber(161).phase);

    TeachingEvidence unknown =
        TeachingEvidenceBuilder.buildTeachingEvidence(
            analysis(12, "D4", null, 0, 0, pvCandidate("Q16", 1, 300, 60.0, 2.0, List.of("Q16"))),
            "prompt",
            List.of(),
            List.of(),
            List.of());
    assertEquals(TeachingSeverity.uncertain, unknown.loss.severity);

    TeachingEvidence low =
        TeachingEvidenceBuilder.buildTeachingEvidence(
            analysis(
                12,
                "D4",
                AnalysisBrain.Severity.MISTAKE,
                8.0,
                4.0,
                pvCandidate("Q16", 1, 40, 60.0, 2.0, List.of("Q16"))),
            "prompt",
            List.of(),
            List.of(),
            List.of());
    assertEquals(TeachingConfidence.low, low.loss.confidence);
  }

  @Test
  void capsMotifsAndKnowledgeAndTreatsMissingCollectionsAsEmpty() {
    List<MotifRecognizer.RecognizedTeachingMotif> motifs = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      motifs.add(
          motif(
              "motif-" + i,
              "Title " + i,
              i == 0 ? "joseki:3-4" : "shape:generic",
              MotifRecognizer.MotifConfidence.medium,
              0.80 - i * 0.01));
    }
    motifs.get(0).whyMatched = "corner 3-4";
    motifs.get(0).relatedMoves = new String[] {"Q16", "D16"};
    motifs.get(0).sourceRefs = new String[] {"sensei:3-4"};
    motifs.get(0).josekiFamily = "komoku";

    List<KnowledgeReference> knowledge = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      knowledge.add(knowledge("k-" + i, "Knowledge " + i, "medium", 0.70 - i * 0.01));
    }

    MoveAnalysis analysis =
        analysis(
            12,
            "D4",
            AnalysisBrain.Severity.INACCURACY,
            3.0,
            1.6,
            pvCandidate("Q16", 1, 200, 60.0, 2.0, List.of("Q16")));

    TeachingEvidence evidence =
        TeachingEvidenceBuilder.buildTeachingEvidence(
            analysis, "prompt", motifs, knowledge, List.of());

    assertEquals(8, evidence.recognizedMotifs.size());
    assertEquals("motif-0", evidence.recognizedMotifs.get(0).id);
    assertEquals("Title 0", evidence.recognizedMotifs.get(0).title);
    assertEquals("joseki:3-4", evidence.recognizedMotifs.get(0).motifType);
    assertEquals("medium", evidence.recognizedMotifs.get(0).confidence);
    assertEquals(0.80, evidence.recognizedMotifs.get(0).score, EPS);
    assertEquals("corner 3-4", evidence.recognizedMotifs.get(0).whyMatched);
    assertEquals(List.of("Q16", "D16"), evidence.recognizedMotifs.get(0).relatedMoves);
    assertEquals(List.of("sensei:3-4"), evidence.recognizedMotifs.get(0).sourceRefs);
    assertEquals("komoku", evidence.recognizedMotifs.get(0).josekiFamily);
    assertEquals("motif-7", evidence.recognizedMotifs.get(7).id);

    assertEquals(6, evidence.knowledgeReferences.size());
    assertEquals("k-0", evidence.knowledgeReferences.get(0).id);
    assertEquals("Knowledge 0", evidence.knowledgeReferences.get(0).title);
    assertEquals("medium", evidence.knowledgeReferences.get(0).confidence);
    assertEquals(0.70, evidence.knowledgeReferences.get(0).score, EPS);
    assertEquals("matched k-0", evidence.knowledgeReferences.get(0).whyMatched);
    assertEquals("k-5", evidence.knowledgeReferences.get(5).id);

    TeachingEvidence empty =
        TeachingEvidenceBuilder.buildTeachingEvidence(analysis, "prompt", null, null, null);
    assertTrue(empty.recognizedMotifs.isEmpty());
    assertTrue(empty.knowledgeReferences.isEmpty());
    assertTrue(empty.recommendedProblems.isEmpty());
  }

  @Test
  void verifyTeacherMarkdownFlagsUnsupportedRecommendationAndImpossiblePercent() {
    TeachingEvidence evidence = evidenceWithLegalMoves();

    MarkdownVerification unsupported =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("推荐T19。", evidence);
    assertFalse(unsupported.ok);
    assertTrue(hasIssue(unsupported.violations, "Unsupported recommended coordinate T19"));

    MarkdownVerification impossible =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("胜率 101%。", evidence);
    assertFalse(impossible.ok);
    assertTrue(hasIssue(impossible.violations, "101.0%"));
    assertTrue(hasIssue(impossible.violations, "Impossible winrate percentage"));

    MarkdownVerification boundary =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("胜率 100%。", evidence);
    assertFalse(hasIssue(boundary.violations, "Impossible winrate percentage"));
  }

  @Test
  void verifyTeacherMarkdownAcceptsLegalActualCandidateAndPvCoordinates() {
    TeachingEvidence evidence = evidenceWithLegalMoves();

    MarkdownVerification actual =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("推荐D4。", evidence);
    MarkdownVerification candidate =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("推荐Q16。", evidence);
    MarkdownVerification pv =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("推荐C3。", evidence);

    assertTrue(actual.ok);
    assertTrue(candidate.ok);
    assertTrue(pv.ok);
    assertFalse(hasIssue(actual.violations, "Unsupported recommended coordinate"));
    assertFalse(hasIssue(candidate.violations, "Unsupported recommended coordinate"));
    assertFalse(hasIssue(pv.violations, "Unsupported recommended coordinate"));
  }

  @Test
  void verifyTeacherMarkdownWarnsOnAbsoluteJosekiAndSourceCitations() {
    TeachingEvidence evidence = evidenceWithLegalMoves();
    evidence.loss.confidence = TeachingConfidence.low;

    MarkdownVerification absolute =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("这是明显恶手。", evidence);
    assertTrue(hasIssue(absolute.warnings, "too absolute"));

    evidence.loss.confidence = TeachingConfidence.high;
    MarkdownVerification highConfidence =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("这是明显恶手。", evidence);
    assertFalse(hasIssue(highConfidence.warnings, "too absolute"));

    MarkdownVerification unnamedJoseki =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("这是定式。", evidence);
    assertTrue(hasIssue(unnamedJoseki.warnings, "Joseki terminology"));

    RecognizedMotifView joseki = new RecognizedMotifView();
    joseki.motifType = "joseki:3-4";
    joseki.confidence = "medium";
    joseki.title = "小目定式";
    evidence.recognizedMotifs.add(joseki);
    MarkdownVerification supportedJoseki =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("这是定式。", evidence);
    assertFalse(hasIssue(supportedJoseki.warnings, "Joseki terminology"));

    MarkdownVerification cited =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("according to Sensei this is forced.", evidence);
    assertTrue(hasIssue(cited.warnings, "cite external sources"));
    assertTrue(hasIssue(cited.warnings, "sourceRefs"));

    MarkdownVerification labelsOnly =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("sourceRefs 只是追溯标签。", evidence);
    assertFalse(hasIssue(labelsOnly.warnings, "cite external sources"));
  }

  @Test
  void verifyTeacherMarkdownStaysQuietForInConstraintText() {
    TeachingEvidence evidence = evidenceWithLegalMoves();

    MarkdownVerification verification =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("实战D4，推荐Q16，后续C3。胜率53.1%。", evidence);

    assertTrue(verification.ok);
    assertTrue(verification.violations.isEmpty());
    assertTrue(verification.warnings.isEmpty());
  }

  @Test
  void verificationNoteLocalizesMotifAndCapsIssueHints() {
    TeachingEvidence evidence = evidenceWithLegalMoves();
    RecognizedMotifView motif = new RecognizedMotifView();
    motif.title = "小目尖冲";
    motif.confidence = "medium";
    motif.score = 0.82;
    motif.sourceRefs = List.of("sensei:3-4", "kogo:high");
    evidence.recognizedMotifs.add(motif);

    MarkdownVerification verification = new MarkdownVerification();
    verification.violations.add("ISSUE-A unsupported coordinate");
    verification.violations.add("ISSUE-B impossible percent");
    verification.warnings.add("ISSUE-C too absolute");
    verification.warnings.add("ISSUE-D extra hint");

    String zh = TeachingEvidenceBuilder.buildVerificationNote(verification, evidence, "zh-CN");
    assertTrue(zh.contains("AI 证据链"));
    assertTrue(zh.contains("识别棋形：小目尖冲"));
    assertTrue(zh.contains("来源标记 sensei:3-4/kogo:high"));
    assertTrue(zh.contains("校验提示"));
    assertTrue(zh.contains("ISSUE-A"));
    assertTrue(zh.contains("ISSUE-B"));
    assertTrue(zh.contains("ISSUE-C"));
    assertFalse(zh.contains("ISSUE-D"));

    String en = TeachingEvidenceBuilder.buildVerificationNote(verification, evidence, "en-US");
    assertTrue(en.contains("Evidence chain"));
    assertTrue(en.contains("Recognized motif: 小目尖冲"));
    assertTrue(en.contains("sources sensei:3-4/kogo:high"));
    assertTrue(en.contains("Verifier notes"));
    assertTrue(en.contains("ISSUE-A"));
    assertTrue(en.contains("ISSUE-B"));
    assertTrue(en.contains("ISSUE-C"));
    assertFalse(en.contains("ISSUE-D"));
  }

  @Test
  void appendVerificationNoteMatchesCurrentCleanAndDirtyContracts() {
    TeachingEvidence evidence = evidenceWithLegalMoves();
    MarkdownVerification clean =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("实战D4，推荐Q16，后续C3。", evidence);
    String cleanNote = TeachingEvidenceBuilder.buildVerificationNote(clean, evidence, "zh-CN");
    String cleanAppended =
        TeachingEvidenceBuilder.appendVerificationNote("实战D4，推荐Q16，后续C3。", clean, evidence, "zh-CN");

    assertTrue(clean.ok);
    assertEquals("实战D4，推荐Q16，后续C3。\n\n" + cleanNote.trim(), cleanAppended);
    assertFalse(cleanAppended.contains("校验提示"));

    MarkdownVerification dirty =
        TeachingEvidenceBuilder.verifyTeacherMarkdown("推荐T19。", evidence);
    String dirtyNote = TeachingEvidenceBuilder.buildVerificationNote(dirty, evidence, "zh-CN");
    String dirtyAppended =
        TeachingEvidenceBuilder.appendVerificationNote("推荐T19。", dirty, evidence, "zh-CN");
    assertEquals("推荐T19。\n\n" + dirtyNote.trim(), dirtyAppended);
    assertTrue(dirtyAppended.contains("校验提示"));
    assertTrue(dirtyAppended.contains("Unsupported recommended coordinate T19"));

    assertEquals(
        dirtyNote, TeachingEvidenceBuilder.appendVerificationNote("  \n", dirty, evidence, "zh-CN"));
  }

  private static TeachingEvidence evidenceForMoveNumber(int moveNumber) {
    return TeachingEvidenceBuilder.buildTeachingEvidence(
        analysis(
            moveNumber,
            "D4",
            AnalysisBrain.Severity.GOOD,
            0.4,
            0.2,
            pvCandidate("Q16", 1, 200, 60.0, 2.0, List.of("Q16"))),
        "prompt",
        List.of(),
        List.of(),
        List.of());
  }

  private static TeachingEvidence evidenceWithLegalMoves() {
    TeachingEvidence evidence = new TeachingEvidence();
    evidence.moveNumber = 12;
    evidence.actualMove = "D4";
    evidence.loss.winrateLoss = 8.1;
    evidence.loss.scoreLoss = 4.2;
    evidence.loss.severity = TeachingSeverity.mistake;
    evidence.loss.confidence = TeachingConfidence.high;
    TeachingEvidenceCandidate candidate = new TeachingEvidenceCandidate();
    candidate.move = "Q16";
    candidate.pv = List.of("Q16", "C3");
    evidence.bestCandidates.add(candidate);
    return evidence;
  }

  private static MoveAnalysis analysis(
      int moveNumber,
      String actualMove,
      AnalysisBrain.Severity severity,
      double winrateLoss,
      double scoreLoss,
      AnalysisBrain.PvCandidate... candidates) {
    MoveAnalysis analysis = new MoveAnalysis();
    analysis.moveNumber = moveNumber;
    analysis.gameId = "game-1";
    analysis.actualMove = actualMove;
    analysis.beforeWinrate = 61.239;
    analysis.beforeScoreLead = 3.456;
    analysis.afterWinrate = 53.111;
    analysis.afterScoreLead = -1.044;
    analysis.actualWinrate = 53.119;
    analysis.actualScoreLead = -1.049;
    if (severity != null) {
      AnalysisBrain.MoveClassification classification = new AnalysisBrain.MoveClassification();
      classification.severity = severity;
      classification.winrateLoss = winrateLoss;
      classification.scoreLoss = scoreLoss;
      analysis.classification = classification;
    }
    AnalysisBrain.KataGoCandidate best = new AnalysisBrain.KataGoCandidate();
    best.move = "Q16";
    best.visits = 300;
    analysis.best = best;
    AnalysisBrain.PvReport pv = new AnalysisBrain.PvReport();
    pv.candidates.addAll(List.of(candidates));
    analysis.pv = pv;
    return analysis;
  }

  private static AnalysisBrain.PvCandidate pvCandidate(
      String move, int rank, int visits, Double winrate, Double scoreLead, List<String> pv) {
    AnalysisBrain.PvCandidate candidate = new AnalysisBrain.PvCandidate();
    candidate.move = move;
    candidate.rank = rank;
    candidate.visits = visits;
    candidate.winrate = winrate;
    candidate.scoreLead = scoreLead;
    candidate.pv = new ArrayList<>(pv);
    return candidate;
  }

  private static MotifRecognizer.RecognizedTeachingMotif motif(
      String id,
      String title,
      String motifType,
      MotifRecognizer.MotifConfidence confidence,
      double score) {
    MotifRecognizer.RecognizedTeachingMotif motif = new MotifRecognizer.RecognizedTeachingMotif();
    motif.id = id;
    motif.title = title;
    motif.motifType = motifType;
    motif.confidence = confidence;
    motif.score = score;
    motif.whyMatched = "matched " + id;
    return motif;
  }

  private static KnowledgeReference knowledge(
      String id, String title, String confidence, double score) {
    KnowledgeReference reference = new KnowledgeReference();
    reference.id = id;
    reference.title = title;
    reference.confidence = confidence;
    reference.score = score;
    reference.whyMatched = "matched " + id;
    reference.matchType = "joseki";
    return reference;
  }

  private static boolean hasConstraint(TeachingEvidence evidence, String fragment) {
    return evidence.constraints.stream().anyMatch(constraint -> constraint.contains(fragment));
  }

  private static boolean hasIssue(List<String> issues, String fragment) {
    return issues.stream().anyMatch(issue -> issue.contains(fragment));
  }
}
