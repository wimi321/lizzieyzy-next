package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import java.util.*;

/**
 * 对齐 GoAgent teacher/katagoTraceTranslator.ts（408 行）：把 KataGo 候选点/PV/policy/ownership 翻译成教学用 trace packet。
 * 含 pvSupport（PV 支撑强度）、policySearchDelta（政策-搜索一致性）、candidateRole（候选教学角色）、
 * ownershipSummary（区域归属摆动）、humanPolicySignals（人类策略信号）、shallowSearchTree、teachingGuidance（主讲重点+禁用结论）。
 * 注：lizzieyzy 端 KataGo 未暴露 prior/ownership/humanPrior 字段，相关解释会走降级路径（与 GoAgent 行为一致）。
 */
public final class KatagoTraceTranslator {

    private KatagoTraceTranslator() {}

    static final String GTP_LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
    static double round(Double v, int d) { if (v == null || !Double.isFinite(v)) return 0; double f = Math.pow(10, d); return Math.round(v * f) / f; }
    static String key(String move) { return move == null ? "" : move.trim().toUpperCase(); }

    public enum PvSupportLevel { strong, medium, weak }
    public enum TeachingRole { best, actual, natural_but_refuted, low_policy_but_strong_search, human_likely_mistake, uncertain }
    public enum PolicySearchInterpretation { policy_and_search_agree, natural_move_refuted_by_search, non_obvious_search_favorite, search_overturned_policy, insufficient_policy_evidence }
    public enum Confidence { high, medium, low }

    public static class PvSupport {
        public String candidate; public List<String> pv = new ArrayList<>(); public List<Double> pvVisits = new ArrayList<>();
        public PvSupportLevel support; public String warning;
    }
    public static class PolicySearchDelta { public String move; public Double prior; public Integer priorRank, searchRank; public int visits; public PolicySearchInterpretation interpretation; public String note; }
    public static class TraceCandidate {
        public String move; public int rank, visits, edgeVisits; public Double prior, priorRank, searchRank, winrate, scoreLead, blackScoreLead, scoreStdev, utility, lcb, humanPrior, humanPolicy;
        public String scoreLeadPerspective = "black-positive"; public String scoreSummary; public List<String> pv = new ArrayList<>(); public List<Double> pvVisits = new ArrayList<>();
        public TeachingRole teachingRole; public String interpretation; public List<String> warnings = new ArrayList<>();
    }
    public static class OwnershipRegion { public String region; public double avgSwing; public List<String> points = new ArrayList<>(); public String explanation; }
    public static class OwnershipSummary { public String mode, note; public List<OwnershipRegion> affectedRegions = new ArrayList<>(); }
    public static class HumanPolicySignals { public Double actualHumanPrior, bestHumanPrior, actualHumanPolicy, bestHumanPolicy; public boolean levelAppropriateMistake; public String interpretation; }
    public static class TraceTreeNode { public String move; public int depth; public int visits; public Double winrate, scoreLead; public String scoreLeadPerspective = "black-positive"; public String scoreSummary; public Double prior; public String pvSupport; public List<TraceTreeNode> children = new ArrayList<>(); }
    public static class SearchSummary { public String bestMove, actualMove; public double winrateLoss, scoreLoss; public Confidence confidence; public String safeWording, reason; }
    public static class TeachingGuidance { public String mainPoint, safeWording; public List<String> forbiddenClaims = new ArrayList<>(); }
    public static class KataGoTracePacket {
        public Position position = new Position();
        public SearchSummary searchSummary = new SearchSummary();
        public List<TraceCandidate> candidateComparison = new ArrayList<>();
        public String scorePerspectiveNote;
        public List<PolicySearchDelta> policySearchDelta = new ArrayList<>();
        public List<PvSupport> pvSupport = new ArrayList<>();
        public OwnershipSummary ownershipSummary;
        public HumanPolicySignals humanPolicySignals;
        public TraceTreeNode shallowSearchTree;
        public TeachingGuidance teachingGuidance = new TeachingGuidance();
    }
    public static class Position { public int moveNumber; public String phase, actualMove; }

    static int candidateEvidenceVisits(AnalysisBrain.PvCandidate c) { return Math.max(0, Math.max(c.visits, c.edgeVisits)); }

