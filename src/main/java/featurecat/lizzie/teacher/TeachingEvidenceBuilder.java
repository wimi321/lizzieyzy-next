package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.knowledge.MotifRecognizer;
import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import featurecat.lizzie.teacher.analysis.ScorePerspective;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对齐 GoAgent teacher/teachingEvidence.ts：构建教学证据（含视角修正后的胜率/目差、定式/motif、
 * loss/severity/confidence、教学节奏 pacing）+ verifyTeacherMarkdown（坐标/百分比/定式引用校验，防编造）。
 */
public final class TeachingEvidenceBuilder {

    private TeachingEvidenceBuilder() {}

    public enum TeachingPhase { opening, middle, endgame }
    public enum TeachingSeverity { good, inaccuracy, mistake, blunder, uncertain }
    public enum TeachingConfidence { high, medium, low }
    public enum TeachingMode { clear_mistake, candidate_choice, style_choice, needs_deeper_search }
    public enum TeachingDensity { minimal, branch, detailed, caution }

    public static class TeachingEvidenceCandidate {
        public String move; public Double winrate, scoreLead; public String perspectiveColor;
        public Double blackWinrate, blackScoreLead; public String scoreSummary; public long visits; public int order; public int rank;
        public List<String> pv = new ArrayList<>(); public String humanLabel;
    }
    public static class ScoreView { public Double winrate, scoreLead; public String perspectiveColor; public Double blackWinrate, blackScoreLead; public String scoreSummary; }
    public static class PlayedMoveView { public String move; public Double winrate, scoreLead; public String perspectiveColor; public Double blackWinrate, blackScoreLead; public String scoreSummary; public long visits; public int rank; public String source; }
    public static class LossView { public Double winrateLoss, scoreLoss; public TeachingSeverity severity; public TeachingConfidence confidence; public String confidenceReason; public TeachingMode teachingMode; }
    public static class RecognizedMotifView {
        public String id, title, motifType, confidence, whyMatched, recognition, wrongThinking, correctThinking, drillPrompt, sourceQuality, josekiFamily;
        public Double score; public List<String> relatedMoves = new ArrayList<>(); public List<String> sourceRefs = new ArrayList<>();
        public List<Object> expectedNextMoves = new ArrayList<>(); public int variationCount;
    }
    public static class KnowledgeReference { public String id, title, confidence, whyMatched, matchType; public Double score; public List<String> keyVariations = new ArrayList<>(); }
    public static class VariationHint { public String move, purpose, expectedReply, result, confidence; public List<String> pv = new ArrayList<>(); }
    public static class StudentView { public String id, level; public List<String> recurringIssues = new ArrayList<>(); }

    public static class TeachingEvidence {
        public int schemaVersion = 1; public String gameId = ""; public int moveNumber; public int boardSize;
        public TeachingPhase phase; public String userPrompt; public String playerColor;
        public String actualMove;
        public ScoreView before = new ScoreView();
        public ScoreView afterActual = new ScoreView();
        public PlayedMoveView playedMove;
        public List<TeachingEvidenceCandidate> bestCandidates = new ArrayList<>();
        public List<RecognizedMotifView> recognizedMotifs = new ArrayList<>();
        public LossView loss = new LossView();
        public List<KnowledgeReference> knowledgeReferences = new ArrayList<>();
        public List<Object> recommendedProblems = new ArrayList<>();
        public TeachingDensity teachingDensity; public String teachingFocus; public String whyThisMuchExplanation;
        public List<VariationHint> variationTeachingHints = new ArrayList<>();
        public StudentView student = new StudentView();
        public List<String> constraints = new ArrayList<>();
    }

    public static class MarkdownVerification {
        public boolean ok; public List<String> warnings = new ArrayList<>(); public List<String> violations = new ArrayList<>(); public List<String> allowedMoves = new ArrayList<>();
    }

    static double round(Double v, int digits) { if (v == null || !Double.isFinite(v)) return 0; double f = Math.pow(10, digits); return Math.round(v * f) / f; }
    static String roundStr(Double v, int digits) { return String.format("%." + digits + "f", round(v, digits)); }

    static TeachingPhase inferPhase(int moveNumber) { return moveNumber <= 50 ? TeachingPhase.opening : moveNumber <= 160 ? TeachingPhase.middle : TeachingPhase.endgame; }

