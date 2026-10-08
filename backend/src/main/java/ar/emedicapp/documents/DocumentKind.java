package ar.emedicapp.documents;

import ar.emedicapp.common.AppException;
import java.util.Locale;

public enum DocumentKind {
    RECETA("PRESCRIPTION"),
    AUTORIZACION("FAMILY_AUTHORIZATION"),
    COBERTURA("HEALTH_COVERAGE"),
    FACTURA("INVOICE"),
    EVIDENCIA("PAYMENT_RECEIPT"),
    CAPACIDAD("CAPABILITY"),
    OTRO("OTHER"),
    ENTREGA("DELIVERY_PROOF");

    private final String databaseValue;

    DocumentKind(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public boolean health() {
        return this == RECETA || this == AUTORIZACION || this == COBERTURA;
    }

    public static DocumentKind parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw AppException.badRequest(
                    "INVALID_DOCUMENT_KIND",
                    "Elegí receta, autorización, factura, evidencia, capacidad, entrega u otro documento.");
        }
    }

    public static String publicValue(String databaseValue) {
        return switch (databaseValue) {
            case "PRESCRIPTION" -> "RECETA";
            case "FAMILY_AUTHORIZATION" -> "AUTORIZACION";
            case "HEALTH_COVERAGE" -> "COBERTURA";
            case "INVOICE" -> "FACTURA";
            case "CAPABILITY" -> "CAPACIDAD";
            case "OTHER" -> "OTRO";
            case "DELIVERY_PROOF" -> "ENTREGA";
            default -> "EVIDENCIA";
        };
    }
}