    static PvSupport pvSupportForCandidate(AnalysisBrain.PvCandidate c) {
        PvSupport s = new PvSupport();
        s.candidate = c.move;
        s.pv = c.pv.size() > 10 ? new ArrayList<>(c.pv.subList(0, 10)) : new ArrayList<>(c.pv);
        List<Double> rawVisits = new ArrayList<>(c.pvVisits);
        rawVisits.removeIf(v -> v == null || !Double.isFinite(v));
        s.pvVisits = rawVisits.size() > 10 ? new ArrayList<>(rawVisits.subList(0, 10)) : rawVisits;
        List<Double> visits = new ArrayList<>(s.pvVisits);
        double minVisits = visits.isEmpty() ? 0 : Collections.min(visits.subList(0, Math.min(5, visits.size())));
        int bestVisits = candidateEvidenceVisits(c);
        if (c.pv.isEmpty()) { s.support = PvSupportLevel.weak; s.warning = "KataGo 没有返回 PV；不能展开变化讲解。"; }
        else if (visits.isEmpty()) { s.support = bestVisits >= 500 ? PvSupportLevel.medium : PvSupportLevel.weak; s.warning = "缺少 pvVisits；只能把 PV 作为参考变化，不要说成必然。"; }
        else if (minVisits >= 120 || bestVisits >= 800) s.support = PvSupportLevel.strong;
        else if (minVisits >= 40 || bestVisits >= 300) { s.support = PvSupportLevel.medium; s.warning = "PV 中后段搜索量不高；只宜讲前几手关键变化。"; }
        else { s.support = PvSupportLevel.weak; s.warning = "PV 支撑较弱；不要把长变化说成铁线。"; }
        return s;
    }

