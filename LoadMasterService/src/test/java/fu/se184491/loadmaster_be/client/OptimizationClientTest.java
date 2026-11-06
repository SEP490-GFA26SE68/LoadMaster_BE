package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OptimizationClientTest {

    private final OptimizationClient client =
            new OptimizationClient(mock(RestClient.class), mock(ServiceTokenProvider.class));

    private static HttpClientErrorException error(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void businessCodeOfOptimizationServiceIsKept() {
        AppException result = client.translate(error(HttpStatus.BAD_REQUEST,
                "{\"success\":false,\"code\":\"PLAN_ALREADY_APPROVED\",\"message\":\"Đã duyệt\"}"));

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PLAN_ALREADY_APPROVED);
    }

    @Test
    void unknownOrMissingCodeIsServiceUnavailable() {
        assertThat(client.translate(error(HttpStatus.FORBIDDEN, "{\"code\":\"FORBIDDEN\"}")).getErrorCode())
                .isEqualTo(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        assertThat(client.translate(error(HttpStatus.BAD_GATEWAY, "<html>bad gateway</html>")).getErrorCode())
                .isEqualTo(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
    }
}
