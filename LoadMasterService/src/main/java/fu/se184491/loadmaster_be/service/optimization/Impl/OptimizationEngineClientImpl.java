package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementResultDto;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OptimizationEngineClientImpl implements OptimizationEngineClient {

    private final RestClient restClient;
    private final String engineBaseUrl;

    public OptimizationEngineClientImpl(
            @Value("${optimization.engine.url:http://localhost:8000}") String engineBaseUrl) {
        this.engineBaseUrl = engineBaseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(engineBaseUrl)
                .build();
    }

    @Override
    public OptimizationResultDto rerunOptimization(RerunEngineRequest request) {
        log.info("Sending rerun request to Optimization Engine at {} for job {}", engineBaseUrl, request.getJobUuid());

        try {
            OptimizationResultDto response = restClient.post()
                    .uri("/api/v1/optimize/rerun")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OptimizationResultDto.class);

            if (response != null) {
                return response;
            }
        } catch (Exception ex) {
            log.warn("Optimization engine call failed: {}. Falling back to default pinned placement calculation.", ex.getMessage());
        }

        // Fallback result preserving pinned placements if external engine is unreachable
        List<PlacementResultDto> placements = new ArrayList<>();
        if (request.getPinnedPlacements() != null) {
            request.getPinnedPlacements().forEach(p -> placements.add(
                    PlacementResultDto.builder()
                            .packageId(p.getPackageId())
                            .posX(p.getPosX())
                            .posY(p.getPosY())
                            .posZ(p.getPosZ())
                            .packedLength(p.getPackedLength())
                            .packedWidth(p.getPackedWidth())
                            .packedHeight(p.getPackedHeight())
                            .rotationType(p.getRotationType())
                            .stepSequence(p.getStepSequence())
                            .pinned(true)
                            .build()
            ));
        }

        return OptimizationResultDto.builder()
                .packedItemsCount(placements.size())
                .volumeUtilization(new BigDecimal("0.00"))
                .weightUtilization(new BigDecimal("0.00"))
                .computationMs(0L)
                .placements(placements)
                .build();
    }
}
