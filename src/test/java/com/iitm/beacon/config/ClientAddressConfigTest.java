package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

/**
 * {@link ClientAddressConfig}: with no trusted proxy configured ({@code
 * beacon.web.trusted-proxies} empty, the default) the servlet container gets
 * no {@link RemoteIpValve}, so a request's address is its connection's and
 * no {@code X-Forwarded-*} header is believed. With proxies configured, it
 * gets one that believes {@code X-Forwarded-For} and {@code
 * X-Forwarded-Proto} from exactly those addresses — never from the private
 * ranges Tomcat trusts by default (decision 27). The client address the
 * per-IP OTP limits count is tested over real HTTP in {@code
 * ClientAddress*Test}.
 */
class ClientAddressConfigTest {

    private static List<String> entries(String... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static RemoteIpValve valveFor(String... entries) {
        return ClientAddressConfig.remoteIpValve(entries(entries))
                .orElseThrow(() -> new AssertionError("no valve"));
    }

    private static Pattern trusted(RemoteIpValve valve) {
        return Pattern.compile(valve.getInternalProxies());
    }

    @Test
    void nothingConfigured_noValve_soTheConnectionsAddressIsTheClients() {
        assertThat(ClientAddressConfig.remoteIpValve(null)).isEmpty();
        assertThat(ClientAddressConfig.remoteIpValve(List.of())).isEmpty();
        assertThat(ClientAddressConfig.remoteIpValve(entries("", "  "))).isEmpty();
        assertThat(ClientAddressConfig.remoteIpValve(entries((String) null))).isEmpty();
    }

    @Test
    void aProxy_believesXForwardedForAndProto_fromThatProxyAlone() {
        RemoteIpValve valve = valveFor("10.0.0.5");

        assertThat(valve.getRemoteIpHeader()).isEqualTo("X-Forwarded-For");
        assertThat(valve.getProtocolHeader()).isEqualTo("X-Forwarded-Proto");
        assertThat(valve.getProtocolHeaderHttpsValue()).isEqualTo("https");
        assertThat(valve.getTrustedProxies()).isNull();
        assertThat(valve.getHostHeader()).isNull();
        assertThat(valve.getPortHeader()).isNull();
        assertThat(trusted(valve).matcher("10.0.0.5").matches()).isTrue();
    }

    /** Exactly the address: no other private address, no longer or shorter one, no regex reading of its dots. */
    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.50", "110.0.0.5", "10.0.0.4", "10.0.0.5 ", "10a0b0c5", "192.168.0.1", "127.0.0.1"})
    void otherAddresses_areNotTrusted(String other) {
        assertThat(trusted(valveFor("10.0.0.5")).matcher(other).matches()).isFalse();
    }

    @Test
    void severalProxies_eachTrimmed_blankEntriesSkipped() {
        Pattern trusted = trusted(valveFor(" 10.0.0.5 ", "", "172.18.0.2", "10.0.0.5"));

        assertThat(trusted.matcher("10.0.0.5").matches()).isTrue();
        assertThat(trusted.matcher("172.18.0.2").matches()).isTrue();
        assertThat(trusted.matcher("172.18.0.20").matches()).isFalse();
    }

    /** Tomcat spells an IPv6 peer in full ({@code 0:0:0:0:0:0:0:1}); the short spelling is trusted as both. */
    @Test
    void ipv6Proxy_isTrustedInEitherSpelling() {
        Pattern trusted = trusted(valveFor("::1"));

        assertThat(trusted.matcher("0:0:0:0:0:0:0:1").matches()).isTrue();
        assertThat(trusted.matcher("::1").matches()).isTrue();
        assertThat(trusted.matcher("0:0:0:0:0:0:0:2").matches()).isFalse();
    }

    /** Anything but a single IP address stops the app at startup: a host name, a range, a port, a typo. */
    @ParameterizedTest
    @ValueSource(strings = {
        "proxy.example", "localhost", "10.0.0.0/8", "10.0.0.*", "*", "10.0.0.1:8080", "256.0.0.1", "1.2.3",
        "1.2.3.4.5", "01.2.3.4", "fe80::1%eth0", "[::1]", "::g"
    })
    void anythingButAnIpAddress_isRefused(String entry) {
        assertThatThrownBy(() -> ClientAddressConfig.remoteIpValve(entries("10.0.0.5", entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beacon.web.trusted-proxies")
                .hasMessageContaining("'" + entry.strip() + "'");
    }

    @Test
    void customizer_addsTheValveToTheEmbeddedTomcat_onlyWhenAProxyIsConfigured() {
        TomcatServletWebServerFactory withProxy = new TomcatServletWebServerFactory();
        TomcatServletWebServerFactory withoutProxy = new TomcatServletWebServerFactory();

        new ClientAddressConfig()
                .trustedProxiesCustomizer(new TrustedProxiesProperties(entries("10.0.0.5")))
                .customize(withProxy);
        new ClientAddressConfig()
                .trustedProxiesCustomizer(new TrustedProxiesProperties(List.of()))
                .customize(withoutProxy);

        assertThat(withProxy.getEngineValves()).singleElement().isInstanceOf(RemoteIpValve.class);
        assertThat(withoutProxy.getEngineValves()).noneMatch((Valve valve) -> valve instanceof RemoteIpValve);
    }
}
