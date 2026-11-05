package fu.se184491.loadmaster_be.service.optimization.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Chạy lại tối ưu giữ kiện đã ghim qua dịch vụ tối ưu ({@code POST /api/v1/optimize/rerun}),
 * bằng service token của backend. Dịch vụ lỗi thì ném lỗi, không trả kết quả thay thế.
 */
@Slf4j
@Component
public class OptimizationEngineClientImpl implements OptimizationEngineClient {

    private final OptimizationClient optimizationClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public OptimizationEngineClientImpl(OptimizationClient optimizationClient) {
        this.optimizationClient = optimizationClient;
    }

    @Override
    public OptimizationResultDto rerunOptimization(RerunEngineRequest request) {
        log.info("Sending rerun request to Optimization Engine for job {}", request.getJobUuid());
        JsonNode data = optimizationClient.rerun(request);
        try {
            return objectMapper.treeToValue(data, OptimizationResultDto.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Rerun response cannot be read: {}", e.getMessage());
            throw new AppException(ErrorCode.OPTIMIZATION_SERVICE_UNAVAILABLE);
        }
    }
}