    static TeachingSeverity inferSeverity(double winrateLoss, double scoreLoss, String judgement) {
        if (judgement != null && judgement.equals("unknown")) return TeachingSeverity.uncertain;
        if (judgement != null && judgement.equals("blunder") || winrateLoss >= 15 || scoreLoss >= 8) return TeachingSeverity.blunder;
        if (judgement != null && judgement.equals("mistake") || winrateLoss >= 7 || scoreLoss >= 4) return TeachingSeverity.mistake;
        if (judgement != null && judgement.equals("inaccuracy") || winrateLoss >= 2.5 || scoreLoss >= 1.5) return TeachingSeverity.inaccuracy;
        return TeachingSeverity.good;
    }

    static double candidateSpread(List<TeachingEvidenceCandidate> cands) {
        if (cands == null || cands.size() < 2) return 99;
        double w0 = cands.get(0).winrate != null ? cands.get(0).winrate : 0;
        double w1 = cands.get(1).winrate != null ? cands.get(1).winrate : 0;
        return Math.abs(w0 - w1);
    }
    static long totalVisits(List<TeachingEvidenceCandidate> cands) { long t = 0; if (cands != null) for (var c : cands) t += c.visits; return t; }

    static class Conf { TeachingConfidence confidence; String reason; TeachingMode mode; }
    static long candidateTotalVisits(AnalysisBrain.PvReport pv) {
        long t = 0;
        if (pv != null && pv.candidates != null) for (AnalysisBrain.PvCandidate c : pv.candidates) t += c.visits;
        return t;
    }
    static double candidateSpreadPv(AnalysisBrain.PvReport pv) {
        if (pv == null || pv.candidates == null || pv.candidates.size() < 2) return 99;
        double w0 = pv.candidates.get(0).winrate != null ? pv.candidates.get(0).winrate : 0;
        double w1 = pv.candidates.get(1).winrate != null ? pv.candidates.get(1).winrate : 0;
        return Math.abs(w0 - w1);
    }
    static Conf inferConfidence(AnalysisBrain.PvReport pv, TeachingSeverity severity, double winrateLoss, double scoreLoss, String judgement, double bestVisits, long playedVisits, boolean hasForcedPlayed) {
        Conf c = new Conf();
        long visits = candidateTotalVisits(pv);
        double spread = candidateSpreadPv(pv);
        boolean consistentLoss = winrateLoss >= 2.5 && scoreLoss >= 1;
        if (visits < 80 || (playedVisits == 0 && hasForcedPlayed)) {
            c.confidence = TeachingConfidence.low;
            c.reason = "KataGo visits are low (" + visits + "); treat the explanation as a teaching hypothesis, not a final verdict.";
            c.mode = TeachingMode.needs_deeper_search; return c;
        }
        if (severity == TeachingSeverity.good && spread < 1.5) {
            c.confidence = TeachingConfidence.medium;
            c.reason = "Top candidates are close (" + round(spread, 1) + " winrate points apart); explain as a choice of style/direction.";
            c.mode = TeachingMode.style_choice; return c;
        }
        if (severity == TeachingSeverity.inaccuracy && spread < 2) {
            c.confidence = visits >= 250 ? TeachingConfidence.medium : TeachingConfidence.low;
            c.reason = "The loss is small and alternatives are close; avoid over-criticizing.";
            c.mode = TeachingMode.candidate_choice; return c;
        }
        if ((severity == TeachingSeverity.mistake || severity == TeachingSeverity.blunder) && visits >= 250 && consistentLoss) {
            c.confidence = TeachingConfidence.high;
            c.reason = "Winrate and score loss agree, with enough visits (" + visits + ").";
            c.mode = TeachingMode.clear_mistake; return c;
        }
        c.confidence = visits >= 160 ? TeachingConfidence.medium : TeachingConfidence.low;
        c.reason = "Evidence is usable but not decisive: visits=" + visits + ", spread=" + round(spread, 1) + ", winrateLoss=" + round(winrateLoss, 1) + ".";
        c.mode = severity == TeachingSeverity.good ? TeachingMode.style_choice : TeachingMode.candidate_choice;
        return c;
    }

    static String labelCandidate(int index) {
        if (index == 0) return "best";
        if (index <= 2) return "playable";
        return "variation";
    }

