package fu.se184491.loadmaster_be.dto.response;

import fu.se184491.loadmaster_be.exception.ErrorCode;

/**
 * Represents a non-blocking warning on a specific row during package batch import.
 *
 * @param rowIndex   1-based row index in the source file
 * @param field      Name of the field with warning
 * @param errorCode  ErrorCode identifying the type of warning
 * @param detail     Optional dynamic detail appended to the base message (e.g. the offending value)
 */
public record RowWarning(
        int rowIndex,
        String field,
        ErrorCode errorCode,
        String detail
) {
    /** Convenience constructor for warnings without dynamic detail. */
    public RowWarning(int rowIndex, String field, ErrorCode errorCode) {
        this(rowIndex, field, errorCode, null);
    }

    /** Returns the full human-readable message, appending detail when present. */
    public String message() {
        if (detail != null && !detail.isBlank()) {
            return errorCode.getMessage() + " (" + detail + ")";
        }
        return errorCode.getMessage();
    }
}
