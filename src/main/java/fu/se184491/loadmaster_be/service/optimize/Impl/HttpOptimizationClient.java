package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.config.OptimizationEngineProperties;
import fu.se184491.loadmaster_be.dto.optimization.engine.OptimizationResult;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest;
import fu.se184491.loadmaster_be.service.auth.KeycloakServiceTokenProvider;
import fu.se184491.loadmaster_be.service.optimize.OptimizationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP implementation of {@link OptimizationClient}.
 *
 * Calls FastAPI at:
 *   {@code POST {optimization.engine.url}/api/v1/optimization/jobs}
 *
 * Every request carries an {@code Authorization: Bearer <service-token>}
 * header obtained from {@link KeycloakServiceTokenProvider}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpOptimizationClient implements OptimizationClient {

    /** FastAPI v1 path — different from Spring Boot's /api/optimization/jobs (S3-04) */
    static final String ENGINE_JOBS_PATH = "/api/v1/optimization/jobs";

    private final RestTemplate restTemplate;
    private final KeycloakServiceTokenProvider tokenProvider;
    private final OptimizationEngineProperties engineProperties;

    // ── OptimizationClient ───────────────────────────────────────────────────

    @Override
    public OptimizationResult solve(ProblemRequest request) {
        String url = engineProperties.getUrl() + ENGINE_JOBS_PATH;
        log.info("Calling optimization engine: POST {}", url);

        HttpHeaders headers = buildAuthHeaders();
        HttpEntity<ProblemRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<OptimizationResult> response =
                restTemplate.postForEntity(url, entity, OptimizationResult.class);

        OptimizationResult result = response.getBody();
        log.info("Engine responded with {} placements, {} unplaced",
                result != null && result.getPlacements() != null ? result.getPlacements().size() : 0,
                result != null && result.getUnplaced()  != null ? result.getUnplaced().size()  : 0);

        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private HttpHeaders buildAuthHeaders() {
        String token = tokenProvider.getServiceToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);   // sets Authorization: Bearer <token>
        return headers;
    }
}
