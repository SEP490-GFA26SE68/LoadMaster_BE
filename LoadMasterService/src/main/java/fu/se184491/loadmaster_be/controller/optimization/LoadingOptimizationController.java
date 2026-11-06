package fu.se184491.loadmaster_be.controller.optimization;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.service.trip.TripService;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStartResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.TripLoadPlansResponse;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.LoadingOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tối ưu xếp hàng 3D của một chuyến và đọc phương án.
 * Điều phối viên chạy tối ưu; quản lý công ty chỉ đọc. Mọi đường dẫn kiểm chuyến / job thuộc công ty của phiên.
 * Đơn vị: toạ độ và kích thước cm, khối lượng kg.
 */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class LoadingOptimizationController {


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TripService tripService;
    private final OptimizationClient optimizationClient;

    private final LoadingOptimizationService loadingOptimizationService;
    private final LoadPlanQueryService loadPlanQueryService;


    @PostMapping(
            value = "/{tripId}/optimize-loading",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('DISPATCHER')")

    public ResponseEntity<Object> optimizeLoading(
            @PathVariable Long tripId
    ) {
        // 1. Kiểm tra Trip có thuộc công ty đang đăng nhập không.
        tripService.getTripById(tripId);

        // 2. Tạo JSON request cho FastAPI.
        Map<String, Object> request = Map.of(
                "trip_id", tripId,
                "objective", "MAX_VOLUME",
                "time_limit_sec", 60
        );

        // 3. Gửi request sang FastAPI bằng service token.
        String response = optimizationClient.createJob(request);

        // 4. Trả kết quả FastAPI cho Frontend (object JSON, không phải chuỗi escaped).
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(readJson(response));
    }


    @GetMapping(
            value = "/optimization-jobs/{jobId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<Object> getOptimizationJobStatus(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobStatus(jobId);

        return ResponseEntity.ok(readJson(response));
    }

    @GetMapping(
            value = "/optimization-jobs/{jobId}/plans",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('DISPATCHER')")
    public ResponseEntity<Object> getOptimizationJobPlans(
            @PathVariable String jobId
    ) {
        String response = optimizationClient.getJobPlans(jobId);

        return ResponseEntity.ok(readJson(response));
    }

    private Object readJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid optimization service response",
                    exception
            );
        }

    public ResponseEntity<ApiResponse<OptimizationJobStartResponse>> optimizeLoading(
            @PathVariable Long tripId
    ) {
        OptimizationJobStartResponse response = loadingOptimizationService.startLoadingOptimization(tripId);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Tác vụ tối ưu đã được khởi tạo", response));
    }

    /** Trạng thái job: status, thời gian chạy và số phương án đã lưu có kiện ({@code packedPlanCount}). */
    @GetMapping("/optimization-jobs/{jobId}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<OptimizationJobStatusResponse>> getOptimizationJobStatus(
            @PathVariable String jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Trạng thái tác vụ tối ưu", loadingOptimizationService.getJobStatus(jobId)));
    }

    @GetMapping("/optimization-jobs/{jobId}/plans")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<LoadPlanDetailResponse>>> getOptimizationJobPlans(
            @PathVariable String jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Danh sách kế hoạch xếp hàng", loadPlanQueryService.getJobPlans(jobId)));
    }

    @GetMapping("/{tripId}/load-plans")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER')")
    public ResponseEntity<ApiResponse<TripLoadPlansResponse>> getTripLoadPlans(
            @PathVariable Long tripId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Danh sách kế hoạch xếp hàng của chuyến", loadPlanQueryService.getTripLoadPlans(tripId)));
    }
}
