package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.dto.request.optimization.OptimizationJobRequest;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobResponse;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.optimize.OptimizationJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages lifecycle of {@link OptimizationJob}.
 *
 * Status flow (S3-03):
 *   PENDING → RUNNING → COMPLETED
 *                     → FAILED
 *                     → TIMEOUT
 *                     → NO_SOLUTION
 *                     → PARTIAL
 */
@Service
@RequiredArgsConstructor
public class OptimizationJobServiceImpl implements OptimizationJobService {

    private final OptimizationJobRepository optimizationJobRepository;
    private final TripRepository tripRepository;

    // ── createJob ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public OptimizationJobResponse createJob(OptimizationJobRequest request) {
        Trip trip = tripRepository.findById(request.getTripId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        OptimizationJob job = OptimizationJob.builder()
                .trip(trip)
                .jobUuid(UUID.randomUUID().toString())
                .objective(request.getObjective())
                .timeLimitSec(request.getTimeLimitSec() != null ? request.getTimeLimitSec() : 60)
                .status(OptimizationJobStatus.PENDING)
                .build();

        job = optimizationJobRepository.save(job);
        return toResponse(job);
    }

    // ── getJob ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public OptimizationJobResponse getJob(String jobUuid) {
        OptimizationJob job = findOrThrow(jobUuid);
        return toResponse(job);
    }

    // ── findByTripId ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<OptimizationJobResponse> findByTripId(Long tripId) {
        return optimizationJobRepository.findByTripId(tripId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── findByStatus ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<OptimizationJobResponse> findByStatus(OptimizationJobStatus status) {
        return optimizationJobRepository.findByStatus(status)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void updateStatus(String jobUuid, OptimizationJobStatus status, Long computationMs) {
        OptimizationJob job = findOrThrow(jobUuid);
        job.setStatus(status);
        job.setComputationMs(computationMs);
        optimizationJobRepository.save(job);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private OptimizationJob findOrThrow(String jobUuid) {
        return optimizationJobRepository.findByJobUuid(jobUuid)
                .orElseThrow(() -> new AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND));
    }

    private OptimizationJobResponse toResponse(OptimizationJob job) {
        return OptimizationJobResponse.builder()
                .id(job.getId())
                .jobUuid(job.getJobUuid())
                .tripId(job.getTrip() != null ? job.getTrip().getId() : null)
                .objective(job.getObjective())
                .timeLimitSec(job.getTimeLimitSec())
                .status(job.getStatus())
                .computationMs(job.getComputationMs())
                .build();
    }
}
