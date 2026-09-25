package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.dto.request.optimization.RerunEngineRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;

public interface OptimizationEngineClient {

    OptimizationResultDto rerunOptimization(RerunEngineRequest request);
}
