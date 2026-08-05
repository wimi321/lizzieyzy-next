package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.knowledge.MotifRecognizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对齐 GoAgent teacher/resultSchema.ts + claimVerifier.ts + structuredTeachingResult.ts：
 * - ResultSchema：结构化教学结果类型
 * - ClaimVerifier：基于 claim 的防编造校验（coordinate/numeric/pv/motif/joseki/life_death/sente_gote/ownership/student_profile）
 * - StructuredTeachingResult：从 LLM 文本提取/校验 GOAGENT_GROUNDING_JSON 结构化输出
 */
public final class ClaimVerifier {

    private ClaimVerifier() {}

    public enum GroundedClaimType { coordinate, numeric, pv, motif, joseki, life_death, sente_gote, ownership, student_profile }
    public enum ClaimConfidence { high, medium, low }

    public static class GroundedTeachingClaim {
        public String id, text, confidence;
        public GroundedClaimType type;
        public List<String> evidenceRefs = new ArrayList<>();
    }
    public static class ClaimVerificationResult {
        public boolean ok; public List<String> warnings = new ArrayList<>(); public List<String> violations = new ArrayList<>();
        public List<String> allowedMoves = new ArrayList<>(); public int checkedClaims;
    }
    public static class StructuredTeacherResultSchema {
        public String taskType, headline, summary, markdown;
        public List<KeyMistake> keyMistakes = new ArrayList<>();
        public List<String> correctThinking = new ArrayList<>(); public List<String> drills = new ArrayList<>();
        public List<String> followupQuestions = new ArrayList<>(); public List<String> knowledgeCardIds = new ArrayList<>();
        public ProfileUpdates profileUpdates = new ProfileUpdates();
    }
    public static class ProfileUpdates {
        public List<String> errorTypes = new ArrayList<>(); public List<String> patterns = new ArrayList<>(); public List<String> trainingFocus = new ArrayList<>();
    }
    public static class KeyMistake { public Integer moveNumber; public String color, played, recommended, errorType, severity, evidence, explanation; }

    public static class GroundedTeachingSection { public String id, section, markdown; public List<String> claimIds = new ArrayList<>(); }
    public static class GroundedTeachingOutput {
        public int schemaVersion = 1; public String headline, summary, confidence, finalMarkdown;
        public List<GroundedTeachingClaim> claims = new ArrayList<>();
        public List<GroundedTeachingSection> sections = new ArrayList<>();
        public List<String> drills = new ArrayList<>(); public List<String> followupQuestions = new ArrayList<>();
    }

    static double round(Double v, int d) { if (v == null || !Double.isFinite(v)) return 0; double f = Math.pow(10, d); return Math.round(v * f) / f; }

