package ar.emedicapp.appbootstrap;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfiguration {
    @Bean
    Clock businessClock(
            @Value("${emedicapp.business-zone:America/Argentina/Mendoza}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
