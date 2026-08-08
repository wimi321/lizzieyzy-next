package featurecat.lizzie.teacher.knowledge;

import java.util.*;

/**
 * 对齐 GoAgent knowledge/localPatternMatcher.ts（275 行）：通用局部模式匹配引擎。
 * 用 ShapePatternCard 的 points/constraints/antiPatterns + 8 种旋转/镜像变换，在当前棋盘上以 anchor 为中心匹配棋形。
 * 与 MotifRecognizer 的 elite 卡快速路径互补：这里更通用，支持任意 ShapePatternCards（elite/joseki/pattern-cards）。
 */
public final class LocalPatternMatcher {

    private LocalPatternMatcher() {}

    public enum LocalPatternPointState { friendly, enemy, empty, black, white, any_stone }
    public enum LocalPatternConfidence { strong, medium, weak }

    public static class LocalPatternPoint { public int dx, dy; public LocalPatternPointState state; public boolean required = true; }
    public static class LocalPatternConstraint { public String type; public int value; }
    public static class ShapePatternCard {
        public String id, title, shapeType, category, anchorRole, sourceQuality;
        public List<String> phase = new ArrayList<>(), regions = new ArrayList<>(), tags = new ArrayList<>(), sourceRefs = new ArrayList<>();
        public Double minScore;
        public List<LocalPatternPoint> points = new ArrayList<>();
        public List<LocalPatternConstraint> constraints = new ArrayList<>();
        public List<LocalPatternPoint> antiPatterns = new ArrayList<>();
        public Teaching teaching = new Teaching();
    }
    public static class Teaching { public String recognition, wrongThinking, correctThinking, drillPrompt; }
    public static class LocalPatternMatcherInput {
        public int boardSize = 19;
        public List<BoardSnapshotStone> boardSnapshot = new ArrayList<>();
        public List<LocalWindow> localWindows = new ArrayList<>();
        public List<String> anchors = new ArrayList<>();
        public String playerColor; public String phase;
    }
    public static class BoardSnapshotStone { public String point; public String color; }
    public static class LocalWindow { public String anchor; public List<BoardSnapshotStone> stones = new ArrayList<>(); }
    public static class LocalPatternMatch {
        public ShapePatternCard card; public String confidence, anchor, transform, perspective;
        public double score, matchedPoints, requiredPoints;
        public List<String> evidence = new ArrayList<>(), counterEvidence = new ArrayList<>();
    }

    static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
    static class Transform {
        String name;
        java.util.function.BiFunction<Integer,Integer,int[]> fn;
        Transform(String n, java.util.function.BiFunction<Integer,Integer,int[]> f){ name=n; fn=f; }
        int[] apply(int dx, int dy){ return fn.apply(dx, dy); }
    }
    static final Transform[] TRANSFORMS = new Transform[]{
        new Transform("identity", (dx,dy)->new int[]{dx,dy}),
        new Transform("rotate90", (dx,dy)->new int[]{-dy,dx}),
        new Transform("rotate180", (dx,dy)->new int[]{-dx,-dy}),
        new Transform("rotate270", (dx,dy)->new int[]{dy,-dx}),
        new Transform("mirror-x", (dx,dy)->new int[]{-dx,dy}),
        new Transform("mirror-y", (dx,dy)->new int[]{dx,-dy}),
        new Transform("mirror-main-diagonal", (dx,dy)->new int[]{dy,dx}),
        new Transform("mirror-anti-diagonal", (dx,dy)->new int[]{-dy,-dx})
    };

