package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.knowledge.MotifRecognizer;
import featurecat.lizzie.teacher.TeacherPanel.MoveAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对齐 GoAgent teacher/teachingArtifact.ts（908 行）：把分析 + 结构化结果 + 知识匹配 → 教学产物（HTML）。
 * 包含 buildTeacherArtifact（构建）、createTeachingArtifact/validateTeachingArtifact（校验）、
 * renderTeacherArtifactHtml（HTML 渲染）、一套 sanitize 函数（防注入/裁剪）。
 */
public final class TeachingArtifactBuilder {

    private TeachingArtifactBuilder() {}

    public static final int MAX_KEY_MOVES = 8;
    public static final int MAX_TEXT_CHARS = 420;

    // ---- 产物数据模型 ----
    public enum ArtifactKind { CURRENT_MOVE_REVIEW, MOVE_RANGE_REVIEW, GAME_REVIEW, TRAINING_PLAN, FREEFORM }
    public enum ArtifactSource { RUNTIME_DERIVED, STATIC, IMPORTED }

    public static class TeacherArtifactCandidate {
        public int rank; public String move, note;
        public Double winrate, scoreLead; public Long visits; public List<String> pv = new ArrayList<>();
    }
    public static class TeacherArtifactKeyMove {
        public int moveNumber; public String color, played, recommended, severity, errorType, summary;
    }
    public static class TeacherArtifactVariation {
        public String label, purpose, result, confidence; public List<String> pv = new ArrayList<>();
    }
    public static class TeacherArtifactTrainingItem {
        public String id, kind, difficulty, objective, firstHint, title;
    }
    public static class TeacherArtifactKnowledgeMatch {
        public String matchType, confidence, title, applicability, summary;
    }
    public static class TeacherArtifactBoardSnapshot {
        public int boardSize, moveNumber; public String currentColor, playedMove, bestMove, judgement;
        public Double winrateBefore, winrateAfter, playerWinrateAfter, winrateLoss, scoreLeadBefore, scoreLeadAfter, playerScoreLeadAfter, scoreLoss;
    }
    public static class TeacherArtifactEvidence {
        public boolean katagoReady, boardImageReady; public int knowledgeMatchCount, recommendedProblemCount; public String sourceNote;
    }
    public static class TeacherArtifactSandboxHtml {
        public String html = ""; public boolean enabled; public String scriptPolicy = "disabled"; public String iframeSandbox = ""; public List<String> warnings = new ArrayList<>();
    }
    public static class TeacherArtifactDraft {
        public String id, kind, source, title, createdAt, summary;
        public TeacherArtifactBoardSnapshot boardSnapshot;
        public List<TeacherArtifactCandidate> candidates = new ArrayList<>();
        public List<TeacherArtifactVariation> variations = new ArrayList<>();
        public List<TeacherArtifactKeyMove> keyMoves = new ArrayList<>();
        public List<TeacherArtifactKnowledgeMatch> knowledgeMatches = new ArrayList<>();
        public List<TeacherArtifactTrainingItem> trainingItems = new ArrayList<>();
        public TeacherArtifactEvidence evidence;
        public TeacherArtifactSandboxHtml sandboxHtml;
    }
    public static class TeacherArtifact extends TeacherArtifactDraft { public String exportHtml; public String exportFileName; }

    public static class BuildInput {
        public String id, title, intent, markdown;
        public MoveAnalysis analysis;
        public MoveAnalysis[] rangeAnalyses;
        public StructuredResultParser.StructuredTeacherResult structured;
        public List<MotifRecognizer.RecognizedTeachingMotif> knowledgeMatches;
        public List<TeacherArtifactTrainingItem> recommendedProblems;
        public TeacherPacingAdvice teachingPacing;
        public VisionEvidenceWrapper visionEvidence;
        public TeacherRunRequestLike request;
    }
    public static class TeacherPacingAdvice {
        public List<VariationHint> variationTeachingHints = new ArrayList<>();
        public static class VariationHint { public String move, purpose, result, confidence; public List<String> pv = new ArrayList<>(); }
    }
    public static class VisionEvidenceWrapper { public List<ImageMeta> images = new ArrayList<>();
        public static class ImageMeta { public boolean valid; } }
    public static class TeacherRunRequestLike { public MoveRangeSummary moveRangeSummary; }
    public static class MoveRangeSummary { public List<KeyMoveLike> keyMoves = new ArrayList<>(); }
    public static class KeyMoveLike { public Integer moveNumber; public String playedMove, bestMove, judgement; public Double winrateLoss, scoreLoss; }

