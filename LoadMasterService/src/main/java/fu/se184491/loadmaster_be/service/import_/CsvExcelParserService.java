package fu.se184491.loadmaster_be.service.import_;

import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CsvExcelParserService {

    /**
     * Parses an uploaded CSV or Excel (.xlsx) file into a list of PackageImportRow DTOs.
     *
     * @param file uploaded MultipartFile
     * @return List of PackageImportRow
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.EMPTY_FILE
     *         if file is empty or contains no data rows
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.FILE_SIZE_LIMIT_EXCEEDED
     *         if file exceeds 10MB
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.UNSUPPORTED_FILE_TYPE
     *         if file extension is not .csv or .xlsx
     * @throws fu.se184491.loadmaster_be.exception.AppException with ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED
     *         if row count exceeds 1000
     */
    List<PackageImportRow> parse(MultipartFile file);
}
