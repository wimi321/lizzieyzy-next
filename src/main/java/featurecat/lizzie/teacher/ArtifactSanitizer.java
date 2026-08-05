package featurecat.lizzie.teacher;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 对齐 GoAgent teachingArtifact.ts 的 JSON sanitize 层（sanitizeText/sanitizeId/sanitizeStringArray/
 * booleanValue/positiveInteger/nonNegativeInteger/normalizeRankValue/firstParagraph/normalizeWinrate/
 * clampWinrate/currentPlayerWinrate/currentPlayerScoreLead/sanitizeFileName/artifactKind/sanitizeKind/
 * sanitizeSource/sanitizeCreatedAt/sanitizeBoardSnapshot/sanitizeCandidates/sanitizeVariations/
 * sanitizeKeyMoves/sanitizeTrainingItems/sanitizeKnowledgeMatches/sanitizeSandboxHtml/validateSandboxHtmlFragment）
 * + 完整 hasRemoteAssetReference / hasLocalPathOrSecret 正则。
 */
public final class ArtifactSanitizer {

    private ArtifactSanitizer() {}

    public static final int MAX_TITLE_CHARS = 120;
    public static final int MAX_SUMMARY_CHARS = 900;
    public static final int MAX_TEXT_CHARS = 420;
    public static final int MAX_CANDIDATES = 8;
    public static final int MAX_VARIATIONS = 6;
    public static final int MAX_KEY_MOVES = 8;
    public static final int MAX_KNOWLEDGE_MATCHES = 4;
    public static final int MAX_TRAINING_ITEMS = 6;
    public static final int MAX_PV_MOVES = 16;
    public static final int MAX_SANDBOX_HTML_CHARS = 60_000;

