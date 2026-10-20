package ar.emedicapp.reports;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ar.emedicapp.appbootstrap.DemoIds;
import ar.emedicapp.auth.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

@SpringBootTest(properties = "emedicapp.mode=DEMO")
@EnabledIfEnvironmentVariable(named = "EMEDICAPP_TEST_DB_URL", matches = ".+")
@Transactional
class SalesIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired UserRepository users;
    private MockMvc mvc;
    private UUID own, other, driverBranch, staff, beneficiary;
    private UserDetails principal;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("EMEDICAPP_TEST_DB_URL"));
        values.add("spring.datasource.username", () -> System.getenv().getOrDefault("EMEDICAPP_TEST_DB_USER", "emedicapp"));
        values.add("spring.datasource.password", () -> System.getenv("EMEDICAPP_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        own = branch();
        other = branch();
        driverBranch = branch();
        staff = UUID.randomUUID();
        sql("INSERT INTO app_user(id,email,name,password_hash,birth_date,email_verified) VALUES(?,?,'Reportes QA','unused','1985-01-01',true)", staff, staff + "@reports.invalid");
        sql("INSERT INTO user_role(user_id,role) VALUES(?,'PHARMACY'),(?,'DRIVER')", staff, staff);
        sql("INSERT INTO membership(id,user_id,branch_id,role) VALUES(?,?,?,'PHARMACY'),(?,?,?,'DRIVER')", UUID.randomUUID(), staff, own, UUID.randomUUID(), staff, driverBranch);
        principal = users.loadUserByUsername(staff + "@reports.invalid");
        beneficiary = UUID.randomUUID();
        sql("INSERT INTO beneficiary(id,owner_user_id,name,birth_date) VALUES(?,?,'Beneficiario QA','1985-01-01')", beneficiary, buyer());
    }

    @Test
    void usesConfirmationDateKeepsCurrenciesSeparateAndDoesNotEraseCancelledSales() throws Exception {
        UUID cancelled = order(own, "CANCELLED");
        UUID first = payment(cancelled, own, "100.10", "ARS", "PARTIALLY_REFUNDED", "2026-10-20T03:00:00Z");
        payment(cancelled, own, "25.25", "ARS", "VERIFIED", "2026-10-20T14:00:00Z");
        payment(order(own, "PAID"), own, "10.50", "USD", "VERIFIED", "2026-10-20T15:00:00Z");
        payment(order(own, "PAID"), own, "900", "ARS", "VERIFIED", "2026-10-20T02:59:59Z");
        payment(order(own, "PAID"), own, "99", "ARS", "VERIFIED", "2026-10-21T03:00:00Z");
        payment(order(own, "ACCEPTED"), own, "999", "ARS", "PENDING", null);
        payment(order(own, "ACCEPTED"), own, "888", "ARS", "SUBMITTED", "2026-10-20T12:00:00Z");
        refund(first, "40.15", "VERIFIED", "2026-10-22T04:00:00Z");
        refund(first, "12", "REQUESTED", null);
        payment(order(other, "PAID"), other, "5000", "ARS", "VERIFIED", "2026-10-20T12:00:00Z");

        var report = report("DAY", "2026-10-20", own);
        assertThat(report.get("from").asText()).isEqualTo("2026-10-20T03:00:00Z");
        assertThat(report.get("toExclusive").asText()).isEqualTo("2026-10-21T03:00:00Z");
        assertThat(report.get("timeZone").asText()).isEqualTo("America/Argentina/Mendoza");
        assertThat(report.get("totals").size()).isEqualTo(2);
        var ars = report.get("totals").get(0);
        assertThat(ars.get("currency").asText()).isEqualTo("ARS");
        assertThat(ars.get("gross").asText()).isEqualTo("125.35");
        assertThat(ars.get("net").asText()).isEqualTo("125.35");
        assertThat(ars.get("refunds").asText()).isEqualTo("0.00");
        assertThat(ars.get("paymentCount").asInt()).isEqualTo(2);
        assertThat(ars.get("paidOrders").asInt()).isEqualTo(1);
        assertThat(report.get("totals").get(1).get("gross").asText()).isEqualTo("10.50");
        assertThat(report.get("buckets").size()).isEqualTo(24);
        assertThat(report.get("buckets").get(0).get("totals").get(0).get("gross").asText()).isEqualTo("100.10");
        assertThat(report.get("buckets").get(1).get("totals").get(0).get("gross").asText()).isEqualTo("0.00");
        assertThat(report.get("byBranch").size()).isEqualTo(1);
        assertThat(report.get("movementCount").asInt()).isEqualTo(3);
        assertThat(report.get("movementsTruncated").asBoolean()).isFalse();
        assertThat(report.get("movements").size()).isEqualTo(3);
        assertThat(report.get("movements").toString()).doesNotContain(other.toString());

        var later = report("DAY", "2026-10-22", own);
        var refundTotals = later.get("totals").get(0);
        assertThat(refundTotals.get("gross").asText()).isEqualTo("0.00");
        assertThat(refundTotals.get("refunds").asText()).isEqualTo("40.15");
        assertThat(refundTotals.get("net").asText()).isEqualTo("-40.15");
        assertThat(refundTotals.get("paidOrders").asInt()).isZero();
        assertThat(later.get("movements").get(0).get("kind").asText()).isEqualTo("REFUND");
    }

    @Test
    void calendarWeekMonthAndYearIncludeOnlyTheirLocalBoundaries() throws Exception {
        UUID order = order(own, "PAID");
        payment(order, own, "5", "ARS", "VERIFIED", "2025-12-29T03:00:00Z");
        payment(order, own, "10", "ARS", "VERIFIED", "2026-01-01T03:00:00Z");
        payment(order, own, "20", "ARS", "VERIFIED", "2026-02-01T03:00:00Z");
        payment(order, own, "30", "ARS", "VERIFIED", "2026-12-31T02:59:59Z");
        payment(order, own, "100", "ARS", "VERIFIED", "2027-01-01T03:00:00Z");
        var week = report("WEEK", "2026-01-01", own);
        assertThat(week.get("from").asText()).isEqualTo("2025-12-29T03:00:00Z");
        assertThat(week.get("toExclusive").asText()).isEqualTo("2026-01-05T03:00:00Z");
        assertThat(week.get("totals").get(0).get("gross").asText()).isEqualTo("15.00");
        assertThat(week.get("buckets").size()).isEqualTo(7);
        var month = report("MONTH", "2026-01-20", own);
        assertThat(month.get("totals").get(0).get("gross").asText()).isEqualTo("10.00");
        assertThat(month.get("buckets").size()).isEqualTo(31);
        var year = report("YEAR", "2026-10-20", own);
        assertThat(year.get("totals").get(0).get("gross").asText()).isEqualTo("60.00");
        assertThat(year.get("buckets").size()).isEqualTo(12);
        assertThat(year.get("buckets").get(1).get("totals").get(0).get("gross").asText()).isEqualTo("20.00");
    }

    @Test
    void checksActualMembershipRolesAndRevocationWithoutLeakingOtherBranches() throws Exception {
        mvc.perform(get("/api/reports/sales")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reports/sales").with(user(users.loadUserByUsername("comprador@demo.invalid"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/sales").with(user(users.loadUserByUsername("cadete@demo.invalid"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/sales").param("branchId", other.toString()).with(user(principal)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/sales").param("branchId", driverBranch.toString()).with(user(principal)))
                .andExpect(status().isForbidden());
        var scoped = report("MONTH", "2026-10-20", null);
        assertThat(scoped.get("branches").size()).isEqualTo(1);
        assertThat(scoped.get("branches").get(0).get("id").asText()).isEqualTo(own.toString());
        mvc.perform(get("/api/reports/sales").param("branchId", other.toString()).with(user(users.loadUserByUsername("admin@demo.invalid"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/sales").param("branchId", DemoIds.id("branch:centro").toString()).with(user(users.loadUserByUsername("adminfarmacia@demo.invalid"))))
                .andExpect(status().isOk());
        sql("UPDATE membership SET active=false WHERE user_id=? AND branch_id=?", staff, own);
        var revoked = report("MONTH", "2026-10-20", null);
        assertThat(revoked.get("branches").size()).isZero();
        assertThat(revoked.get("movementCount").asInt()).isZero();
        mvc.perform(get("/api/reports/sales").param("branchId", own.toString()).with(user(principal)))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyReportsHaveCalendarBucketsAndRejectInvalidParameters() throws Exception {
        var empty = report("MONTH", "2024-02-29", own);
        assertThat(empty.get("totals").size()).isZero();
        assertThat(empty.get("buckets").size()).isEqualTo(29);
        assertThat(empty.get("movements").size()).isZero();
        assertThat(empty.get("movementCount").asInt()).isZero();
        for (var invalid : new String[] {"1899-01-01", "9999-01-01", "invalid", "2026-02-30"})
            mvc.perform(get("/api/reports/sales").param("date", invalid).with(user(principal)))
                    .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/sales").param("period", "ALL").with(user(principal)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void movementPreviewLimitNeverTruncatesFinancialTotals() throws Exception {
        UUID order = order(own, "PAID");
        jdbc.sql("""
                INSERT INTO payment(id,order_id,branch_id,amount,currency,status,external_reference,verified_by,verified_at)
                SELECT md5(:prefix || i::text)::uuid,:order,:branch,1.01,'ARS','VERIFIED',:prefix || i::text,:actor,'2026-10-20T12:00:00Z'::timestamptz
                FROM generate_series(1,501) i
                """)
                .param("prefix", UUID.randomUUID().toString())
                .param("order", order)
                .param("branch", own)
                .param("actor", staff)
                .update();
        var report = report("DAY", "2026-10-20", own);
        assertThat(report.get("movements").size()).isEqualTo(500);
        assertThat(report.get("movementCount").asInt()).isEqualTo(501);
        assertThat(report.get("movementsTruncated").asBoolean()).isTrue();
        assertThat(report.get("totals").get(0).get("gross").asText()).isEqualTo("506.01");
        assertThat(report.get("totals").get(0).get("paidOrders").asInt()).isEqualTo(1);
    }

    private JsonNode report(String period, String date, UUID branch) throws Exception {
        var request = get("/api/reports/sales").with(user(principal)).param("period", period).param("date", date);
        if (branch != null) request.param("branchId", branch.toString());
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID branch() {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO branch(id,name,address,latitude,longitude) VALUES(?,'Sucursal reportes QA','Domicilio QA',-32.89,-68.85)", id);
        return id;
    }

    private UUID order(UUID branch, String status) {
        UUID purchase = UUID.randomUUID(), order = UUID.randomUUID();
        sql("INSERT INTO purchase(id,buyer_user_id,beneficiary_id,mode,latitude,longitude,status,total) VALUES(?,?,?,'PICKUP',-32.89,-68.85,'ACCEPTED',1000)", purchase, buyer(), beneficiary);
        sql("INSERT INTO child_order(id,purchase_id,branch_id,status,total) VALUES(?,?,?,?,1000)", order, purchase, branch, status);
        return order;
    }

    private UUID payment(UUID order, UUID branch, String amount, String currency, String status, String verifiedAt) {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO payment(id,order_id,branch_id,amount,currency,status,external_reference,verified_by,verified_at) VALUES(?,?,?,?,?,?,?,?,?)", id, order, branch, new java.math.BigDecimal(amount), currency, status, "REPORT-" + id, verifiedAt == null ? null : staff, verifiedAt == null ? null : OffsetDateTime.parse(verifiedAt));
        return id;
    }

    private void refund(UUID payment, String amount, String status, String verifiedAt) {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO refund(id,payment_id,requested_by,amount,currency,status,reason,external_reference,verified_by,verified_at) VALUES(?,?,?,?,'ARS',?,'Reportes QA',?,?,?)", id, payment, staff, new java.math.BigDecimal(amount), status, "REFUND-REPORT-" + id, verifiedAt == null ? null : staff, verifiedAt == null ? null : OffsetDateTime.parse(verifiedAt));
    }

    private UUID buyer() {
        return DemoIds.id("user:comprador@demo.invalid");
    }

    private void sql(String sql, Object... values) {
        jdbc.sql(sql).params(values).update();
    }
}
