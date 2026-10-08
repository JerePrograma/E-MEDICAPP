package ar.emedicapp.auth;

import ar.emedicapp.common.Access;
import ar.emedicapp.common.ApiError;
import ar.emedicapp.common.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            UserRepository users,
            Access access,
            Json json,
            LoginRateLimiter limiter)
            throws Exception {
        http.authorizeHttpRequests(
                authorize ->
                        authorize
                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/api/bootstrap",
                                        "/api/auth/csrf",
                                        "/actuator/health",
                                        "/actuator/health/**")
                                .permitAll()
                                .requestMatchers(
                                        HttpMethod.POST, "/api/auth/login", "/api/auth/register")
                                .permitAll()
                                .requestMatchers("/api/**")
                                .authenticated()
                                .requestMatchers("/actuator/**")
                                .denyAll()
                                .anyRequest()
                                .permitAll());
        http.sessionManagement(
                session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        http.formLogin(
                login ->
                        login.loginProcessingUrl("/api/auth/login")
                                .usernameParameter("email")
                                .passwordParameter("password")
                                .successHandler(
                                        (request, response, authentication) ->
                                                write(response, 200, json.encode(access.actor())))
                                .failureHandler(
                                        (request, response, exception) ->
                                                write(
                                                        response,
                                                        401,
                                                        json.encode(
                                                                new ApiError(
                                                                        "INVALID_CREDENTIALS",
                                                                        "No se pudo ingresar con esos datos.")))));
        http.logout(
                logout ->
                        logout.logoutUrl("/api/auth/logout")
                                .invalidateHttpSession(true)
                                .clearAuthentication(true)
                                .deleteCookies("JSESSIONID")
                                .logoutSuccessHandler(
                                        (request, response, authentication) ->
                                                write(response, 200, "{\"loggedOut\":true}")));
        http.exceptionHandling(
                errors ->
                        errors.authenticationEntryPoint(
                                        (request, response, exception) ->
                                                write(
                                                        response,
                                                        401,
                                                        json.encode(
                                                                new ApiError(
                                                                        "LOGIN_REQUIRED",
                                                                        "Ingresá a tu cuenta para continuar."))))
                                .accessDeniedHandler(
                                        (request, response, exception) ->
                                                write(
                                                        response,
                                                        403,
                                                        json.encode(
                                                                new ApiError(
                                                                        "ACCESS_DENIED",
                                                                        "No se pudo autorizar la operación. Actualizá la página y volvé a intentar.")))));
        http.requestCache(cache -> cache.disable());
        http.addFilterBefore(
                new LoginRateLimitFilter(limiter, json),
                UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(
                new ActiveAccountFilter(users, json), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void write(HttpServletResponse response, int status, String body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
