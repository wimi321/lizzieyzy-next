package featurecat.lizzie.teacher.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对齐 GoAgent knowledge/josekiSgfDatabase.ts：
 * 从打包的 SGF 定式库（pachi + josekle）解析出定式线，构建 JosekiPatternCard。
 * 不依赖 electron/node：从 classpath 读取 resources/knowledge/joseki-sgf/ 下 SGF。
 */
public final class JosekiSgfDatabase {

    private JosekiSgfDatabase() {}

    static final int BOARD_SIZE = 19;
    static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";

    public static class SgfMove {
        public char color; // 'B' or 'W'
        public String sgf;  // SGF coord like "qd"
        public String gtp;  // GTP coord like "Q4"
        public String relative; // "rx-ry"
        public String comment;
    }

    public static class BundledJosekiSource {
        public String id;
        public String dir;
        public String displayName;
        public String license;
        public String sourceQuality;
        public String url;
        public Map<String, String> familyByFile;
    }

    private static final List<BundledJosekiSource> BUNDLED_SOURCES = new ArrayList<>();
    static {
        BundledJosekiSource pachi = new BundledJosekiSource();
        pachi.id = "pachi-joseki-gpl2"; pachi.dir = "pachi";
        pachi.displayName = "Pachi joseki SGF set";
        pachi.license = "GPL-2.0-only";
        pachi.sourceQuality = "bundled-open-source-gpl";
        pachi.url = "https://github.com/pasky/pachi/tree/master/joseki";
        pachi.familyByFile = new HashMap<>();
        pachi.familyByFile.put("joseki_33.sgf", "Pachi san-san 3-3 joseki");
        pachi.familyByFile.put("joseki_34.sgf", "Pachi komoku 3-4 joseki");
        pachi.familyByFile.put("joseki_44.sgf", "Pachi hoshi 4-4 joseki");
        pachi.familyByFile.put("joseki_54.sgf", "Pachi takamoku 5-4 joseki");
        BUNDLED_SOURCES.add(pachi);
        BundledJosekiSource josekle = new BundledJosekiSource();
        josekle.id = "josekle-mit-dictionary"; josekle.dir = "josekle";
        josekle.displayName = "Josekle dictionary SGF";
        josekle.license = "MIT";
        josekle.sourceQuality = "bundled-open-source-mit";
        josekle.url = "https://github.com/okonomichiyaki/josekle/tree/master/sgf";
        josekle.familyByFile = new HashMap<>();
        josekle.familyByFile.put("dictionary.sgf", "Josekle joseki explorer dictionary");
        BUNDLED_SOURCES.add(josekle);
        BundledJosekiSource aiJoseki = new BundledJosekiSource();
        aiJoseki.id = "ai-joseki-daquan"; aiJoseki.dir = "ai-joseki";
        aiJoseki.displayName = "AI围棋定式大全（江维杰）";
        aiJoseki.license = "user-provided";
        aiJoseki.sourceQuality = "user-provided-book";
        aiJoseki.url = "";
        aiJoseki.familyByFile = new HashMap<>();
        aiJoseki.familyByFile.put("ai_joseki_daquan.sgf", "AI围棋定式大全");
        BUNDLED_SOURCES.add(aiJoseki);
    }

    // ---- SGF 解析（对齐 extractMovePathsFromSgf）----
    static class ParseNode {
        SgfMove move;
        int next;
    }

