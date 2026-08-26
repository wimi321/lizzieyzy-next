package featurecat.lizzie.teacher.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.teacher.TeachingEvidenceBuilder;

import org.junit.jupiter.api.Test;

class QualityGateTest {
  @Test
  void ordinaryMarkdownDoesNotWarnThatStructuredJsonIsMissing() {
    QualityGate.TeacherQualityGateResult result =
        QualityGate.runTeacherQualityGate("这手应先看D4附近的变化。", null, false);

    assertTrue(result.structuredWarnings.isEmpty());
    assertTrue(result.structuredViolations.isEmpty());
  }

  @Test
  void malformedStructuredGroundingJsonIsRejected() {
    QualityGate.TeacherQualityGateResult result =
        QualityGate.runTeacherQualityGate("```json\n{\"claims\": [}\n```", null, false);

    assertFalse(result.structuredViolations.isEmpty());
  }

  @Test
  void runTeacherQualityGateUsesBuilderVerificationWhenEvidenceIsPresent() {
    TeachingEvidenceBuilder.TeachingEvidence evidence =
        new TeachingEvidenceBuilder.TeachingEvidence();
    evidence.moveNumber = 12;
    evidence.actualMove = "D4";
    evidence.loss.winrateLoss = 8.0;
    evidence.loss.scoreLoss = 4.0;
    evidence.loss.severity = TeachingEvidenceBuilder.TeachingSeverity.mistake;
    evidence.loss.confidence = TeachingEvidenceBuilder.TeachingConfidence.medium;
    TeachingEvidenceBuilder.TeachingEvidenceCandidate candidate =
        new TeachingEvidenceBuilder.TeachingEvidenceCandidate();
    candidate.move = "Q16";
    candidate.pv.add("Q16");
    candidate.pv.add("D16");
    evidence.bestCandidates.add(candidate);

    QualityGate.TeacherQualityGateResult result =
        QualityGate.runTeacherQualityGate("推荐T19。", evidence, false);

    assertTrue(
        result.violations.stream()
            .anyMatch(message -> message.contains("Unsupported recommended coordinate T19")));
    assertTrue(result.note.contains("AI 证据链"));
    assertTrue(result.note.contains("校验提示"));

    String appended = QualityGate.appendTeacherQualityGateNote("推荐T19。", result);
    assertTrue(appended.contains("推荐T19。"));
    assertTrue(appended.contains("AI 证据链"));
  }
}
