package ar.emedicapp.appbootstrap;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AppMode {
    private final String mode;

    public AppMode(
            @Value("${emedicapp.mode:DEMO}") String mode,
            @Value("${spring.datasource.url}") String databaseUrl) {
        this.mode = mode.trim().toUpperCase(Locale.ROOT);
        if (!this.mode.equals("DEMO") && !this.mode.equals("MANUAL"))
            throw new IllegalStateException("EMEDICAPP_MODE debe ser DEMO o MANUAL.");
        String database =
                databaseUrl.substring(databaseUrl.lastIndexOf('/') + 1).split("[?;]", 2)[0];
        String requiredSuffix = this.mode.equals("DEMO") ? "_demo" : "_manual";
        if (!database.endsWith(requiredSuffix))
            throw new IllegalStateException(
                    "La base de datos debe tener un nombre separado para el modo elegido (sufijo "
                            + requiredSuffix
                            + ").");
    }

    public String value() {
        return mode;
    }

    public boolean demo() {
        return mode.equals("DEMO");
    }
}
