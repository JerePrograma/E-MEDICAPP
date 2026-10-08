package ar.emedicapp.appbootstrap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class DemoIds {
    public static final String SCENARIO_VERSION = "Mendoza 2026.2";
    public static final String BUYER_NAME = "Lucía Fernández";
    public static final String BUYER_BIRTH_DATE = "1990-01-01";

    private DemoIds() {}

    public static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("emedicapp-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
