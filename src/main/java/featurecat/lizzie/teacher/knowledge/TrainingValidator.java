package featurecat.lizzie.teacher.knowledge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对齐 GoAgent knowledge/training.ts（250 行）：
 * loadKnowledgeTrainingLibrary（解析 training-catalog.json 为 KnowledgeTrainingLibrary）
 * + validateKnowledgeTrainingLibrary（duplicate id / title/family / sourceKind / GTP 点 / branches /
 *   decisionRules / initialStones / correctMoves / failureMoves / teaching guidance / patternCardId 引用校验）。
 */
public final class TrainingValidator {

    private TrainingValidator() {}

    public static class KnowledgeTrainingLibrary {
        public int version = 1;
        public String generatedAt = "";
        public SourcePolicy sourcePolicy = new SourcePolicy();
        public List<JsonKnowledgeLoader.JosekiLineEntry> josekiLines = new ArrayList<>();
        public List<LocalShapeGeometryMatcher.ProblemEntry> lifeDeathProblems = new ArrayList<>();
        public List<LocalShapeGeometryMatcher.ProblemEntry> tesujiProblems = new ArrayList<>();
    }
    public static class SourcePolicy {
        public String defaultSourceKind = "common-pattern", rule = "";
        public List<String> allowedKinds = new ArrayList<>(Arrays.asList("original", "common-pattern", "licensed-source"));
    }
    public static class KnowledgeTrainingValidationResult {
        public boolean ok;
        public Counts counts = new Counts();
        public List<String> errors = new ArrayList<>();
    }
    public static class Counts { public int josekiLines, lifeDeathProblems, tesujiProblems; }

    private static KnowledgeTrainingLibrary cached;
    private static String cachedRoot = "";

