package ar.emedicapp.catalog;

import static org.assertj.core.api.Assertions.*;

import ar.emedicapp.common.AppException;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

class ChainLogoImageTest {
    @Test
    void normalizesPngWithTransparencyAndFitsLargeImageTo512Pixels() throws Exception {
        var original = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
        original.setRGB(512, 256, Color.RED.getRGB());
        byte[] result = ChainLogoImage.normalize(dataUrl(original, "png"));
        var image = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).isEqualTo(256);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(image.getRGB(0, 0) >>> 24).isZero();
    }

    @Test
    void convertsJpegToPngWithoutUpscaling() throws Exception {
        var original = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        byte[] result = ChainLogoImage.normalize(dataUrl(original, "jpeg"));
        assertThat(result).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        var image = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(image.getWidth()).isEqualTo(40);
        assertThat(image.getHeight()).isEqualTo(20);
    }

    @Test
    void stripsEmbeddedTextMetadata() throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        var metadata =
                writer.getDefaultImageMetadata(
                        javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null);
        var root = new IIOMetadataNode("javax_imageio_png_1.0");
        var text = new IIOMetadataNode("tEXt");
        var entry = new IIOMetadataNode("tEXtEntry");
        entry.setAttribute("keyword", "Description");
        entry.setAttribute("value", "private-source-metadata");
        text.appendChild(entry);
        root.appendChild(text);
        metadata.mergeTree("javax_imageio_png_1.0", root);
        var source = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(source)) {
            writer.setOutput(output);
            writer.write(null, new javax.imageio.IIOImage(image, null, metadata), null);
        } finally {
            writer.dispose();
        }
        assertThat(new String(source.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains("private-source-metadata");
        byte[] result =
                ChainLogoImage.normalize(
                        "data:image/png;base64,"
                                + Base64.getEncoder().encodeToString(source.toByteArray()));
        assertThat(new String(result, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("private-source-metadata", "tEXt");
    }

    @Test
    void rejectsUnsupportedSpoofedMalformedAndEmptyPayloads() throws Exception {
        for (String value :
                new String[] {
                    "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                    "data:image/gif;base64,R0lGODlh",
                    "data:image/png;base64,not base64",
                    "data:image/png;base64,",
                    "data:image/png;base64,PHN2Zz48L3N2Zz4=",
                    dataUrl(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpeg")
                            .replace("image/jpeg", "image/png")
                }) assertInvalid(value);
        assertThat(ChainLogoImage.normalize(null)).isNull();
    }

    @Test
    void rejectsEncodedAndDecodedSizeOverflow() {
        assertInvalid("data:image/png;base64," + "A".repeat(ChainLogoImage.MAX_DATA_URL_LENGTH));
        assertInvalid(
                "data:image/png;base64,"
                        + Base64.getEncoder()
                                .encodeToString(new byte[ChainLogoImage.MAX_SOURCE_BYTES + 1]));
    }

    @Test
    void rejectsDimensionsBeforeDecodingRaster() throws Exception {
        var source =
                Base64.getDecoder()
                        .decode(
                                dataUrl(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png")
                                        .substring(22));
        assertInvalid(withDimensions(source.clone(), 4097, 1));
        assertInvalid(withDimensions(source.clone(), 1, 4097));
    }

    private static String withDimensions(byte[] source, int width, int height) {
        ByteBuffer.wrap(source).putInt(16, width).putInt(20, height);
        var crc = new CRC32();
        crc.update(source, 12, 17);
        ByteBuffer.wrap(source).putInt(29, (int) crc.getValue());
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(source);
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> ChainLogoImage.normalize(value))
                .isInstanceOf(AppException.class)
                .extracting(error -> ((AppException) error).code())
                .isEqualTo("CHAIN_LOGO_INVALID");
    }

    private static String dataUrl(BufferedImage image, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return "data:image/"
                + format
                + ";base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