    static class RC { int row, col; RC(int r,int c){row=r;col=c;} }
    static RC gtpToCoord(String point, int boardSize) {
        if (point == null) return null;
        var m = java.util.regex.Pattern.compile("^([A-HJ-Z])(\\d{1,2})$").matcher(point.trim().toUpperCase());
        if (!m.find()) return null;
        int col = GTP_COLUMNS.substring(0, boardSize).indexOf(m.group(1));
        int number = Integer.parseInt(m.group(2));
        if (col < 0 || number < 1 || number > boardSize) return null;
        return new RC(boardSize - number, col);
    }
    static String key(int row, int col) { return row + "," + col; }
    static Map<String,String> buildBoard(List<BoardSnapshotStone> stones, int boardSize) {
        Map<String,String> board = new HashMap<>();
        if (stones != null) for (BoardSnapshotStone s : stones) { RC c = gtpToCoord(s.point, boardSize); if (c != null) board.put(key(c.row, c.col), s.color); }
        return board;
    }
    static String opposite(String color) { return "B".equals(color) ? "W" : "B"; }
    static boolean stateMatches(String actual, LocalPatternPointState expected, String perspective) {
        if (expected == LocalPatternPointState.empty) return actual == null;
        if (expected == LocalPatternPointState.any_stone) return actual != null;
        if (expected == LocalPatternPointState.black) return "B".equals(actual);
        if (expected == LocalPatternPointState.white) return "W".equals(actual);
        if (expected == LocalPatternPointState.friendly) return actual != null && actual.equals(perspective);
        return actual != null && actual.equals(opposite(perspective));
    }
    static List<RC> neighbors(int row, int col, int boardSize) {
        List<RC> out = new ArrayList<>();
        int[][] d = {{ -1,0},{1,0},{0,-1},{0,1}};
        for (int[] dd : d) { int r=row+dd[0], c=col+dd[1]; if (r>=0&&c>=0&&r<boardSize&&c<boardSize) out.add(new RC(r,c)); }
        return out;
    }
    static int libertyCount(Map<String,String> board, int row, int col, int boardSize) {
        String color = board.get(key(row,col)); if (color == null) return 0;
        Set<String> seen = new HashSet<>(), libs = new HashSet<>();
        Deque<RC> stack = new ArrayDeque<>(); stack.push(new RC(row,col));
        while (!stack.isEmpty()) {
            RC cur = stack.pop(); String ck = key(cur.row, cur.col);
            if (seen.contains(ck) || !color.equals(board.get(ck))) continue;
            seen.add(ck);
            for (RC nx : neighbors(cur.row, cur.col, boardSize)) {
                String nk = key(nx.row, nx.col), nc = board.get(nk);
                if (nc == null) libs.add(nk); else if (nc.equals(color)) stack.push(nx);
            }
        }
        return libs.size();
    }
    static String pointRegion(int row, int col, int boardSize) {
        int x = Math.min(col, boardSize - 1 - col), y = Math.min(row, boardSize - 1 - row);
        if (x <= 5 && y <= 5) return "corner";
        if (Math.min(x, y) <= 3) return "side";
        return "center";
    }
    static boolean phaseMatches(ShapePatternCard card, String phase) {
        return card.phase.contains("any") || phase == null || card.phase.contains(phase);
    }
    static boolean regionMatches(ShapePatternCard card, RC anchor, int boardSize) {
        return card.regions.contains("any") || card.regions.contains(pointRegion(anchor.row, anchor.col, boardSize));
    }
    static boolean constraintSatisfied(LocalPatternConstraint constraint, Map<String,String> board, RC anchor, int boardSize, String perspective) {
        if ("anchor-empty".equals(constraint.type)) return (board.containsKey(key(anchor.row, anchor.col))) == (constraint.value == 0);
        if ("edge-distance".equals(constraint.type)) {
            int edge = Math.min(Math.min(anchor.row, anchor.col), Math.min(boardSize - 1 - anchor.row, boardSize - 1 - anchor.col));
            return edge <= constraint.value;
        }
        String wanted = "min-friendly-liberties".equals(constraint.type) ? perspective : opposite(perspective);
        int best = 0;
        for (Map.Entry<String,String> e : board.entrySet()) {
            if (!wanted.equals(e.getValue())) continue;
            String[] parts = e.getKey().split(","); int r = Integer.parseInt(parts[0]), c = Integer.parseInt(parts[1]);
            if (Math.max(Math.abs(r - anchor.row), Math.abs(c - anchor.col)) <= 2) best = Math.max(best, libertyCount(board, r, c, boardSize));
        }
        return best >= constraint.value;
    }

