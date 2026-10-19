package ar.emedicapp.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.common.Json;
import ar.emedicapp.config.OperationalConfigService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

class StockPolicyTest {
    @Test
    void physicalCountCannotDestroyAnExistingReservation() {
        var current = new CatalogService.StockState(10, 7, 4, "ARS");
        assertThrows(AppException.class, () -> CatalogService.validateStockChange(current, 6, 4));
        assertDoesNotThrow(() -> CatalogService.validateStockChange(current, 7, 4));
    }

    @Test
    void staleOperatorCannotOverwriteAConcurrentReservationOrCount() {
        var current = new CatalogService.StockState(10, 1, 5, "ARS");
        assertThrows(AppException.class, () -> CatalogService.validateStockChange(current, 15, 4));
        assertDoesNotThrow(() -> CatalogService.validateStockChange(current, 15, 5));
    }

    @Test
    void missingVersionIsNotAnUnconditionalOverwrite() {
        assertThrows(
                AppException.class,
                () ->
                        CatalogService.validateStockChange(
                                new CatalogService.StockState(0, 0, 1, "ARS"), 0, 0));
    }

    @Test
    void anotherBranchIsRejectedBeforeAnyStockRead() {
        var access = mock(Access.class);
        var jdbc = mock(JdbcClient.class);
        var branch = UUID.randomUUID();
        doThrow(AppException.forbidden("BRANCH_ACCESS_DENIED", "Sin acceso"))
                .when(access)
                .requireBranchRole(branch, "ADMIN", "PHARMACY_ADMIN", "PHARMACY", "PHARMACIST");
        var service =
                new CatalogService(
                        jdbc,
                        access,
                        mock(AuditLog.class),
                        mock(Json.class),
                        mock(OperationalConfigService.class),
                        java.time.Clock.systemUTC());
        assertThrows(AppException.class, () -> service.stock(branch));
        verifyNoInteractions(jdbc);
    }
}
