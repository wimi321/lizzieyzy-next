package featurecat.lizzie.teacher.analysis;

/**
 * 对齐 GoAgent 的 scorePerspective.ts：胜率/目差视角校准工具。
 * KataGo 约定：scoreLead 为"黑方领先目数"（black-positive）；winrate 为"当前轮到走棋方胜率"。
 * 提供把数据转到"指定方视角"的工具，避免手拍视角导致符号错乱。
 */
public final class ScorePerspective {

    private ScorePerspective() {}

    /** 黑正约定下的目差，转到指定方视角（B 不变，W 取负） */
    public static double scoreLeadForColor(double blackScoreLead, boolean blackToMove) {
        return blackToMove ? blackScoreLead : -blackScoreLead;
    }

    /** 当前走棋方胜率 → 指定方胜率（对手方视角需 1 - winrate） */
    public static double winrateForColor(double currentMoverWinrate, boolean forCurrentMover) {
        // 百分比 0-100
        return forCurrentMover ? currentMoverWinrate : (100.0 - currentMoverWinrate);
    }

    public static double round(double value, int digits) {
        if (!Double.isFinite(value)) return 0;
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    /** 把"落子后局面（轮到对手）"的数据，转成"实战手（落子方）视角" */
    public static double winrateFromAfterMove(double afterMoveWinrate) {
        // afterMoveWinrate 是落子后轮到对手的胜率（对手方视角，百分比 0-100）
        // → 实战手方 = 100 - it
        return 100.0 - afterMoveWinrate;
    }

    public static double scoreLeadFromAfterMove(double afterMoveBlackLead, boolean playedByBlack) {
        // afterMoveBlackLead 是"黑正"约定、落子后局面的目差
        // 实战手方视角：若实战手是黑则直接用，是白则取负
        return scoreLeadForColor(afterMoveBlackLead, playedByBlack);
    }

    /** 对齐 GoAgent scoreSummaryFromBlackLead：KataGoScoreSummary（leader/leadPoints/text 中文文案） */
    public static class ScoreSummary {
        public String signConvention = "black-positive";
        public String perspectiveColor;
        public Double perspectiveScoreLead;
        public Double blackScoreLead;
        public Double whiteScoreLead;
        public String leader; // 'even' | 'B' | 'W'
        public double leadPoints;
        public String text;
    }

    public static ScoreSummary scoreSummaryFromBlackLead(Double blackScoreLead, String perspectiveColor) {
        double blackLead = (blackScoreLead != null && Double.isFinite(blackScoreLead)) ? blackScoreLead : 0;
        double leadPoints = round(Math.abs(blackLead), 1);
        String leader = leadPoints < 0.1 ? "even" : blackLead > 0 ? "B" : "W";
        String text = leader.equals("even")
            ? "双方目差接近均势"
            : (leader.equals("B") ? "黑" : "白") + "领先约 " + leadPoints + " 目";
        ScoreSummary s = new ScoreSummary();
        s.perspectiveColor = perspectiveColor;
        s.perspectiveScoreLead = perspectiveColor != null ? round(scoreLeadForColor(blackLead, perspectiveColor.equals("B")), 2) : null;
        s.blackScoreLead = round(blackLead, 2);
        s.whiteScoreLead = round(-blackLead, 2);
        s.leader = leader;
        s.leadPoints = leadPoints;
        s.text = text;
        return s;
    }
}