    static ParseNode parseNode(String input, int start) {
        ParseNode r = new ParseNode();
        int i = start;
        StringBuilder prop = new StringBuilder();
        char color = 0;
        String coord = "";
        String comment = "";
        while (i < input.length()) {
            char ch = input.charAt(i);
            if (ch == ';' || ch == '(' || ch == ')') break;
            if (ch == '[') {
                int end = input.indexOf(']', i);
                if (end < 0) break;
                String val = input.substring(i + 1, end);
                if (prop.toString().equals("B") || prop.toString().equals("W")) {
                    color = prop.charAt(0);
                    coord = val;
                } else if (prop.toString().equals("C")) {
                    comment = val;
                }
                prop.setLength(0);
                i = end + 1;
                continue;
            } else if (ch == ']' || ch == '\n' || ch == '\r' || ch == '\t') {
                i++;
            } else {
                prop.append(ch);
                i++;
            }
        }
        r.next = i;
        if (color != 0 && !coord.equals("")) {
            SgfMove m = new SgfMove();
            m.color = color;
            m.sgf = coord;
            m.comment = comment;
            Point p = pointFromSgf(coord);
            if (p != null) { m.gtp = p.gtp; m.relative = p.relative; }
            r.move = m;
        }
        return r;
    }

    static class Point {
        int x, yFromTop;
        String gtp, relative;
        Point(int x, int yFromTop) {
            this.x = x; this.yFromTop = yFromTop;
            this.gtp = GTP_COLUMNS.charAt(x) + "" + (BOARD_SIZE - yFromTop);
            int yFromBottom = BOARD_SIZE - 1 - yFromTop;
            this.relative = Math.min(x + 1, BOARD_SIZE - x) + "-" + Math.min(yFromBottom + 1, BOARD_SIZE - yFromBottom);
        }
    }

    static Point pointFromSgf(String coord) {
        if (coord == null || coord.length() < 2) return null;
        String sgf = coord.trim().toLowerCase();
        if (sgf.equals("tt") || sgf.length() < 2) return null;
        // 对齐 GoAgent moveFromSgf：SGF 坐标是双字母，charCodeAt-97 转 0-based
        int x = sgf.charAt(0) - 'a';
        int yFromTop = sgf.charAt(1) - 'a';
        if (x < 0 || x >= BOARD_SIZE || yFromTop < 0 || yFromTop >= BOARD_SIZE) return null;
        return new Point(x, yFromTop);
    }