    static boolean strongMatch(KnowledgeReference m) { return m != null && ("strong".equals(m.confidence) || "exact".equals(m.confidence)); }
    static KnowledgeReference matchByType(List<KnowledgeReference> ms, String type) {
        if (ms == null) return null;
        for (KnowledgeReference m : ms) if (type.equals(m.matchType)) return m;
        return null;
    }
    static RecognizedMotifView recognizedJoseki(List<RecognizedMotifView> motifs) {
        if (motifs == null) return null;
        for (RecognizedMotifView m : motifs) if (m.motifType != null && m.motifType.startsWith("joseki:") && ("strong".equals(m.confidence) || "medium".equals(m.confidence))) return m;
        return null;
    }
    static String focusFromEvidence(TeachingPhase phase, double winrateLoss, List<KnowledgeReference> matches, List<RecognizedMotifView> motifs) {
        KnowledgeReference lifeDeath = matchByType(matches, "life_death");
        if (strongMatch(lifeDeath)) return "life-death";
        KnowledgeReference tesuji = matchByType(matches, "tesuji");
        if (strongMatch(tesuji)) return "tesuji";
        KnowledgeReference joseki = matchByType(matches, "joseki");
        if (phase == TeachingPhase.opening && (strongMatch(joseki) || recognizedJoseki(motifs) != null)) {
            return winrateLoss < 2 ? "joseki-normal" : "joseki-branch";
        }
        if (joseki != null && "partial".equals(joseki.confidence)) return "joseki-branch";
        if (phase == TeachingPhase.middle) return "middlegame-fight";
        if (phase == TeachingPhase.endgame) return "endgame";
        return "general-shape";
    }
    static String candidateConfidence(long visits) { return visits >= 250 ? "high" : visits >= 80 ? "medium" : "low"; }
    static String hintPurpose(String label, String focus, boolean isActual) {
        if (isActual) return "实战选择，用来检验对方正常应对后为什么会稍亏或可行。";
        if (label.equals("best")) {
            if ("middlegame-fight".equals(focus)) return "首选变化，用来说明这手的作战目的和后续攻防收益。";
            if ("joseki-branch".equals(focus)) return "首选分支，用来比较这个定式/布局选择的方向。";
            if ("life-death".equals(focus) || "tesuji".equals(focus)) return "首选急所，用来读清局部成立的第一步。";
            return "首选变化，用来校准全局方向。";
        }
        return "可下分支，用来说明选择条件和代价。";
    }

    static long playedVisitsOf(MoveAnalysis analysis) {
        if (analysis == null || analysis.actualMove == null || analysis.pv == null) return 0;
        for (AnalysisBrain.PvCandidate c : analysis.pv.candidates) if (c.move != null && c.move.equals(analysis.actualMove)) return c.visits;
        return 0;
    }

    static List<TeachingEvidenceCandidate> uniqueHintCandidates(MoveAnalysis analysis) {
        // 对齐 TS uniqueHintCandidates：top3 候选 → [best, played, second] 去重选 3
        List<TeachingEvidenceCandidate> candidates = new ArrayList<>();
        if (analysis != null && analysis.pv != null) {
            int i = 0;
            for (AnalysisBrain.PvCandidate c : analysis.pv.candidates) {
                if (i >= 3) break;
                TeachingEvidenceCandidate tc = new TeachingEvidenceCandidate();
                tc.move = c.move;
                tc.winrate = round(c.winrate != null ? c.winrate : analysis.actualWinrate, 2);
                tc.scoreLead = round(c.scoreLead != null ? c.scoreLead : analysis.actualScoreLead, 2);
                tc.perspectiveColor = "B";
                tc.blackWinrate = round(c.winrate != null ? c.winrate : analysis.actualWinrate, 2);
                tc.blackScoreLead = round(c.scoreLead != null ? c.scoreLead : analysis.actualScoreLead, 2);
                tc.visits = c.visits; tc.order = c.rank; tc.rank = i + 1;
                tc.pv = c.pv.size() > 8 ? new ArrayList<>(c.pv.subList(0, 8)) : new ArrayList<>(c.pv);
                tc.humanLabel = labelCandidate(i);
                candidates.add(tc);
                i++;
            }
        }
        TeachingEvidenceCandidate played = null;
        if (analysis != null && analysis.actualMove != null) {
            for (TeachingEvidenceCandidate c : candidates) if (c.move != null && c.move.equals(analysis.actualMove)) { played = c; break; }
        }
        List<TeachingEvidenceCandidate> selected = new ArrayList<>();
        TeachingEvidenceCandidate first = candidates.isEmpty() ? null : candidates.get(0);
        TeachingEvidenceCandidate second = candidates.size() > 1 ? candidates.get(1) : null;
        for (TeachingEvidenceCandidate c : new TeachingEvidenceCandidate[]{ first, played, second }) {
            if (c == null) continue;
            boolean dup = false;
            for (TeachingEvidenceCandidate e : selected) if (e.move != null && e.move.equals(c.move)) { dup = true; break; }
            if (!dup) selected.add(c);
            if (selected.size() >= 3) break;
        }
        return selected;
    }

