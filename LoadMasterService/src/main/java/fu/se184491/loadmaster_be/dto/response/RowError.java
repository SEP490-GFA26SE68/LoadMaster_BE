package fu.se184491.loadmaster_be.dto.response;

import fu.se184491.loadmaster_be.exception.ErrorCode;

/**
 * Represents a validation error on a specific row and field during package batch import.
 *
 * @param rowIndex   1-based row index in the source file
 * @param field      Name of the field with error
 * @param errorCode  ErrorCode identifying the type of validation failure
 * @param detail     Optional dynamic detail appended to the base message (e.g. the offending value)
 */
public record RowError(
        int rowIndex,
        String field,
        ErrorCode errorCode,
        String detail
) {
    /** Convenience constructor for errors without dynamic detail. */
    public RowError(int rowIndex, String field, ErrorCode errorCode) {
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
