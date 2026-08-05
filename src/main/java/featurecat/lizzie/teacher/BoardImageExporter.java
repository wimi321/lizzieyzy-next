package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * 对齐 GoAgent 的 boardImageDataUrl：把当前棋盘渲染成 PNG 的 base64 data URL，供多模态 LLM 作为视觉证据。
 * 不截屏幕——直接用 BoardRenderer 把当前局面画到 BufferedImage（与 GoAgent 用 canvas 渲染棋盘图等价）。
 */
public final class BoardImageExporter {

    private BoardImageExporter() {}

    /** 导出当前显示节点的棋盘为 PNG data URL；失败返回 null */
    public static String exportCurrentBoard(int size) {
        try {
            BoardRenderer renderer = Lizzie.frame.boardRenderer;
            if (renderer == null) return null;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            // 背景填白，避免透明区域
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, size, size);
            renderer.draw(g);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/png;base64," + base64;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