    // ---- sanitize helpers ----
    static double finiteNumber(Double v) { return v != null && Double.isFinite(v) ? v : 0; }
    static String sanitizeText(Object v, int maxChars) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.length() > maxChars) s = s.substring(0, maxChars);
        return s;
    }
    static String sanitizeId(Object v, String fallback) { String s = v == null ? "" : String.valueOf(v); return s.isEmpty() ? fallback : s.replaceAll("[^a-zA-Z0-9_-]", ""); }
    static String firstParagraph(String md) {
        if (md == null) return "";
        for (String line : md.split("\n")) { String t = line.trim(); if (!t.isEmpty()) return t; }
        return "";
    }
    static Double normalizeWinrate(Double v) { if (v == null) return null; if (v < 0) return 0.0; if (v > 100) return 100.0; return v; }
    static Double clampWinrate(Double v) { if (v == null) return null; return Math.max(0, Math.min(100, v)); }
    static int normalizeRankValue(double order, int index, boolean zeroBased) {
        if (zeroBased) return (int) Math.round(order) + 1;
        return (int) Math.round(order);
    }
    // 简易敏感信息脱敏（对齐 redactSensitiveText）
    static String redactSensitiveText(String v) {
        if (v == null) return "";
        return v.replaceAll("(?i)(sk-[a-z0-9]{8,}|sk_live_[a-z0-9]+|xoxb-[0-9]+|AIza[0-9A-Za-z_-]{10,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})", "[REDACTED]");
    }
    static String escapeHtml(String v) {
        if (v == null) return "";
        return redactSensitiveText(v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    static String sanitizeFileName(String v) { if (v == null) return "artifact"; return v.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_").substring(0, Math.min(80, v.length())); }
    static ArtifactKind artifactKind(String intent) {
        if ("current-move".equals(intent)) return ArtifactKind.CURRENT_MOVE_REVIEW;
        if ("move-range".equals(intent)) return ArtifactKind.MOVE_RANGE_REVIEW;
        if ("game-review".equals(intent) || "batch-review".equals(intent)) return ArtifactKind.GAME_REVIEW;
        if ("training-plan".equals(intent)) return ArtifactKind.TRAINING_PLAN;
        return ArtifactKind.FREEFORM;
    }
    static String formatWinrate(Double v) { if (v == null) return "—"; return String.format("%.1f%%", v); }
    static String formatScore(Double v) { if (v == null) return "—"; return java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.scoreLeadFormat", "{0}目"), String.format("%.1f", v)); }

    // ---- build ----
    static String candidateNote(String move, int rank, String playedMove) {
        if (playedMove != null && playedMove.equals(move)) return featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.actualPoint", "实战点");
        if (rank == 1) return featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.katagoBest", "KataGo 首选");
        return java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.choiceN", "第 {0} 选"), rank);
    }

    static List<TeacherArtifactCandidate> buildCandidates(MoveAnalysis analysis) {
        List<TeacherArtifactCandidate> out = new ArrayList<>();
        if (analysis == null || analysis.pv == null) return out;
        List<featurecat.lizzie.teacher.analysis.AnalysisBrain.PvCandidate> top = analysis.pv.candidates;
        boolean zeroBased = !top.isEmpty() && top.get(0).rank == 0;
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            featurecat.lizzie.teacher.analysis.AnalysisBrain.PvCandidate c = top.get(i);
            int rank = normalizeRankValue(c.rank, i, zeroBased);
            TeacherArtifactCandidate tc = new TeacherArtifactCandidate();
            tc.rank = rank;
            tc.move = sanitizeText(c.move, 24);
            tc.winrate = analysis.actualWinrate;
            tc.scoreLead = analysis.actualScoreLead;
            tc.visits = (long) c.visits;
            tc.note = candidateNote(c.move, rank, analysis.actualMove);
            out.add(tc);
        }
        return out;
    }

    static List<TeacherArtifactVariation> buildVariations(List<TeacherArtifactCandidate> candidates, TeacherPacingAdvice pacing) {
        List<TeacherArtifactVariation> fromPacing = new ArrayList<>();
        if (pacing != null && pacing.variationTeachingHints != null) {
            for (TeacherPacingAdvice.VariationHint h : pacing.variationTeachingHints.stream().limit(3).toList()) {
                TeacherArtifactVariation v = new TeacherArtifactVariation();
                v.label = h.move; v.purpose = h.purpose; v.pv = h.pv; v.result = h.result; v.confidence = h.confidence;
                fromPacing.add(v);
            }
        }
        if (!fromPacing.isEmpty()) return fromPacing;
        List<TeacherArtifactVariation> out = new ArrayList<>();
        for (TeacherArtifactCandidate c : candidates.stream().limit(3).toList()) {
            if (c.pv.isEmpty()) continue;
            TeacherArtifactVariation v = new TeacherArtifactVariation();
            v.label = c.move; v.purpose = c.rank == 1 ? featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.bestVariation", "首选变化") : (java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.choiceVariation", "第 {0} 选变化"), c.rank));
            v.pv = c.pv; v.result = formatWinrate(c.winrate) + " · " + formatScore(c.scoreLead);
            v.confidence = c.rank == 1 ? "high" : "medium";
            out.add(v);
        }
        return out;
    }

    static List<TeacherArtifactKeyMove> buildKeyMoves(StructuredResultParser.StructuredTeacherResult structured) {
        List<TeacherArtifactKeyMove> out = new ArrayList<>();
        if (structured == null || structured.keyMistakes == null) return out;
        for (StructuredResultParser.KeyMistake m : structured.keyMistakes) {
            if (m.moveNumber == null) continue;
            TeacherArtifactKeyMove km = new TeacherArtifactKeyMove();
            km.moveNumber = m.moveNumber; km.color = m.color; km.played = m.played;
            km.recommended = m.recommended; km.severity = m.severity; km.errorType = m.errorType;
            km.summary = m.explanation != null ? m.explanation : (m.evidence != null ? m.evidence : featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.keyProblemMove", "关键问题手"));
            out.add(km);
        }
        return out.stream().limit(6).toList();
    }

    static List<TeacherArtifactKeyMove> buildAnalysisKeyMoves(MoveAnalysis analysis) {
        if (analysis == null) return new ArrayList<>();
        double loss = analysis.classification != null ? analysis.classification.winrateLoss : 0;
        double scoreLoss = analysis.classification != null ? analysis.classification.scoreLoss : 0;
        String judgement = analysis.classification != null ? analysis.classification.severity.name().toLowerCase() : "unknown";
        if (loss <= 0 && ("good".equals(judgement) || "uncertain".equals(judgement))) return new ArrayList<>();
        TeacherArtifactKeyMove km = new TeacherArtifactKeyMove();
        km.moveNumber = analysis.moveNumber;
        km.color = null;
        km.played = analysis.actualMove;
        km.recommended = analysis.best != null ? analysis.best.move : null;
        km.severity = judgement;
        km.errorType = "good".equals(judgement) ? featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.goodMove", "好手") : "unknown".equals(judgement) ? featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.pending", "待判断") : featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.katagoKeyMove", "KataGo 标记的关键手");
        String lossText = (loss > 0) ? java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.winrateLossAbout", "胜率损失约 {0}%"), String.format("%.1f", loss)) : featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.winrateLossPending", "胜率损失待确认");
        String scoreText = (scoreLoss > 0) ? java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.scoreLeadAbout", "，目差约 {0}目"), String.format("%.1f", scoreLoss)) : "";
        km.summary = (km.recommended != null)
            ? java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.playedVsBest", "实战 {0}，KataGo 首选 {1}；{2}{3}。"), km.played, km.recommended, lossText, scoreText)
            : java.text.MessageFormat.format(featurecat.lizzie.teacher.TeacherI18n.t("TeachingArtifactBuilder.playedOnly", "实战 {0}；{1}{2}。"), km.played, lossText, scoreText);
        List<TeacherArtifactKeyMove> out = new ArrayList<>(); out.add(km); return out;
    }

    static List<TeacherArtifactKeyMove> dedupeKeyMoves(List<TeacherArtifactKeyMove> moves) {
        Set<Integer> seen = new java.util.HashSet<>();
        List<TeacherArtifactKeyMove> out = new ArrayList<>();
        for (TeacherArtifactKeyMove m : moves) {
            if (m.moveNumber == 0 || seen.contains(m.moveNumber)) continue;
            seen.add(m.moveNumber); out.add(m);
            if (out.size() >= MAX_KEY_MOVES) break;
        }
        return out;
    }

    static List<TeacherArtifactTrainingItem> buildTrainingItems(List<TeacherArtifactTrainingItem> problems, StructuredResultParser.StructuredTeacherResult structured) {
        List<TeacherArtifactTrainingItem> out = new ArrayList<>();
        if (problems != null) for (TeacherArtifactTrainingItem p : problems.stream().limit(3).toList()) out.add(p);
        if (structured != null && structured.drills != null) {
            int need = Math.max(0, 3 - out.size());
            for (int i = 0; i < Math.min(need, structured.drills.size()); i++) {
                TeacherArtifactTrainingItem t = new TeacherArtifactTrainingItem();
                t.id = "drill-" + (i + 1); t.title = "训练 " + (i + 1); t.kind = "concept"; t.objective = structured.drills.get(i);
                out.add(t);
            }
        }
        return out;
    }

    static List<TeacherArtifactKnowledgeMatch> toKnowledgeMatches(List<MotifRecognizer.RecognizedTeachingMotif> motifs) {
        List<TeacherArtifactKnowledgeMatch> out = new ArrayList<>();
        if (motifs == null) return out;
        for (MotifRecognizer.RecognizedTeachingMotif m : motifs.stream().limit(4).toList()) {
            TeacherArtifactKnowledgeMatch km = new TeacherArtifactKnowledgeMatch();
            km.matchType = m.motifType; km.confidence = m.confidence.name().toLowerCase();
            km.title = m.title; km.applicability = m.whyMatched; km.summary = m.recognition;
            out.add(km);
        }
        return out;
    }

    /** 对齐 buildTeacherArtifact：把分析 + 结构化结果 + 知识匹配 → 校验后的产物 */
    public static TeacherArtifact buildTeacherArtifact(BuildInput input) {
        List<TeacherArtifactCandidate> candidates = buildCandidates(input.analysis);
        List<TeacherArtifactKeyMove> analysisKeyMoves = buildAnalysisKeyMoves(input.analysis);
        List<TeacherArtifactKeyMove> trustedKeyMoves = dedupeKeyMoves(new ArrayList<>() {{
            addAll(analysisKeyMoves);
            if (input.rangeAnalyses != null) for (MoveAnalysis a : input.rangeAnalyses) addAll(buildAnalysisKeyMoves(a));
            if (input.request != null && input.request.moveRangeSummary != null)
                for (KeyMoveLike mk : input.request.moveRangeSummary.keyMoves) {
                    if (mk.moveNumber == null) continue;
                    TeacherArtifactKeyMove km = new TeacherArtifactKeyMove();
                    km.moveNumber = mk.moveNumber;
                    km.played = sanitizeText(mk.playedMove, 24);
                    km.recommended = sanitizeText(mk.bestMove, 24);
                    km.severity = sanitizeText(mk.judgement, 32);
                    km.errorType = "区间快扫关键手";
                    String loss = (mk.winrateLoss != null) ? "胜率损失约 " + String.format("%.1f", mk.winrateLoss) + "%" : "胜率损失待确认";
                    String score = (mk.scoreLoss != null) ? "，目差约 " + String.format("%.1f", mk.scoreLoss) + "目" : "";
                    km.summary = (mk.bestMove != null)
                        ? "实战 " + (mk.playedMove != null ? mk.playedMove : "未知") + "，建议 " + mk.bestMove + "；" + loss + score + "。"
                        : "实战 " + (mk.playedMove != null ? mk.playedMove : "未知") + "；" + loss + score + "。";
                    add(km);
                }
        }});
        List<TeacherArtifactKeyMove> keyMoves = !trustedKeyMoves.isEmpty() ? trustedKeyMoves : buildKeyMoves(input.structured);
        List<TeacherArtifactKnowledgeMatch> knowledgeMatches = toKnowledgeMatches(input.knowledgeMatches);
        List<TeacherArtifactTrainingItem> trainingItems = buildTrainingItems(input.recommendedProblems, input.structured);
        boolean hasEvidence = input.analysis != null || !keyMoves.isEmpty() || !knowledgeMatches.isEmpty() || !trainingItems.isEmpty();
        if (!hasEvidence) return null;

        TeacherArtifactBoardSnapshot snap = null;
        if (input.analysis != null) {
            snap = new TeacherArtifactBoardSnapshot();
            snap.boardSize = featurecat.lizzie.rules.Board.boardWidth;
            snap.moveNumber = input.analysis.moveNumber;
            snap.playedMove = input.analysis.actualMove;
            snap.bestMove = input.analysis.best != null ? input.analysis.best.move : null;
            snap.judgement = input.analysis.classification != null ? input.analysis.classification.severity.name().toLowerCase() : "unknown";
            snap.winrateLoss = input.analysis.classification != null ? input.analysis.classification.winrateLoss : null;
            snap.scoreLoss = input.analysis.classification != null ? input.analysis.classification.scoreLoss : null;
            snap.winrateBefore = normalizeWinrate(input.analysis.beforeWinrate);
            snap.winrateAfter = normalizeWinrate(input.analysis.afterWinrate);
            snap.playerWinrateAfter = normalizeWinrate(input.analysis.actualWinrate);
            snap.scoreLeadBefore = finiteNumber(input.analysis.beforeScoreLead);
            snap.scoreLeadAfter = finiteNumber(input.analysis.afterScoreLead);
            snap.playerScoreLeadAfter = finiteNumber(input.analysis.actualScoreLead);
            snap.currentColor = "B";
        }
        String summary = (input.structured != null && input.structured.headline != null) ? input.structured.headline
            : (input.structured != null && input.structured.summary != null) ? input.structured.summary
            : firstParagraph(input.markdown) != null ? firstParagraph(input.markdown) : input.title;

        TeacherArtifactDraft draft = new TeacherArtifactDraft();
        draft.id = sanitizeId(input.id + "-artifact", "artifact");
        draft.kind = artifactKind(input.intent).name().toLowerCase().replace("_", "-");
        draft.source = "runtime-derived";
        draft.title = input.title;
        draft.createdAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new java.util.Date());
        draft.summary = summary;
        draft.boardSnapshot = snap;
        draft.candidates = candidates;
        draft.variations = buildVariations(candidates, input.teachingPacing);
        draft.keyMoves = keyMoves;
        draft.knowledgeMatches = knowledgeMatches;
        draft.trainingItems = trainingItems;
        draft.evidence = new TeacherArtifactEvidence();
        draft.evidence.katagoReady = input.analysis != null;
        draft.evidence.boardImageReady = input.visionEvidence != null && input.visionEvidence.images.stream().anyMatch(im -> im.valid);
        draft.evidence.knowledgeMatchCount = knowledgeMatches.size();
        draft.evidence.recommendedProblemCount = trainingItems.size();
        draft.evidence.sourceNote = "Artifact 只使用 GoAgent 已执行工具返回的 KataGo、棋盘图元数据、知识匹配和老师文本摘要。";
        return validateTeachingArtifact(draft).artifact;
    }

    // ---- validate ----
    public static class ValidationResult { public boolean ok; public List<String> warnings = new ArrayList<>(); public TeacherArtifact artifact; }

    public static ValidationResult validateTeachingArtifact(TeacherArtifactDraft draft) {
        ValidationResult r = new ValidationResult();
        r.ok = true;
        if (draft.title == null || draft.title.isEmpty()) { draft.title = "未命名讲解"; r.warnings.add("title 缺失，已用默认"); }
        if (draft.kind == null || draft.kind.isEmpty()) { draft.kind = "freeform"; r.warnings.add("kind 缺失，已用 freeform"); }
        if (draft.source == null || draft.source.isEmpty()) draft.source = "runtime-derived";
        // sanitize 文本字段
        if (draft.summary != null && draft.summary.length() > 2000) draft.summary = draft.summary.substring(0, 2000);
        if (draft.candidates.size() > 8) draft.candidates = draft.candidates.subList(0, 8);
        if (draft.keyMoves.size() > MAX_KEY_MOVES) draft.keyMoves = draft.keyMoves.subList(0, MAX_KEY_MOVES);
        if (draft.knowledgeMatches.size() > 4) draft.knowledgeMatches = draft.knowledgeMatches.subList(0, 4);
        if (draft.trainingItems.size() > 6) draft.trainingItems = draft.trainingItems.subList(0, 6);
        if (draft.variations.size() > 6) draft.variations = draft.variations.subList(0, 6);
        TeacherArtifact a = new TeacherArtifact();
        a.id = draft.id; a.kind = draft.kind; a.source = draft.source; a.title = draft.title;
        a.createdAt = draft.createdAt; a.summary = draft.summary; a.boardSnapshot = draft.boardSnapshot;
        a.candidates = draft.candidates; a.variations = draft.variations; a.keyMoves = draft.keyMoves;
        a.knowledgeMatches = draft.knowledgeMatches; a.trainingItems = draft.trainingItems; a.evidence = draft.evidence;
        // 对齐 GoAgent validateTeachingArtifact：渲染导出 HTML 并做静态安全校验
        String exportHtml = renderTeacherArtifactHtml(draft);
        StaticHtmlValidation hv = validateStaticTeacherArtifactHtml(exportHtml);
        if (!hv.ok) {
            r.ok = false;
            for (String e : hv.errors) r.warnings.add("静态导出校验: " + e);
        } else {
            a.exportHtml = exportHtml;
        }
        r.artifact = a;
        return r;
    }

    public static class StaticHtmlValidation { public boolean ok; public List<String> errors = new ArrayList<>(); }

    /** 对齐 GoAgent validateStaticTeacherArtifactHtml：静态导出不得含 script/base/内联事件/javascript 链接/远程或本地资产/base64 图/本地路径与密钥 */
    public static StaticHtmlValidation validateStaticTeacherArtifactHtml(String html) {
        StaticHtmlValidation v = new StaticHtmlValidation();
        if (html == null) { v.ok = false; v.errors.add("html is null"); return v; }
        if (java.util.regex.Pattern.compile("<script\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find()) v.errors.add("static export must not include script tags");
        if (java.util.regex.Pattern.compile("<base\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find()) v.errors.add("static export must not include base tags");
        if (java.util.regex.Pattern.compile("<[^>]+\\son[a-z]+\\s*=", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find()) v.errors.add("static export must not include inline event handlers");
        if (java.util.regex.Pattern.compile("<[^>]+(?:href|src)\\s*=\\s*[\"']?\\s*javascript:", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find()) v.errors.add("static export must not include javascript URLs");
        if (hasRemoteAssetReference(html)) v.errors.add("static export must not include remote, local or data assets");
        if (java.util.regex.Pattern.compile("data:image\\/[a-z0-9.+-]+;base64", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find()) v.errors.add("static export must not include base64 images");
        if (hasLocalPathOrSecret(html)) v.errors.add("static export must not include local paths or API keys");
        v.ok = v.errors.isEmpty();
        return v;
    }
    static boolean hasRemoteAssetReference(String html) {
        return ArtifactSanitizer.hasRemoteAssetReference(html);
    }
    static boolean hasLocalPathOrSecret(String html) {
        return ArtifactSanitizer.hasLocalPathOrSecret(html);
    }

    /** 对齐 TS validateTeachingArtifact(input: unknown)：接受原始 JSON Map，经 sanitize 层清洗为 draft 再校验 */
    public static ValidationResult validateTeachingArtifactFromJson(Object input) {
        ValidationResult r = new ValidationResult();
        if (!(input instanceof java.util.Map)) { r.ok = false; r.warnings.add("TeachingArtifact input must be a JSON object."); return r; }
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) input;
        Object payload = m.containsKey("artifact") && m.get("artifact") instanceof java.util.Map ? m.get("artifact") : input;
        if (!(payload instanceof java.util.Map)) { r.ok = false; r.warnings.add("TeachingArtifact input must be a JSON object."); return r; }
        java.util.Map<?, ?> p = (java.util.Map<?, ?>) payload;
        TeacherArtifactDraft draft = new TeacherArtifactDraft();
        draft.id = ArtifactSanitizer.sanitizeId(p.get("id"), "teaching-artifact");
        String kind = ArtifactSanitizer.sanitizeText(p.get("kind"), 40);
        draft.kind = kind.isEmpty() ? "freeform" : kind;
        draft.source = ArtifactSanitizer.sanitizeText(p.get("source"), 40);
        if (draft.source.isEmpty()) draft.source = "agent-json";
        draft.title = ArtifactSanitizer.sanitizeText(p.get("title"), ArtifactSanitizer.MAX_TITLE_CHARS);
        if (draft.title.isEmpty()) draft.title = "GoAgent Teaching Artifact";
        draft.createdAt = ArtifactSanitizer.sanitizeText(p.get("createdAt"), 64);
        String summary = ArtifactSanitizer.sanitizeText(p.get("summary"), ArtifactSanitizer.MAX_SUMMARY_CHARS);
        draft.summary = summary.isEmpty() ? draft.title : summary;
        draft.boardSnapshot = ArtifactSanitizer.sanitizeBoardSnapshot(p.get("boardSnapshot"));
        draft.candidates = ArtifactSanitizer.sanitizeCandidates(p.get("candidates"));
        draft.variations = ArtifactSanitizer.sanitizeVariations(p.get("variations"));
        draft.keyMoves = ArtifactSanitizer.sanitizeKeyMoves(p.get("keyMoves"));
        draft.trainingItems = ArtifactSanitizer.sanitizeTrainingItems(p.get("trainingItems"));
        // knowledgeMatches：轻量清洗
        draft.knowledgeMatches = new ArrayList<>();
        if (p.get("knowledgeMatches") instanceof java.util.List) {
            int i = 0;
            for (Object kmo : (java.util.List<?>) p.get("knowledgeMatches")) {
                if (!(kmo instanceof java.util.Map)) continue;
                if (draft.knowledgeMatches.size() >= ArtifactSanitizer.MAX_KNOWLEDGE_MATCHES) break;
                java.util.Map<?, ?> km = (java.util.Map<?, ?>) kmo;
                TeacherArtifactKnowledgeMatch k = new TeacherArtifactKnowledgeMatch();
                k.title = ArtifactSanitizer.sanitizeText(km.get("title"), 120);
                k.matchType = ArtifactSanitizer.sanitizeText(km.get("matchType"), 32);
                k.confidence = ArtifactSanitizer.sanitizeText(km.get("confidence"), 16);
                k.applicability = ArtifactSanitizer.sanitizeText(km.get("applicability"), ArtifactSanitizer.MAX_TEXT_CHARS);
                k.summary = ArtifactSanitizer.sanitizeText(km.get("summary"), ArtifactSanitizer.MAX_TEXT_CHARS);
                if (!k.title.isEmpty() || !k.summary.isEmpty()) draft.knowledgeMatches.add(k);
                i++;
            }
        }
        Object evidenceInput = p.get("evidence");
        TeacherArtifactEvidence ev = new TeacherArtifactEvidence();
        if (evidenceInput instanceof java.util.Map) {
            java.util.Map<?, ?> em = (java.util.Map<?, ?>) evidenceInput;
            ev.katagoReady = ArtifactSanitizer.booleanValue(em.get("katagoReady"), draft.boardSnapshot != null || !draft.candidates.isEmpty());
            ev.boardImageReady = ArtifactSanitizer.booleanValue(em.get("boardImageReady"), false);
            ev.knowledgeMatchCount = ArtifactSanitizer.nonNegativeInteger(em.get("knowledgeMatchCount"), draft.knowledgeMatches.size(), ArtifactSanitizer.MAX_KNOWLEDGE_MATCHES);
            ev.recommendedProblemCount = ArtifactSanitizer.nonNegativeInteger(em.get("recommendedProblemCount"), draft.trainingItems.size(), ArtifactSanitizer.MAX_TRAINING_ITEMS);
            String note = ArtifactSanitizer.sanitizeText(em.get("sourceNote"), 360);
            ev.sourceNote = note.isEmpty() ? "Artifact 由 GoAgent 运行时验证、裁剪并生成安全静态导出。" : note;
        } else {
            ev.katagoReady = draft.boardSnapshot != null || !draft.candidates.isEmpty();
            ev.boardImageReady = false;
            ev.knowledgeMatchCount = draft.knowledgeMatches.size();
            ev.recommendedProblemCount = draft.trainingItems.size();
            ev.sourceNote = "Artifact 由 GoAgent 运行时验证、裁剪并生成安全静态导出。";
        }
        draft.evidence = ev;
        ArtifactSanitizer.SandboxResult sr = ArtifactSanitizer.sanitizeSandboxHtml(p.get("sandboxHtml"), false);
        r.warnings.addAll(sr.warnings);
        if (sr.sandboxHtml != null) draft.sandboxHtml = sr.sandboxHtml;
        // 至少一个证据区段（对齐 hasVisibleEvidence）
        boolean hasVisible = draft.boardSnapshot != null || !draft.candidates.isEmpty() || !draft.variations.isEmpty()
            || !draft.keyMoves.isEmpty() || !draft.knowledgeMatches.isEmpty() || !draft.trainingItems.isEmpty();
        if (!hasVisible) r.warnings.add("TeachingArtifact requires at least one evidence-backed section.");
        return validateTeachingArtifact(draft);
    }

    public static TeacherArtifact createTeachingArtifact(TeacherArtifactDraft draft) { return validateTeachingArtifact(draft).artifact; }
    public static ValidationResult validateTeacherArtifact(TeacherArtifactDraft draft) { return validateTeachingArtifact(draft); }
    public static TeacherArtifact createTeacherArtifact(TeacherArtifactDraft draft) { return createTeachingArtifact(draft); }

    // ---- render HTML（对齐 renderTeacherArtifactHtml）----
    public static String renderTeacherArtifactHtml(TeacherArtifactDraft artifact) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\" />\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n")
          .append("<title>").append(escapeHtml(artifact.title)).append(" · GoAgent</title>\n<style>\n")
          .append(":root { color-scheme: light; font-family: Inter, \"SF Pro Display\", \"PingFang SC\", system-ui, sans-serif; color: #1b2624; background: #f6f0e5; }\n")
          .append("body { margin: 0; background: radial-gradient(circle at 20% 0%, #fff7e8, #f2eadc 44%, #e7ddca); }\n")
          .append("main { width: min(1040px, calc(100vw - 40px)); margin: 0 auto; padding: 44px 0 56px; }\n")
          .append("header { padding: 26px 28px; border: 1px solid rgba(60,47,26,.12); border-radius: 24px; background: rgba(255,255,255,.62); box-shadow: 0 24px 80px rgba(68,51,25,.14); }\n")
          .append(".eyebrow, .pill, .mini-card span { color: #25736b; font-size: 12px; font-weight: 760; letter-spacing: .04em; text-transform: uppercase; }\n")
          .append("h1 { margin: 8px 0 10px; font-size: clamp(32px,5vw,56px); line-height: 1; letter-spacing: -.02em; }\n")
          .append("p { color: #4f5b56; line-height: 1.72; }\n")
          .append(".grid { display: grid; gap: 14px; grid-template-columns: repeat(4,1fr); margin: 18px 0 0; }\n")
          .append(".pill { padding: 11px 12px; border: 1px solid rgba(44,104,96,.14); border-radius: 16px; background: rgba(255,255,255,.56); text-transform: none; }\n")
          .append("section { margin-top: 18px; padding: 22px 24px; border: 1px solid rgba(60,47,26,.11); border-radius: 22px; background: rgba(255,255,255,.54); box-shadow: 0 18px 52px rgba(68,51,25,.08); }\n")
          .append("h2 { margin: 0 0 12px; font-size: 19px; }\n")
          .append("table { width: 100%; border-collapse: collapse; overflow: hidden; border-radius: 16px; background: rgba(255,255,255,.58); }\n")
          .append("th, td { padding: 11px 12px; border-bottom: 1px solid rgba(60,47,26,.08); text-align: left; font-size: 13px; }\n")
          .append("th { color: #65726b; font-size: 12px; }\n")
          .append("ol { margin: 0; padding-left: 20px; color: #3b4742; line-height: 1.74; }\n")
          .append(".cards { display: grid; grid-template-columns: repeat(auto-fit,minmax(220px,1fr)); gap: 12px; }\n")
          .append(".mini-card, .variation { padding: 15px; border: 1px solid rgba(44,104,96,.12); border-radius: 18px; background: rgba(255,255,255,.56); }\n")
          .append(".mini-card h3, .variation h3 { margin: 5px 0 7px; font-size: 15px; }\n")
          .append(".mini-card p, .variation p { margin: 0; font-size: 13px; }\n")
          .append(".mini-card small, .variation small { display: block; margin-top: 8px; color: #78837e; line-height: 1.5; }\n")
          .append(".muted { color: #7a857f; }\n")
          .append("footer { margin-top: 22px; color: #7a857f; font-size: 12px; text-align: center; }\n")
          .append("@media (max-width: 760px) { .grid { grid-template-columns: 1fr 1fr; } main { width: min(100vw - 24px, 1040px); padding-top: 18px; } }\n")
          .append("</style>\n</head>\n<body>\n<main>\n<header>\n")
          .append("<div class=\"eyebrow\">GoAgent Teaching Artifact</div>\n")
          .append("<h1>").append(escapeHtml(artifact.title)).append("</h1>\n")
          .append("<p>").append(escapeHtml(artifact.summary)).append("</p>\n<div class=\"grid\">\n")
          .append("<div class=\"pill\">手数：").append(escapeHtml(artifact.boardSnapshot != null ? String.valueOf(artifact.boardSnapshot.moveNumber) : "暂无")).append("</div>\n")
          .append("<div class=\"pill\">实战：").append(escapeHtml(artifact.boardSnapshot != null ? str(artifact.boardSnapshot.playedMove) : "暂无")).append("</div>\n")
          .append("<div class=\"pill\">首选：").append(escapeHtml(artifact.boardSnapshot != null ? str(artifact.boardSnapshot.bestMove) : "暂无")).append("</div>\n")
          .append("<div class=\"pill\">胜率损失：").append(escapeHtml(artifact.boardSnapshot != null ? formatWinrate(artifact.boardSnapshot.winrateLoss) : "暂无")).append("</div>\n")
          .append("</div>\n</header>\n")
          .append("<section><h2>KataGo 候选点</h2>").append(renderCandidateRows(artifact.candidates)).append("</section>\n")
          .append("<section><h2>关键变化</h2>").append(renderVariations(artifact.variations)).append("</section>\n")
          .append("<section><h2>问题手</h2>").append(renderKeyMoves(artifact.keyMoves)).append("</section>\n")
          .append("<section><h2>知识匹配</h2><div class=\"cards\">").append(renderKnowledge(artifact.knowledgeMatches)).append("</div></section>\n")
          .append("<section><h2>训练建议</h2><div class=\"cards\">").append(renderTraining(artifact.trainingItems)).append("</div></section>\n")
          .append("<footer>由 GoAgent 生成。KataGo 是事实依据，知识库用于教学迁移。</footer>\n")
          .append("</main>\n</body>\n</html>");
        return sb.toString();
    }

    static String str(String s) { return s == null ? "" : s; }
    static String renderCandidateRows(List<TeacherArtifactCandidate> candidates) {
        if (candidates.isEmpty()) return "<p class=\"muted\">本轮没有可展示候选点。</p>";
        StringBuilder sb = new StringBuilder("<table><thead><tr><th>顺位</th><th>点位</th><th>落子方胜率</th><th>落子方目差</th><th>搜索</th><th>PV</th></tr></thead><tbody>");
        for (TeacherArtifactCandidate c : candidates) {
            sb.append("<tr><td>").append(escapeHtml(c.note != null ? c.note : ("第 " + c.rank + " 选")))
              .append("</td><td><strong>").append(escapeHtml(c.move)).append("</strong></td>")
              .append("<td>").append(escapeHtml(formatWinrate(c.winrate))).append("</td>")
              .append("<td>").append(escapeHtml(formatScore(c.scoreLead))).append("</td>")
              .append("<td>").append(escapeHtml(c.visits != null ? String.valueOf(c.visits) : "暂无")).append("</td>")
              .append("<td>").append(escapeHtml(String.join(" → ", c.pv.stream().limit(8).toList()))).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }
    static String renderVariations(List<TeacherArtifactVariation> variations) {
        if (variations.isEmpty()) return "<p class=\"muted\">暂无可导出的变化图文本。</p>";
        StringBuilder sb = new StringBuilder();
        for (TeacherArtifactVariation v : variations) {
            sb.append("<article class=\"variation\"><h3>").append(escapeHtml(v.label)).append(" · ").append(escapeHtml(v.purpose)).append("</h3>")
              .append("<p>").append(escapeHtml(String.join(" → ", v.pv))).append("</p>")
              .append("<small>").append(escapeHtml(v.result)).append("</small></article>");
        }
        return sb.toString();
    }
    static String renderKeyMoves(List<TeacherArtifactKeyMove> moves) {
        if (moves.isEmpty()) return "<p class=\"muted\">本轮没有单独列出问题手。</p>";
        StringBuilder sb = new StringBuilder("<ol>");
        for (TeacherArtifactKeyMove m : moves)
            sb.append("<li>第 ").append(escapeHtml(String.valueOf(m.moveNumber))).append(" 手 ").append(escapeHtml(str(m.played)))
              .append("：").append(escapeHtml(str(m.summary))).append("</li>");
        return sb.append("</ol>").toString();
    }
    static String renderKnowledge(List<TeacherArtifactKnowledgeMatch> matches) {
        if (matches.isEmpty()) return "<p class=\"muted\">本轮没有强制引用知识条目。</p>";
        StringBuilder sb = new StringBuilder();
        for (TeacherArtifactKnowledgeMatch m : matches.stream().limit(4).toList()) {
            sb.append("<article class=\"mini-card\"><span>").append(escapeHtml(m.matchType)).append(" · ").append(escapeHtml(m.confidence))
              .append("</span><h3>").append(escapeHtml(m.title)).append("</h3>")
              .append("<p>").append(escapeHtml(str(m.summary != null ? m.summary : m.applicability))).append("</p></article>");
        }
        return sb.toString();
    }
    static String renderTraining(List<TeacherArtifactTrainingItem> items) {
        if (items.isEmpty()) return "<p class=\"muted\">老师本轮没有追加专项题。</p>";
        StringBuilder sb = new StringBuilder();
        for (TeacherArtifactTrainingItem it : items) {
            sb.append("<article class=\"mini-card\"><span>").append(escapeHtml(it.kind))
              .append(it.difficulty != null ? " · " + escapeHtml(it.difficulty) : "").append("</span>")
              .append("<h3>").append(escapeHtml(it.title)).append("</h3>")
              .append("<p>").append(escapeHtml(str(it.objective))).append("</p>")
              .append(it.firstHint != null ? "<small>提示：" + escapeHtml(it.firstHint) + "</small>" : "").append("</article>");
        }
        return sb.toString();
    }
}
