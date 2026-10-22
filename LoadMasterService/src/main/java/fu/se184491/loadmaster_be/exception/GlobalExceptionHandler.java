package fu.se184491.loadmaster_be.exception;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern ENUM_ERROR_PATTERN = Pattern.compile(
            "from String \"([^\"]*)\": not one of the values accepted for Enum class: (\\[[^\\]]+\\])"
    );
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile(
            "\\[\"([^\"]+)\"\\]\\)?\\s*$"
    );

    @ExceptionHandler(ImportValidationException.class)
    public ResponseEntity<ApiResponse<ImportPreviewResult>> handleImportValidationException(
            ImportValidationException ex, HttpServletRequest request) {
        log.warn("Import validation exception at {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.<ImportPreviewResult>builder()
                        .success(false)
                        .code(errorCode.name())
                        .message(ex.getMessage())
                        .data(ex.getPreviewResult())
                        .errors(ex.getPreviewResult() != null ? ex.getPreviewResult().errors() : null)
                        .build());
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(
            AppException ex, HttpServletRequest request) {
        log.warn("Business exception at {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(InsufficientCreditsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientCreditsException(
            InsufficientCreditsException ex, HttpServletRequest request) {
        log.warn("Insufficient credits at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(IncompleteLoadingException.class)
    public ResponseEntity<ApiResponse<Void>> handleIncompleteLoadingException(
            IncompleteLoadingException ex, HttpServletRequest request) {
        log.warn("Incomplete loading exception at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(DuplicateUnloadException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateUnloadException(
            DuplicateUnloadException ex, HttpServletRequest request) {
        log.warn("Duplicate unload exception at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(WrongStopException.class)
    public ResponseEntity<ApiResponse<Void>> handleWrongStopException(
            WrongStopException ex, HttpServletRequest request) {
        log.warn("Wrong stop exception at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(IncompleteStopException.class)
    public ResponseEntity<ApiResponse<Void>> handleIncompleteStopException(
            IncompleteStopException ex, HttpServletRequest request) {
        log.warn("Incomplete stop exception at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationExceptions(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        FieldError fieldError = ex.getFieldError();
        String errorMessage = fieldError != null ? fieldError.getDefaultMessage() : ErrorCode.VALIDATION_ERROR.getMessage();
        ErrorCode errorCode = ErrorCode.VALIDATION_ERROR;

        if (errorMessage != null) {
            try {
                errorCode = ErrorCode.valueOf(errorMessage);
                errorMessage = errorCode.getMessage();
            } catch (IllegalArgumentException ignored) {
                // Sử dụng trực tiếp message từ validation annotation
            }
        }

        log.warn("Validation error at {}: {}", request.getRequestURI(), errorMessage);

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(errorMessage)
                        .data(null)
                        .build());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        log.warn("Database constraint violation at {}: {}",
                request.getRequestURI(), ex.getMessage());

        Throwable cause = ex;

        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintEx) {

                String constraintName = constraintEx.getConstraintName();

                if ("uk_package_types_company_code".equalsIgnoreCase(constraintName)) {
                    return ResponseEntity
                            .status(HttpStatus.CONFLICT)
                            .body(ApiResponse.<Void>builder()
                                    .success(false)
                                    .code("PACKAGE_TYPE_CODE_ALREADY_EXISTS")
                                    .message("Mã loại kiện đã tồn tại trong công ty")
                                    .data(null)
                                    .build());
                }
            }

            cause = cause.getCause();
        }

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message("Dữ liệu đang vi phạm ràng buộc hoặc được sử dụng bởi dữ liệu khác")
                        .data(null)
                        .build());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(ErrorCode.UNAUTHORIZED.getHttpStatus())
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ErrorCode.UNAUTHORIZED.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(
            IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message("Phương thức HTTP không được hỗ trợ: " + ex.getMethod())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message("Định dạng dữ liệu không được hỗ trợ: " + ex.getContentType())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message("Thiếu tham số bắt buộc: " + ex.getParameterName())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message;
        Class<?> requiredType = ex.getRequiredType();
        if (requiredType != null && requiredType.isEnum()) {
            message = String.format(
                    "Giá trị tham số '%s' không hợp lệ: '%s'. Các giá trị hợp lệ: %s",
                    ex.getName(),
                    ex.getValue(),
                    Arrays.toString(requiredType.getEnumConstants())
            );
        } else {
            message = String.format(
                    "Giá trị tham số '%s' không đúng định dạng: '%s'",
                    ex.getName(),
                    ex.getValue()
            );
        }
        log.warn("Type mismatch at {}: {}", request.getRequestURI(), message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(message)
                        .data(null)
                        .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        String rawMessage = ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage()
                : ex.getMessage();
        log.warn("Malformed JSON or invalid enum body at {}: {}", request.getRequestURI(), rawMessage);

        String message = "Dữ liệu JSON đầu vào không đúng định dạng hoặc bị thiếu";
        if (rawMessage != null) {
            Matcher enumMatcher = ENUM_ERROR_PATTERN.matcher(rawMessage);
            if (enumMatcher.find()) {
                String invalidValue = enumMatcher.group(1);
                String validValues = resolveAllowedEnumValues(rawMessage, enumMatcher.group(2));
                Matcher fieldMatcher = FIELD_NAME_PATTERN.matcher(rawMessage);
                if (fieldMatcher.find()) {
                    String fieldName = fieldMatcher.group(1);
                    message = String.format(
                            "Giá trị trường '%s' không hợp lệ: '%s'. Các giá trị hợp lệ: %s",
                            fieldName,
                            invalidValue,
                            validValues
                    );
                } else {
                    message = String.format(
                            "Giá trị Enum không hợp lệ: '%s'. Các giá trị hợp lệ: %s",
                            invalidValue,
                            validValues
                    );
                }
            }
        }

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(message)
                        .data(null)
                        .build());
    }

    private String resolveAllowedEnumValues(String rawMessage, String defaultEnumValues) {
        if (!rawMessage.contains("UserRole")) {
            return defaultEnumValues;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            boolean isSystemAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_SYSTEM_ADMIN".equals(a.getAuthority()) || "SYSTEM_ADMIN".equals(a.getAuthority()));
            if (isSystemAdmin) {
                return "[SYSTEM_ADMIN, SYSTEM_MANAGER, SYSTEM_SUPPORTER, ADMIN, MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]";
            }
            boolean isSystemManager = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_SYSTEM_MANAGER".equals(a.getAuthority()) || "SYSTEM_MANAGER".equals(a.getAuthority()));
            if (isSystemManager) {
                return "[ADMIN, MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]";
            }
            boolean isCompanyAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
            if (isCompanyAdmin) {
                return "[MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]";
            }
        }
        return defaultEnumValues;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message("Không tìm thấy đường dẫn API hoặc thiếu tham số đường dẫn: " + request.getRequestURI())
                        .data(null)
                        .build());
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at " + request.getRequestURI(), ex);

        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.<Void>builder()
                        .success(false)
                        .message(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
                        .data(null)
                        .build());
    }
}

