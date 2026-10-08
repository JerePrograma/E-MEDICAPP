package ar.emedicapp.appbootstrap;

import ar.emedicapp.common.Json;
import ar.emedicapp.documents.DocumentStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class BootstrapInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Json json;
    private final AppMode mode;
    private final DocumentStorage documents;
    private final Clock clock;
    private final String adminEmail;
    private final String adminPassword;

    public BootstrapInitializer(
            JdbcClient jdbc,
            PasswordEncoder passwords,
            Json json,
            AppMode mode,
            DocumentStorage documents,
            Clock clock,
            @Value("${emedicapp.bootstrap.admin-email:}") String adminEmail,
            @Value("${emedicapp.bootstrap.admin-password:}") String adminPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.json = json;
        this.mode = mode;
        this.documents = documents;
        this.clock = clock;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) throws Exception {
        if (mode.demo()) demo();
        else manualAdmin();
    }

    private void manualAdmin() {
        boolean exists =
                jdbc.sql(
                                """
                SELECT EXISTS(SELECT 1 FROM app_user u JOIN user_role r ON r.user_id=u.id
                    WHERE u.active AND r.role='ADMIN')
                """)
                        .query(Boolean.class)
                        .single();
        if (exists) return;
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || email.endsWith(".invalid")
                || adminPassword.isBlank()
                || adminPassword.length() < 12
                || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || adminPassword.equals("DemoMedicapp2026!")) {
            throw new IllegalStateException(
                    "El primer inicio MANUAL requiere un correo real y una contraseña propia de al menos 12 caracteres en las variables de bootstrap. No se habilitan credenciales DEMO.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO app_user(id,email,name,password_hash,birth_date,active)
                VALUES(:id,:email,'Administración',:password,DATE '1970-01-01',true)
                """)
                .param("id", id)
                .param("email", email)
                .param("password", passwords.encode(adminPassword))
                .update();
        jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,'ADMIN')")
                .param("id", id)
                .update();
    }

    private void demo() throws IOException {
        UUID admin = user("admin@demo.invalid", "Marina Suárez", "1985-04-12", "ADMIN");
        user("comprador@demo.invalid", DemoIds.BUYER_NAME, DemoIds.BUYER_BIRTH_DATE, "BUYER");
        UUID pharmacy = user("farmacia@demo.invalid", "Paula Benítez", "1988-07-09", "PHARMACY");
        UUID pharmacist =
                user("farmaceutico@demo.invalid", "Valentina Ríos", "1982-11-18", "PHARMACIST");
        UUID secondPharmacist =
                user("farmaceutico2@demo.invalid", "Gabriel Ponce", "1984-02-23", "PHARMACIST");
        UUID driver = user("cadete@demo.invalid", "Santiago Molina", "1993-06-16", "DRIVER");
        UUID andes = chain("andes", "Farmacias Nivalis");
        UUID sur = chain("sur", "Farmacias Valnera");
        UUID center =
                branch(
                        "centro",
                        andes,
                        "Nivalis · Ciudad",
                        "Av. España 1185, Ciudad, Mendoza",
                        -32.889,
                        -68.845,
                        "Nivalis Cuyo S.R.L.",
                        true,
                        true,
                        "12.00",
                        null,
                        null);
        UUID east =
                branch(
                        "este",
                        andes,
                        "Nivalis · Dorrego",
                        "Adolfo Calle 1960, Dorrego, Guaymallén, Mendoza",
                        -32.893,
                        -68.813,
                        "Nivalis Cuyo S.R.L.",
                        false,
                        false,
                        "10.00",
                        "08:00",
                        "22:00");
        UUID south =
                branch(
                        "sur",
                        sur,
                        "Valnera · Godoy Cruz",
                        "San Martín 1568, Godoy Cruz, Mendoza",
                        -32.930,
                        -68.854,
                        "Valnera Salud S.R.L.",
                        true,
                        true,
                        "12.00",
                        "08:00",
                        "23:00");
        UUID fallback =
                branch(
                        "fallback",
                        null,
                        "Luma Cuyo · Las Heras",
                        "San Miguel 820, Las Heras, Mendoza",
                        -32.858,
                        -68.821,
                        "Luma Cuyo S.A.S.",
                        false,
                        false,
                        "8.00",
                        "09:00",
                        "21:00");
        var branches = List.of(center, east, south, fallback);
        for (int index = 0; index < branches.size(); index++) {
            UUID branch = branches.get(index);
            boolean special = index == 0 || index == 2;
            membership(pharmacy, branch, "PHARMACY", special);
            membership(pharmacist, branch, "PHARMACIST", special);
            membership(secondPharmacist, branch, "PHARMACIST", special);
            membership(driver, branch, "DRIVER", special);
            capability(branch, pharmacist);
        }
        List<DemoProduct> catalog =
                List.of(
                        new DemoProduct(
                                "a",
                                "Paracetamol Nivalen 500 mg",
                                "Caja de 16 comprimidos",
                                false,
                                false,
                                false,
                                "6420.50"),
                        new DemoProduct(
                                "b",
                                "Ibuprofeno Relivex 400 mg",
                                "Caja de 20 comprimidos recubiertos",
                                false,
                                false,
                                false,
                                "11840.00"),
                        new DemoProduct(
                                "c",
                                "Solución fisiológica Aquavia 0,9 %",
                                "Frasco de 500 ml",
                                false,
                                false,
                                false,
                                "7860.75"),
                        new DemoProduct(
                                "d",
                                "Amoxicilina Amoxilen 500 mg",
                                "Caja de 21 cápsulas",
                                true,
                                false,
                                false,
                                "26490.00"),
                        new DemoProduct(
                                "e",
                                "Insulina glargina Glucovalis 100 UI/ml",
                                "Lapicera prellenada de 3 ml",
                                true,
                                true,
                                false,
                                "186420.80"),
                        new DemoProduct(
                                "f",
                                "Clonazepam Nervalia 2 mg",
                                "Caja de 30 comprimidos",
                                true,
                                false,
                                true,
                                "22175.50"),
                        new DemoProduct(
                                "g",
                                "Loratadina Loradina 10 mg",
                                "Caja de 10 comprimidos",
                                false,
                                false,
                                false,
                                "9850.00"),
                        new DemoProduct(
                                "h",
                                "Omeprazol Gastrovia 20 mg",
                                "Caja de 28 cápsulas",
                                true,
                                false,
                                false,
                                "18480.00"),
                        new DemoProduct(
                                "i",
                                "Losartán Losavia 50 mg",
                                "Caja de 30 comprimidos recubiertos",
                                true,
                                false,
                                false,
                                "28960.40"),
                        new DemoProduct(
                                "j",
                                "Metformina Metavalis 850 mg",
                                "Caja de 60 comprimidos",
                                true,
                                false,
                                false,
                                "34780.00"),
                        new DemoProduct(
                                "k",
                                "Levotiroxina Tirovalis 50 mcg",
                                "Caja de 50 comprimidos",
                                true,
                                false,
                                false,
                                "22970.00"),
                        new DemoProduct(
                                "l",
                                "Salbutamol Broncuya 100 mcg/dosis",
                                "Aerosol dosificador de 200 dosis",
                                true,
                                false,
                                false,
                                "31680.00"),
                        new DemoProduct(
                                "m",
                                "Amoxicilina / clavulánico Duoamox 875/125 mg",
                                "Caja de 14 comprimidos",
                                true,
                                false,
                                false,
                                "48920.00"),
                        new DemoProduct(
                                "n",
                                "Diclofenac Diclovalis 75 mg",
                                "Caja de 20 comprimidos",
                                true,
                                false,
                                false,
                                "21640.50"),
                        new DemoProduct(
                                "o",
                                "Protector solar Dermavia FPS 50+",
                                "Pomo de 50 ml",
                                false,
                                false,
                                false,
                                "36490.00"),
                        new DemoProduct(
                                "p",
                                "Tiras reactivas Glucomax",
                                "Caja de 50 tiras para medidor Glucomax",
                                false,
                                false,
                                false,
                                "58620.00"),
                        new DemoProduct(
                                "q",
                                "Adalimumab Immunavia 40 mg",
                                "Jeringa prellenada de 0,8 ml",
                                true,
                                true,
                                false,
                                "1875000.00"),
                        new DemoProduct(
                                "r",
                                "Alcohol en gel Higienalia 70 %",
                                "Frasco con dosificador de 250 ml",
                                false,
                                false,
                                false,
                                "7240.00"));
        int[][] quantities = {
            {42, 24, 0, 0, 7, 0, 18, 16, 21, 12, 14, 9, 0, 13, 17, 8, 2, 36},
            {28, 0, 19, 0, 0, 0, 25, 12, 15, 24, 10, 8, 0, 20, 14, 0, 0, 30},
            {31, 0, 0, 16, 4, 9, 11, 18, 13, 20, 22, 12, 7, 0, 10, 13, 1, 28},
            {21, 14, 25, 8, 0, 0, 9, 6, 8, 5, 4, 3, 9, 7, 12, 0, 0, 24}
        };
        BigDecimal[] priceFactors = {
            new BigDecimal("1.00"),
            new BigDecimal("1.025"),
            new BigDecimal("0.985"),
            new BigDecimal("1.04")
        };
        for (int index = 0; index < catalog.size(); index++) {
            DemoProduct item = catalog.get(index);
            UUID product = product(item);
            for (int branchIndex = 0; branchIndex < branches.size(); branchIndex++) {
                BigDecimal price =
                        new BigDecimal(item.price())
                                .multiply(priceFactors[branchIndex])
                                .setScale(2, java.math.RoundingMode.HALF_UP);
                stock(
                        branches.get(branchIndex),
                        product,
                        item.code(),
                        quantities[branchIndex][index],
                        price,
                        index);
            }
        }
        UUID plan1 = plan(1, 1, "Esencial · una sucursal", 1);
        UUID plan2 = plan(2, 5, "Red · 2 a 5 sucursales", 4);
        plan(6, 20, "Expansión · 6 a 20 sucursales", 12);
        plan(21, 50, "Corporativo · 21 a 50 sucursales", 25);
        plan(51, null, "Acuerdo a medida · más de 50 sucursales", null);
        contract("andes", plan2, andes, null, List.of(center, east), admin, "89000.00");
        contract("sur", plan1, sur, null, List.of(south), admin, "39500.00");
        jdbc.sql(
                        """
                INSERT INTO operational_setting(id,version,updated_by,messages)
                VALUES(:id,1,:admin,CAST(:messages AS jsonb)) ON CONFLICT(version) DO NOTHING
                """)
                .param("id", DemoIds.id("settings:1"))
                .param("admin", admin)
                .param(
                        "messages",
                        json.encode(
                                Map.of(
                                        "demo",
                                        "Escenario de demostración de Mendoza. Personas, comercios, marcas, stock, precios, requisitos y documentos son simulados. No existe un convenio, habilitación ni cobro real.")))
                .update();
    }

    private record DemoProduct(
            String code,
            String name,
            String presentation,
            boolean prescription,
            boolean cold,
            boolean restricted,
            String price) {}

    private UUID user(String email, String name, String birthDate, String role) {
        UUID id = DemoIds.id("user:" + email);
        int created =
                jdbc.sql(
                                """
                INSERT INTO app_user(id,email,name,password_hash,birth_date,active)
                VALUES(:id,:email,:name,:password,:birthDate,true) ON CONFLICT(id) DO NOTHING
                """)
                        .param("id", id)
                        .param("email", email)
                        .param("name", name)
                        .param("password", passwords.encode("DemoMedicapp2026!"))
                        .param("birthDate", LocalDate.parse(birthDate))
                        .update();
        if (created == 1)
            jdbc.sql("INSERT INTO user_role(user_id,role) VALUES(:id,:role)")
                    .param("id", id)
                    .param("role", role)
                    .update();
        return id;
    }

    private UUID chain(String code, String name) {
        UUID id = DemoIds.id("chain:" + code);
        jdbc.sql("INSERT INTO chain(id,name) VALUES(:id,:name) ON CONFLICT(id) DO NOTHING")
                .param("id", id)
                .param("name", name)
                .update();
        return id;
    }

    private UUID branch(
            String code,
            UUID chain,
            String name,
            String address,
            double latitude,
            double longitude,
            String merchant,
            boolean cold,
            boolean restricted,
            String radius,
            String opensAt,
            String closesAt) {
        UUID id = DemoIds.id("branch:" + code);
        Map<String, Object> capabilities = new java.util.LinkedHashMap<>();
        capabilities.put("demo", true);
        capabilities.put("deliveryRadiusKm", radius);
        capabilities.put(
                "availabilitySource",
                opensAt == null
                        ? "Guardia permanente del escenario"
                        : "Horario diario del escenario");
        if (opensAt != null) {
            capabilities.put("opensAt", opensAt);
            capabilities.put("closesAt", closesAt);
        }
        jdbc.sql(
                        """
                INSERT INTO branch(id,chain_id,name,address,latitude,longitude,verified,agreement_accepted,
                    cold_chain_capable,restricted_capable,driver_available,merchant_name,payment_instructions,capabilities)
                VALUES(:id,:chain,:name,:address,:lat,:lon,true,true,:cold,:restricted,true,:merchant,:instructions,CAST(:capabilities AS jsonb))
                ON CONFLICT(id) DO NOTHING
                """)
                .param("id", id)
                .param("chain", chain)
                .param("name", name)
                .param("address", address)
                .param("lat", latitude)
                .param("lon", longitude)
                .param("cold", cold)
                .param("restricted", restricted)
                .param("merchant", merchant)
                .param("capabilities", json.encode(capabilities))
                .param(
                        "instructions",
                        "El pago se registra directamente a nombre de "
                                + merchant
                                + ". Transferencia o pago presencial, con comprobante y referencia. En esta demostración se adjunta un comprobante de ejemplo: no transfieras dinero real.")
                .update();
        return id;
    }

    private void membership(UUID user, UUID branch, String role, boolean special) {
        jdbc.sql(
                        """
                INSERT INTO membership(id,user_id,branch_id,role,capabilities)
                VALUES(:id,:user,:branch,:role,CAST(:capabilities AS jsonb))
                ON CONFLICT(user_id,branch_id,role) DO NOTHING
                """)
                .param("id", DemoIds.id("membership:" + user + ":" + branch + ":" + role))
                .param("user", user)
                .param("branch", branch)
                .param("role", role)
                .param(
                        "capabilities",
                        json.encode(
                                Map.of("coldChain", special, "restricted", special, "demo", true)))
                .update();
    }

    private UUID product(DemoProduct product) {
        UUID id = DemoIds.id("product:" + product.code());
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("demo", true);
        metadata.put(
                "label",
                "Marca, presentación, requisitos y precio de escenario. No es información para indicar ni dispensar un producto real.");
        metadata.put("source", "CATALOGO_ESCENARIO_MENDOZA_2026");
        if (product.cold()) {
            metadata.put("minTemperatureC", 2);
            metadata.put("maxTemperatureC", 8);
        }
        jdbc.sql(
                        """
                INSERT INTO product(id,code,name,presentation,requires_prescription,cold_chain,restricted,metadata)
                VALUES(:id,:code,:name,:presentation,:prescription,:cold,:restricted,CAST(:metadata AS jsonb))
                ON CONFLICT(id) DO NOTHING
                """)
                .param("id", id)
                .param("code", "EMC-" + product.code().toUpperCase(Locale.ROOT) + "-2026")
                .param("name", product.name())
                .param("presentation", product.presentation())
                .param("prescription", product.prescription())
                .param("cold", product.cold())
                .param("restricted", product.restricted())
                .param("metadata", json.encode(metadata))
                .update();
        return id;
    }

    private void stock(
            UUID branch, UUID product, String code, int quantity, BigDecimal price, int position) {
        jdbc.sql(
                        """
                INSERT INTO branch_stock(branch_id,product_id,quantity,price,source)
                VALUES(:branch,:product,:quantity,:price,'DEMO') ON CONFLICT(branch_id,product_id) DO NOTHING
                """)
                .param("branch", branch)
                .param("product", product)
                .param("quantity", quantity)
                .param("price", price)
                .update();
        jdbc.sql(
                        """
                INSERT INTO stock_lot(id,branch_id,product_id,lot_code,expires_on,quantity,status,metadata)
                VALUES(:id,:branch,:product,:lotCode,:expiry,:quantity,:status,CAST(:metadata AS jsonb)) ON CONFLICT(id) DO NOTHING
                """)
                .param("id", DemoIds.id("lot:" + branch + ":" + product))
                .param("branch", branch)
                .param("product", product)
                .param("lotCode", "CU-" + code.toUpperCase(Locale.ROOT) + "-2608")
                .param("expiry", LocalDate.now(clock).plusMonths(10 + position % 9))
                .param("quantity", quantity)
                .param("status", quantity == 0 ? "DEPLETED" : "AVAILABLE")
                .param(
                        "metadata",
                        json.encode(
                                Map.of(
                                        "demo",
                                        true,
                                        "supplier",
                                        "Distribuidora Cuya Salud",
                                        "source",
                                        "Inventario del escenario de demostración")))
                .update();
    }

    private UUID plan(int min, Integer max, String name, Integer units) {
        UUID id = DemoIds.id("plan:" + min);
        jdbc.sql(
                        """
                INSERT INTO plan(id,name,min_branches,max_branches,symbolic_units)
                VALUES(:id,:name,:min,:max,:units) ON CONFLICT(id) DO NOTHING
                """)
                .param("id", id)
                .param("name", name)
                .param("min", min)
                .param("max", max)
                .param("units", units)
                .update();
        return id;
    }

    private void contract(
            String key,
            UUID plan,
            UUID chain,
            UUID branch,
            List<UUID> branches,
            UUID verifier,
            String monthlyPrice) {
        UUID contract = DemoIds.id("contract:" + key);
        jdbc.sql(
                        """
                INSERT INTO subscription_contract(id,plan_id,chain_id,branch_id,monthly_price,currency,status)
                VALUES(:id,:plan,:chain,:branch,:monthlyPrice,'ARS','ACTIVE') ON CONFLICT(id) DO NOTHING
                """)
                .param("id", contract)
                .param("plan", plan)
                .param("chain", chain)
                .param("branch", branch)
                .param("monthlyPrice", new BigDecimal(monthlyPrice))
                .update();
        for (UUID covered : branches)
            jdbc.sql(
                            """
                INSERT INTO branch_subscription(contract_id,branch_id) VALUES(:contract,:branch)
                ON CONFLICT(contract_id,branch_id) DO NOTHING
                """)
                    .param("contract", contract)
                    .param("branch", covered)
                    .update();
        LocalDate periodMonth =
                jdbc.sql("SELECT date_trunc('month',now())::date").query(LocalDate.class).single();
        jdbc.sql(
                        """
                INSERT INTO subscription_period(id,contract_id,starts_at,ends_at,amount,currency,status,
                    verified_by,verified_at,payment_reference,source,snapshot)
                SELECT :id,sc.id,date_trunc('month',now()),date_trunc('month',now())+interval '1 month',
                    sc.monthly_price,sc.currency,'PAID',:verifier,now(),'ESCENARIO-SUSCRIPCION-SIN-COBRO-REAL','DEMO',
                    jsonb_build_object('demo',true,'planId',sc.plan_id,'monthlyPrice',sc.monthly_price,
                        'currency',sc.currency,'branchIds',COALESCE((SELECT jsonb_agg(bs.branch_id ORDER BY bs.branch_id)
                            FROM branch_subscription bs WHERE bs.contract_id=sc.id AND bs.active),'[]'::jsonb))
                FROM subscription_contract sc WHERE sc.id=:contract AND sc.status='ACTIVE'
                    AND sc.monthly_price>0 AND sc.currency IS NOT NULL
                    AND NOT EXISTS(SELECT 1 FROM subscription_period sp WHERE sp.contract_id=sc.id
                        AND sp.starts_at<date_trunc('month',now())+interval '1 month'
                        AND sp.ends_at>date_trunc('month',now()))
                ON CONFLICT(id) DO NOTHING
                """)
                .param("id", DemoIds.id("period:" + key + ":" + periodMonth))
                .param("contract", contract)
                .param("verifier", verifier)
                .update();
    }

    private void capability(UUID branch, UUID pharmacist) throws IOException {
        UUID document = DemoIds.id("capability:" + branch);
        boolean exists =
                jdbc.sql("SELECT EXISTS(SELECT 1 FROM document WHERE id=:id)")
                        .param("id", document)
                        .query(Boolean.class)
                        .single();
        if (exists) {
            driverCapability(branch, pharmacist, document);
            return;
        }
        var branchRow =
                jdbc.sql(
                                "SELECT name,address,cold_chain_capable,restricted_capable FROM branch WHERE id=:id")
                        .param("id", branch)
                        .query()
                        .singleRow();
        var stored = documents.writeBytes(document, demoPdf(branchRow, LocalDate.now(clock)));
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try {
                                documents.deleteDemoFile(stored.key());
                            } catch (IOException exception) {
                                /* Private orphan is never exposed through the API. */
                            }
                        }
                    }
                });
        jdbc.sql(
                        """
                INSERT INTO document(id,owner_user_id,kind,branch_id,original_name,content_type,size_bytes,storage_key,sha256,snapshot)
                VALUES(:id,:owner,'CAPABILITY',:branch,:fileName,:type,:size,:key,:sha,CAST(:snapshot AS jsonb))
                """)
                .param("id", document)
                .param("owner", pharmacist)
                .param(
                        "fileName",
                        "Registro-operativo-"
                                + String.valueOf(branchRow.get("name"))
                                        .replace(" · ", "-")
                                        .replace(" ", "-")
                                + ".pdf")
                .param("branch", branch)
                .param("type", stored.contentType())
                .param("size", stored.size())
                .param("key", stored.key())
                .param("sha", stored.sha256())
                .param("snapshot", json.encode(Map.of("demo", true, "notRealEvidence", true)))
                .update();
        jdbc.sql(
                        "UPDATE branch SET capabilities=capabilities || CAST(:capabilities AS jsonb) WHERE id=:id AND capabilities->>'demo'='true' AND NOT jsonb_exists(capabilities,'capabilityEvidenceDocumentId')")
                .param("id", branch)
                .param(
                        "capabilities",
                        json.encode(
                                Map.of(
                                        "demo",
                                        true,
                                        "capabilityEvidenceDocumentId",
                                        document,
                                        "verifiedAt",
                                        clock.instant().toString(),
                                        "verifiedBy",
                                        pharmacist)))
                .update();
        driverCapability(branch, pharmacist, document);
    }

    private void driverCapability(UUID branch, UUID pharmacist, UUID document) {
        jdbc.sql(
                        """
                UPDATE membership m SET capabilities=m.capabilities || CAST(:verification AS jsonb),version=version+1
                WHERE m.branch_id=:branch AND m.role='DRIVER' AND m.active AND m.version=1
                    AND m.capabilities->>'demo'='true'
                    AND (m.capabilities->>'coldChain'='true' OR m.capabilities->>'restricted'='true')
                    AND NOT jsonb_exists(m.capabilities,'capabilityEvidenceDocumentId')
                    AND NOT jsonb_exists(m.capabilities,'verifiedAt') AND NOT jsonb_exists(m.capabilities,'verifiedBy')
                    AND EXISTS(SELECT 1 FROM document d WHERE d.id=:document AND d.branch_id=:branch
                        AND d.owner_user_id=:pharmacist AND d.kind='CAPABILITY' AND d.snapshot->>'demo'='true')
                """)
                .param("branch", branch)
                .param("document", document)
                .param("pharmacist", pharmacist)
                .param(
                        "verification",
                        json.encode(
                                Map.of(
                                        "capabilityEvidenceDocumentId",
                                        document,
                                        "verifiedAt",
                                        clock.instant().toString(),
                                        "verifiedBy",
                                        pharmacist)))
                .update();
    }

    private static byte[] demoPdf(Map<String, Object> branch, LocalDate date) throws IOException {
        boolean cold = Boolean.TRUE.equals(branch.get("cold_chain_capable"));
        boolean restricted = Boolean.TRUE.equals(branch.get("restricted_capable"));
        var content = new StringBuilder();
        content.append("0.08 0.20 0.28 rg 0 746 595 96 re f\n");
        pdfText(content, "F2", 20, 42, 804, "1 1 1", "E-MEDICAPP");
        pdfText(content, "F1", 11, 42, 780, "1 1 1", "Registro operativo de sucursal");
        pdfText(content, "F2", 19, 42, 710, "0.08 0.20 0.28", String.valueOf(branch.get("name")));
        pdfText(
                content,
                "F1",
                11,
                42,
                686,
                "0.20 0.26 0.31",
                String.valueOf(branch.get("address")));
        pdfText(
                content,
                "F1",
                10,
                42,
                660,
                "0.20 0.26 0.31",
                "Fecha: " + date + "    |    Versión del escenario: " + DemoIds.SCENARIO_VERSION);
        content.append("0.90 0.93 0.95 rg 42 555 511 80 re f\n");
        pdfText(content, "F2", 11, 54, 612, "0.08 0.20 0.28", "RESPONSABLES DEL ESCENARIO");
        pdfText(
                content,
                "F1",
                11,
                54,
                590,
                "0.20 0.26 0.31",
                "Responsable farmacéutica: Valentina Ríos");
        pdfText(
                content,
                "F1",
                11,
                54,
                570,
                "0.20 0.26 0.31",
                "Segundo control: Gabriel Ponce    |    Operación: Paula Benítez");
        pdfText(content, "F2", 13, 42, 528, "0.08 0.20 0.28", "Alcance operativo configurado");
        pdfText(
                content,
                "F1",
                11,
                42,
                500,
                "0.20 0.26 0.31",
                "Recepción de pedidos, retiro en sucursal y entrega con cadete propio.");
        pdfText(
                content,
                "F1",
                11,
                42,
                478,
                "0.20 0.26 0.31",
                "Control manual de identidad, receta, autorización y comprobantes.");
        pdfText(
                content,
                "F1",
                11,
                42,
                456,
                "0.20 0.26 0.31",
                "Cadena de frío: "
                        + (cold
                                ? "habilitada en el escenario, rango 2 a 8 °C."
                                : "fuera del alcance de esta sucursal."));
        pdfText(
                content,
                "F1",
                11,
                42,
                434,
                "0.20 0.26 0.31",
                "Medicamentos restringidos: "
                        + (restricted
                                ? "doble control documental y custodia del escenario."
                                : "fuera del alcance de esta sucursal."));
        pdfText(content, "F2", 13, 42, 392, "0.08 0.20 0.28", "Controles del circuito");
        String[] controls = {
            "1. Comprobar disponibilidad y condiciones antes de emitir la propuesta.",
            "2. Conservar la aceptación del comprador y la referencia de pago.",
            "3. Adjuntar factura, registrar dispensación y verificar al receptor.",
            "4. Registrar temperatura y custodia cuando el pedido lo requiera.",
            "5. Documentar incidencias y reintegros sin alterar la trazabilidad."
        };
        for (int i = 0; i < controls.length; i++)
            pdfText(content, "F1", 11, 42, 364 - i * 23, "0.20 0.26 0.31", controls[i]);
        content.append("0.75 0.81 0.85 RG 42 225 m 553 225 l S\n");
        pdfText(content, "F2", 11, 42, 202, "0.08 0.20 0.28", "Validación interna de muestra");
        pdfText(
                content,
                "F1",
                10,
                42,
                182,
                "0.20 0.26 0.31",
                "Firma de muestra: Valentina Ríos / Gabriel Ponce. Personas simuladas.");
        content.append("0.96 0.94 0.87 rg 42 73 511 79 re f\n");
        pdfText(
                content,
                "F2",
                10,
                54,
                132,
                "0.36 0.30 0.14",
                "DOCUMENTO DE DEMOSTRACIÓN - SIN VALOR SANITARIO NI CONTRACTUAL");
        pdfText(
                content,
                "F1",
                10,
                54,
                113,
                "0.36 0.30 0.14",
                "Comercios, responsables, capacidades y verificaciones pertenecen al escenario.");
        pdfText(
                content,
                "F1",
                10,
                54,
                94,
                "0.36 0.30 0.14",
                "No acredita habilitación real, matrícula, certificación ni un acuerdo comercial.");
        pdfText(
                content,
                "F1",
                9,
                42,
                45,
                "0.40 0.46 0.50",
                "E-MEDICAPP · Mendoza · Registro interno del entorno DEMO                                      1 / 1");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
            "<< /Length " + stream.length + " >>\nstream\n" + content + "\nendstream"
        };
        var out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = out.size();
            out.write(
                    ((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = out.size();
        out.write(
                ("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n")
                        .getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets)
            out.write(
                    String.format(Locale.ROOT, "%010d 00000 n \n", offset)
                            .getBytes(StandardCharsets.US_ASCII));
        out.write(
                ("trailer\n<< /Size "
                                + (objects.length + 1)
                                + " /Root 1 0 R >>\nstartxref\n"
                                + xref
                                + "\n%%EOF\n")
                        .getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void pdfText(
            StringBuilder out, String font, int size, int x, int y, String color, String value) {
        String escaped = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        out.append(color)
                .append(" rg BT /")
                .append(font)
                .append(' ')
                .append(size)
                .append(" Tf ")
                .append(x)
                .append(' ')
                .append(y)
                .append(" Td (")
                .append(escaped)
                .append(") Tj ET\n");
    }
}