    public static KnowledgeTrainingLibrary loadKnowledgeTrainingLibrary() {
        if (cached != null && "resources".equals(cachedRoot)) return cached;
        try (InputStream in = TrainingValidator.class.getResourceAsStream("/knowledge/training-catalog.json")) {
            if (in == null) { cachedRoot = "resources"; cached = new KnowledgeTrainingLibrary(); return cached; }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object root = JsonKnowledgeLoader.parse(text);
            KnowledgeTrainingLibrary lib = new KnowledgeTrainingLibrary();
            if (root instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) root;
                if (m.get("version") instanceof Number) lib.version = ((Number) m.get("version")).intValue();
                if (m.get("generatedAt") instanceof String) lib.generatedAt = (String) m.get("generatedAt");
                Object sp = m.get("sourcePolicy");
                if (sp instanceof Map) {
                    Map<?, ?> sm = (Map<?, ?>) sp;
                    if (sm.get("defaultSourceKind") instanceof String) lib.sourcePolicy.defaultSourceKind = (String) sm.get("defaultSourceKind");
                    if (sm.get("rule") instanceof String) lib.sourcePolicy.rule = (String) sm.get("rule");
                    if (sm.get("allowedKinds") instanceof List) {
                        List<String> kinds = new ArrayList<>();
                        for (Object k : (List<?>) sm.get("allowedKinds")) if (k != null) kinds.add(String.valueOf(k));
                        lib.sourcePolicy.allowedKinds = kinds;
                    }
                }
            }
            lib.josekiLines = JsonKnowledgeLoader.loadJosekiLines();
            List<LocalShapeGeometryMatcher.ProblemEntry> all = JsonKnowledgeLoader.loadTrainingProblems();
            for (LocalShapeGeometryMatcher.ProblemEntry p : all) {
                if ("life_death".equals(p.problemKind)) lib.lifeDeathProblems.add(p);
                else if ("tesuji".equals(p.problemKind)) lib.tesujiProblems.add(p);
            }
            cachedRoot = "resources"; cached = lib; return lib;
        } catch (Exception e) {
            cachedRoot = "resources"; cached = new KnowledgeTrainingLibrary(); return cached;
        }
    }

    static boolean isGtpPoint(String point) {
        if (point == null) return false;
        var m = java.util.regex.Pattern.compile("^([A-HJ-T])(\\d{1,2})$").matcher(point.trim().toUpperCase());
        if (!m.find()) return false;
        String letters = "ABCDEFGHJKLMNOPQRST";
        int col = letters.indexOf(m.group(1));
        int row = Integer.parseInt(m.group(2));
        return col >= 0 && col < 19 && row >= 1 && row <= 19;
    }
    static void validateSourceKind(String kind, Set<String> allowed, List<String> errors, String id) {
        if (kind == null || !allowed.contains(kind)) errors.add(id + ": sourceKind must be original, common-pattern, or licensed-source");
    }
    static void validatePointList(List<String> points, List<String> errors, String id, String field) {
        for (String p : points) if (!isGtpPoint(p)) errors.add(id + ": invalid GTP point in " + field + ": " + p);
    }
    static void validateStones(List<LocalPatternMatcher.BoardSnapshotStone> stones, List<String> errors, String id) {
        if (stones == null || stones.isEmpty()) { errors.add(id + ": initialStones must not be empty"); return; }
        for (LocalPatternMatcher.BoardSnapshotStone s : stones) {
            if (!("B".equals(s.color) || "W".equals(s.color)) || !isGtpPoint(s.point)) errors.add(id + ": invalid initial stone " + s.point + "/" + s.color);
        }
    }
    static void validateMoveExplanations(List<LocalShapeGeometryMatcher.TrainingMove> moves, List<String> errors, String id, String field) {
        if (moves == null || moves.isEmpty()) { errors.add(id + ": " + field + " must not be empty"); return; }
        for (LocalShapeGeometryMatcher.TrainingMove m : moves) if (!isGtpPoint(m.move)) errors.add(id + ": invalid " + field + " move " + m.move);
    }
    static void validatePatternRefs(List<String> ids, Set<String> knownPatternIds, List<String> errors, String id) {
        if (knownPatternIds == null) return;
        for (String pid : ids) if (!knownPatternIds.contains(pid)) errors.add(id + ": unknown patternCardId " + pid);
    }

    public static KnowledgeTrainingValidationResult validateKnowledgeTrainingLibrary(KnowledgeTrainingLibrary lib, Set<String> knownPatternIds) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> allowedKinds = new HashSet<>(lib.sourcePolicy.allowedKinds);

        for (JsonKnowledgeLoader.JosekiLineEntry line : lib.josekiLines) {
            if (ids.contains(line.id)) errors.add(line.id + ": duplicate id");
            ids.add(line.id);
            if (line.title == null || line.title.isEmpty() || line.family == null || line.family.isEmpty()) errors.add(line.id + ": missing title or family");
            validateSourceKind(line.sourceKind, allowedKinds, errors, line.id);
            validatePointList(line.relativeSequence, errors, line.id, "relativeSequence");
            if (line.branches == null || line.branches.isEmpty()) errors.add(line.id + ": branches must not be empty");
            if (line.decisionRules == null || line.decisionRules.isEmpty()) errors.add(line.id + ": decisionRules must not be empty");
            validatePatternRefs(line.patternCardIds != null ? line.patternCardIds : new ArrayList<>(), knownPatternIds, errors, line.id);
        }
        for (LocalShapeGeometryMatcher.ProblemEntry p : lib.lifeDeathProblems) {
            if (ids.contains(p.id)) errors.add(p.id + ": duplicate id");
            ids.add(p.id);
            if (p.title == null || p.title.isEmpty() || p.objective == null || p.objective.isEmpty()) errors.add(p.id + ": missing title or objective");
            validateSourceKind(p.sourceKind, allowedKinds, errors, p.id);
            validateStones(p.initialStones, errors, p.id);
            validateMoveExplanations(p.correctMoves, errors, p.id, "correctMoves");
            validateMoveExplanations(p.failureMoves, errors, p.id, "failureMoves");
            if (p.teachingRecognition == null || p.teachingRecognition.isEmpty() || p.teachingFailureExplanation() == null) errors.add(p.id + ": missing teaching guidance");
            validatePatternRefs(p.patternCardIds != null ? p.patternCardIds : new ArrayList<>(), knownPatternIds, errors, p.id);
        }
        for (LocalShapeGeometryMatcher.ProblemEntry p : lib.tesujiProblems) {
            if (ids.contains(p.id)) errors.add(p.id + ": duplicate id");
            ids.add(p.id);
            if (p.title == null || p.title.isEmpty() || p.objective == null || p.objective.isEmpty()) errors.add(p.id + ": missing title or objective");
            validateSourceKind(p.sourceKind, allowedKinds, errors, p.id);
            validateStones(p.initialStones, errors, p.id);
            validateMoveExplanations(p.correctMoves, errors, p.id, "correctMoves");
            validateMoveExplanations(p.failureMoves, errors, p.id, "failureMoves");
            if (p.teachingRecognition == null || p.teachingRecognition.isEmpty() || p.teachingFailureExplanation() == null) errors.add(p.id + ": missing teaching guidance");
            validatePatternRefs(p.patternCardIds != null ? p.patternCardIds : new ArrayList<>(), knownPatternIds, errors, p.id);
        }

        KnowledgeTrainingValidationResult r = new KnowledgeTrainingValidationResult();
        r.ok = errors.isEmpty();
        r.counts.josekiLines = lib.josekiLines.size();
        r.counts.lifeDeathProblems = lib.lifeDeathProblems.size();
        r.counts.tesujiProblems = lib.tesujiProblems.size();
        r.errors = errors;
        return r;
    }
}