    public static List<List<SgfMove>> extractMovePathsFromSgf(String input, int maxMoves) {
        List<List<SgfMove>> paths = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<List<SgfMove>> stack = new ArrayList<>();
        List<SgfMove> current = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char ch = input.charAt(i);
            if (ch == '(') {
                stack.add(new ArrayList<>(current));
                i++;
                continue;
            }
            if (ch == ')') {
                remember(paths, seen, current, maxMoves);
                current = stack.isEmpty() ? new ArrayList<>() : stack.remove(stack.size() - 1);
                i++;
                continue;
            }
            if (ch == ';') {
                ParseNode parsed = parseNode(input, i + 1);
                if (parsed.move != null) {
                    current = new ArrayList<>(current);
                    current.add(parsed.move);
                    if (current.size() > maxMoves) current = current.subList(0, maxMoves);
                    remember(paths, seen, current, maxMoves);
                }
                i = parsed.next;
                continue;
            }
            i++;
        }
        remember(paths, seen, current, maxMoves);
        return paths;
    }

    private static void remember(List<List<SgfMove>> paths, Set<String> seen, List<SgfMove> current, int maxMoves) {
        if (current.size() < 2) return;
        List<SgfMove> clipped = current.size() > maxMoves ? current.subList(0, maxMoves) : current;
        String key = uniquePathKey(clipped);
        if (seen.contains(key)) return;
        seen.add(key);
        paths.add(new ArrayList<>(clipped));
    }

    private static String uniquePathKey(List<SgfMove> path) {
        StringBuilder sb = new StringBuilder();
        for (SgfMove m : path) sb.append(m.color).append(m.sgf).append(" ");
        return sb.toString().trim();
    }

    static String stableHash(String value) {
        int hash = (int) 2166136261L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash = hash * 16777619;
        }
        return Integer.toUnsignedString(hash, 36);
    }

    static String labelForNextMove(SgfMove move, BundledJosekiSource source) {
        List<String> tags = new ArrayList<>();
        String c = move.comment == null ? "" : move.comment;
        if (c.contains("<dont>") || c.contains("<don't>") || c.contains("<bad>") || c.contains("<avoid>"))
            tags.add("avoid/deviation response");
        if (c.contains("<later>") || c.contains("<tenuki>")) tags.add("tenuki follow-up");
        return source.displayName + " branch" + (tags.isEmpty() ? "" : " (" + String.join(", ", tags) + ")");
    }

    static String familyForFile(BundledJosekiSource source, String filename, SgfMove firstMove) {
        if (source.familyByFile != null && source.familyByFile.containsKey(filename))
            return source.familyByFile.get(filename);
        if (firstMove != null && firstMove.relative != null)
            return source.displayName + " " + firstMove.relative + " family";
        return source.displayName;
    }

    static class CardAccumulator {
        String id, family, file;
        BundledJosekiSource source;
        List<SgfMove> prefixMoves = new ArrayList<>();
        Map<String, JosekiRecognizer.JosekiNextMove> nextMoves = new LinkedHashMap<>();
        int pathCount = 0;
    }

    static List<JosekiRecognizer.JosekiPatternCard> buildCardsForFile(BundledJosekiSource source, String filename, String content) {
        List<List<SgfMove>> paths = extractMovePathsFromSgf(content, 32);
        Map<String, CardAccumulator> byPrefix = new LinkedHashMap<>();
        for (List<SgfMove> path : paths) {
            int maxPrefix = Math.min(12, path.size() - 1);
            for (int prefixLength = 2; prefixLength <= maxPrefix; prefixLength++) {
                List<SgfMove> prefix = path.subList(0, prefixLength);
                SgfMove next = path.get(prefixLength);
                if (next == null) continue;
                String id = source.id + ":" + filename + ":" + stableHash(prefixKey(prefix));
                CardAccumulator entry = byPrefix.get(id);
                if (entry == null) {
                    entry = new CardAccumulator();
                    entry.id = id; entry.source = source; entry.family = familyForFile(source, filename, path.get(0));
                    entry.file = filename; entry.prefixMoves = new ArrayList<>(prefix);
                    byPrefix.put(id, entry);
                }
                entry.pathCount++;
                if (!entry.nextMoves.containsKey(next.relative)) {
                    JosekiRecognizer.JosekiNextMove nm = new JosekiRecognizer.JosekiNextMove();
                    nm.relativeMove = next.relative;
                    nm.label = labelForNextMove(next, source);
                    nm.condition = next.comment == null ? null : next.comment.replaceAll("\\s+", " ").trim();
                    if (nm.condition != null && nm.condition.length() > 120) nm.condition = nm.condition.substring(0, 120);
                    entry.nextMoves.put(next.relative, nm);
                }
            }
        }
        List<JosekiRecognizer.JosekiPatternCard> out = new ArrayList<>();
        for (CardAccumulator entry : byPrefix.values()) {
            JosekiRecognizer.JosekiPatternCard card = new JosekiRecognizer.JosekiPatternCard();
            card.id = entry.id;
            StringBuilder seq = new StringBuilder();
            Set<String> relStones = new LinkedHashSet<>();
            for (SgfMove m : entry.prefixMoves) {
                relStones.add(m.relative);
                seq.append(m.color).append(m.relative).append(" → ");
            }
            if (seq.length() > 0) seq.setLength(seq.length() - 3);
            card.name = entry.family + ": " + seq.toString();
            card.family = entry.family;
            card.boardSize = BOARD_SIZE;
            card.sourceRefs = new String[] { entry.source.id };
            card.sourceQuality = entry.source.sourceQuality;
            card.requiredRelativeStones = relStones.toArray(new String[0]);
            List<String> seqSignals = new ArrayList<>();
            seqSignals.add(entry.family);
            seqSignals.add(filename.replace(".sgf", ""));
            seqSignals.addAll(relStones);
            card.sequenceSignals = seqSignals.toArray(new String[0]);
            card.variationCount = Math.max(entry.nextMoves.size(), entry.pathCount);
            card.commonNextMoves = new ArrayList<>(entry.nextMoves.values());
            card.variations = new ArrayList<>();
            for (JosekiRecognizer.JosekiNextMove nm : card.commonNextMoves)
                card.variations.add(seq + " → " + nm.relativeMove);
            card.recognition = "Bundled SGF database match from " + entry.source.displayName
                + ". Treat this as a joseki-tree hypothesis and verify the final recommendation against KataGo for the current whole-board position.";
            card.wrongThinking = "Do not memorize this branch mechanically; joseki choice depends on ladder status, neighboring stones, sente value, and whole-board direction.";
            card.correctThinking = "Identify the corner family, compare the SGF branch candidates with KataGo candidates, then explain whether the player should continue the local sequence, tenuki, simplify, or choose a different direction.";
            card.drillPrompt = "Cover the next move and ask: which branch keeps the right direction in this whole-board position, and what outside stones would change the answer?";
            out.add(card);
        }
        return out;
    }

    private static String prefixKey(List<SgfMove> prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < prefix.size(); i++) {
            if (i > 0) sb.append("-");
            sb.append(prefix.get(i).color).append(prefix.get(i).relative);
        }
        return sb.toString();
    }

    public static List<JosekiRecognizer.JosekiPatternCard> loadBundledJosekiSgfCards() {
        List<JosekiRecognizer.JosekiPatternCard> cards = new ArrayList<>();
        ClassLoader cl = JosekiSgfDatabase.class.getClassLoader();
        for (BundledJosekiSource source : BUNDLED_SOURCES) {
            String dir = "knowledge/joseki-sgf/" + source.dir + "/";
            // 用类加载器枚举资源目录下 sgf（简化：直接尝试已知文件名）
            List<String> files = listSgfFiles(dir);
            for (String file : files) {
                try (InputStream is = cl.getResourceAsStream(dir + file)) {
                    if (is == null) continue;
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    cards.addAll(buildCardsForFile(source, file, content));
                } catch (IOException e) { /* skip */ }
            }
        }
        // 去重
        Map<String, JosekiRecognizer.JosekiPatternCard> seen = new LinkedHashMap<>();
        for (JosekiRecognizer.JosekiPatternCard c : cards) if (!seen.containsKey(c.id)) seen.put(c.id, c);
        return new ArrayList<>(seen.values());
    }

    private static List<String> listSgfFiles(String dir) {
        // 已知文件名（来自 GoAgent data/knowledge/joseki-sgf）
        if (dir.contains("/pachi/")) return Arrays.asList("joseki_33.sgf", "joseki_34.sgf", "joseki_44.sgf", "joseki_54.sgf");
        if (dir.contains("/josekle/")) return Arrays.asList("dictionary.sgf");
        if (dir.contains("/ai-joseki/")) return Arrays.asList("ai_joseki_daquan.sgf");
        return new ArrayList<>();
    }

    /** 对齐 TS summarizeBundledJosekiSgfCards */
    public static class BundledJosekiSummary { public int sourceCount, cardCount; public List<String> sources = new ArrayList<>(); }
    public static BundledJosekiSummary summarizeBundledJosekiSgfCards() {
        BundledJosekiSummary sum = new BundledJosekiSummary();
        List<JosekiRecognizer.JosekiPatternCard> cards = loadBundledJosekiSgfCards();
        Set<String> srcs = new TreeSet<>();
        for (JosekiRecognizer.JosekiPatternCard c : cards) if (c.sourceRefs != null) for (String s2 : c.sourceRefs) srcs.add(s2);
        sum.sources = new ArrayList<>(srcs);
        sum.sourceCount = sum.sources.size();
        sum.cardCount = cards.size();
        return sum;
    }
}
