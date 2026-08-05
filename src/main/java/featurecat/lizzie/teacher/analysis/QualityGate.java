package featurecat.lizzie.teacher.analysis;

import featurecat.lizzie.teacher.ClaimVerifier;
import featurecat.lizzie.teacher.TeachingEvidenceBuilder;
import java.util.*;

/**
 * 对齐 GoAgent 的 qualityGate.ts（87 行）全量：
 * runTeacherQualityGate（结构化输出验证 + verifyTeacherMarkdown + claimVerification 聚合，
 * failOnWarning 可配置，产出 note）+ appendTeacherQualityGateNote。
 */
public final class QualityGate {

    private QualityGate() {}

    public static class TeacherQualityGateResult {
        public boolean ok;
        public List<String> warnings = new ArrayList<>();
        public List<String> violations = new ArrayList<>();
        public TeachingEvidenceBuilder.MarkdownVerification markdownVerification;
        public ClaimVerifier.ClaimVerificationResult claimVerification;
        public ClaimVerifier.GroundedTeachingOutput structuredOutput;
        public List<String> structuredWarnings = new ArrayList<>();
        public List<String> structuredViolations = new ArrayList<>();
        public String note = "";
    }

    static List<String> mergeUnique(List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        for (String v : values) if (v != null && !v.isEmpty()) set.add(v);
        return new ArrayList<>(set);
    }

    public static TeacherQualityGateResult runTeacherQualityGate(String markdown, TeachingEvidenceBuilder.TeachingEvidence evidence, boolean failOnWarning) {
        TeacherQualityGateResult r = new TeacherQualityGateResult();
        // 结构化输出验证
        ClaimVerifier.GroundedTeachingOutput structured = null;
        Object parsed = null;
        try { parsed = featurecat.lizzie.teacher.knowledge.JsonKnowledgeLoader.parse(ClaimVerifier.normalizeJsonCandidate(markdown) != null ? ClaimVerifier.normalizeJsonCandidate(markdown) : "{}"); } catch (Exception e) { parsed = null; }
        if (ClaimVerifier.normalizeJsonCandidate(markdown) != null) {
            ClaimVerifier.StructuredTeachingValidation sv = ClaimVerifier.validateGroundedTeachingResult(parsed);
            r.structuredWarnings = sv.warnings; r.structuredViolations = sv.violations;
            if (sv.ok) structured = sv.result;
        } else {
            r.structuredWarnings = Collections.singletonList("No structured grounding JSON found; using markdown claim extraction.");
        }
        r.structuredOutput = structured;

        List<String> warnings = new ArrayList<>(r.structuredWarnings);
        List<String> violations = new ArrayList<>(r.structuredViolations);
        TeachingEvidenceBuilder.MarkdownVerification mdv = null;
        ClaimVerifier.ClaimVerificationResult cv = null;
        if (evidence != null) {
            mdv = TeachingEvidenceBuilder.verifyTeacherMarkdown(markdown, evidence);
            cv = (structured != null && structured.claims != null && !structured.claims.isEmpty())
                ? ClaimVerifier.verifyGroundedClaims(structured.claims, evidence)
                : ClaimVerifier.verifyTeacherClaimsFromMarkdown(markdown, evidence);
            warnings.addAll(mdv.warnings); warnings.addAll(cv.warnings);
            violations.addAll(mdv.violations); violations.addAll(cv.violations);
        } else {
            warnings.add("No TeachingEvidence was provided; quality gate could not verify coordinates, numbers, PV, joseki names, or confidence wording.");
        }
        r.warnings = mergeUnique(warnings);
        r.violations = mergeUnique(violations);
        r.ok = r.violations.isEmpty() && (!failOnWarning || r.warnings.isEmpty());

        List<String> noteParts = new ArrayList<>();
        if (evidence != null && mdv != null) noteParts.add(TeachingEvidenceBuilder.buildVerificationNote(mdv, evidence, "zh-CN"));
        if (evidence != null && cv != null) noteParts.add(ClaimVerifier.buildClaimVerificationNote(cv));
        if (noteParts.isEmpty()) {
            List<String> issue = new ArrayList<>(r.violations); issue.addAll(r.warnings);
            String sample = issue.size() > 3 ? String.join("；", issue.subList(0, 3)) : String.join("；", issue);
            noteParts.add("> GoAgent 质量门禁：" + (r.ok ? "通过" : "未通过") + "。" + (sample.isEmpty() ? "没有可校验的证据。" : sample));
        }
        r.note = String.join("\n", noteParts);
        return r;
    }

    public static String appendTeacherQualityGateNote(String markdown, TeacherQualityGateResult gate) {
        String trimmed = markdown == null ? "" : markdown.trim();
        if (gate.note == null || gate.note.trim().isEmpty()) return trimmed;
        return trimmed + "\n\n" + gate.note.trim();
    }
}
