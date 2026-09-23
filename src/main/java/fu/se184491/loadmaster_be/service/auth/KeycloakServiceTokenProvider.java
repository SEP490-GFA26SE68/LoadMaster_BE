package fu.se184491.loadmaster_be.service.auth;

import fu.se184491.loadmaster_be.config.KeycloakServiceProperties;
import fu.se184491.loadmaster_be.exception.ServiceAuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtains and caches a service access token from Keycloak using the
 * {@code client_credentials} OAuth 2.0 grant.
 *
 * <p>Thread-safe: uses a {@link ReentrantLock} so concurrent requests during
 * token expiry don't all hit Keycloak simultaneously (thundering herd).
 *
 * <p>Token is refreshed proactively when fewer than {@link #REFRESH_BUFFER_SEC}
 * seconds remain before expiry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakServiceTokenProvider {

    /** Refresh token this many seconds before it actually expires. */
    static final int REFRESH_BUFFER_SEC = 30;

    private final KeycloakServiceProperties keycloakProperties;
    private final RestTemplate restTemplate;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;   // force initial fetch
    private final ReentrantLock lock = new ReentrantLock();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a valid service access token string.
     * Fetches from Keycloak on first call; uses cache on subsequent calls
     * until {@link #REFRESH_BUFFER_SEC} seconds before expiry.
     *
     * @throws ServiceAuthenticationException if Keycloak is unreachable or returns an error
     */
    public String getServiceToken() {
        if (needsRefresh()) {
            lock.lock();
            try {
                // double-checked locking — another thread may have refreshed while we waited
                if (needsRefresh()) {
                    fetchAndCacheToken();
                }
            } finally {
                lock.unlock();
            }
        }
        return cachedToken;
    }

    // ── Token fetch ───────────────────────────────────────────────────────────

    private void fetchAndCacheToken() {
        log.info("Fetching new service token from Keycloak: {}",
                keycloakProperties.getTokenEndpoint());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type",    "client_credentials");
        body.add("client_id",     keycloakProperties.getServiceClient().getClientId());
        body.add("client_secret", keycloakProperties.getServiceClient().getClientSecret());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    keycloakProperties.getTokenEndpoint(),
                    new HttpEntity<>(body, headers),
                    Map.class);

            Map<?, ?> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("access_token")) {
                throw new ServiceAuthenticationException(
                        "Keycloak không trả về access_token hợp lệ");
            }

            cachedToken = (String) responseBody.get("access_token");
            Object expiresInRaw = responseBody.get("expires_in");
            int expiresIn = expiresInRaw instanceof Number n ? n.intValue() : 300;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);

            log.info("Service token obtained, expires in {}s", expiresIn);

        } catch (RestClientException e) {
            throw new ServiceAuthenticationException(
                    "Không thể lấy service token từ Keycloak: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true if no token is cached or the token expires within the refresh buffer.
     */
    boolean needsRefresh() {
        return cachedToken == null
                || Instant.now().isAfter(tokenExpiresAt.minusSeconds(REFRESH_BUFFER_SEC));
    }
}
