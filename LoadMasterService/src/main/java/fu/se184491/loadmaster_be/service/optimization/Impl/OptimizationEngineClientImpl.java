package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import org.springframework.stereotype.Component;

/** Python currently has no pinned-rerun route. Fail closed instead of inventing placements. */
@Component
public class OptimizationEngineClientImpl implements OptimizationEngineClient {
    @Override
    public OptimizationResultDto rerunOptimization(RerunEngineRequest request) {
        throw new AppException(ErrorCode.OPTIMIZATION_RERUN_UNAVAILABLE);
    }
}
