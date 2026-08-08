package featurecat.lizzie.teacher.knowledge;

import java.util.*;
import java.util.regex.Matcher;

/**
 * 对齐 GoAgent knowledge/matchEngine.ts 的 localShapeGeometryMatch（585-648 行）：
 * 以 anchor 为中心的局部形状几何匹配——计算 anchor 的 liberty profile（气数/邻接/组数），
 * 对 problem 的 initialStones 做旋转/换色变换，比较相对坐标集合与气数 profile，给出匹配分。
 * problem 来自 training-catalog.json 的 lifeDeathProblems / tesujiProblems。
 */
public final class LocalShapeGeometryMatcher {

    private LocalShapeGeometryMatcher() {}

    static final int LOCAL_SHAPE_RADIUS = 4;

    public static class RelativeStone { public int dx, dy; public String color; }
    public static class AnchorLibertyProfile {
        public Map<String, Integer> adjacent = new LinkedHashMap<>();
        public Map<String, Integer> groups = new LinkedHashMap<>();
        public Map<String, Integer> minLiberties = new LinkedHashMap<>();
        { adjacent.put("B", 0); adjacent.put("W", 0); adjacent.put("empty", 0); adjacent.put("edge", 4);
          groups.put("B", 0); groups.put("W", 0); }
    }
    public static class GeometryMatchResult {
        public int score; public double ratio; public int matched, expected, libertyScore;
        public String transform, colorMode, queryAnchor, problemAnchor;
    }
    public static class ProblemEntry {
        public List<LocalPatternMatcher.BoardSnapshotStone> initialStones = new ArrayList<>();
        public List<TrainingMove> correctMoves = new ArrayList<>();
        public List<TrainingMove> failureMoves = new ArrayList<>();
        public String type;
        public String problemKind; // 'life_death' | 'tesuji'
        public String id, title, region, difficulty, objective, sourceKind;
        public List<String> tags = new ArrayList<>();
        public String teachingRecognition, teachingTesujiIdea, teachingExplanation, teachingMemoryCue, teachingFirstFeeling, teachingFirstHint;
        public String teachingFailureExplanation;
        public List<String> patternCardIds = new ArrayList<>();
        public String teachingFailureExplanation() { return teachingFailureExplanation; }
    }
    public static class TrainingMove { public String move, explanation, why; }
    public static class LocalWindow { public String anchor; public List<LocalPatternMatcher.BoardSnapshotStone> stones = new ArrayList<>(); }

    public static class KnowledgeMatchQuery {
        public int boardSize = 19;
        public String playedMove;
        public List<String> candidateMoves;
        public List<String> principalVariation;
        public List<LocalPatternMatcher.BoardSnapshotStone> boardSnapshot;
        public List<LocalWindow> localWindows;
    }

    interface RelativeTransform { int[] apply(int dx, int dy); String name(); }

