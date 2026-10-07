package com.iitm.beacon.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.catalina.valves.RemoteIpValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which address a request comes from — what the per-IP OTP request limits
 * count (decisions 4, 17) — and whether it was https.
 *
 * <p>By default the connection's own: every {@code X-Forwarded-*} header is
 * ignored, since any client can send one. Behind a reverse proxy that would
 * make every visitor the proxy, so the proxy's addresses can be listed in
 * {@code beacon.web.trusted-proxies} ({@link TrustedProxiesProperties}).
 * For a request from exactly one of them — and from nothing else, never the
 * private address ranges Tomcat trusts by default, which would include the
 * Docker network (decision 27) — Tomcat's {@link RemoteIpValve} then takes
 * the client's address from {@code X-Forwarded-For} (the right-most entry
 * that isn't a listed proxy: the one the proxy appended; whatever the client
 * sent in the header itself stays to its left and is ignored) and the scheme
 * from {@code X-Forwarded-Proto}. A forwarded {@code https} makes the request
 * secure: the session and CSRF cookies get {@code Secure}, and the
 * same-origin check of {@code common.web.SameOriginGuard} expects an {@code
 * https} origin on the scheme's default port unless {@code
 * beacon.web.allowed-origins} lists the site's origins, which still replace
 * the request-derived one. Host and port headers are not taken.
 *
 * <p>Each entry must be one IP address (IPv4 dotted quad or IPv6) — no host
 * name, range, port or wildcard — or the app doesn't start: a typo must not
 * silently trust nothing, or the wrong thing.
 */
@Configuration
public class ClientAddressConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientAddressConfig.class);

    private static final String PROPERTY = "beacon.web.trusted-proxies";
    private static final Pattern IPV4 =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> trustedProxiesCustomizer(
            TrustedProxiesProperties properties) {
        Optional<RemoteIpValve> valve = remoteIpValve(properties.trustedProxies());
        if (valve.isPresent()) {
            log.info("Client address: X-Forwarded-For and X-Forwarded-Proto are believed from {} only",
                    valve.get().getInternalProxies());
        } else {
            log.info("Client address: the connection's own; X-Forwarded-* headers are ignored");
        }
        return factory -> valve.ifPresent(factory::addEngineValves);
    }

    /**
     * The valve that believes {@code X-Forwarded-For} and {@code
     * X-Forwarded-Proto} from exactly {@code addresses} (blank entries
     * skipped), or none if there is no address.
     *
     * @throws IllegalArgumentException if an entry isn't a single IP address
     */
    static Optional<RemoteIpValve> remoteIpValve(List<String> addresses) {
        Set<String> spellings = new LinkedHashSet<>();
        if (addresses != null) {
            for (String entry : addresses) {
                String address = entry == null ? "" : entry.strip();
                if (!address.isEmpty()) {
                    spellings.add(address);
                    spellings.add(canonical(address));
                }
            }
        }
        if (spellings.isEmpty()) {
            return Optional.empty();
        }
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(spellings.stream().map(Pattern::quote).collect(Collectors.joining("|")));
        valve.setTrustedProxies(null);
        valve.setRemoteIpHeader("X-Forwarded-For");
        valve.setProtocolHeader("X-Forwarded-Proto");
        valve.setProtocolHeaderHttpsValue("https");
        return Optional.of(valve);
    }

    /**
     * The address as Tomcat spells a connection's address — an IPv6 one in
     * full ({@code 0:0:0:0:0:0:0:1}). An IPv4 address is taken as is;
     * anything else that isn't an IPv6 literal is refused, without a name
     * lookup.
     */
    private static String canonical(String address) {
        if (IPV4.matcher(address).matches()) {
            return address;
        }
        if (IPV6_CHARACTERS.matcher(address).matches()) {
            try {
                // A literal containing ':' is parsed, never looked up.
                return InetAddress.getByName(address).getHostAddress();
            } catch (UnknownHostException ex) {
                throw invalid(address);
            }
        }
        throw invalid(address);
    }

    private static IllegalArgumentException invalid(String address) {
        return new IllegalArgumentException("Invalid entry in " + PROPERTY + ": '" + address
                + "' (expected the IP address of a reverse proxy, e.g. 172.18.0.2)");
    }
}
