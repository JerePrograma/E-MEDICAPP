package ar.emedicapp.catalog;

final class ChainLogoImage {
    static final int MAX_SOURCE_BYTES = CatalogImage.MAX_SOURCE_BYTES;
    static final int MAX_DATA_URL_LENGTH = CatalogImage.MAX_DATA_URL_LENGTH;
    private static final CatalogImage NORMALIZER = new CatalogImage("CHAIN_LOGO_INVALID");

    private ChainLogoImage() {}

    static byte[] normalize(String dataUrl) {
        return NORMALIZER.normalize(dataUrl);
    }
}
