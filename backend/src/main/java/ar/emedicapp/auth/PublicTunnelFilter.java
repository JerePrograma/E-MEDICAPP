package ar.emedicapp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only the explicitly configured, direct ngrok agent on loopback is trusted. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class PublicTunnelFilter extends OncePerRequestFilter {
    private final URI publicUrl;
    private final String host;
    private final String authority;
    private final int port;

    public PublicTunnelFilter(
            @Value("${emedicapp.security.public-url:}") String publicUrl,
            @Value("${emedicapp.mode:DEMO}") String mode,
            @Value("${server.address:127.0.0.1}") String address,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies,
            @Value("${server.forward-headers-strategy:NONE}") String forwardedStrategy) {
        if (publicUrl.isBlank()) {
            this.publicUrl = null;
            host = null;
            authority = null;
            port = 0;
            return;
        }
        if (!"MANUAL".equalsIgnoreCase(mode.trim())
                || !("127.0.0.1".equals(address.trim()) || "::1".equals(address.trim()))
                || !secureCookies
                || !"NONE".equalsIgnoreCase(forwardedStrategy.trim())) {
            throw new IllegalStateException(
                    "El acceso público requiere MANUAL, dirección loopback, cookies seguras y forwarded headers NONE.");
        }
        URI configured;
        try {
            configured = URI.create(publicUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("EMEDICAPP_PUBLIC_URL debe ser una URL HTTPS válida.");
        }
        if (!"https".equalsIgnoreCase(configured.getScheme())
                || configured.getHost() == null
                || configured.getUserInfo() != null
                || configured.getRawQuery() != null
                || configured.getRawFragment() != null
                || !(configured.getRawPath().isEmpty() || configured.getRawPath().equals("/"))
                || configured.getPort() == 0
                || configured.getPort() > 65535) {
            throw new IllegalStateException(
                    "EMEDICAPP_PUBLIC_URL debe ser una URL HTTPS de origen, sin credenciales, ruta, parámetros ni fragmento.");
        }
        this.publicUrl = configured;
        String configuredHost = configured.getHost().toLowerCase(Locale.ROOT);
        host =
                configuredHost.startsWith("[")
                        ? configuredHost.substring(1, configuredHost.length() - 1)
                        : configuredHost;
        port = configured.getPort() == -1 ? 443 : configured.getPort();
        String hostHeader = host.contains(":") ? "[" + host + "]" : host;
        authority = hostHeader + (port == 443 ? "" : ":" + port);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (publicUrl == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!loopback(request.getRemoteAddr())) {
            reject(response);
            return;
        }
        if (!hasForwardingHeaders(request) && localProbe(request)) {
            chain.doFilter(request, response);
            return;
        }
        String client = lastHeaderValue(request, "X-Forwarded-For");
        if (!validHost(request)
                || !"https".equalsIgnoreCase(lastHeaderValue(request, "X-Forwarded-Proto"))
                || numericAddress(client) == null) {
            reject(response);
            return;
        }
        chain.doFilter(new PublicRequest(request, client, host, authority, port), response);
    }

    private boolean validHost(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Host"));
        if (values.size() != 1) return false;
        String value = values.getFirst().trim();
        return authority.equalsIgnoreCase(value)
                || (port == 443 && (authority + ":443").equalsIgnoreCase(value));
    }

    private static boolean localProbe(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/bootstrap")
                || path.equals("/actuator/health")
                || path.equals("/actuator/health/readiness")
                || path.equals("/actuator/health/liveness");
    }

    private static boolean hasForwardingHeaders(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .anyMatch(PublicTunnelFilter::forwardingHeader);
    }

    private static boolean forwardingHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("forwarded")
                || lower.equals("x-real-ip")
                || lower.startsWith("x-forwarded-");
    }

    // ngrok appends the observed client IP and protocol after any client-supplied values.
    private static String lastHeaderValue(HttpServletRequest request, String name) {
        String last = null;
        var headers = request.getHeaders(name);
        while (headers.hasMoreElements()) {
            String value = headers.nextElement();
            last = value.substring(value.lastIndexOf(',') + 1).trim();
        }
        return last;
    }

    private static InetAddress numericAddress(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) return null;
        if (!value.contains(":") && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean loopback(String peer) {
        InetAddress address = numericAddress(peer);
        return address != null && address.isLoopbackAddress();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write(
                        "{\"code\":\"PUBLIC_ACCESS_REQUIRED\",\"message\":\"La solicitud no llegó por el acceso público configurado.\"}");
    }

    private static final class PublicRequest extends HttpServletRequestWrapper {
        private final String client;
        private final String host;
        private final String authority;
        private final int port;
        private final Map<String, String> headers;

        PublicRequest(
                HttpServletRequest request,
                String client,
                String host,
                String authority,
                int port) {
            super(request);
            this.client = client;
            this.host = host;
            this.authority = authority;
            this.port = port;
            headers =
                    Map.of(
                            "host", authority,
                            "x-forwarded-for", client,
                            "x-forwarded-proto", "https",
                            "x-forwarded-host", authority,
                            "x-forwarded-port", Integer.toString(port),
                            "x-real-ip", client);
        }

        @Override
        public String getRemoteAddr() {
            return client;
        }

        @Override
        public String getRemoteHost() {
            return client;
        }

        @Override
        public boolean isSecure() {
            return true;
        }

        @Override
        public String getScheme() {
            return "https";
        }

        @Override
        public String getServerName() {
            return host;
        }

        @Override
        public int getServerPort() {
            return port;
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer("https://" + authority + getRequestURI());
        }

        @Override
        public String getHeader(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (headers.containsKey(lower)) return headers.get(lower);
            return forwardingHeader(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (headers.containsKey(lower))
                return Collections.enumeration(Collections.singleton(headers.get(lower)));
            return forwardingHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = new LinkedHashSet<String>();
            Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !name.equalsIgnoreCase("Host") && !forwardingHeader(name))
                    .forEach(names::add);
            names.addAll(headers.keySet());
            return Collections.enumeration(names);
        }

        @Override
        public int getIntHeader(String name) {
            String value = getHeader(name);
            return value == null ? -1 : Integer.parseInt(value);
        }
    }
}
