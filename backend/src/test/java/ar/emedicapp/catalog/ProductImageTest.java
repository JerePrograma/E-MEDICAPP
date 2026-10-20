package ar.emedicapp.catalog;

import static org.assertj.core.api.Assertions.*;

import ar.emedicapp.common.AppException;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

class ProductImageTest {
    @Test
    void storesBoundedPngFromPngAndJpegAndAllowsNoPhoto() throws Exception {
        for (String format : new String[] {"png", "jpeg"}) {
            byte[] result = ProductImage.normalize(image(1024, 512, format));
            var decoded = ImageIO.read(new ByteArrayInputStream(result));
            assertThat(result).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
            assertThat(decoded.getWidth()).isEqualTo(512);
            assertThat(decoded.getHeight()).isEqualTo(256);
        }
        assertThat(ProductImage.normalize(null)).isNull();
    }

    @Test
    void rejectsMalformedUnsupportedSpoofedAndOversizedPhotos() throws Exception {
        for (String invalid : new String[] {
                "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                "data:image/png;base64,not base64",
                "data:image/png;base64,",
                "data:image/png;base64,PHN2Zz48L3N2Zz4=",
                image(1, 1, "jpeg").replace("image/jpeg", "image/png"),
                "data:image/png;base64," + "A".repeat(ProductImage.MAX_DATA_URL_LENGTH),
                "data:image/png;base64," + Base64.getEncoder().encodeToString(
                        new byte[ProductImage.MAX_SOURCE_BYTES + 1])
        }) assertInvalid(invalid);
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecodingImage() throws Exception {
        byte[] source = Base64.getDecoder().decode(image(1, 1, "png").substring(22));
        ByteBuffer.wrap(source).putInt(16, 4097);
        var crc = new CRC32();
        crc.update(source, 12, 17);
        ByteBuffer.wrap(source).putInt(29, (int) crc.getValue());
        assertInvalid("data:image/png;base64," + Base64.getEncoder().encodeToString(source));
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> ProductImage.normalize(value))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).code())
                .isEqualTo("PRODUCT_IMAGE_INVALID");
    }

    private static String image(int width, int height, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return "data:image/" + format + ";base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
