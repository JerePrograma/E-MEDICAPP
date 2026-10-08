package ar.emedicapp.documents;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.Actor;
import ar.emedicapp.common.AppException;
import ar.emedicapp.common.AuditLog;
import ar.emedicapp.common.Json;
import ar.emedicapp.config.OperationalConfigService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
class DocumentQuotaIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired Json json;
    @Autowired OperationalConfigService config;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path directory;
    private final List<UUID> users = new ArrayList<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        values.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        values.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @AfterEach
    void removeIsolatedRecords() {
        for (UUID user : users) {
            jdbc.sql("DELETE FROM document WHERE owner_user_id=:user").param("user", user).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:user").param("user", user).update();
        }
    }

    @Test
    void concurrentUsersCannotExceedTheSharedDatabaseQuota() throws Exception {
        var repository = new DocumentRepository(jdbc, json);
        var storage = new DocumentStorage(directory.toString(), 0);
        Actor first = actor(), second = actor();
        byte[] pdf = "%PDF-1.4\nFICTICIO".getBytes();
        long initialBytes = repository.storageUsage(first.id()).totalBytes();
        var limits =
                new DocumentUploadLimits(
                        1024 * 1024, initialBytes + pdf.length, "", Clock.systemUTC());
        var firstService = service(first, repository, storage, limits);
        var secondService = service(second, repository, storage, limits);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> upload(firstService, pdf, ready, start));
            var two = executor.submit(() -> upload(secondService, pdf, ready, start));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("UPLOADED", "DOCUMENT_TOTAL_STORAGE_LIMIT");
        }
        assertThat(repository.storageUsage(first.id()).totalBytes())
                .isEqualTo(initialBytes + pdf.length);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM document WHERE owner_user_id IN (:users)")
                                .param("users", users)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO app_user(id,email,name,password_hash,birth_date) VALUES(:id,:email,'Cuenta aislada de prueba','disabled','1990-01-01')")
                .param("id", id)
                .param("email", id + "@quota.example.test")
                .update();
        users.add(id);
        return new Actor(
                id, id + "@quota.example.test", "Cuenta aislada", Set.of("BUYER"), Set.of());
    }

    private DocumentService service(
            Actor actor,
            DocumentRepository repository,
            DocumentStorage storage,
            DocumentUploadLimits limits) {
        var access = mock(Access.class);
        when(access.actor()).thenReturn(actor);
        return new DocumentService(
                access, repository, storage, mock(AuditLog.class), config, limits);
    }

    private String upload(
            DocumentService service, byte[] pdf, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("No se inició la carga concurrente.");
        try {
            new TransactionTemplate(transactions)
                    .execute(
                            status -> {
                                try {
                                    service.upload(
                                            new MockMultipartFile(
                                                    "file",
                                                    "documento.pdf",
                                                    "application/pdf",
                                                    pdf),
                                            "EVIDENCIA",
                                            null);
                                } catch (IOException exception) {
                                    throw new UncheckedIOException(exception);
                                }
                                return null;
                            });
            return "UPLOADED";
        } catch (AppException exception) {
            return exception.code();
        }
    }
}