    public static boolean isRecord(Object v) { return v instanceof Map; }
    public static Object[] arrayValue(Object v) {
        if (v instanceof List) return ((List<?>) v).toArray();
        if (v instanceof Object[]) return (Object[]) v;
        return new Object[0];
    }
    public static boolean booleanValue(Object v, boolean fallback) {
        if (v instanceof Boolean) return (Boolean) v;
        return fallback;
    }
    public static int positiveInteger(Object v, int fallback, int max) {
        Double n = numericValue(v);
        if (n == null || n <= 0) return fallback;
        return (int) Math.min(max, Math.round(n));
    }
    public static int nonNegativeInteger(Object v, int fallback, int max) {
        Double n = numericValue(v);
        if (n == null || n < 0) return fallback;
        return (int) Math.min(max, Math.round(n));
    }
    public static int normalizeRankValue(Object v, int index, boolean zeroBased) {
        Double n = numericValue(v);
        if (n == null) return index + (zeroBased ? 0 : 1);
        return (int) Math.round(n) + (zeroBased ? 0 : 1);
    }
    public static Double numericValue(Object v) {
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return Double.isFinite(d) ? d : null; }
        if (v instanceof String) { try { double d = Double.parseDouble((String) v); return Double.isFinite(d) ? d : null; } catch (Exception e) { return null; } }
        return null;
    }
    public static Double finiteNumber(Object v) { return numericValue(v); }

    public static String sanitizeText(Object v, int maxChars) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.length() > maxChars) s = s.substring(0, maxChars);
        return s;
    }
    public static String sanitizeId(Object v, String fallback) {
        String id = sanitizeText(v, 120);
        if (id.isEmpty()) return fallback;
        return id.replaceAll("[^a-zA-Z0-9_.:\\-\\u4e00-\\u9fa5]", "_");
    }
    public static List<String> sanitizeStringArray(Object v, int limit, int maxChars) {
        List<String> out = new ArrayList<>();
        if (v instanceof List) for (Object item : (List<?>) v) {
            String s = sanitizeText(item, maxChars);
            if (!s.isEmpty()) out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }
    public static String firstParagraph(String markdown) {
        if (markdown == null) return null;
        String s = markdown.trim();
        if (s.isEmpty()) return null;
        int end = s.indexOf("\n\n");
        return end > 0 ? s.substring(0, end).trim() : s;
    }
    public static Double normalizeWinrate(Double v) { if (v == null || !Double.isFinite(v)) return null; return Math.max(0, Math.min(100, v)); }
    public static Double clampWinrate(Double v) { return normalizeWinrate(v); }
    public static boolean isWhiteColor(Object v) { return "W".equals(v); }
    public static Double currentPlayerWinrate(Double blackWinrate, Object color) {
        if (blackWinrate == null) return null;
        return isWhiteColor(color) ? 100 - blackWinrate : blackWinrate;
    }
    public static Double currentPlayerScoreLead(Double blackScoreLead, Object color) {
        if (blackScoreLead == null) return null;
        return isWhiteColor(color) ? -blackScoreLead : blackScoreLead;
    }
    public static String formatWinrate(Double v) {
        if (v == null || !Double.isFinite(v)) return "暂无";
        return String.format("%.1f%%", v);
    }
    public static String formatScore(Double v) {
        if (v == null || !Double.isFinite(v)) return "暂无";
        return String.format("%.1f 目", v);
    }
    public static String sanitizeFileName(String v) {
        if (v == null) return "artifact";
        return v.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_").substring(0, Math.min(80, v.length()));
    }

    public static final Pattern REMOTE_ASSET_TAG = Pattern.compile(
        "<(?:img|script|iframe|link|source|video|audio|object|embed)\\b[^>]*(?:src|href|data|poster)\\s*=\\s*[\"']?\\s*(?:https?:|//|file:|data:)[^>]*>",
        Pattern.CASE_INSENSITIVE);
    public static final Pattern REMOTE_ASSET_CSS = Pattern.compile(
        "(?:url\\(|@import\\s+)(?:\\s*[\"']?)?(?:https?:|//|file:|data:image)", Pattern.CASE_INSENSITIVE);
    public static boolean hasRemoteAssetReference(String html) {
        if (html == null) return false;
        return REMOTE_ASSET_TAG.matcher(html).find() || REMOTE_ASSET_CSS.matcher(html).find();
    }
    public static final Pattern LOCAL_PATH_SECRET = Pattern.compile(
        "(file://|(?:^|[\\s\"'(])(?:/Users|/home|/var|/private|/tmp|/Volumes)/[^\\s\"'<>)]*|\\b[A-Za-z]:\\\\[^\\s\"'<>)]*|\\b(sk-[A-Za-z0-9_-]{12,}|github_pat_[A-Za-z0-9_]{12,}|ghp_[A-Za-z0-9_]{12,}|xox[baprs]-[A-Za-z0-9-]{12,}|AKIA[A-Z0-9]{12,})\\b|[\"']?(?:api[_-]?key|apikey|llmApiKey|ttsCustomApiKey|ttsVolcengineApiKey|ttsVolcengineAccessToken|proxyApiKey|token|password|secret|authorization)[\"']?\\s*[=:]\\s*[\"']?[^\\s\"',}`<>]+)",
        Pattern.CASE_INSENSITIVE);
    public static boolean hasLocalPathOrSecret(String html) {
        return html != null && LOCAL_PATH_SECRET.matcher(html).find();
    }

    // ---- sanitize 对象层 ----
    public static TeachingArtifactBuilder.TeacherArtifactBoardSnapshot sanitizeBoardSnapshot(Object value) {
        if (!isRecord(value)) return null;
        Map<?, ?> m = (Map<?, ?>) value;
        TeachingArtifactBuilder.TeacherArtifactBoardSnapshot snap = new TeachingArtifactBuilder.TeacherArtifactBoardSnapshot();
        snap.boardSize = positiveInteger(m.get("boardSize"), 19, 25);
        Double moveNumber = numericValue(m.get("moveNumber"));
        if (moveNumber != null) snap.moveNumber = Math.max(0, (int) Math.floor(moveNumber));
        if ("B".equals(m.get("currentColor")) || "W".equals(m.get("currentColor"))) snap.currentColor = (String) m.get("currentColor");
        String judgement = sanitizeText(m.get("judgement"), 24);
        if (Arrays.asList("good_move", "inaccuracy", "mistake", "blunder", "unknown").contains(judgement)) snap.judgement = judgement;
        String played = sanitizeText(m.get("playedMove"), 24);
        String best = sanitizeText(m.get("bestMove"), 24);
        if (!played.isEmpty()) snap.playedMove = played;
        if (!best.isEmpty()) snap.bestMove = best;
        for (String f : new String[]{"winrateBefore", "winrateAfter", "playerWinrateAfter", "winrateLoss", "scoreLeadBefore", "scoreLeadAfter", "playerScoreLeadAfter", "scoreLoss"}) {
            Double n = numericValue(m.get(f));
            if (n != null) setSnapNumber(snap, f, n);
        }
        return snap;
    }
    static void setSnapNumber(TeachingArtifactBuilder.TeacherArtifactBoardSnapshot s, String field, double v) {
        switch (field) {
            case "winrateBefore": s.winrateBefore = v; break;
            case "winrateAfter": s.winrateAfter = v; break;
            case "playerWinrateAfter": s.playerWinrateAfter = v; break;
            case "winrateLoss": s.winrateLoss = v; break;
            case "scoreLeadBefore": s.scoreLeadBefore = v; break;
            case "scoreLeadAfter": s.scoreLeadAfter = v; break;
            case "playerScoreLeadAfter": s.playerScoreLeadAfter = v; break;
            case "scoreLoss": s.scoreLoss = v; break;
        }
    }

    public static List<TeachingArtifactBuilder.TeacherArtifactCandidate> sanitizeCandidates(Object value) {
        Object[] items = arrayValue(value);
        Double firstRank = null;
        if (items.length > 0 && isRecord(items[0])) {
            Map<?, ?> m = (Map<?, ?>) items[0];
            Double r = numericValue(m.get("rank"));
            if (r == null) r = numericValue(m.get("order"));
            firstRank = r;
        }
        boolean zeroBased = firstRank != null && firstRank == 0;
        List<TeachingArtifactBuilder.TeacherArtifactCandidate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_CANDIDATES, items.length); i++) {
            Object item = items[i];
            if (!isRecord(item)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            String move = sanitizeText(m.get("move"), 24);
            if (move.isEmpty()) continue;
            Object rankV = m.get("rank");
            if (rankV == null) rankV = m.get("order");
            int rank = normalizeRankValue(rankV, i, zeroBased);
            TeachingArtifactBuilder.TeacherArtifactCandidate c = new TeachingArtifactBuilder.TeacherArtifactCandidate();
            c.rank = rank; c.move = move;
            c.winrate = normalizeWinrate(numericValue(m.get("winrate")));
            c.scoreLead = numericValue(m.get("scoreLead"));
            Double visitsD = numericValue(m.get("visits"));
            c.visits = visitsD != null ? visitsD.longValue() : null;
            c.pv = sanitizeStringArray(m.get("pv"), MAX_PV_MOVES, 24);
            String note = sanitizeText(m.get("note"), 80);
            c.note = note.isEmpty() ? (rank == 1 ? "KataGo 首选" : "第 " + rank + " 选") : note;
            out.add(c);
        }
        return out;
    }

    public static List<TeachingArtifactBuilder.TeacherArtifactVariation> sanitizeVariations(Object value) {
        Object[] items = arrayValue(value);
        List<TeachingArtifactBuilder.TeacherArtifactVariation> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_VARIATIONS, items.length); i++) {
            Object item = items[i];
            if (!isRecord(item)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            String label = sanitizeText(m.get("label"), 80);
            List<String> pv = sanitizeStringArray(m.get("pv"), MAX_PV_MOVES, 24);
            if (label.isEmpty() && pv.isEmpty()) continue;
            TeachingArtifactBuilder.TeacherArtifactVariation v = new TeachingArtifactBuilder.TeacherArtifactVariation();
            v.label = label.isEmpty() ? (pv.isEmpty() ? "变化" : pv.get(0)) : label;
            v.purpose = sanitizeText(m.get("purpose"), 160);
            if (v.purpose.isEmpty()) v.purpose = "教学变化";
            v.pv = pv;
            v.result = sanitizeText(m.get("result"), 160);
            if (v.result.isEmpty()) v.result = "暂无结论";
            Object conf = m.get("confidence");
            if ("high".equals(conf) || "medium".equals(conf) || "low".equals(conf)) v.confidence = (String) conf;
            out.add(v);
        }
        return out;
    }

    public static List<TeachingArtifactBuilder.TeacherArtifactKeyMove> sanitizeKeyMoves(Object value) {
        Object[] items = arrayValue(value);
        List<TeachingArtifactBuilder.TeacherArtifactKeyMove> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_KEY_MOVES, items.length); i++) {
            Object item = items[i];
            if (!isRecord(item)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Double moveNumber = numericValue(m.get("moveNumber"));
            if (moveNumber == null) continue;
            TeachingArtifactBuilder.TeacherArtifactKeyMove km = new TeachingArtifactBuilder.TeacherArtifactKeyMove();
            km.moveNumber = Math.max(0, (int) Math.floor(moveNumber));
            if ("B".equals(m.get("color")) || "W".equals(m.get("color"))) km.color = (String) m.get("color");
            String played = sanitizeText(m.get("played"), 24);
            String recommended = sanitizeText(m.get("recommended"), 24);
            km.played = played.isEmpty() ? null : played;
            km.recommended = recommended.isEmpty() ? null : recommended;
            String sev = sanitizeText(m.get("severity"), 32);
            km.severity = sev.isEmpty() ? null : sev;
            String err = sanitizeText(m.get("errorType"), 80);
            km.errorType = err.isEmpty() ? null : err;
            String summary = sanitizeText(m.get("summary") != null ? m.get("summary") : (m.get("explanation") != null ? m.get("explanation") : m.get("evidence")), MAX_TEXT_CHARS);
            km.summary = summary.isEmpty() ? "关键问题手" : summary;
            out.add(km);
        }
        return out;
    }

    public static List<TeachingArtifactBuilder.TeacherArtifactTrainingItem> sanitizeTrainingItems(Object value) {
        Object[] items = arrayValue(value);
        List<TeachingArtifactBuilder.TeacherArtifactTrainingItem> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_TRAINING_ITEMS, items.length); i++) {
            Object item = items[i];
            if (!isRecord(item)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            String title = sanitizeText(m.get("title"), 120);
            String objective = sanitizeText(m.get("objective"), MAX_TEXT_CHARS);
            if (title.isEmpty() && objective.isEmpty()) continue;
            TeachingArtifactBuilder.TeacherArtifactTrainingItem ti = new TeachingArtifactBuilder.TeacherArtifactTrainingItem();
            ti.id = sanitizeId(m.get("id"), "training-" + (i + 1));
            ti.title = title.isEmpty() ? "训练 " + (i + 1) : title;
            Object kind = m.get("kind");
            ti.kind = ("life_death".equals(kind) || "tesuji".equals(kind) || "concept".equals(kind)) ? (String) kind : "concept";
            String diff = sanitizeText(m.get("difficulty"), 80);
            ti.difficulty = diff.isEmpty() ? null : diff;
            ti.objective = objective.isEmpty() ? "围绕本轮关键点做专项练习" : objective;
            String hint = sanitizeText(m.get("firstHint"), 180);
            ti.firstHint = hint.isEmpty() ? null : hint;
            out.add(ti);
        }
        return out;
    }

    // ---- sandboxHtml ----
    public static class SandboxResult {
        public TeachingArtifactBuilder.TeacherArtifactSandboxHtml sandboxHtml;
        public List<String> warnings = new ArrayList<>();
    }
    public static SandboxResult sanitizeSandboxHtml(Object value, boolean allowSandboxScripts) {
        SandboxResult r = new SandboxResult();
        if (value == null) return r;
        Map<?, ?> input;
        if (value instanceof String) { input = new HashMap<>(); ((Map<Object, Object>) input).put("html", value); }
        else if (isRecord(value)) input = (Map<?, ?>) value;
        else { r.warnings.add("sandboxHtml ignored because it was not an object or string."); return r; }
        String requestedPolicy = sanitizeText(input.get("scriptPolicy"), 40);
        String scriptPolicy = (allowSandboxScripts && requestedPolicy.equals("sandbox-iframe-only")) ? "sandbox-iframe-only" : "disabled";
        String html = TeachingArtifactBuilder.redactSensitiveText(String.valueOf(input.get("html") != null ? input.get("html") : ""));
        if (html.length() > MAX_SANDBOX_HTML_CHARS) html = html.substring(0, MAX_SANDBOX_HTML_CHARS);
        if (html.trim().isEmpty()) return r;
        if (scriptPolicy.equals("disabled") && Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE).matcher(html).find()) {
            r.warnings.add("sandboxHtml scripts were removed because scriptPolicy is disabled.");
            html = html.replaceAll("(?is)<script\\b[\\s\\S]*?</script>", "");
        }
        if (scriptPolicy.equals("disabled") && Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find()) {
            r.warnings.add("sandboxHtml inline event handlers were removed because scriptPolicy is disabled.");
            html = html.replaceAll("(?i)\\son[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        }
        if (scriptPolicy.equals("disabled") && Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE).matcher(html).find()) {
            r.warnings.add("sandboxHtml javascript: URLs were neutralized because scriptPolicy is disabled.");
            html = html.replaceAll("(?i)javascript:", "");
        }
        if (hasRemoteAssetReference(html)) {
            r.warnings.add("sandboxHtml remote, local or data asset tags were removed.");
            html = html.replaceAll("(?is)<(?:img|script|iframe|link|source|video|audio|object|embed)\\b[^>]*(?:src|href|data|poster)\\s*=\\s*[\"']?\\s*(?:https?:|//|file:|data:)[^>]*>", "")
                      .replaceAll("(?i)url\\(\\s*[\"']?(?:https?:|//|file:|data:image)[^)]*\\)", "none")
                      .replaceAll("(?is)@import\\s+[^;]+;", "");
        }
        if (Pattern.compile("data:image/[a-z0-9.+-]+;base64", Pattern.CASE_INSENSITIVE).matcher(html).find() || hasLocalPathOrSecret(html)) {
            r.warnings.add("sandboxHtml sensitive inline data was redacted.");
            html = TeachingArtifactBuilder.redactSensitiveText(html);
        }
        List<String> errors = validateSandboxHtmlFragment(html, scriptPolicy);
        if (!errors.isEmpty()) {
            for (String e : errors) r.warnings.add("sandboxHtml ignored: " + e);
            return r;
        }
        TeachingArtifactBuilder.TeacherArtifactSandboxHtml sh = new TeachingArtifactBuilder.TeacherArtifactSandboxHtml();
        sh.html = html;
        sh.enabled = booleanValue(input.get("enabled"), false) && html.trim().length() > 0;
        sh.scriptPolicy = scriptPolicy;
        sh.iframeSandbox = scriptPolicy.equals("sandbox-iframe-only") ? "allow-scripts" : "";
        sh.warnings = r.warnings;
        r.sandboxHtml = sh;
        return r;
    }
    public static List<String> validateSandboxHtmlFragment(String html, String scriptPolicy) {
        List<String> errors = new ArrayList<>();
        if (scriptPolicy.equals("disabled") && Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE).matcher(html).find()) errors.add("scripts are disabled");
        if (scriptPolicy.equals("disabled") && Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find()) errors.add("inline event handlers are disabled");
        if (scriptPolicy.equals("disabled") && Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE).matcher(html).find()) errors.add("javascript URLs are disabled");
        if (hasRemoteAssetReference(html)) errors.add("remote, local and data assets are not allowed");
        if (Pattern.compile("data:image/[a-z0-9.+-]+;base64", Pattern.CASE_INSENSITIVE).matcher(html).find()) errors.add("base64 images are not allowed");
        if (hasLocalPathOrSecret(html)) errors.add("local paths and API keys are not allowed");
        return errors;
    }
}
