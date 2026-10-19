package ar.emedicapp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
public class EmailSender {
    private final JavaMailSenderImpl mail;
    private final String from;
    private final String baseUrl;
    private final boolean configured;

    public EmailSender(
            @Value("${EMEDICAPP_SMTP_HOST:}") String host,
            @Value("${EMEDICAPP_SMTP_PORT:587}") int port,
            @Value("${EMEDICAPP_SMTP_USERNAME:}") String username,
            @Value("${EMEDICAPP_SMTP_PASSWORD:}") String password,
            @Value("${EMEDICAPP_MAIL_FROM:}") String from,
            @Value("${EMEDICAPP_EMAIL_BASE_URL:${EMEDICAPP_FRONTEND_URL:${EMEDICAPP_PUBLIC_URL:}}}") String baseUrl,
            @Value("${EMEDICAPP_SMTP_STARTTLS:true}") boolean startTls,
            @Value("${EMEDICAPP_SMTP_SSL:false}") boolean ssl) {
        this.from = from.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        configured =
                !host.isBlank()
                        && port > 0
                        && port <= 65535
                        && this.from.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                        && validBaseUrl(this.baseUrl)
                        && (username.isBlank() == password.isBlank())
                        && !(ssl && startTls);
        mail = new JavaMailSenderImpl();
        mail.setHost(host.trim());
        mail.setPort(port);
        mail.setUsername(username.isBlank() ? null : username);
        mail.setPassword(password.isBlank() ? null : password);
        mail.setDefaultEncoding("UTF-8");
        var properties = mail.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(startTls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(startTls));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(ssl));
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
    }

    public boolean configured() {
        return configured;
    }

    void send(String recipient, String purpose, String token, int ttlMinutes) {
        if (!configured) throw new IllegalStateException("El envío de correo no está configurado.");
        boolean verification = purpose.equals("VERIFY_EMAIL");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(
                verification
                        ? "Confirmá tu correo · E-MEDICAPP"
                        : "Recuperá tu acceso · E-MEDICAPP");
        message.setText(
                (verification
                                ? "Confirmá tu correo para habilitar tu cuenta."
                                : "Elegí una contraseña nueva para tu cuenta.")
                        + "\n\n"
                        + baseUrl
                        + "/#"
                        + (verification ? "verify-email=" : "reset-password=")
                        + token
                        + "\n\nEl enlace vence en "
                        + ttlMinutes
                        + " minutos y se utiliza una sola vez.\n"
                        + "Si no solicitaste esta operación, ignorá este correo. No compartas el"
                        + " enlace.");
        mail.send(message);
    }

    private static boolean validBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !uri.normalize().getRawPath().equals(uri.getRawPath())) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme())
                            && (host.equals("localhost")
                                    || host.equals("127.0.0.1")
                                    || host.equals("[::1]")));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
