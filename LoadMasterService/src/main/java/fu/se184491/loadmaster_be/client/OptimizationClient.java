package fu.se184491.loadmaster_be.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Gọi dịch vụ tối ưu (Python) bằng service token của backend.
 *
 * <p>Lỗi của dịch vụ tối ưu có phong bì {@code {success, code, message}}: mã nghiệp vụ mà Java
 * cũng có (phương án không tồn tại, đã duyệt…) được giữ nguyên; lỗi khác và lỗi kết nối thành
 * {@code OPTIMIZATION_SERVICE_UNAVAILABLE}.</p>
 */
@Slf4j
@Component
public class OptimizationClient {

    /** Mã lỗi của dịch vụ tối ưu được chuyển thẳng cho người gọi. */
    private static final Set<String> PASS_THROUGH_CODES = Set.of(
            "LOAD_PLAN_NOT_FOUND",
            "PLAN_ALREADY_APPROVED",
            "PLAN_HAS_NO_PLACEMENTS",
            "PLAN_LIFO_INVALID",
            "PLAN_STALE",
            "PLAN_TRIP_NOT_PLANNED",
            "OPTIMIZATION_JOB_NOT_FOUND",
            "TRIP_NOT_FOUND",
            "VEHICLE_NOT_FOUND",
            "PACKAGE_NOT_FOUND",
            "INSUFFICIENT_CREDITS",
            "PINNED_SET_INVALID"
    );

    private final RestClient optimizationRestClient;
    private final ServiceTokenProvider serviceTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OptimizationClient(
            @Qualifier("optimizationRestClient")
            RestClient optimizationRestClient,
            ServiceTokenProvider serviceTokenProvider
    ) {
        this.optimizationRestClient = optimizationRestClient;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    // POST /api/v1/optimization/jobs
    public String createJob(Object request) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String jobUuid = null;
        Long tripId = null;
        String stage = "serialization";
        try {
            String jsonBody = toJson(request);
            JsonNode identity = readTree(jsonBody);
            jobUuid = safeUuid(identity.path("job_uuid").asText(null));
            if (identity.path("trip_id").isIntegralNumber() && identity.path("trip_id").canConvertToLong()
                    && identity.path("trip_id").longValue() > 0) tripId = identity.path("trip_id").longValue();
            stage = "service_token";
            String serviceToken = token();
            stage = "http_dispatch";
            dispatchLog(requestId, jobUuid, tripId, "http_dispatch_started", null, "NONE", started, "NONE");
            var response = optimizationRestClient.post().uri(identity.path("protocol_version").asInt() == 2 ? "/api/v2/optimization/jobs" : "/api/v1/optimization/jobs")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header("X-Request-ID", requestId)
                    .headers(headers -> headers.setBearerAuth(serviceToken))
                    .body(jsonBody).retrieve().toEntity(String.class);
            dispatchLog(requestId, jobUuid, tripId, "http_response_received", response.getStatusCode().value(),
                    "NONE", started, "NONE");
            return response.getBody();
        } catch (RestClientResponseException ex) {
            dispatchLog(requestId, jobUuid, tripId, "http_error", ex.getStatusCode().value(),
                    remoteCode(ex), started, ex.getClass().getSimpleName());
            if (isDeterministicDispatchRejection(ex)) {
                dispatchLog(requestId, jobUuid, tripId, "pre_ack_rejected", 400,
                        ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID.name(), started, "NONE");
                throw new OptimizationDispatchRejectedException();
            }
            throw translate(ex);
        } catch (ResourceAccessException ex) {
            dispatchLog(requestId, jobUuid, tripId, "transport_error", null, "NONE", started, ex.getClass().getSimpleName());
            throw new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        } catch (RuntimeException ex) {
            dispatchLog(requestId, jobUuid, tripId, stage, null, "NONE", started, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private static String safeUuid(String value) {
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    /** Narrow allowlist proven by Python's pre-scheduling profile preflight. Not a job-level verdict. */
    private boolean isDeterministicDispatchRejection(RestClientResponseException ex) {
        if (ex.getStatusCode().value() != 400) return false;
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            return body != null && body.isObject()
                    && body.path("success").isBoolean() && !body.path("success").booleanValue()
                    && body.path("code").isTextual()
                    && ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID.name().equals(body.path("code").textValue());
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private String remoteCode(RestClientResponseException ex) {
        try { return safeRemoteCode(objectMapper.readTree(ex.getResponseBodyAsString()).path("code").asText(null)); }
        catch (Exception ignored) { return "UNRECOGNIZED"; }
    }

    private static String safeRemoteCode(String code) {
        if (code == null) return "NONE";
        // Closed allowlist: a syntactically valid arbitrary string can still be a secret.
        try { return ErrorCode.valueOf(code).name(); }
        catch (IllegalArgumentException ignored) { return "UNRECOGNIZED"; }
    }

    private static void dispatchLog(String requestId, String jobUuid, Long tripId, String stage,
                                    Integer status, String code, long started, String exceptionType) {
        log.info("optimization_dispatch request_id={} job_uuid={} trip_id={} stage={} remote_http_status={} remote_code={} elapsed_ms={} exception_type={}",
                requestId, jobUuid, tripId, stage, status, code,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), exceptionType);
    }

    // GET /api/v1/optimization/jobs/{id}
    public String getJobStatus(String jobId) {
        return call(() -> optimizationRestClient
                .get()
                .uri("/api/v1/optimization/jobs/{id}", jobId)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token()))
                .retrieve()
                .body(String.class));
    }

    // GET /api/v1/optimization/jobs/{id}/plans
    public String getJobPlans(String jobId) {
        return call(() -> optimizationRestClient
                .get()
                .uri("/api/v1/optimization/jobs/{id}/plans", jobId)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token()))
                .retrieve()
                .body(String.class));
    }

