package ar.emedicapp.catalog;

final class ProductImage {
    static final int MAX_SOURCE_BYTES = CatalogImage.MAX_SOURCE_BYTES;
    static final int MAX_DATA_URL_LENGTH = CatalogImage.MAX_DATA_URL_LENGTH;
    private static final CatalogImage NORMALIZER = new CatalogImage("PRODUCT_IMAGE_INVALID");

    private ProductImage() {}

    static byte[] normalize(String dataUrl) {
        return NORMALIZER.normalize(dataUrl);
    }
}