    public static class TeachingPacingAdvice {
        public TeachingDensity teachingDensity;
        public String teachingFocus;
        public String whyThisMuchExplanation;
        public List<VariationHint> variationTeachingHints = new ArrayList<>();
    }

    static TeachingPacingAdvice buildTeachingPacingAdvice(MoveAnalysis analysis, List<KnowledgeReference> knowledgeMatches, List<RecognizedMotifView> recognizedMotifs) {
        TeachingPacingAdvice advice = new TeachingPacingAdvice();
        TeachingPhase phase = inferPhase(analysis != null ? analysis.moveNumber : 0);
        double winrateLoss = analysis != null && analysis.classification != null ? analysis.classification.winrateLoss : 0;
        double scoreLoss = analysis != null && analysis.classification != null ? analysis.classification.scoreLoss : 0;
        TeachingSeverity severity = inferSeverity(winrateLoss, scoreLoss, analysis != null && analysis.classification != null ? analysis.classification.severity.name().toLowerCase() : "unknown");
        Conf confidence = inferConfidence(analysis != null ? analysis.pv : null, severity, winrateLoss, scoreLoss, analysis != null && analysis.classification != null ? analysis.classification.severity.name().toLowerCase() : "", analysis != null && analysis.best != null ? analysis.best.visits : 0, playedVisitsOf(analysis), false);
        String focus = focusFromEvidence(phase, winrateLoss, knowledgeMatches, recognizedMotifs);
        KnowledgeReference joseki = matchByType(knowledgeMatches, "joseki");
        boolean tactical = strongMatch(matchByType(knowledgeMatches, "life_death")) || strongMatch(matchByType(knowledgeMatches, "tesuji"));
        boolean hasJosekiBranch = joseki != null && ("partial".equals(joseki.confidence) || (joseki.keyVariations != null && !joseki.keyVariations.isEmpty()) || winrateLoss >= 2);

        if (confidence.confidence == TeachingConfidence.low) {
            advice.teachingDensity = TeachingDensity.caution;
            advice.whyThisMuchExplanation = "KataGo 搜索或实战手证据还不够强，只能讲判断倾向，不能下绝对结论。";
        } else if (tactical || phase == TeachingPhase.middle || severity == TeachingSeverity.mistake || severity == TeachingSeverity.blunder || winrateLoss >= 7 || scoreLoss >= 4) {
            advice.teachingDensity = TeachingDensity.detailed;
            advice.whyThisMuchExplanation = "这是中盘战、急所计算或明显损失局面，需要讲清这手目的、对方应手、PV 后续和实战代价。";
        } else if (hasJosekiBranch || "joseki-branch".equals(focus) || (phase == TeachingPhase.opening && winrateLoss >= 2)) {
            advice.teachingDensity = TeachingDensity.branch;
            advice.whyThisMuchExplanation = "这是定式分支、布局选择或相似型局面，适合列 1-2 个关键变化和选择条件。";
        } else if (("joseki-normal".equals(focus) && winrateLoss < 2) || (severity == TeachingSeverity.good && winrateLoss < 2)) {
            advice.teachingDensity = TeachingDensity.minimal;
            advice.whyThisMuchExplanation = "这是常规定式或损失很小的正常选择，只点明棋形方向即可，不需要长篇讲解。";
        } else {
            advice.teachingDensity = TeachingDensity.branch;
            advice.whyThisMuchExplanation = "局面需要说明选择条件，但不必展开成完整报告。";
        }
        advice.teachingFocus = focus;
        for (TeachingEvidenceCandidate candidate : uniqueHintCandidates(analysis)) {
            VariationHint h = new VariationHint();
            h.move = candidate.move;
            h.purpose = hintPurpose(candidate.humanLabel, focus, analysis.actualMove != null && candidate.move.equals(analysis.actualMove));
            String expectedReply = null;
            for (String pvMove : candidate.pv) if (pvMove != null && !pvMove.equals(candidate.move)) { expectedReply = pvMove; break; }
            h.expectedReply = expectedReply;
            h.pv = candidate.pv.size() > 6 ? candidate.pv.subList(0, 6) : candidate.pv;
            String summaryText = candidate.scoreSummary != null && !candidate.scoreSummary.isEmpty()
                ? candidate.scoreSummary
                : "目差 " + roundStr(candidate.scoreLead, 1);
            h.result = "胜率 " + roundStr(candidate.winrate, 1) + "%，" + summaryText + "，搜索 " + candidate.visits + "。";
            h.confidence = candidateConfidence(candidate.visits);
            advice.variationTeachingHints.add(h);
        }
        return advice;
    }