    static List<String> allowedMoves(TeachingEvidenceBuilder.TeachingEvidence ev) {
        Set<String> values = new HashSet<>();
        if (ev.actualMove != null) values.add(ev.actualMove.toUpperCase());
        for (TeachingEvidenceBuilder.TeachingEvidenceCandidate c : ev.bestCandidates) {
            if (c.move != null) values.add(c.move.toUpperCase());
            if (c.pv != null) for (String m : c.pv) if (m != null) values.add(m.toUpperCase());
        }
        for (TeachingEvidenceBuilder.RecognizedMotifView motif : ev.recognizedMotifs) {
            if (motif.relatedMoves != null) for (String m : motif.relatedMoves) if (m != null) values.add(m.toUpperCase());
            if (motif.expectedNextMoves != null) for (Object o : motif.expectedNextMoves) values.add(o.toString().toUpperCase());
        }
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.equals("PASS")) out.add(v);
        return out;
    }

    static List<String> extractCoordinates(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return new ArrayList<>(result);
        Matcher mt = Pattern.compile("\\b([A-HJ-T](?:1?\\d|2[0-5]))\\b").matcher(text);
        while (mt.find()) result.add(mt.group(1).toUpperCase());
        return new ArrayList<>(result);
    }
    static List<Double> extractPercentages(String text) {
        List<Double> out = new ArrayList<>();
        if (text == null) return out;
        Matcher mt = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(text);
        while (mt.find()) { try { out.add(Double.parseDouble(mt.group(1))); } catch (Exception ignore) {} }
        return out;
    }
    static boolean hasSupportedJoseki(TeachingEvidenceBuilder.TeachingEvidence ev) {
        for (TeachingEvidenceBuilder.RecognizedMotifView m : ev.recognizedMotifs)
            if (m.motifType != null && m.motifType.startsWith("joseki:") && ("strong".equals(m.confidence) || "medium".equals(m.confidence))) return true;
        return false;
    }
    static boolean hasTacticalSupport(TeachingEvidenceBuilder.TeachingEvidence ev) {
        for (TeachingEvidenceBuilder.RecognizedMotifView m : ev.recognizedMotifs) {
            String combined = (m.motifType != null ? m.motifType : "") + " " + (m.title != null ? m.title : "");
            if (combined.matches(".*(life|death|tesuji|cut|connect|ladder|net|snapback|throw|eye|semeai|ko).*") && ("strong".equals(m.confidence) || "medium".equals(m.confidence))) return true;
        }
        return false;
    }
    static boolean near(double value, double target, double tol) { return Math.abs(value - target) <= tol; }

    static void verifyNumericText(String text, TeachingEvidenceBuilder.TeachingEvidence ev, List<String> warnings, List<String> violations) {
        for (double percent : extractPercentages(text)) {
            if (percent > 100) { violations.add("Impossible percentage " + percent + "%."); continue; }
            List<Double> known = new ArrayList<>();
            if (ev.before.winrate != null) known.add(round(ev.before.winrate, 1));
            if (ev.afterActual.winrate != null) known.add(round(ev.afterActual.winrate, 1));
            if (ev.loss.winrateLoss != null) known.add(round(ev.loss.winrateLoss, 1));
            for (TeachingEvidenceBuilder.TeachingEvidenceCandidate c : ev.bestCandidates) if (c.winrate != null) known.add(round(c.winrate, 1));
            if (!known.stream().anyMatch(t -> near(percent, t, 0.6))) warnings.add("Percentage " + percent + "% is not close to any provided winrate/loss evidence.");
        }
        List<Double> scoreMentions = new ArrayList<>();
        if (text != null) {
            Matcher mt = Pattern.compile("(?:目差|亏|领先|落后|score|points?).{0,8}?(-?\\d+(?:\\.\\d+)?)").matcher(text);
            while (mt.find()) { try { scoreMentions.add(Double.parseDouble(mt.group(1))); } catch (Exception ignore) {} }
        }
        List<Double> knownScores = new ArrayList<>();
        if (ev.before.scoreLead != null) knownScores.add(round(ev.before.scoreLead, 1));
        if (ev.before.blackScoreLead != null) knownScores.add(round(ev.before.blackScoreLead, 1));
        if (ev.afterActual.scoreLead != null) knownScores.add(round(ev.afterActual.scoreLead, 1));
        if (ev.afterActual.blackScoreLead != null) knownScores.add(round(ev.afterActual.blackScoreLead, 1));
        if (ev.loss.scoreLoss != null) knownScores.add(round(ev.loss.scoreLoss, 1));
        for (TeachingEvidenceBuilder.TeachingEvidenceCandidate c : ev.bestCandidates) {
            if (c.scoreLead != null) knownScores.add(round(c.scoreLead, 1));
            if (c.blackScoreLead != null) knownScores.add(round(c.blackScoreLead, 1));
        }
        for (double sc : scoreMentions) if (!knownScores.stream().anyMatch(t -> near(sc, t, 0.8))) warnings.add("Score/point value " + sc + " is not close to provided score evidence.");
    }

    public static ClaimVerificationResult verifyGroundedClaims(List<GroundedTeachingClaim> claims, TeachingEvidenceBuilder.TeachingEvidence ev) {
        ClaimVerificationResult r = new ClaimVerificationResult();
        List<String> warnings = r.warnings, violations = r.violations;
        List<String> allowed = allowedMoves(ev);
        Set<String> allowedSet = new HashSet<>(allowed);
        r.allowedMoves = allowed;
        for (GroundedTeachingClaim claim : claims) {
            if (claim.text == null || claim.text.trim().isEmpty()) { warnings.add("Claim " + claim.id + " is empty."); continue; }
            if (claim.evidenceRefs == null || claim.evidenceRefs.isEmpty()) warnings.add("Claim " + claim.id + " has no evidenceRefs.");
            for (String coord : extractCoordinates(claim.text)) if (!allowedSet.contains(coord)) violations.add("Claim " + claim.id + " mentions unsupported coordinate " + coord + ".");
            if (claim.type == GroundedClaimType.numeric) verifyNumericText(claim.text, ev, warnings, violations);
            if (claim.type == GroundedClaimType.joseki && !hasSupportedJoseki(ev)) violations.add("Claim " + claim.id + " names joseki without medium/strong joseki motif evidence.");
            if ((claim.type == GroundedClaimType.life_death || claim.type == GroundedClaimType.sente_gote) && "high".equals(claim.confidence) && !hasTacticalSupport(ev))
                warnings.add("Claim " + claim.id + " is high-confidence tactical claim without explicit tactical motif support.");
            if (ev.loss.confidence != TeachingEvidenceBuilder.TeachingConfidence.high && claim.text.matches(".*(唯一|必然|必杀|净杀|必活|绝对|certain|only\\s+move|forced).*"))
                violations.add("Claim " + claim.id + " is too absolute for " + ev.loss.confidence + "-confidence evidence.");
        }
        r.checkedClaims = claims.size();
        r.ok = violations.isEmpty();
        return r;
    }

    public static List<GroundedTeachingClaim> claimsFromMarkdown(String markdown) {
        List<GroundedTeachingClaim> out = new ArrayList<>();
        if (markdown == null) return out;
        String[] parts = markdown.split("\\n{2,}|(?<=。)|(?<=！)|(?<=？)");
        int idx = 0;
        for (String item : parts) {
            String text = item.trim();
            if (text.isEmpty()) continue;
            GroundedTeachingClaim c = new GroundedTeachingClaim();
            c.id = "markdown-" + (++idx);
            c.text = text;
            c.evidenceRefs.add("markdown-derived");
            c.confidence = "medium";
            if (Pattern.compile("\\b[A-HJ-T](?:1?\\d|2[0-5])\\b").matcher(text).find()) c.type = GroundedClaimType.coordinate;
            else if (Pattern.compile("\\d+(?:\\.\\d+)?\\s*%|目差|score|points?").matcher(text.toLowerCase()).find()) c.type = GroundedClaimType.numeric;
            else if (text.matches(".*(定式|joseki|定石|정석).*")) c.type = GroundedClaimType.joseki;
            else if (text.matches(".*(死活|做活|杀棋|眼|气|libert|life|death).*")) c.type = GroundedClaimType.life_death;
            else if (text.matches(".*(先手|后手|逆收|sente|gote).*")) c.type = GroundedClaimType.sente_gote;
            else c.type = GroundedClaimType.motif;
            out.add(c);
        }
        return out;
    }

    public static ClaimVerificationResult verifyTeacherClaimsFromMarkdown(String markdown, TeachingEvidenceBuilder.TeachingEvidence ev) {
        return verifyGroundedClaims(claimsFromMarkdown(markdown), ev);
    }

    public static String buildClaimVerificationNote(ClaimVerificationResult r) {
        List<String> issues = new ArrayList<>();
        issues.addAll(r.violations); issues.addAll(r.warnings);
        if (issues.size() > 4) issues = issues.subList(0, 4);
        if (issues.isEmpty()) return "> Claim verifier: checked " + r.checkedClaims + " claims; no unsupported coordinates, impossible percentages, or over-absolute claims found.";
        return "> Claim verifier: checked " + r.checkedClaims + " claims; notes: " + String.join("；", issues);
    }

    // ---- structuredTeachingResult.ts ----
    public static final String GOAGENT_GROUNDING_JSON_MARKER = "GOAGENT_GROUNDING_JSON";

    public static String normalizeJsonCandidate(String text) {
        if (text == null) return null;
        Pattern marker = Pattern.compile(GOAGENT_GROUNDING_JSON_MARKER + "\\s*:?\\s*(\\{[\\s\\S]*?\\})\\s*(?:$|\\n)", Pattern.CASE_INSENSITIVE);
        Matcher m = marker.matcher(text);
        if (m.find()) return m.group(1).trim();
        Matcher fenced = Pattern.compile("```(?:json|goagent-grounding-json|goagent_grounding_json)\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(text);
        if (fenced.find() && fenced.group(1).contains("\"claims\"")) return fenced.group(1).trim();
        int first = text.indexOf('{'), last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            String cand = text.substring(first, last + 1);
            return cand.contains("\"claims\"") ? cand : null;
        }
        return null;
    }

    public static class StructuredTeachingValidation { public boolean ok; public List<String> warnings = new ArrayList<>(), violations = new ArrayList<>(); public GroundedTeachingOutput result; }

    public static GroundedTeachingOutput extractGroundedTeachingResult(String text) {
        String candidate = normalizeJsonCandidate(text);
        if (candidate == null) return null;
        try {
            Object parsed = JsonKnowledgeLoader_min().parse(candidate);
            StructuredTeachingValidation v = validateGroundedTeachingResult(parsed);
            return v.ok ? v.result : null;
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    public static StructuredTeachingValidation validateGroundedTeachingResult(Object value) {
        StructuredTeachingValidation v = new StructuredTeachingValidation();
        if (!(value instanceof Map)) { v.violations.add("Structured teaching result must be an object."); return v; }
        Map<String, Object> m = (Map<String, Object>) value;
        if (!Integer.valueOf(1).equals(m.get("schemaVersion"))) v.violations.add("schemaVersion must be 1.");
        for (String key : new String[]{"headline", "summary", "finalMarkdown"}) {
            Object o = m.get(key);
            if (!(o instanceof String) || ((String) o).trim().isEmpty()) v.violations.add(key + " must be a non-empty string.");
        }
        if (!java.util.Arrays.asList("high", "medium", "low").contains(String.valueOf(m.get("confidence")))) v.violations.add("confidence must be high, medium, or low.");
        if (!(m.get("claims") instanceof List)) v.violations.add("claims must be an array.");
        if (!(m.get("sections") instanceof List)) v.violations.add("sections must be an array.");
        List<String> drills = asStringArray(m.get("drills")); if (drills == null) v.violations.add("drills must be an array of strings.");
        List<String> fqs = asStringArray(m.get("followupQuestions")); if (fqs == null) v.violations.add("followupQuestions must be an array of strings.");

        List<GroundedTeachingClaim> claims = new ArrayList<>();
        Set<String> claimIds = new HashSet<>();
        if (m.get("claims") instanceof List) {
            int i = 0;
            for (Object raw : (List<?>) m.get("claims")) {
                if (!(raw instanceof Map)) { v.violations.add("claims[" + i + "] must be an object."); i++; continue; }
                Map<String, Object> c = (Map<String, Object>) raw;
                String id = str(c.get("id")).trim();
                String type = str(c.get("type")).trim();
                String ctext = str(c.get("text")).trim();
                List<String> refs = asStringArray(c.get("evidenceRefs"));
                String conf = str(c.get("confidence")).trim();
                if (id.isEmpty()) v.violations.add("claims[" + i + "].id is required.");
                if (!id.isEmpty() && claimIds.contains(id)) v.violations.add("Duplicate claim id " + id + ".");
                if (!id.isEmpty()) claimIds.add(id);
                if (!java.util.Arrays.asList("coordinate","numeric","pv","motif","joseki","life_death","sente_gote","ownership","student_profile").contains(type)) v.violations.add("claims[" + i + "].type is invalid.");
                if (ctext.isEmpty()) v.violations.add("claims[" + i + "].text is required.");
                if (refs == null || refs.isEmpty()) v.violations.add("claims[" + i + "].evidenceRefs must be non-empty.");
                if (!java.util.Arrays.asList("high","medium","low").contains(conf)) v.violations.add("claims[" + i + "].confidence is invalid.");
                if (!id.isEmpty() && !type.isEmpty() && !ctext.isEmpty() && refs != null && !refs.isEmpty() && java.util.Arrays.asList("high","medium","low").contains(conf)) {
                    GroundedTeachingClaim gtc = new GroundedTeachingClaim();
                    gtc.id = id; gtc.type = GroundedClaimType.valueOf(type); gtc.text = ctext; gtc.evidenceRefs = refs; gtc.confidence = conf;
                    claims.add(gtc);
                }
                i++;
            }
        }
        List<GroundedTeachingSection> sections = new ArrayList<>();
        if (m.get("sections") instanceof List) {
            int i = 0;
            for (Object raw : (List<?>) m.get("sections")) {
                if (!(raw instanceof Map)) { v.violations.add("sections[" + i + "] must be an object."); i++; continue; }
                Map<String, Object> s = (Map<String, Object>) raw;
                String id = str(s.get("id")).trim();
                String section = str(s.get("section")).trim();
                String md = str(s.get("markdown")).trim();
                List<String> scids = asStringArray(s.get("claimIds"));
                if (id.isEmpty()) v.violations.add("sections[" + i + "].id is required.");
                if (!java.util.Arrays.asList("judgement","reason","variation","training","profile","evidence-note").contains(section)) v.violations.add("sections[" + i + "].section is invalid.");
                if (md.isEmpty()) v.warnings.add("sections[" + i + "] has empty markdown.");
                if (scids == null) v.violations.add("sections[" + i + "].claimIds must be an array of strings.");
                if (scids != null) for (String cid : scids) if (!claimIds.contains(cid)) v.violations.add("sections[" + i + "] references unknown claim " + cid);
                if (!id.isEmpty() && java.util.Arrays.asList("judgement","reason","variation","training","profile","evidence-note").contains(section) && scids != null) {
                    GroundedTeachingSection gts = new GroundedTeachingSection(); gts.id = id; gts.section = section; gts.markdown = md; gts.claimIds = scids;
                    sections.add(gts);
                }
                i++;
            }
        }
        if (claims.isEmpty()) v.warnings.add("Structured result has no validated claims; quality gate will fall back to markdown scanning.");
        if (v.violations.isEmpty()) {
            GroundedTeachingOutput out = new GroundedTeachingOutput();
            out.headline = str(m.get("headline")); out.summary = str(m.get("summary"));
            out.confidence = str(m.get("confidence")); out.finalMarkdown = str(m.get("finalMarkdown"));
            out.claims = claims; out.sections = sections; out.drills = drills != null ? drills : new ArrayList<>(); out.followupQuestions = fqs != null ? fqs : new ArrayList<>();
            v.result = out;
        }
        v.ok = v.violations.isEmpty();
        return v;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static List<String> asStringArray(Object o) {
        if (!(o instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object x : (List<?>) o) if (x instanceof String) out.add((String) x);
        return out.isEmpty() && ((List<?>) o).isEmpty() ? new ArrayList<>() : out;
    }
    // 用 knowledge.JsonKnowledgeLoader 的解析器
    static featurecat.lizzie.teacher.knowledge.JsonKnowledgeLoader JsonKnowledgeLoader_min() { return new featurecat.lizzie.teacher.knowledge.JsonKnowledgeLoader(); }

    public static String buildStructuredTeachingInstruction() {
        return "为了便于 GoAgent 校验证据，最终答案应能被拆成结构化 claims。\n"
            + "如果当前 LLM 支持结构化 JSON，请按 " + GOAGENT_GROUNDING_JSON_MARKER + " 输出 GroundedTeachingOutput；否则输出自然语言，但每个关键结论必须可回指到 KataGo、棋盘、知识库或学生画像证据。\n"
            + "每条 claim 必须包含 evidenceRefs；没有证据的坐标、胜率、目差、PV、定式名、死活结论和先后手判断必须降级为假设或省略。\n"
            + "finalMarkdown 是给学生看的最终讲解，claims 是给本地 verifier 检查的证据声明。";
    }
}
