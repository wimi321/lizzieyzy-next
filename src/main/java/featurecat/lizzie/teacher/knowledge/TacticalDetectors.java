package featurecat.lizzie.teacher.knowledge;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.Stone;
import java.util.*;

/**
 * 对齐 GoAgent knowledge/tacticalDetectors.ts：从棋盘状态检测战术信号
 * （气不足 / 切断点 / 眼形风险 / 先手逼迫）。需要 BoardGroup/BoardState，这里用 lizzieyzy Board 自算棋块与气。
 */
public final class TacticalDetectors {

    private TacticalDetectors() {}

    public static class BoardGroup {
        public String id; public String color; public List<String> stones = new ArrayList<>(); public List<String> liberties = new ArrayList<>();
    }
    public static class BoardState {
        public List<BoardGroup> groups = new ArrayList<>();
    }
    public static class TacticalSignal {
        public String type, confidence;
        public List<String> evidence = new ArrayList<>(), relatedMoves = new ArrayList<>();
    }

    static final String GTP = "ABCDEFGHJKLMNOPQRST";
    static String name(int x, int y, int size) { return GTP.substring(x, x + 1) + (size - y); }
    static int[] xy(String gtp, int size) {
        if (gtp == null) return null;
        var m = java.util.regex.Pattern.compile("^([A-HJ-T])(\\d{1,2})$").matcher(gtp.toUpperCase());
        if (!m.find()) return null;
        int x = GTP.indexOf(m.group(1)), y = size - Integer.parseInt(m.group(2));
        if (x < 0 || x >= size || y < 0 || y >= size) return null;
        return new int[]{x, y};
    }

    static boolean groupNearMove(BoardGroup g, Set<String> moves) {
        for (String s : g.stones) if (moves.contains(s.toUpperCase())) return true;
        for (String l : g.liberties) if (moves.contains(l.toUpperCase())) return true;
        return false;
    }
    static String confidenceFromCount(int count) { return count >= 2 ? "high" : count == 1 ? "medium" : "low"; }