    public static TeachingEvidence buildTeachingEvidence(MoveAnalysis analysis, String userPrompt, List<MotifRecognizer.RecognizedTeachingMotif> motifs,
                                                          List<KnowledgeReference> knowledgeMatches, List<Object> recommendedProblems) {
        if (knowledgeMatches == null) knowledgeMatches = new ArrayList<>();
        if (recommendedProblems == null) recommendedProblems = new ArrayList<>();
        TeachingEvidence ev = new TeachingEvidence();
        double winrateLoss = analysis != null && analysis.classification != null ? analysis.classification.winrateLoss : 0;
        double scoreLoss = analysis != null && analysis.classification != null ? analysis.classification.scoreLoss : 0;
        TeachingSeverity severity = inferSeverity(winrateLoss, scoreLoss, analysis != null && analysis.classification != null ? analysis.classification.severity.name().toLowerCase() : "unknown");
        Conf confidence = inferConfidence(analysis != null ? analysis.pv : null, severity, winrateLoss, scoreLoss, analysis != null && analysis.classification != null ? analysis.classification.severity.name().toLowerCase() : "", analysis != null && analysis.best != null ? analysis.best.visits : 0, playedVisitsOf(analysis), false);
        List<RecognizedMotifView> motifViews = toMotifViews(motifs);
        TeachingPacingAdvice pacing = buildTeachingPacingAdvice(analysis, new ArrayList<>(), motifViews);

        ev.moveNumber = analysis != null ? analysis.moveNumber : 0;
        ev.boardSize = featurecat.lizzie.rules.Board.boardWidth;
        ev.phase = inferPhase(ev.moveNumber);
        ev.userPrompt = userPrompt;
        ev.playerColor = "B";
        ev.actualMove = analysis != null ? analysis.actualMove : null;
        ev.gameId = analysis != null ? analysis.gameId : null;

        String perspectiveColor = "B";
        if (analysis != null) {
            // 视角转换（对齐 TS displayWinrateForColor/displayScoreLeadForColor）
            ev.before.winrate = round(analysis.beforeWinrate, 2);
            ev.before.scoreLead = round(ScorePerspective.scoreLeadForColor(analysis.beforeScoreLead, true), 2);
            ev.before.perspectiveColor = perspectiveColor;
            ev.before.blackWinrate = round(analysis.beforeWinrate, 2);
            ev.before.blackScoreLead = round(analysis.beforeScoreLead, 2);
            ev.before.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(analysis.beforeScoreLead, perspectiveColor).text;
            ev.afterActual.winrate = round(analysis.afterWinrate, 2);
            ev.afterActual.scoreLead = round(ScorePerspective.scoreLeadForColor(analysis.afterScoreLead, true), 2);
            ev.afterActual.perspectiveColor = perspectiveColor;
            ev.afterActual.blackWinrate = round(analysis.afterWinrate, 2);
            ev.afterActual.blackScoreLead = round(analysis.afterScoreLead, 2);
            ev.afterActual.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(analysis.afterScoreLead, perspectiveColor).text;
            ev.playedMove = new PlayedMoveView();
            ev.playedMove.move = analysis.actualMove;
            ev.playedMove.winrate = round(analysis.actualWinrate, 2);
            ev.playedMove.scoreLead = round(ScorePerspective.scoreLeadForColor(analysis.actualScoreLead, true), 2);
            ev.playedMove.perspectiveColor = perspectiveColor;
            ev.playedMove.blackWinrate = round(analysis.actualWinrate, 2);
            ev.playedMove.blackScoreLead = round(analysis.actualScoreLead, 2);
            ev.playedMove.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(analysis.actualScoreLead, perspectiveColor).text;
            ev.playedMove.visits = analysis.best != null ? analysis.best.visits : 0;
            ev.playedMove.rank = 0;
            ev.playedMove.source = "katago";
            for (AnalysisBrain.PvCandidate c : analysis.pv.candidates) {
                TeachingEvidenceCandidate tc = new TeachingEvidenceCandidate();
                tc.move = c.move;
                tc.winrate = round(c.winrate != null ? c.winrate : analysis.actualWinrate, 2);
                tc.scoreLead = round(c.scoreLead != null ? c.scoreLead : analysis.actualScoreLead, 2);
                tc.perspectiveColor = perspectiveColor;
                tc.blackWinrate = round(c.winrate != null ? c.winrate : analysis.actualWinrate, 2);
                tc.blackScoreLead = round(c.scoreLead != null ? c.scoreLead : analysis.actualScoreLead, 2);
                tc.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(c.scoreLead != null ? c.scoreLead : analysis.actualScoreLead, perspectiveColor).text;
                tc.visits = c.visits; tc.order = c.rank; tc.rank = c.rank;
                tc.pv = c.pv.size() > 8 ? new ArrayList<>(c.pv.subList(0, 8)) : new ArrayList<>(c.pv);
                tc.humanLabel = labelCandidate(c.rank - 1);
                ev.bestCandidates.add(tc);
            }
        }

        ev.recognizedMotifs = motifViews;
        ev.loss.winrateLoss = winrateLoss; ev.loss.scoreLoss = scoreLoss; ev.loss.severity = severity;
        ev.loss.confidence = confidence.confidence; ev.loss.confidenceReason = confidence.reason; ev.loss.teachingMode = confidence.mode;
        ev.teachingDensity = pacing.teachingDensity; ev.teachingFocus = pacing.teachingFocus; ev.whyThisMuchExplanation = pacing.whyThisMuchExplanation;
        ev.variationTeachingHints = pacing.variationTeachingHints;
        // knowledgeReferences（对齐 TS knowledgeMatches.slice(0,6) → id/title/confidence/score/whyMatched）
        for (KnowledgeReference kr : knowledgeMatches.subList(0, Math.min(6, knowledgeMatches.size()))) {
            ev.knowledgeReferences.add(kr);
        }
        // recommendedProblems
        ev.recommendedProblems = recommendedProblems;
        ev.student.level = "intermediate";

        ev.constraints.add("Only use coordinates and candidate moves present in this evidence or in the attached board image.");
        ev.constraints.add("Do not invent winrate, scoreLead, joseki names, pro-player references, source citations, or PV lines.");
        ev.constraints.add("For winner and margin claims, use scoreSummary.text/leader/leadPoints. blackScoreLead is black-positive; negative means White leads.");
        ev.constraints.add("Only name a joseki when recognizedMotifs contains a joseki:* motif with medium/strong confidence; otherwise describe it as an opening/corner pattern.");
        ev.constraints.add("When a motif has sourceRefs, treat them as traceability labels, not as quoted sources. Do not claim a source says something unless source text is present.");
        ev.constraints.add("If confidence is medium/low, speak as preference or hypothesis, not as a final verdict.");
        ev.constraints.add("Explain the thinking order a human should use before mentioning numbers.");
        return ev;
    }

