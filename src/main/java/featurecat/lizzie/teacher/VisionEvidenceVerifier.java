package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 对齐 GoAgent teacher/visionEvidenceVerifier.ts：校验 LLM 是否谎称看不到已附的棋盘图 */
public final class VisionEvidenceVerifier {

    private VisionEvidenceVerifier() {}

    public static class VisionEvidenceReport { public boolean required; public boolean attached; }
    public static class Issue { public String type, message, severity; }

    static final Pattern[] NO_BOARD_IMAGE_PATTERNS = new Pattern[]{
        Pattern.compile("没有棋盘图"),
        Pattern.compile("没有(?:提供|看到|收到).{0,8}(?:棋盘|图片|图像)"),
        Pattern.compile("看不到(?:棋盘|图片|图像)"),
        Pattern.compile("未提供(?:棋盘|图片|图像)"),
        Pattern.compile("无法看到(?:棋盘|图片|图像)"),
        Pattern.compile("no\\s+(?:board\\s+)?image", Pattern.CASE_INSENSITIVE),
        Pattern.compile("cannot\\s+see\\s+(?:the\\s+)?(?:board|image)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("no\\s+visual\\s+input", Pattern.CASE_INSENSITIVE)
    };

    public static List<Issue> verifyVisionEvidenceMarkdown(String markdown, VisionEvidenceReport report) {
        List<Issue> issues = new ArrayList<>();
        if (report == null) return issues;
        if (report.required && !report.attached) {
            issues.add(mk("required-image-missing", "该老师任务要求棋盘图，但 VisionEvidenceReport 显示没有附图。", "error"));
        }
        if (report.attached && markdown != null && NO_BOARD_IMAGE_PATTERNS.length > 0) {
            for (Pattern p : NO_BOARD_IMAGE_PATTERNS) if (p.matcher(markdown).find()) {
                issues.add(mk("false-no-board-image-claim", "本轮已附棋盘图，但老师回答声称没有棋盘图或看不到图片。", "error"));
                break;
            }
        }
        return issues;
    }

    static Issue mk(String type, String message, String severity) { Issue i = new Issue(); i.type = type; i.message = message; i.severity = severity; return i; }

    public static String buildVisionEvidenceRepairNote(List<Issue> issues) {
        if (issues == null || issues.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【棋盘图证据修正】\n");
        for (Issue i : issues) sb.append("- ").append(i.message).append("\n");
        sb.append("请删除“没有棋盘图/看不到图片”的说法，并基于本轮 visionEvidence、KataGo 数据和知识库重新表述。");
        return sb.toString();
    }
}
