package ar.emedicapp.catalog;

import ar.emedicapp.common.AppException;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Validates uploaded raster data before decoding and stores only a fresh, bounded PNG. */
final class ChainLogoImage {
    static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    static final int MAX_DATA_URL_LENGTH = ((MAX_SOURCE_BYTES + 2) / 3) * 4 + 23;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = (long) MAX_DIMENSION * MAX_DIMENSION;
    private static final int STORED_DIMENSION = 512;

    private ChainLogoImage() {}

    static byte[] normalize(String dataUrl) {
        if (dataUrl == null) return null;
        String expectedFormat;
        int separator;
        if (dataUrl.startsWith("data:image/png;base64,")) {
            expectedFormat = "png";
            separator = 22;
        } else if (dataUrl.startsWith("data:image/jpeg;base64,")) {
            expectedFormat = "jpeg";
            separator = 23;
        } else {
            throw invalid("Elegí una imagen PNG o JPG para el logo.");
        }
        if (dataUrl.length() > MAX_DATA_URL_LENGTH)
            throw invalid("El logo puede pesar hasta 2 MB.");
        byte[] source;
        try {
            source = Base64.getDecoder().decode(dataUrl.substring(separator));
        } catch (IllegalArgumentException malformed) {
            throw invalid("No se pudo leer el logo. Elegí una imagen PNG o JPG válida.");
        }
        if (source.length == 0 || source.length > MAX_SOURCE_BYTES)
            throw invalid("El logo debe ser una imagen de hasta 2 MB.");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("El archivo no es una imagen PNG o JPG válida.");
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(expectedFormat))
                    throw invalid("El formato del logo no coincide con el archivo.");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > MAX_DIMENSION
                        || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS)
                    throw invalid("El logo debe medir hasta 4096 píxeles por lado.");
                var image = reader.read(0);
                if (image == null) throw invalid("No se pudo leer la imagen del logo.");
                double scale = Math.min(1d, (double) STORED_DIMENSION / Math.max(width, height));
                var normalized =
                        new BufferedImage(
                                Math.max(1, (int) Math.round(width * scale)),
                                Math.max(1, (int) Math.round(height * scale)),
                                BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = normalized.createGraphics();
                try {
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(
                            image, 0, 0, normalized.getWidth(), normalized.getHeight(), null);
                } finally {
                    graphics.dispose();
                    image.flush();
                }
                try (var output = new ByteArrayOutputStream()) {
                    ImageIO.write(normalized, "png", output);
                    return output.toByteArray();
                } finally {
                    normalized.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid("No se pudo leer el logo. Elegí una imagen PNG o JPG válida.");
        }
    }

    private static AppException invalid(String message) {
        return AppException.badRequest("CHAIN_LOGO_INVALID", message);
    }
}