    static List<RecognizedMotifView> toMotifViews(List<MotifRecognizer.RecognizedTeachingMotif> motifs) {
        List<RecognizedMotifView> out = new ArrayList<>();
        if (motifs == null) return out;
        for (MotifRecognizer.RecognizedTeachingMotif m : motifs.stream().limit(8).toList()) {
            RecognizedMotifView v = new RecognizedMotifView();
            v.id = m.id; v.title = m.title; v.motifType = m.motifType; v.confidence = m.confidence.name().toLowerCase();
            v.score = m.score; v.whyMatched = m.whyMatched; v.recognition = m.recognition; v.wrongThinking = m.wrongThinking;
            v.correctThinking = m.correctThinking; v.drillPrompt = m.drillPrompt; v.sourceQuality = m.sourceQuality; v.josekiFamily = m.josekiFamily;
            v.relatedMoves = m.relatedMoves != null ? new ArrayList<>(java.util.Arrays.asList(m.relatedMoves)) : new ArrayList<>();
            v.sourceRefs = m.sourceRefs != null ? new ArrayList<>(java.util.Arrays.asList(m.sourceRefs)) : new ArrayList<>();
            v.variationCount = m.variationCount;
            out.add(v);
        }
        return out;
    }

    // ---- verifyTeacherMarkdown ----
    static List<String> allowedMoves(TeachingEvidence evidence) {
        Set<String> moves = new HashSet<>();
        if (evidence.actualMove != null) moves.add(evidence.actualMove);
        for (TeachingEvidenceCandidate c : evidence.bestCandidates) {
            if (c.move != null) moves.add(c.move);
            if (c.pv != null) for (String pv : c.pv) if (pv != null) moves.add(pv);
        }
        List<String> out = new ArrayList<>();
        for (String m : moves) if (m != null && !m.toLowerCase().equals("pass")) out.add(m.toUpperCase());
        return out;
    }

