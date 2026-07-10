package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class RemoteComputeQrDependencyTest {
  @Test
  void qrShareCodeRoundTripsWithoutOptionalCliOrTiffLibraries() throws Exception {
    String shareCode = "wss://compute.example.test/session/abc123";
    BitMatrix matrix =
        new QRCodeWriter().encode(shareCode, BarcodeFormat.QR_CODE, 192, 192);
    BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

    Result decoded =
        new MultiFormatReader()
            .decode(
                new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image))));

    assertEquals(shareCode, decoded.getText());
  }
}
