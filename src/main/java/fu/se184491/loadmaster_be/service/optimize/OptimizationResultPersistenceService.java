package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.dto.optimization.engine.OptimizationResult;

/**
 * Persists the result of a completed optimization run to the database (S3-09).
 *
 * <p>All inserts must be wrapped in a single transaction:
 * <ol>
 *   <li>INSERT {@link fu.se184491.loadmaster_be.entity.optimization.LoadPlan}</li>
 *   <li>INSERT {@link fu.se184491.loadmaster_be.entity.optimization.PackagePlacement}
 *       for each placed package</li>
 *   <li>INSERT {@link fu.se184491.loadmaster_be.entity.optimization.UnplacedPackage}
 *       for each unplaced package</li>
 *   <li>UPDATE {@link fu.se184491.loadmaster_be.entity.optimization.OptimizationJob}
 *       status → COMPLETED and computationMs</li>
 * </ol>
 */
public interface OptimizationResultPersistenceService {

    /**
     * Saves everything returned by the engine for the given job in one transaction.
     *
     * @param jobUuid the UUID of the job that completed
     * @param result  the result returned by the FastAPI engine
     */
    void persist(String jobUuid, OptimizationResult result);
}