    static List<String> extractCoordinates(String markdown) {
        Set<String> result = new HashSet<>();
        if (markdown == null) return new ArrayList<>(result);
        Matcher mt = Pattern.compile("\\b([A-HJ-T](?:1?\\d|2[0-5]))\\b").matcher(markdown);
        while (mt.find()) result.add(mt.group(1).toUpperCase());
        return new ArrayList<>(result);
    }

    static boolean nearRecommendationPhrase(String markdown, String coord) {
        if (markdown == null) return false;
        int index = markdown.toUpperCase().indexOf(coord.toUpperCase());
        if (index < 0) return false;
        String window = markdown.substring(Math.max(0, index - 24), Math.min(markdown.length(), index + coord.length() + 24));
        return window.matches(".*(推荐|最佳|首选|应该|建议|好点|更好|best|recommend|should|play\\s+at|候補|추천|권장).*");
    }

    public static MarkdownVerification verifyTeacherMarkdown(String markdown, TeachingEvidence evidence) {
        MarkdownVerification v = new MarkdownVerification();
        List<String> warnings = v.warnings, violations = v.violations;
        List<String> allowed = allowedMoves(evidence);
        Set<String> allowedSet = new HashSet<>(allowed);
        v.allowedMoves = allowed;
        if (markdown != null) {
            for (String coord : extractCoordinates(markdown)) {
                if (!allowedSet.contains(coord) && nearRecommendationPhrase(markdown, coord)) {
                    violations.add("Unsupported recommended coordinate " + coord + "; it is not in top candidates, actual move, or PV evidence.");
                }
            }
            List<Double> percents = new ArrayList<>();
            Matcher pm = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(markdown);
            while (pm.find()) { try { percents.add(Double.parseDouble(pm.group(1))); } catch (Exception ignore) {} }
            for (Double value : percents) if (value > 100) violations.add("Impossible winrate percentage " + value + "%.");
            if (percents.size() >= 5) warnings.add("Many percentages were mentioned; consider simplifying the teacher explanation.");
            if (evidence.loss.confidence != TeachingConfidence.high && markdown.matches(".*(明显恶手|必败|唯一|绝对|certainly|only\\s+move|forced).*"))
                warnings.add("Explanation sounds too absolute for medium/low-confidence evidence.");
            boolean mentionsJoseki = markdown.matches(".*(定式|joseki|jōseki|定石|정석).*");
            boolean supportedJoseki = false;
            for (RecognizedMotifView m : evidence.recognizedMotifs) if (m.motifType != null && m.motifType.startsWith("joseki:") && ("strong".equals(m.confidence) || "medium".equals(m.confidence))) supportedJoseki = true;
            if (mentionsJoseki && !supportedJoseki) warnings.add("Joseki terminology was used without a medium/strong recognized joseki motif.");
            if (markdown.matches(".*(据.*(Sensei|Kogo|GoGoD|Wikibooks)|source says|according to).*"))
                warnings.add("Teacher appears to cite external sources; sourceRefs are traceability labels, not source text.");
        }
        v.ok = violations.isEmpty();
        return v;
    }

