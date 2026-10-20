package ar.emedicapp.catalog;

import ar.emedicapp.common.AppException;

import java.util.HashMap;
import java.util.Map;

public enum CatalogCategory {
    PRESCRIPTION,
    NON_PRESCRIPTION,
    SEXUAL_HEALTH,
    SKIN_CARE,
    BEAUTY,
    VITAMINS_SUPPLEMENTS,
    FIRST_AID,
    BABY,
    PERSONAL_CARE,
    MEDICAL_SUPPLIES,
    FOOD_DRINK,
    HOUSEHOLD;

    static Map<String, Object> normalize(Map<String, Object> metadata, boolean requiresPrescription) {
        var result = new HashMap<String, Object>(metadata == null ? Map.of() : metadata);
        CatalogCategory category = parse(result.get("catalogCategory"));
        if (requiresPrescription) category = PRESCRIPTION;
        else if (category == null || category == PRESCRIPTION) category = NON_PRESCRIPTION;
        result.put("catalogCategory", category.name());
        return result;
    }

    static void validate(Map<String, Object> metadata, boolean requiresPrescription) {
        if (metadata == null || !metadata.containsKey("catalogCategory")) return;
        CatalogCategory category = parse(metadata.get("catalogCategory"));
        if (category == null)
            throw AppException.badRequest(
                    "INVALID_CATALOG_CATEGORY", "Elegí una categoría válida para el catálogo.");
        if ((category == PRESCRIPTION) != requiresPrescription)
            throw AppException.badRequest(
                    "CATALOG_PRESCRIPTION_MISMATCH",
                    "La categoría de medicamentos con receta debe coincidir con el requisito de receta.");
    }

    private static CatalogCategory parse(Object value) {
        if (!(value instanceof String text)) return null;
        try {
            return valueOf(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
