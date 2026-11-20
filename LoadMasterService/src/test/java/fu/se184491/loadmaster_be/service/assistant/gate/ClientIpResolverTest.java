package fu.se184491.loadmaster_be.service.assistant.gate;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void behindLocalProxyTheRightmostForwardedAddressWins() {
        assertThat(resolver.resolve(request("127.0.0.1", "1.2.3.4, 9.9.9.9"))).isEqualTo("9.9.9.9");
    }

    @Test
    void dockerBridgeAndIpv6LoopbackCountAsLocalProxies() {
        assertThat(resolver.resolve(request("172.17.0.1", "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("::1", "2001:db8::5"))).isEqualTo("2001:db8::5");
    }

    @Test
    void publicPeerIgnoresTheHeader() {
        assertThat(resolver.resolve(request("8.8.4.4", "1.2.3.4"))).isEqualTo("8.8.4.4");
    }

    @Test
    void noHeaderMeansThePeer() {
        assertThat(resolver.resolve(request("127.0.0.1", null))).isEqualTo("127.0.0.1");
    }

    @Test
    void malformedForwardedValueFallsBackToThePeer() {
        assertThat(resolver.resolve(request("127.0.0.1", "1.2.3.4, evil.example.com"))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", "1.2.3.4, 999.1.1.1"))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", " , "))).isEqualTo("127.0.0.1");
    }
}
