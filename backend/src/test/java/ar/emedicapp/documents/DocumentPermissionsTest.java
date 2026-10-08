package ar.emedicapp.documents;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.config.OperationalConfigService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentPermissionsTest {
    private Access access;
    private DocumentRepository repository;
    private DocumentService service;
    private UUID id;
    private UUID owner;

    @BeforeEach
    void setup() {
        access = mock(Access.class);
        repository = mock(DocumentRepository.class);
        service =
                new DocumentService(
                        access,
                        repository,
                        mock(DocumentStorage.class),
                        mock(AuditLog.class),
                        mock(OperationalConfigService.class),
                        mock(DocumentUploadLimits.class));
        id = UUID.randomUUID();
        owner = UUID.randomUUID();
        when(repository.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentFile(
                                        id,
                                        owner,
                                        "PRESCRIPTION",
                                        null,
                                        "receta.pdf",
                                        "application/pdf",
                                        20,
                                        id.toString(),
                                        null,
                                        Instant.now())));
    }

    @Test
    void adminRoleDoesNotGrantClinicalAccess() {
        when(access.actor())
                .thenReturn(
                        new Actor(
                                UUID.randomUUID(),
                                "admin@example.test",
                                "Admin",
                                Set.of("ADMIN"),
                                Set.of()));
        assertThatThrownBy(() -> service.metadata(id))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void pharmacistNeedsActualOrderAndBranchPermission() {
        UUID pharmacist = UUID.randomUUID();
        when(access.actor())
                .thenReturn(
                        new Actor(
                                pharmacist,
                                "pharmacist@example.test",
                                "Profesional",
                                Set.of("PHARMACIST"),
                                Set.of(UUID.randomUUID())));
        when(repository.clinicalOrderAccess(id, pharmacist)).thenReturn(false);
        assertThatThrownBy(() -> service.metadata(id)).isInstanceOf(AppException.class);
        when(repository.clinicalOrderAccess(id, pharmacist)).thenReturn(true);
        assertThat(service.metadata(id).id()).isEqualTo(id);
    }

    @Test
    void driverHasNoClinicalAccess() {
        when(access.actor())
                .thenReturn(
                        new Actor(
                                UUID.randomUUID(),
                                "driver@example.test",
                                "Cadete",
                                Set.of("DRIVER"),
                                Set.of(UUID.randomUUID())));
        assertThatThrownBy(() -> service.metadata(id)).isInstanceOf(AppException.class);
    }

    @Test
    void uploaderCanReadOwnDocumentWithoutClinicalRole() {
        when(access.actor())
                .thenReturn(
                        new Actor(
                                owner, "buyer@example.test", "Persona", Set.of("BUYER"), Set.of()));
        assertThat(service.metadata(id).id()).isEqualTo(id);
        verify(repository, never()).clinicalOrderAccess(any(), any());
    }

    @Test
    void revokedGlobalCommercialRoleDeniesForeignBranchDocument() {
        UUID user = UUID.randomUUID(), branch = UUID.randomUUID();
        when(access.actor())
                .thenReturn(
                        new Actor(
                                user,
                                "former@example.test",
                                "Persona",
                                Set.of("BUYER"),
                                Set.of(branch)));
        when(repository.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentFile(
                                        id,
                                        owner,
                                        "PAYMENT_RECEIPT",
                                        branch,
                                        "comprobante.pdf",
                                        "application/pdf",
                                        20,
                                        id.toString(),
                                        null,
                                        Instant.now())));
        when(repository.commercialBranchAccess(branch, user)).thenReturn(true);
        assertThatThrownBy(() -> service.metadata(id))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    void healthCoverageBelongsToUploaderAndDoesNotGrantCommercialAccess() {
        healthCoverageDocument();
        when(access.actor())
                .thenReturn(
                        new Actor(
                                owner, "buyer@example.test", "Titular", Set.of("BUYER"), Set.of()));
        assertThat(service.metadata(id).id()).isEqualTo(id);
        when(repository.commercialBranchAccess(any(), any())).thenReturn(true);
        for (String role : Set.of("ADMIN", "PHARMACY", "DRIVER")) {
            when(access.actor())
                    .thenReturn(
                            new Actor(
                                    UUID.randomUUID(),
                                    "other@example.test",
                                    "Otra persona",
                                    Set.of(role),
                                    Set.of(UUID.randomUUID())));
            assertThatThrownBy(() -> service.metadata(id))
                    .as("La credencial clínica no está disponible para el rol %s", role)
                    .isInstanceOfSatisfying(
                            AppException.class,
                            error -> assertThat(error.code()).isEqualTo("DOCUMENT_ACCESS_DENIED"));
        }
        verify(repository, never()).commercialBranchAccess(any(), any());
        verify(repository, never()).clinicalOrderAccess(any(), any());
    }

    @Test
    void healthCoverageRequiresScopedClinicalPermissionForPharmacist() {
        healthCoverageDocument();
        UUID pharmacist = UUID.randomUUID();
        when(access.actor())
                .thenReturn(
                        new Actor(
                                pharmacist,
                                "pharmacist@example.test",
                                "Profesional",
                                Set.of("PHARMACIST"),
                                Set.of(UUID.randomUUID())));
        when(repository.clinicalOrderAccess(id, pharmacist)).thenReturn(false);
        assertThatThrownBy(() -> service.metadata(id))
                .isInstanceOfSatisfying(
                        AppException.class,
                        error -> assertThat(error.code()).isEqualTo("DOCUMENT_ACCESS_DENIED"));
        when(repository.clinicalOrderAccess(id, pharmacist)).thenReturn(true);
        assertThat(service.metadata(id).id()).isEqualTo(id);
        verify(repository, times(2)).clinicalOrderAccess(id, pharmacist);
        verify(repository, never()).commercialBranchAccess(any(), any());
    }

    private void healthCoverageDocument() {
        when(repository.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentFile(
                                        id,
                                        owner,
                                        "HEALTH_COVERAGE",
                                        null,
                                        "credencial.pdf",
                                        "application/pdf",
                                        20,
                                        id.toString(),
                                        null,
                                        Instant.now())));
    }
}