    /** 从当前局面构建 BoardState（棋块 + 气） */
    public static BoardState buildBoardState() {
        BoardState st = new BoardState();
        try {
            var board = featurecat.lizzie.Lizzie.board;
            int size = Board.boardWidth;
            var data = board.getHistory().getEnd().getData();
            Stone[] stones = data.stones;
            boolean[][] visited = new boolean[size][size];
            int gid = 0;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                Stone s = stones[y * size + x];
                if (s == null || s == Stone.EMPTY || visited[y][x]) continue;
                BoardGroup g = new BoardGroup();
                g.id = "g" + (gid++); g.color = s.isBlack() ? "B" : "W";
                Deque<int[]> stack = new ArrayDeque<>(); stack.push(new int[]{x, y}); visited[y][x] = true;
                while (!stack.isEmpty()) {
                    int[] cur = stack.pop();
                    g.stones.add(name(cur[0], cur[1], size));
                    for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        int nx = cur[0] + d[0], ny = cur[1] + d[1];
                        if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue;
                        Stone ns = stones[ny * size + nx];
                        if (ns != null && ns.isBlack() == s.isBlack()) {
                            if (!visited[ny][nx]) { visited[ny][nx] = true; stack.push(new int[]{nx, ny}); }
                        } else if (ns == null || ns == Stone.EMPTY) {
                            String lib = name(nx, ny, size);
                            if (!g.liberties.contains(lib)) g.liberties.add(lib);
                        }
                    }
                }
                st.groups.add(g);
            }
        } catch (Exception e) { /* ignore */ }
        return st;
    }

    public static List<TacticalSignal> detectLibertyShortage(BoardState state, String[] anchors) {
        Set<String> a = anchors != null ? new HashSet<>(java.util.Arrays.asList(anchors)) : new HashSet<>();
        a = new HashSet<>(); for (String s : a) a.add(s.toUpperCase());
        List<BoardGroup> weak = new ArrayList<>();
        for (BoardGroup g : state.groups) if (g.liberties.size() <= 2 && (anchors == null || anchors.length == 0 || groupNearMove(g, a))) weak.add(g);
        if (weak.isEmpty()) return new ArrayList<>();
        TacticalSignal sig = new TacticalSignal();
        sig.type = "liberty-shortage"; sig.confidence = confidenceFromCount(weak.size());
        for (BoardGroup g : weak.subList(0, Math.min(4, weak.size())))
            sig.evidence.add(g.color + " group " + String.join("/", g.stones.subList(0, Math.min(3, g.stones.size()))) + " has " + g.liberties.size() + " liberties: " + String.join(",", g.liberties));
        Set<String> rm = new LinkedHashSet<>();
        for (BoardGroup g : weak) { rm.addAll(g.stones); rm.addAll(g.liberties); }
        sig.relatedMoves = new ArrayList<>(rm).subList(0, Math.min(12, rm.size()));
        return new ArrayList<>(Collections.singletonList(sig));
    }

    public static List<TacticalSignal> detectCutPoints(BoardState state, String[] anchors) {
        Set<String> a = anchors != null ? new HashSet<>() : new HashSet<>();
        if (anchors != null) for (String s : anchors) a.add(s.toUpperCase());
        List<String> candidates = new ArrayList<>();
        for (BoardGroup g : state.groups) {
            if (anchors != null && anchors.length > 0 && !groupNearMove(g, a)) continue;
            for (String lib : g.liberties) {
                List<BoardGroup> friendly = new ArrayList<>(), enemy = new ArrayList<>();
                for (BoardGroup o : state.groups) {
                    if (o.id.equals(g.id)) continue;
                    if (o.liberties.contains(lib)) { if (o.color.equals(g.color)) friendly.add(o); else enemy.add(o); }
                }
                if (friendly.size() >= 1 && enemy.size() >= 1) candidates.add(lib);
            }
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(candidates));
        if (unique.isEmpty()) return new ArrayList<>();
        TacticalSignal sig = new TacticalSignal();
        sig.type = "cut-or-connection-point"; sig.confidence = unique.size() >= 2 ? "high" : "medium";
        for (String p : unique.subList(0, Math.min(6, unique.size()))) sig.evidence.add(p + " is a shared liberty between friendly connection and enemy pressure.");
        sig.relatedMoves = unique.subList(0, Math.min(12, unique.size()));
        return new ArrayList<>(Collections.singletonList(sig));
    }

    public static List<TacticalSignal> detectEyeShapeRisk(BoardState state, String[] anchors) {
        Set<String> a = anchors != null ? new HashSet<>() : new HashSet<>();
        if (anchors != null) for (String s : anchors) a.add(s.toUpperCase());
        List<BoardGroup> cands = new ArrayList<>();
        for (BoardGroup g : state.groups)
            if (g.liberties.size() >= 2 && g.liberties.size() <= 4 && g.stones.size() >= 3 && (anchors == null || anchors.length == 0 || groupNearMove(g, a)))
                cands.add(g);
        if (cands.isEmpty()) return new ArrayList<>();
        TacticalSignal sig = new TacticalSignal();
        sig.type = "eye-shape-risk"; sig.confidence = cands.size() >= 2 ? "medium" : "low";
        for (BoardGroup g : cands.subList(0, Math.min(3, cands.size())))
            sig.evidence.add(g.color + " group " + String.join("/", g.stones.subList(0, Math.min(4, g.stones.size()))) + " has compact eye-space candidates " + String.join(",", g.liberties) + ".");
        Set<String> rm = new LinkedHashSet<>();
        for (BoardGroup g : cands) rm.addAll(g.liberties);
        sig.relatedMoves = new ArrayList<>(rm).subList(0, Math.min(10, rm.size()));
        return new ArrayList<>(Collections.singletonList(sig));
    }

    public static List<TacticalSignal> detectSenteGoteHints(BoardState state, String[] anchors) {
        Set<String> a = anchors != null ? new HashSet<>() : new HashSet<>();
        if (anchors != null) for (String s : anchors) a.add(s.toUpperCase());
        List<BoardGroup> atari = new ArrayList<>();
        for (BoardGroup g : state.groups)
            if (g.liberties.size() == 1 && (anchors == null || anchors.length == 0 || groupNearMove(g, a))) atari.add(g);
        if (atari.isEmpty()) return new ArrayList<>();
        TacticalSignal sig = new TacticalSignal();
        sig.type = "sente-gote-forcing-move"; sig.confidence = atari.size() >= 2 ? "high" : "medium";
        for (BoardGroup g : atari.subList(0, Math.min(4, atari.size())))
            sig.evidence.add(g.color + " group at " + String.join("/", g.stones.subList(0, Math.min(3, g.stones.size()))) + " is in atari; " + g.liberties.get(0) + " is forcing.");
        Set<String> rm = new LinkedHashSet<>();
        for (BoardGroup g : atari) rm.addAll(g.liberties);
        sig.relatedMoves = new ArrayList<>(rm);
        return new ArrayList<>(Collections.singletonList(sig));
    }

    public static List<TacticalSignal> detectTacticalSignals(BoardState state, String[] anchors) {
        List<TacticalSignal> signals = new ArrayList<>();
        signals.addAll(detectLibertyShortage(state, anchors));
        signals.addAll(detectCutPoints(state, anchors));
        signals.addAll(detectEyeShapeRisk(state, anchors));
        signals.addAll(detectSenteGoteHints(state, anchors));
        Map<String, Integer> rank = new HashMap<>(); rank.put("high", 3); rank.put("medium", 2); rank.put("low", 1);
        signals.sort((x, y) -> (rank.get(y.confidence) - rank.get(x.confidence)) != 0 ? (rank.get(y.confidence) - rank.get(x.confidence)) : (y.evidence.size() - x.evidence.size()));
        return signals.size() > 6 ? signals.subList(0, 6) : signals;
    }
}
