package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequest;
import fu.se184491.loadmaster_be.dto.optimization.engine.ProblemRequestMapper;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.optimization.CargoPackageForTripRepository;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.optimize.AsyncOptimizationRunner;
import fu.se184491.loadmaster_be.service.optimize.EngineExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous implementation of {@link AsyncOptimizationRunner}.
 *
 * <p>Methods annotated with {@code @Async("optimizationExecutor")} run in
 * the dedicated thread pool defined in
 * {@link fu.se184491.loadmaster_be.config.AsyncConfig}.
 *
 * <p>The {@code @Transactional} scope on {@link #runJob(String)} is intentionally
 * scoped only to the data loading phase. The engine call (which can last minutes)
 * runs outside any transaction; {@link EngineExceptionHandler} handles its own
 * transactional status updates via {@link fu.se184491.loadmaster_be.service.optimize.OptimizationJobService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncOptimizationRunnerImpl implements AsyncOptimizationRunner {

    private final OptimizationJobRepository  optimizationJobRepository;
    private final CargoPackageForTripRepository cargoPackageForTripRepository;
    private final DeliveryStopRepository     deliveryStopRepository;
    private final ProblemRequestMapper       problemRequestMapper;
    private final EngineExceptionHandler     engineExceptionHandler;

    // ── AsyncOptimizationRunner ───────────────────────────────────────────────

    @Override
    @Async("optimizationExecutor")
    @Transactional(readOnly = true)
    public CompletableFuture<Void> runJob(String jobUuid) {
        log.info("[job={}] Background execution started on thread: {}",
                jobUuid, Thread.currentThread().getName());

        try {
            // 1. Load job + trip
            OptimizationJob job = optimizationJobRepository.findByJobUuid(jobUuid)
                    .orElseThrow(() -> new AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND));
            Trip trip = job.getTrip();

            // 2. Load packages and stops (trip-scoped data)
            List<CargoPackage> packages = cargoPackageForTripRepository
                    .findPackagesByTripId(trip.getId());
            List<DeliveryStop> stops = deliveryStopRepository
                    .findByTripIdOrderBySequence(trip.getId());

            // 3. Build ProblemRequest from domain objects
            ProblemRequest request = problemRequestMapper.toRequest(
                    trip, packages, stops,
                    job.getObjective(),
                    job.getTimeLimitSec(),
                    null,           // seed — optional, not stored in job currently
                    Collections.emptyList()  // pinned — no pre-fixed placements
            );

            // 4. Delegate to handler — handles exceptions + status updates internally
            engineExceptionHandler.executeWithHandling(jobUuid, request);

        } catch (AppException e) {
            // Job not found or trip inconsistency — nothing to update
            log.error("[job={}] Setup failed before engine call: {}", jobUuid, e.getMessage());
        } catch (Exception e) {
            // Unexpected error — EngineExceptionHandler already updated status for engine errors;
            // this catches setup-phase errors
            log.error("[job={}] Unexpected error in async runner: {}", jobUuid, e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(null);
    }
}
