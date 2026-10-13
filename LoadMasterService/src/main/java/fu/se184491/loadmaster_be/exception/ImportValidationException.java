package fu.se184491.loadmaster_be.exception;

import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import lombok.Getter;

@Getter
public class ImportValidationException extends AppException {
    private final ImportPreviewResult previewResult;

    public ImportValidationException(ImportPreviewResult previewResult) {
        super(ErrorCode.IMPORT_HAS_ERRORS);
        this.previewResult = previewResult;
    }

    @Override
    public String getMessage() {
        int errorCount = previewResult != null && previewResult.errors() != null ? previewResult.errors().size() : 0;
        return String.format("File import chứa %d dòng lỗi dữ liệu, vui lòng kiểm tra lại", errorCount);
    }
}