    static final List<RelativeTransform> LOCAL_SHAPE_TRANSFORMS = Arrays.asList(
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{dx, dy}; } public String name() { return "identity"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{-dy, dx}; } public String name() { return "rot90"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{-dx, -dy}; } public String name() { return "rot180"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{dy, -dx}; } public String name() { return "rot270"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{-dx, dy}; } public String name() { return "flipX"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{dx, -dy}; } public String name() { return "flipY"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{dy, dx}; } public String name() { return "transpose"; } },
        new RelativeTransform() { public int[] apply(int dx, int dy) { return new int[]{-dy, -dx}; } public String name() { return "anti-transpose"; } }
    );

    static class Point { int row, col; }
    static Point gtpToPoint(String gtp, int boardSize) {
        if (gtp == null || gtp.isEmpty()) return null;
        Matcher mt = GTP.matcher(gtp.toUpperCase());
        if (!mt.find()) return null;
        int col = "ABCDEFGHJKLMNOPQRST".indexOf(mt.group(1));
        int row = Integer.parseInt(mt.group(2)) - 1;
        if (col < 0 || col >= boardSize || row < 0 || row >= boardSize) return null;
        Point p = new Point(); p.col = col; p.row = row; return p;
    }
    static final java.util.regex.Pattern GTP = java.util.regex.Pattern.compile("^([A-HJ-T])(\\d{1,2})$");

    static List<RelativeStone> stonesNearAnchor(List<LocalPatternMatcher.BoardSnapshotStone> stones, String anchor, int boardSize) {
        Point ap = gtpToPoint(anchor, boardSize);
        if (ap == null) return new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<RelativeStone> out = new ArrayList<>();
        for (LocalPatternMatcher.BoardSnapshotStone s : stones) {
            Point p = gtpToPoint(s.point, boardSize);
            if (p == null) continue;
            int dx = p.col - ap.col, dy = p.row - ap.row;
            if (dx == 0 && dy == 0) continue;
            if (Math.max(Math.abs(dx), Math.abs(dy)) > LOCAL_SHAPE_RADIUS) continue;
            String key = s.color + ":" + dx + ":" + dy;
            if (seen.contains(key)) continue;
            seen.add(key);
            RelativeStone r = new RelativeStone(); r.dx = dx; r.dy = dy; r.color = s.color; out.add(r);
        }
        return out;
    }

    static String stoneKey(RelativeStone s, RelativeTransform t, boolean swap) {
        int[] d = t.apply(s.dx, s.dy);
        String color = swap ? (s.color.equals("B") ? "W" : "B") : s.color;
        return color + ":" + d[0] + ":" + d[1];
    }

    static List<LocalPatternMatcher.BoardSnapshotStone> queryStonesForAnchor(KnowledgeMatchQuery q, String anchor) {
        if (q.localWindows != null) for (LocalWindow w : q.localWindows) if (w.anchor.equals(anchor) && !w.stones.isEmpty()) return w.stones;
        return q.boardSnapshot != null ? q.boardSnapshot : new ArrayList<>();
    }

    static String boardKey(int r, int c) { return r + "," + c; }

    static Map<String, String> localBoard(List<LocalPatternMatcher.BoardSnapshotStone> stones, int boardSize) {
        Map<String, String> b = new LinkedHashMap<>();
        for (LocalPatternMatcher.BoardSnapshotStone s : stones) { Point p = gtpToPoint(s.point, boardSize); if (p != null) b.put(boardKey(p.row, p.col), s.color); }
        return b;
    }

    static List<Point> neighbors(int r, int c, int boardSize) {
        List<Point> out = new ArrayList<>();
        int[][] d = {{-1,0},{1,0},{0,-1},{0,1}};
        for (int[] dd : d) { int nr = r + dd[0], nc = c + dd[1]; if (nr >= 0 && nc >= 0 && nr < boardSize && nc < boardSize) { Point p = new Point(); p.row = nr; p.col = nc; out.add(p); } }
        return out;
    }

    static List<Point> collectLocalGroup(Map<String, String> board, int r, int c, int boardSize) {
        String color = board.get(boardKey(r, c));
        if (color == null) return new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Point> group = new ArrayList<>();
        Deque<Point> stack = new ArrayDeque<>();
        Point start = new Point(); start.row = r; start.col = c; stack.push(start);
        while (!stack.isEmpty()) {
            Point cur = stack.pop();
            String key = boardKey(cur.row, cur.col);
            if (seen.contains(key) || !color.equals(board.get(key))) continue;
            seen.add(key); group.add(cur);
            for (Point n : neighbors(cur.row, cur.col, boardSize)) {
                if (color.equals(board.get(boardKey(n.row, n.col)))) stack.push(n);
            }
        }
        return group;
    }

    static int localLibertyCount(Map<String, String> board, List<Point> group, int boardSize) {
        Set<String> libs = new HashSet<>();
        for (Point s : group) for (Point n : neighbors(s.row, s.col, boardSize)) { String k = boardKey(n.row, n.col); if (!board.containsKey(k)) libs.add(k); }
        return libs.size();
    }

    static AnchorLibertyProfile anchorLibertyProfile(List<LocalPatternMatcher.BoardSnapshotStone> stones, String anchor, int boardSize) {
        Point ap = gtpToPoint(anchor, boardSize);
        if (ap == null) return null;
        Map<String, String> board = localBoard(stones, boardSize);
        if (board.containsKey(boardKey(ap.row, ap.col))) return null;
        AnchorLibertyProfile profile = new AnchorLibertyProfile();
        Set<String> visitedGroups = new HashSet<>();
        List<Point> vns = neighbors(ap.row, ap.col, boardSize);
        profile.adjacent.put("edge", 4 - vns.size());
        for (Point n : vns) {
            String key = boardKey(n.row, n.col);
            String color = board.get(key);
            if (color == null) { profile.adjacent.put("empty", profile.adjacent.get("empty") + 1); continue; }
            profile.adjacent.put(color, profile.adjacent.get(color) + 1);
            List<Point> group = collectLocalGroup(board, n.row, n.col, boardSize);
            String groupKey = group.stream().map(g -> boardKey(g.row, g.col)).sorted().collect(java.util.stream.Collectors.joining("|"));
            if (visitedGroups.contains(groupKey)) continue;
            visitedGroups.add(groupKey);
            profile.groups.put(color, profile.groups.get(color) + 1);
            int lib = localLibertyCount(board, group, boardSize);
            Integer prev = profile.minLiberties.get(color);
            profile.minLiberties.put(color, prev == null ? lib : Math.min(prev, lib));
        }
        return profile;
    }

    static String swappedColor(String c) { return c.equals("B") ? "W" : "B"; }
    static int profileValue(AnchorLibertyProfile p, String color, String field, boolean swap) {
        String sc = swap ? swappedColor(color) : color;
        if ("minLiberties".equals(field)) { Integer v = p.minLiberties.get(sc); return v == null ? 0 : v; }
        Map<String, Integer> m = "adjacent".equals(field) ? p.adjacent : p.groups;
        return m.getOrDefault(sc, 0);
    }
    static int closeCountScore(Integer qv, Integer ev, int exact, int near) {
        int q = qv == null ? 0 : qv, e = ev == null ? 0 : ev;
        if (q == e) return exact;
        if (Math.abs(q - e) <= 1) return near;
        return 0;
    }
    static int anchorProfileScore(AnchorLibertyProfile qp, AnchorLibertyProfile pp, boolean swap) {
        int score = 0;
        score += closeCountScore(qp.adjacent.get("empty"), pp.adjacent.get("empty"), 2, 1);
        score += closeCountScore(qp.adjacent.get("edge"), pp.adjacent.get("edge"), 2, 1);
        for (String color : new String[]{"B", "W"}) {
            score += closeCountScore(qp.adjacent.get(color), profileValue(pp, color, "adjacent", swap), 2, 1);
            score += closeCountScore(qp.groups.get(color), profileValue(pp, color, "groups", swap), 1, 0);
            Integer ql = qp.minLiberties.get(color), el = profileValue2(pp, color, swap);
            if (ql != null || el != null) score += closeCountScore(ql, el, 2, 1);
        }
        return score;
    }
    static Integer profileValue2(AnchorLibertyProfile p, String color, boolean swap) { return profileValue(p, color, "minLiberties", swap); }

    static List<String> uniqueValidPoints(List<String> moves, int boardSize) {
        Set<String> out = new LinkedHashSet<>();
        if (moves != null) for (String m : moves) if (m != null && gtpToPoint(m, boardSize) != null) out.add(m.toUpperCase());
        return new ArrayList<>(out);
    }

    /** 匹配单个 problem（对齐 localShapeGeometryMatch） */
    public static GeometryMatchResult match(ProblemEntry problem, KnowledgeMatchQuery query) {
        List<String> qAnchors = uniqueValidPoints(new ArrayList<>() {{
            if (query.playedMove != null) add(query.playedMove);
            if (query.candidateMoves != null) addAll(query.candidateMoves.stream().limit(6).toList());
            if (query.principalVariation != null) addAll(query.principalVariation.stream().limit(4).toList());
            if (query.localWindows != null) for (LocalWindow w : query.localWindows) add(w.anchor);
        }}, query.boardSize);
        List<String> pAnchors = uniqueValidPoints(problem.correctMoves.stream().limit(2).map(m -> m.move).toList(), query.boardSize);
        if (qAnchors.isEmpty() || pAnchors.isEmpty()) return null;

        GeometryMatchResult best = null;
        for (String qa : qAnchors) {
            List<LocalPatternMatcher.BoardSnapshotStone> qStones = queryStonesForAnchor(query, qa);
            AnchorLibertyProfile qProfile = anchorLibertyProfile(qStones, qa, query.boardSize);
            if (qProfile == null) continue;
            List<RelativeStone> qRel = stonesNearAnchor(qStones, qa, query.boardSize);
            if (qRel.size() < 3) continue;
            Set<String> qKeys = new HashSet<>();
            for (RelativeStone s : qRel) qKeys.add(stoneKey(s, LOCAL_SHAPE_TRANSFORMS.get(0), false));

            for (String pa : pAnchors) {
                AnchorLibertyProfile pProfile = anchorLibertyProfile(problem.initialStones, pa, query.boardSize);
                if (pProfile == null) continue;
                List<RelativeStone> pRel = stonesNearAnchor(problem.initialStones, pa, query.boardSize);
                if (pRel.size() < 3) continue;
                for (RelativeTransform t : LOCAL_SHAPE_TRANSFORMS) {
                    for (boolean swap : new boolean[]{false, true}) {
                        int matched = 0;
                        Set<String> transformedKeys = new HashSet<>();
                        for (RelativeStone s : pRel) transformedKeys.add(stoneKey(s, t, swap));
                        for (String k : transformedKeys) if (qKeys.contains(k)) matched++;
                        int expected = transformedKeys.size();
                        double rawRatio = expected > 0 ? (double) matched / expected : 0;
                        int libertyScore = anchorProfileScore(qProfile, pProfile, swap);
                        double profileRatio = Math.min(1, libertyScore / 12.0);
                        double ratio = (swap ? 0.92 : 1) * (rawRatio * 0.76 + profileRatio * 0.24);
                        if (matched < 3 || ratio < 0.55 || libertyScore < 3) continue;
                        int score = (int) Math.round(ratio * 24) + Math.min(matched, 8) + libertyScore;
                        GeometryMatchResult cand = new GeometryMatchResult();
                        cand.score = score; cand.ratio = ratio; cand.matched = matched; cand.expected = expected;
                        cand.libertyScore = libertyScore; cand.transform = t.name(); cand.colorMode = swap ? "color-swapped" : "same-color";
                        cand.queryAnchor = qa; cand.problemAnchor = pa;
                        if (best == null || cand.score > best.score || (cand.score == best.score && cand.ratio > best.ratio)) best = cand;
                    }
                }
            }
        }
        return best;
    }

    /** 在题库里找最佳几何匹配（用于 KnowledgeMatcher 的形状识别层） */
    public static GeometryMatchResult matchProblems(List<ProblemEntry> problems, KnowledgeMatchQuery query) {
        GeometryMatchResult best = null;
        for (ProblemEntry p : problems) {
            GeometryMatchResult r = match(p, query);
            if (r != null && (best == null || r.score > best.score)) best = r;
        }
        return best;
    }
}