    static Map<String, Integer> priorRanks(List<AnalysisBrain.PvCandidate> candidates) {
        List<AnalysisBrain.PvCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Double.compare(b.prior != null ? b.prior : 0, a.prior != null ? a.prior : 0));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int idx = 0;
        for (AnalysisBrain.PvCandidate c : ranked) { if (c.prior != null) ranks.put(key(c.move), ++idx); }
        return ranks;
    }

    static PolicySearchInterpretation policyDeltaInterpretation(AnalysisBrain.PvCandidate c, int searchRank, Integer priorRank) {
        if (priorRank == null || c.prior == null) return PolicySearchInterpretation.insufficient_policy_evidence;
        if (priorRank <= 2 && searchRank <= 2) return PolicySearchInterpretation.policy_and_search_agree;
        if (priorRank <= 2 && searchRank >= 4) return PolicySearchInterpretation.natural_move_refuted_by_search;
        if (priorRank >= 5 && searchRank <= 2) return PolicySearchInterpretation.non_obvious_search_favorite;
        if (Math.abs(priorRank - searchRank) >= 3) return PolicySearchInterpretation.search_overturned_policy;
        return PolicySearchInterpretation.policy_and_search_agree;
    }
    static String policyDeltaNote(AnalysisBrain.PvCandidate c, int searchRank, Integer priorRank) {
        String prior = c.prior != null ? round(c.prior, 1) + "%" : "未知";
        if (priorRank == null) return "缺少 policy/prior 排名，不能判断第一感与搜索是否一致。搜索排名 " + searchRank + "，prior=" + prior + "。";
        PolicySearchInterpretation interp = policyDeltaInterpretation(c, searchRank, priorRank);
        if (interp == PolicySearchInterpretation.policy_and_search_agree) return "policy 与搜索基本一致：prior 排名 " + priorRank + "，搜索排名 " + searchRank + "。";
        if (interp == PolicySearchInterpretation.natural_move_refuted_by_search) return "这手看起来很自然：prior 排名 " + priorRank + "，但搜索后跌到第 " + searchRank + "，应解释为“自然但被搜索否定”。";
        if (interp == PolicySearchInterpretation.non_obvious_search_favorite) return "这手不直观：prior 排名 " + priorRank + "，但搜索升到第 " + searchRank + "，适合讲成急所/手筋/方向。";
        if (interp == PolicySearchInterpretation.search_overturned_policy) return "搜索明显修正了 policy 第一感：prior 排名 " + priorRank + "，搜索排名 " + searchRank + "。";
        return "搜索排名 " + searchRank + "，prior 排名 " + priorRank + "。";
    }

    static TeachingRole candidateRole(AnalysisBrain.PvCandidate c, int searchRank, Integer priorRank, String actualMove, PvSupport pvSupport, double scoreLoss) {
        boolean isBest = searchRank == 1;
        boolean isActual = actualMove != null && key(c.move).equals(key(actualMove));
        PolicySearchInterpretation delta = policyDeltaInterpretation(c, searchRank, priorRank);
        if (isBest) return TeachingRole.best;
        if (isActual && scoreLoss >= 1.5 && (c.humanPrior != null ? c.humanPrior : (c.humanPolicy != null ? c.humanPolicy : 0)) >= 8) return TeachingRole.human_likely_mistake;
        if (isActual) return TeachingRole.actual;
        if (delta == PolicySearchInterpretation.natural_move_refuted_by_search) return TeachingRole.natural_but_refuted;
        if (delta == PolicySearchInterpretation.non_obvious_search_favorite) return TeachingRole.low_policy_but_strong_search;
        if (pvSupport.support == PvSupportLevel.weak || candidateEvidenceVisits(c) < 80) return TeachingRole.uncertain;
        return TeachingRole.uncertain;
    }
    static String roleInterpretation(TeachingRole role) {
        if (role == TeachingRole.best) return "搜索后的首选，可以作为主线讲。";
        if (role == TeachingRole.actual) return "这是实战手，应和首选比较，讲清它损失在哪里。";
        if (role == TeachingRole.natural_but_refuted) return "这手直觉上自然，但搜索后不支持，适合讲“为什么看似合理却亏”。";
        if (role == TeachingRole.low_policy_but_strong_search) return "这手不直观，但搜索支持，适合讲急所、次序或手筋。";
        if (role == TeachingRole.human_likely_mistake) return "这像当前水平常见的人类自然错误，应温和解释判断顺序。";
        return "证据不足或排序不突出，只能作为辅助参考。";
    }

    static List<String> candidateWarnings(AnalysisBrain.PvCandidate c, PvSupport pvSupport, Confidence qualityConfidence) {
        Set<String> warnings = new LinkedHashSet<>();
        if (candidateEvidenceVisits(c) < 80) warnings.add("candidate visits 较低，不能讲成定论。");
        if (c.scoreStdev != null && c.scoreStdev >= 8) warnings.add("scoreStdev 较高，局面不确定性大。");
        if (pvSupport.warning != null) warnings.add(pvSupport.warning);
        if (qualityConfidence != Confidence.high) warnings.add("analysisQuality=" + qualityConfidence + "，需要谨慎语气。");
        return new ArrayList<>(warnings);
    }

    static List<TraceCandidate> buildCandidateComparison(List<AnalysisBrain.PvCandidate> candidates, String actualMove, double scoreLoss, Confidence qualityConfidence) {
        Map<String, Integer> ranks = priorRanks(candidates);
        List<TraceCandidate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(8, candidates.size()); i++) {
            AnalysisBrain.PvCandidate c = candidates.get(i);
            int searchRank = i + 1;
            PvSupport ps = pvSupportForCandidate(c);
            Integer priorRank = ranks.get(key(c.move));
            TeachingRole role = candidateRole(c, searchRank, priorRank, actualMove, ps, scoreLoss);
            TraceCandidate tc = new TraceCandidate();
            tc.move = c.move; tc.rank = searchRank; tc.visits = c.visits; tc.edgeVisits = c.edgeVisits;
            tc.prior = c.prior; tc.priorRank = priorRank != null ? priorRank.doubleValue() : null; tc.searchRank = (double) searchRank;
            tc.winrate = c.winrate != null ? round(c.winrate, 2) : null;
            tc.scoreLead = c.scoreLead != null ? round(c.scoreLead, 2) : null;
            tc.blackScoreLead = c.scoreLead != null ? round(c.scoreLead, 2) : null;
            tc.scoreLeadPerspective = "black-positive";
            tc.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(c.scoreLead, "B").text;
            tc.scoreStdev = c.scoreStdev; tc.utility = c.utility; tc.lcb = c.lcb; tc.humanPrior = c.humanPrior; tc.humanPolicy = c.humanPolicy;
            tc.pv = c.pv; tc.pvVisits = c.pvVisits; tc.teachingRole = role; tc.interpretation = roleInterpretation(role);
            tc.warnings = candidateWarnings(c, ps, qualityConfidence);
            out.add(tc);
        }
        return out;
    }

    static HumanPolicySignals buildHumanPolicySignals(List<AnalysisBrain.PvCandidate> candidates, String actualMove, double scoreLoss, String level) {
        AnalysisBrain.PvCandidate best = candidates.isEmpty() ? null : candidates.get(0);
        AnalysisBrain.PvCandidate actual = null;
        for (AnalysisBrain.PvCandidate c : candidates) if (key(c.move).equals(key(actualMove))) { actual = c; break; }
        // lizzieyzy 侧：humanPrior/humanPolicy 未请求，用已接线的 KataGo prior（policy）作为人类策略信号源
        Double actualHumanPrior = actual != null ? (actual.humanPrior != null ? actual.humanPrior : actual.prior) : null;
        Double bestHumanPrior = best != null ? (best.humanPrior != null ? best.humanPrior : best.prior) : null;
        Double actualHumanPolicy = actual != null ? actual.humanPolicy : null;
        Double bestHumanPolicy = best != null ? best.humanPolicy : null;
        if (actualHumanPrior == null && bestHumanPrior == null && actualHumanPolicy == null && bestHumanPolicy == null) return null;
        double humanGap = (actualHumanPrior != null ? actualHumanPrior : (actualHumanPolicy != null ? actualHumanPolicy : 0))
            - (bestHumanPrior != null ? bestHumanPrior : (bestHumanPolicy != null ? bestHumanPolicy : 0));
        boolean levelAppropriateMistake = humanGap >= 4 && scoreLoss >= 1;
        String levelText = "dan".equals(level) ? "段位" : "advanced".equals(level) ? "高级" : "beginner".equals(level) ? "入门" : "级位/中级";
        HumanPolicySignals h = new HumanPolicySignals();
        h.actualHumanPrior = actualHumanPrior; h.bestHumanPrior = bestHumanPrior;
        h.actualHumanPolicy = actualHumanPolicy; h.bestHumanPolicy = bestHumanPolicy;
        h.levelAppropriateMistake = levelAppropriateMistake;
        h.interpretation = levelAppropriateMistake
            ? "实战手在人类策略中更自然，但 KataGo 认为有损失；适合作为" + levelText + "学生常见误区讲解。"
            : "humanPolicy/humanPrior 信号不足以说明这是某一棋力层的典型误区；不要过度贴标签。";
        return h;
    }

    static List<PolicySearchDelta> buildPolicySearchDelta(List<AnalysisBrain.PvCandidate> candidates) {
        Map<String, Integer> ranks = priorRanks(candidates);
        List<PolicySearchDelta> out = new ArrayList<>();
        for (int i = 0; i < Math.min(8, candidates.size()); i++) {
            AnalysisBrain.PvCandidate c = candidates.get(i);
            int searchRank = i + 1;
            Integer priorRank = ranks.get(key(c.move));
            PolicySearchDelta d = new PolicySearchDelta();
            d.move = c.move; d.prior = c.prior; d.priorRank = priorRank; d.searchRank = searchRank; d.visits = c.visits;
            d.interpretation = policyDeltaInterpretation(c, searchRank, priorRank);
            d.note = policyDeltaNote(c, searchRank, priorRank);
            out.add(d);
        }
        return out;
    }

    static String gtpFromIndex(int index, int boardSize) {
        int row = index / boardSize, col = index % boardSize;
        String letter = col < GTP_LETTERS.length() ? String.valueOf(GTP_LETTERS.charAt(col)) : "?";
        return letter + (boardSize - row);
    }
    static String regionForIndex(int index, int boardSize) {
        int row = index / boardSize, col = index % boardSize;
        boolean top = row < boardSize / 3, bottom = row >= boardSize * 2 / 3, left = col < boardSize / 3, right = col >= boardSize * 2 / 3;
        if (top && left) return "左上"; if (top && right) return "右上";
        if (bottom && left) return "左下"; if (bottom && right) return "右下";
        if (top) return "上边"; if (bottom) return "下边"; if (left) return "左边"; if (right) return "右边";
        return "中腹";
    }
    static OwnershipSummary ownershipRegionsFromArrays(int boardSize, double[] bestOwnership, double[] actualOwnership) {
        OwnershipSummary s = new OwnershipSummary();
        if (bestOwnership == null || bestOwnership.length == 0) { s.mode = "unavailable"; s.note = "KataGo 没有返回 ownership；不能用厚薄/区域归属作为主证据。"; return s; }
        Map<String, List<double[]>> byRegion = new LinkedHashMap<>(); // region -> [value, count]
        for (int index = 0; index < Math.min(bestOwnership.length, boardSize * boardSize); index++) {
            double best = bestOwnership[index];
            double value = (actualOwnership != null && actualOwnership.length > index) ? Math.abs(best - actualOwnership[index]) : Math.abs(best);
            if (value < 0.08) continue;
            String region = regionForIndex(index, boardSize);
            List<double[]> entry = byRegion.computeIfAbsent(region, k -> new ArrayList<>());
            entry.add(new double[]{value, index});
        }
        List<OwnershipRegion> regions = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : byRegion.entrySet()) {
            List<double[]> pts = e.getValue();
            double sum = 0; for (double[] p : pts) sum += p[0];
            pts.sort((a, b) -> Double.compare(b[0], a[0]));
            OwnershipRegion r = new OwnershipRegion();
            r.region = e.getKey();
            r.avgSwing = round(sum / Math.max(1, pts.size()), 3);
            for (double[] p : pts.subList(0, Math.min(8, pts.size()))) r.points.add(gtpFromIndex((int) p[1], boardSize));
            r.explanation = (actualOwnership != null && actualOwnership.length > 0)
                ? e.getKey() + "在首选和实战之间 ownership 摆动明显，可作为厚薄/实地变化证据。"
                : e.getKey() + "在首选分支 ownership 信号明显，可作为区域归属参考。";
            regions.add(r);
        }
        regions.sort((a, b) -> Double.compare(b.avgSwing, a.avgSwing));
        s.mode = (actualOwnership != null && actualOwnership.length > 0) ? "best-vs-actual" : "best-ownership";
        s.note = (actualOwnership != null && actualOwnership.length > 0)
            ? "ownership 摘要比较首选手与实战手对应候选的区域归属差异。"
            : "只有首选 ownership，可作为区域归属参考，不能称为实战差异。";
        s.affectedRegions = regions.size() > 5 ? regions.subList(0, 5) : regions;
        return s;
    }

    static Confidence confidenceFromPvSupport(List<PvSupport> pvSupport, String bestMove) {
        if (pvSupport.isEmpty()) return Confidence.high;
        for (PvSupport p : pvSupport) if (p.candidate != null && p.candidate.equals(bestMove) && p.support == PvSupportLevel.weak) return Confidence.medium;
        return Confidence.high;
    }
    static String mainPoint(List<AnalysisBrain.PvCandidate> candidates, List<PolicySearchDelta> deltas, String bestMove, String actualMove, double scoreLoss) {
        String nonObvious = null, refuted = null;
        for (PolicySearchDelta d : deltas) {
            if (d.interpretation == PolicySearchInterpretation.non_obvious_search_favorite) nonObvious = d.move;
            if (d.move.equals(actualMove) && d.interpretation == PolicySearchInterpretation.natural_move_refuted_by_search) refuted = d.move;
        }
        if (refuted != null) return "实战 " + actualMove + " 看似自然，但搜索不支持；主线应解释它为什么被首选 " + bestMove + " 替代。";
        if (nonObvious != null && nonObvious.equals(bestMove)) return "首选 " + bestMove + " 不是最直观第一感，但搜索支持；主线应讲急所、次序或后续收益。";
        if (scoreLoss >= 2) return "实战 " + actualMove + " 相比首选 " + bestMove + " 有可见损失；主线应讲损失来自哪里。";
        return "候选差距不大；主线应讲判断方向和可复用思路，不要把它说成大恶手。";
    }
    static String safeWording(Confidence c) {
        if (c == Confidence.high) return "可以明确说 KataGo 首选和实战差异，但仍需引用证据。";
        if (c == Confidence.medium) return "使用“AI 更倾向 / 更像 / 参考变化”措辞，避免唯一和必然。";
        return "只能说低置信倾向，不得下绝对结论或讲长变化。";
    }

    public static KataGoTracePacket buildKataGoTracePacket(List<AnalysisBrain.PvCandidate> candidates, int moveNumber, double winrateLoss, double scoreLoss, String actualMove, Confidence qualityConfidence, String userLevel, double[] ownershipArray) {
        KataGoTracePacket packet = new KataGoTracePacket();
        List<PvSupport> pvSupport = new ArrayList<>();
        for (AnalysisBrain.PvCandidate c : candidates.subList(0, Math.min(8, candidates.size()))) pvSupport.add(pvSupportForCandidate(c));
        Confidence confidence = qualityConfidence != null ? qualityConfidence : confidenceFromPvSupport(pvSupport, candidates.isEmpty() ? null : candidates.get(0).move);
        String bestMove = candidates.isEmpty() ? null : candidates.get(0).move;
        String safe = safeWording(confidence);

        packet.position.moveNumber = moveNumber;
        packet.position.phase = moveNumber <= 50 ? "opening" : moveNumber <= 160 ? "middle" : "endgame";
        packet.position.actualMove = actualMove;
        packet.searchSummary.bestMove = bestMove; packet.searchSummary.actualMove = actualMove;
        packet.searchSummary.winrateLoss = round(winrateLoss, 2); packet.searchSummary.scoreLoss = round(scoreLoss, 2);
        packet.searchSummary.confidence = confidence; packet.searchSummary.safeWording = safe; packet.searchSummary.reason = "KataGo trace built from candidate visits, prior, PV and ownership fields.";
        packet.candidateComparison = buildCandidateComparison(candidates, actualMove, scoreLoss, confidence);
        packet.scorePerspectiveNote = "tracePacket scoreLead/blackScoreLead are black-positive: positive means Black leads, negative means White leads. Use scoreSummary.text/leader/leadPoints for spoken winner and margin.";
        packet.policySearchDelta = buildPolicySearchDelta(candidates);
        packet.pvSupport = pvSupport;
        double[] bestOwnership = null;
        double[] actualOwnership = null;
        if (!candidates.isEmpty() && candidates.get(0).ownership != null) bestOwnership = candidates.get(0).ownership;
        for (AnalysisBrain.PvCandidate c : candidates) if (key(c.move).equals(key(actualMove)) && c.ownership != null) actualOwnership = c.ownership;
        if (bestOwnership == null && ownershipArray != null) bestOwnership = ownershipArray;
        packet.ownershipSummary = ownershipRegionsFromArrays(featurecat.lizzie.rules.Board.boardWidth, bestOwnership, actualOwnership);
        packet.humanPolicySignals = buildHumanPolicySignals(candidates, actualMove, scoreLoss, userLevel);
        packet.shallowSearchTree = buildShallowSearchTree(candidates, winrateLoss, scoreLoss);
        packet.teachingGuidance.mainPoint = mainPoint(candidates, packet.policySearchDelta, bestMove, actualMove, scoreLoss);
        packet.teachingGuidance.safeWording = safe;
        if (confidence == Confidence.high)
            packet.teachingGuidance.forbiddenClaims.add("不要编造未在 tracePacket 或 KataGo 中出现的坐标、PV、定式名。");
        else
            packet.teachingGuidance.forbiddenClaims.addAll(Arrays.asList("唯一", "必杀", "必败", "绝对", "净杀", "无条件成立", "完整定式结论"));
        return packet;
    }

    static TraceTreeNode buildShallowSearchTree(List<AnalysisBrain.PvCandidate> candidates, double winrateLoss, double scoreLoss) {
        TraceTreeNode root = new TraceTreeNode();
        root.move = "ROOT"; root.depth = 0; root.scoreLeadPerspective = "black-positive";
        root.scoreSummary = "black-positive";
        if (!candidates.isEmpty()) {
            AnalysisBrain.PvCandidate best = candidates.get(0);
            root.winrate = best.winrate != null ? round(best.winrate, 2) : null;
            root.scoreLead = best.scoreLead != null ? round(best.scoreLead, 2) : null;
            root.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(best.scoreLead, "B").text;
        }
        for (int i = 0; i < Math.min(5, candidates.size()); i++) {
            AnalysisBrain.PvCandidate c = candidates.get(i);
            PvSupport ps = pvSupportForCandidate(c);
            TraceTreeNode node = new TraceTreeNode();
            node.move = c.move; node.depth = 1; node.visits = c.visits; node.winrate = c.winrate; node.scoreLead = c.scoreLead;
            node.scoreLeadPerspective = "black-positive";
            node.scoreSummary = featurecat.lizzie.teacher.analysis.ScorePerspective.scoreSummaryFromBlackLead(c.scoreLead, "B").text;
            node.prior = c.prior; node.pvSupport = ps.support.name();
            node.children = pvLineAsTree(c, ps);
            root.children.add(node);
        }
        return root;
    }
    static List<TraceTreeNode> pvLineAsTree(AnalysisBrain.PvCandidate c, PvSupport ps) {
        List<TraceTreeNode> nodes = new ArrayList<>();
        for (int i = 0; i < Math.min(5, c.pv.size()); i++) {
            TraceTreeNode n = new TraceTreeNode();
            n.move = c.pv.get(i); n.depth = i + 2; n.pvSupport = ps.support.name(); nodes.add(n);
        }
        List<TraceTreeNode> tree = new ArrayList<>();
        for (int i = nodes.size() - 1; i >= 0; i--) { TraceTreeNode n = nodes.get(i); n.children = tree; tree = new ArrayList<>(); tree.add(n); }
        return tree;
    }

    public static String formatKataGoTraceForPrompt(KataGoTracePacket packet) {
        if (packet == null) return "KataGo Trace Packet: 未生成。请只引用原始 KataGo 数据，且谨慎表达。";
        StringBuilder sb = new StringBuilder("【KataGo Trace Packet】\n");
        sb.append("局面：第 ").append(packet.position.moveNumber).append(" 手，阶段 ").append(packet.position.phase).append("。\n");
        sb.append("搜索摘要：首选 ").append(packet.searchSummary.bestMove != null ? packet.searchSummary.bestMove : "未知")
          .append("，实战 ").append(packet.searchSummary.actualMove != null ? packet.searchSummary.actualMove : "未知")
          .append("，胜率损失 ").append(packet.searchSummary.winrateLoss).append("%，目差损失 ").append(packet.searchSummary.scoreLoss)
          .append("，置信度 ").append(packet.searchSummary.confidence).append("。\n");
        sb.append("目差口径：").append(packet.scorePerspectiveNote).append("\n");
        sb.append("安全措辞：").append(packet.searchSummary.safeWording).append("\n");
        sb.append("主讲重点：").append(packet.teachingGuidance.mainPoint).append("\n");
        sb.append("policy-vs-search：").append(packet.policySearchDelta.stream().limit(4).map(d -> d.move + ": " + d.note).reduce((a, b) -> a + "；" + b).orElse("无")).append("\n");
        sb.append("PV 支撑：").append(packet.pvSupport.stream().limit(4).map(s -> s.candidate + "=" + s.support + (s.warning != null ? "(" + s.warning + ")" : "")).reduce((a, b) -> a + "；" + b).orElse("无")).append("\n");
        if (packet.ownershipSummary != null && !packet.ownershipSummary.affectedRegions.isEmpty()) {
            List<String> regions = new ArrayList<>();
            for (OwnershipRegion r : packet.ownershipSummary.affectedRegions) regions.add(r.region + " swing=" + r.avgSwing);
            sb.append("ownership 区域：").append(String.join("；", regions)).append("\n");
        } else {
            sb.append("ownership 区域：").append(packet.ownershipSummary != null ? packet.ownershipSummary.note : "无").append("\n");
        }
        sb.append("humanPolicy：").append(packet.humanPolicySignals != null ? packet.humanPolicySignals.interpretation : "未返回人类策略信号。").append("\n");
        sb.append("禁用结论：").append(String.join("、", packet.teachingGuidance.forbiddenClaims)).append("\n");
        return sb.toString();
    }
}