    static String evidenceSummaryZh(TeachingEvidence evidence) {
        String best = evidence.bestCandidates.isEmpty() ? "未知" : evidence.bestCandidates.get(0).move;
        String actual = evidence.actualMove != null ? evidence.actualMove : "未知";
        return "AI 证据链：第 " + evidence.moveNumber + " 手，实战 " + actual + "，首选 " + best
            + "，胜率损失 " + round(evidence.loss.winrateLoss, 1) + "%，目差损失约 " + round(evidence.loss.scoreLoss, 1)
            + "，判断 " + evidence.loss.severity + "，置信度 " + evidence.loss.confidence + "。";
    }
    static String evidenceSummaryTw(TeachingEvidence evidence) {
        String best = evidence.bestCandidates.isEmpty() ? "未知" : evidence.bestCandidates.get(0).move;
        String actual = evidence.actualMove != null ? evidence.actualMove : "未知";
        return "AI 證據鏈：第 " + evidence.moveNumber + " 手，實戰 " + actual + "，首選 " + best
            + "，勝率損失 " + round(evidence.loss.winrateLoss, 1) + "%，目差損失約 " + round(evidence.loss.scoreLoss, 1)
            + "，判斷 " + evidence.loss.severity + "，置信度 " + evidence.loss.confidence + "。";
    }
    static String evidenceSummaryEn(TeachingEvidence evidence) {
        String best = evidence.bestCandidates.isEmpty() ? "unknown" : evidence.bestCandidates.get(0).move;
        String actual = evidence.actualMove != null ? evidence.actualMove : "unknown";
        return "Evidence chain: move " + evidence.moveNumber + ", played " + actual + ", top candidate " + best
            + ", winrate loss " + round(evidence.loss.winrateLoss, 1) + "%, score loss about " + round(evidence.loss.scoreLoss, 1)
            + ", severity " + evidence.loss.severity + ", confidence " + evidence.loss.confidence + ".";
    }

    /** 对齐 TS buildVerificationNote（证据链摘要 + 识别棋形行 + 校验提示） */
    public static String buildVerificationNote(MarkdownVerification verification, TeachingEvidence evidence, String localeInput) {
        String locale = localeInput != null && (localeInput.equals("zh-TW") || localeInput.equals("en-US") || localeInput.equals("ja-JP") || localeInput.equals("ko-KR") || localeInput.equals("th-TH") || localeInput.equals("vi-VN")) ? localeInput : "zh-CN";
        String summary = locale.equals("zh-CN") ? evidenceSummaryZh(evidence) : locale.equals("zh-TW") ? evidenceSummaryTw(evidence) : evidenceSummaryEn(evidence);
        String motifLine = "";
        if (evidence != null && !evidence.recognizedMotifs.isEmpty()) {
            RecognizedMotifView motif = evidence.recognizedMotifs.get(0);
            if (locale.equals("zh-TW")) {
                motifLine = "\n> 識別棋形：" + motif.title + "（" + motif.confidence + "，score " + round(motif.score, 1)
                    + (motif.sourceRefs != null && !motif.sourceRefs.isEmpty() ? "，來源標記 " + String.join("/", motif.sourceRefs) : "") + "）。";
            } else if (locale.equals("zh-CN")) {
                motifLine = "\n> 识别棋形：" + motif.title + "（" + motif.confidence + "，score " + round(motif.score, 1)
                    + (motif.sourceRefs != null && !motif.sourceRefs.isEmpty() ? "，来源标记 " + String.join("/", motif.sourceRefs) : "") + "）。";
            } else {
                motifLine = "\n> Recognized motif: " + motif.title + " (" + motif.confidence + ", score " + round(motif.score, 1)
                    + (motif.sourceRefs != null && !motif.sourceRefs.isEmpty() ? ", sources " + String.join("/", motif.sourceRefs) : "") + ").";
            }
        }
        List<String> issueLines = new ArrayList<>();
        if (verification != null) { issueLines.addAll(verification.violations); issueLines.addAll(verification.warnings); }
        String issues = "";
        if (!issueLines.isEmpty()) {
            List<String> shown = issueLines.size() > 3 ? issueLines.subList(0, 3) : issueLines;
            String prefix = locale.equals("zh-CN") ? "校验提示" : locale.equals("zh-TW") ? "校驗提示" : "Verifier notes";
            issues = "\n> " + prefix + "：" + String.join("；", shown);
        }
        return "> " + summary + motifLine + issues;
    }

    /** 对齐 TS appendVerificationNote */
    public static String appendVerificationNote(String markdown, MarkdownVerification verification, TeachingEvidence evidence, String localeInput) {
        String note = buildVerificationNote(verification, evidence, localeInput);
        if (markdown == null || markdown.trim().isEmpty()) return note;
        return markdown.trim() + "\n\n" + note.trim();
    }
}