    static LocalPatternMatch matchPatternAt(ShapePatternCard card, Map<String,String> board, String anchorName, int boardSize, String perspective) {
        RC anchor = gtpToCoord(anchorName, boardSize);
        if (anchor == null || !regionMatches(card, anchor, boardSize)) return null;
        LocalPatternMatch best = null;
        for (Transform transform : TRANSFORMS) {
            double matched = 0; double required = 0;
            List<String> evidence = new ArrayList<>(), counterEvidence = new ArrayList<>();
            for (LocalPatternPoint point : card.points) {
                int[] t = transform.apply(point.dx, point.dy);
                int row = anchor.row + t[1], col = anchor.col + t[0];
                boolean requiredPoint = point.required;
                if (requiredPoint) required += 1;
                if (row < 0 || col < 0 || row >= boardSize || col >= boardSize) { if (requiredPoint) counterEvidence.add("required point off board dx=" + point.dx + ",dy=" + point.dy); continue; }
                String actual = board.get(key(row, col));
                if (stateMatches(actual, point.state, perspective)) { matched += requiredPoint ? 1 : 0.5; evidence.add(point.state + "@" + point.dx + "," + point.dy); }
                else if (requiredPoint) counterEvidence.add("expected " + point.state + "@" + point.dx + "," + point.dy);
            }
            boolean antiHit = card.antiPatterns.stream().anyMatch(p -> {
                int[] t = transform.apply(p.dx, p.dy);
                int row = anchor.row + t[1], col = anchor.col + t[0];
                if (row < 0 || col < 0 || row >= boardSize || col >= boardSize) return false;
                return stateMatches(board.get(key(row, col)), p.state, perspective);
            });
            if (antiHit) counterEvidence.add("anti-pattern matched");
            boolean constraintsOk = card.constraints.stream().allMatch(c -> constraintSatisfied(c, board, anchor, boardSize, perspective));
            if (!constraintsOk) counterEvidence.add("constraint failed");
            double ratio = required > 0 ? matched / required : 0;
            int rawScore = (int) Math.round(ratio * 20 + Math.min(8, matched) + (constraintsOk ? 4 : 0) - (antiHit ? 8 : 0));
            double minScore = card.minScore != null ? card.minScore : 16;
            if (rawScore < minScore || counterEvidence.size() > Math.max(1, (int) (required - matched + 1))) continue;
            String confidence = rawScore >= 26 ? "strong" : rawScore >= 20 ? "medium" : "weak";
            LocalPatternMatch candidate = new LocalPatternMatch();
            candidate.card = card; candidate.confidence = confidence; candidate.score = rawScore;
            candidate.anchor = anchorName; candidate.transform = transform.name; candidate.perspective = perspective;
            candidate.matchedPoints = matched; candidate.requiredPoints = required;
            evidence.add(0, "anchor=" + anchorName); evidence.add(1, "transform=" + transform.name); evidence.add(2, "perspective=" + perspective);
            candidate.evidence = evidence.subList(0, Math.min(10, evidence.size()));
            candidate.counterEvidence = counterEvidence.subList(0, Math.min(6, counterEvidence.size()));
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best;
    }

    public static List<LocalPatternMatch> findLocalPatternMatches(List<ShapePatternCard> cards, LocalPatternMatcherInput input) {
        Map<String,String> board = buildBoard(input.boardSnapshot, input.boardSize);
        Set<String> anchors = new LinkedHashSet<>(input.anchors);
        if (input.localWindows != null) for (LocalWindow w : input.localWindows) if (w.anchor != null) anchors.add(w.anchor);
        anchors.remove(null);
        Set<String> perspectives = new LinkedHashSet<>();
        if (input.playerColor != null) perspectives.add(input.playerColor);
        perspectives.add("B"); perspectives.add("W");
        List<LocalPatternMatch> matches = new ArrayList<>();
        for (ShapePatternCard card : cards) {
            if (!phaseMatches(card, input.phase)) continue;
            for (String anchor : anchors) for (String perspective : perspectives) {
                LocalPatternMatch m = matchPatternAt(card, board, anchor, input.boardSize, perspective);
                if (m != null) matches.add(m);
            }
        }
        matches.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) : a.card.title.compareTo(b.card.title));
        List<LocalPatternMatch> dedup = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LocalPatternMatch m : matches) { String k = m.card.id + "|" + m.anchor; if (seen.add(k)) dedup.add(m); }
        return dedup.size() > 12 ? dedup.subList(0, 12) : dedup;
    }
}