    // POST /api/v1/load-plans/{id}/approve — người duyệt là người dùng Java, gửi trong thân.
    public void approvePlan(Long planId, Long approvedByUserId) {
        String jsonBody = toJson(Map.of("approved_by_user_id", approvedByUserId));
        call(() -> optimizationRestClient
                .post()
                .uri("/api/v1/load-plans/{id}/approve", planId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token()))
                .body(jsonBody)
                .retrieve()
                .body(String.class));
    }

    // POST /api/v1/optimize/rerun — trả phần data của phong bì.
    public JsonNode rerun(Object request) {
        String jsonBody = toJson(request);
        String response = call(() -> optimizationRestClient
                .post()
                .uri("/api/v1/optimize/rerun")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token()))
                .body(jsonBody)
                .retrieve()
                .body(String.class));
        JsonNode data = readTree(response).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        }
        return data;
    }

    /** Đọc {@code data.job_id} trong phản hồi tạo job. */
    public String jobIdOf(String createJobResponse) {
        String jobId = readTree(createJobResponse).path("data").path("job_id").asText(null);
        if (jobId == null || jobId.isBlank()) {
            throw new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        }
        return jobId;
    }

    private String token() {
        return serviceTokenProvider.getOptimizationServiceToken();
    }

    private String call(Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            throw translate(ex);
        } catch (ResourceAccessException ex) {
            log.warn("Không gọi được dịch vụ tối ưu: {}", ex.getClass().getSimpleName());
            throw new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        }
    }

    AppException translate(RestClientResponseException ex) {
        String code = null;
        String message = null;
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            if (body != null) {
                code = body.path("code").asText(null);
                message = body.path("message").asText(null);
            }
        } catch (JsonProcessingException ignored) {
            // Thân không phải JSON: coi như lỗi dịch vụ.
        }
        log.warn("Dịch vụ tối ưu trả {} (code={})", ex.getStatusCode().value(), safeRemoteCode(code));
        if (code != null && PASS_THROUGH_CODES.contains(code)) {
            ErrorCode errorCode = ErrorCode.valueOf(code);
            return message != null ? new AppException(errorCode, message) : new AppException(errorCode);
        }
        return new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
    }

    private JsonNode readTree(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        }
    }

    private String toJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize optimization request", e);
        }
    }
}
