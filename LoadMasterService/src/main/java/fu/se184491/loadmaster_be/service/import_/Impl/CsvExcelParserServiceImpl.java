package fu.se184491.loadmaster_be.service.import_.Impl;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.import_.CsvExcelParserService;
import fu.se184491.loadmaster_be.dto.request.PackageImportRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class CsvExcelParserServiceImpl implements CsvExcelParserService {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10MB
    public static final int MAX_BATCH_ROWS = 1000;

    @Override
    public List<PackageImportRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new AppException(ErrorCode.EMPTY_FILE);
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new AppException(ErrorCode.FILE_SIZE_LIMIT_EXCEEDED);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            throw new AppException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        String lowerName = originalFilename.trim().toLowerCase();
        try (InputStream is = file.getInputStream()) {
            if (lowerName.endsWith(".csv")) {
                return parseCsv(is);
            } else if (lowerName.endsWith(".xlsx")) {
                return parseExcel(is);
            } else {
                throw new AppException(ErrorCode.UNSUPPORTED_FILE_TYPE);
            }
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to read file input stream", e);
            throw new AppException(ErrorCode.FILE_INVALID);
        }
    }

    // -------------------------------------------------------------------------
    // CSV parsing
    // -------------------------------------------------------------------------

    private List<PackageImportRow> parseCsv(InputStream inputStream) {
        List<PackageImportRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             CSVReader csvReader = new CSVReader(reader)) {

            List<String[]> allLines = csvReader.readAll();
            if (allLines.isEmpty()) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            // Find header line (first non-empty line)
            int headerLineIndex = -1;
            String[] headerRow = null;
            for (int i = 0; i < allLines.size(); i++) {
                String[] line = allLines.get(i);
                if (!isLineEmpty(line)) {
                    headerLineIndex = i;
                    headerRow = line;
                    break;
                }
            }

            if (headerRow == null) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            Map<String, Integer> colMap = buildColumnMapping(headerRow);

            int dataRowCount = 0;
            for (int i = headerLineIndex + 1; i < allLines.size(); i++) {
                String[] line = allLines.get(i);
                if (isLineEmpty(line)) {
                    continue;
                }

                dataRowCount++;
                if (dataRowCount > MAX_BATCH_ROWS) {
                    throw new AppException(ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED);
                }

                int rowIndex = i + 1; // 1-based line number in file
                rows.add(buildRow(rowIndex, line, colMap));
            }

            if (dataRowCount == 0) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            return rows;

        } catch (AppException e) {
            throw e;
        } catch (IOException | CsvException e) {
            log.error("Failed to parse CSV file", e);
            throw new AppException(ErrorCode.FILE_INVALID);
        }
    }

    // -------------------------------------------------------------------------
    // Excel parsing
    // -------------------------------------------------------------------------

    private List<PackageImportRow> parseExcel(InputStream inputStream) {
        List<PackageImportRow> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            Iterator<Row> rowIterator = sheet.rowIterator();
            if (!rowIterator.hasNext()) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            // Read header row (skip blank rows)
            Row headerRow = null;
            while (rowIterator.hasNext()) {
                Row r = rowIterator.next();
                if (!isExcelRowEmpty(r)) {
                    headerRow = r;
                    break;
                }
            }

            if (headerRow == null) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            Map<String, Integer> colMap = buildExcelColumnMapping(headerRow);

            int dataRowCount = 0;
            while (rowIterator.hasNext()) {
                Row r = rowIterator.next();
                if (isExcelRowEmpty(r)) {
                    continue;
                }

                dataRowCount++;
                if (dataRowCount > MAX_BATCH_ROWS) {
                    throw new AppException(ErrorCode.BATCH_SIZE_LIMIT_EXCEEDED);
                }

                int rowIndex = r.getRowNum() + 1; // 1-based row index
                rows.add(buildExcelRow(rowIndex, r, colMap));
            }

            if (dataRowCount == 0) {
                throw new AppException(ErrorCode.EMPTY_FILE);
            }

            return rows;

        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to read Excel workbook", e);
            throw new AppException(ErrorCode.FILE_INVALID);
        }
    }

    // -------------------------------------------------------------------------
    // Column mapping helpers
    // -------------------------------------------------------------------------

    private Map<String, Integer> buildColumnMapping(String[] headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerRow.length; i++) {
            String colName = normalize(headerRow[i]);
            map.put(colName, i);
        }
        return map;
    }

    private Map<String, Integer> buildExcelColumnMapping(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            if (cell != null) {
                String val = getCellStringValue(cell);
                if (val != null && !val.trim().isEmpty()) {
                    map.put(normalize(val), i);
                }
            }
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Row builders
    // -------------------------------------------------------------------------

    private PackageImportRow buildRow(int rowIndex, String[] line, Map<String, Integer> colMap) {
        String packageCode    = getField(line, colMap, "packagecode", "package_code", "code", "makien");
        BigDecimal lengthCm   = parseBigDecimal(getField(line, colMap, "length", "lengthcm", "length_cm", "chieudai"));
        BigDecimal widthCm    = parseBigDecimal(getField(line, colMap, "width", "widthcm", "width_cm", "chieurong"));
        BigDecimal heightCm   = parseBigDecimal(getField(line, colMap, "height", "heightcm", "height_cm", "chieucao"));
        BigDecimal weightKg   = parseBigDecimal(getField(line, colMap, "weight", "weightkg", "weight_kg", "khoiluong", "trongluong"));
        String handlingClass  = getField(line, colMap, "handlingclass", "handling_class", "loaihang");
        String destination    = getField(line, colMap, "destination", "dest", "dich", "diemden", "address", "stop");

        return new PackageImportRow(rowIndex, packageCode, lengthCm, widthCm, heightCm, weightKg, handlingClass, destination);
    }

    private PackageImportRow buildExcelRow(int rowIndex, Row row, Map<String, Integer> colMap) {
        String packageCode    = getExcelField(row, colMap, "packagecode", "package_code", "code", "makien");
        BigDecimal lengthCm   = parseBigDecimal(getExcelField(row, colMap, "length", "lengthcm", "length_cm", "chieudai"));
        BigDecimal widthCm    = parseBigDecimal(getExcelField(row, colMap, "width", "widthcm", "width_cm", "chieurong"));
        BigDecimal heightCm   = parseBigDecimal(getExcelField(row, colMap, "height", "heightcm", "height_cm", "chieucao"));
        BigDecimal weightKg   = parseBigDecimal(getExcelField(row, colMap, "weight", "weightkg", "weight_kg", "khoiluong", "trongluong"));
        String handlingClass  = getExcelField(row, colMap, "handlingclass", "handling_class", "loaihang");
        String destination    = getExcelField(row, colMap, "destination", "dest", "dich", "diemden", "address", "stop");

        return new PackageImportRow(rowIndex, packageCode, lengthCm, widthCm, heightCm, weightKg, handlingClass, destination);
    }

    // -------------------------------------------------------------------------
    // Field extraction utilities
    // -------------------------------------------------------------------------

    private String getField(String[] line, Map<String, Integer> colMap, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colMap.get(alias);
            if (idx != null && idx < line.length) {
                String val = line[idx];
                return val != null ? val.trim() : null;
            }
        }
        return null;
    }

    private String getExcelField(Row row, Map<String, Integer> colMap, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colMap.get(alias);
            if (idx != null) {
                Cell cell = row.getCell(idx);
                if (cell != null) {
                    return getCellStringValue(cell);
                }
            }
        }
        return null;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num)) {
                    return String.valueOf((long) num);
                }
                return String.valueOf(num);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            default:
                return null;
        }
    }

    // -------------------------------------------------------------------------
    // Empty-row detection utilities
    // -------------------------------------------------------------------------

    private boolean isLineEmpty(String[] line) {
        if (line == null || line.length == 0) return true;
        for (String s : line) {
            if (s != null && !s.trim().isEmpty()) return false;
        }
        return true;
    }

    private boolean isExcelRowEmpty(Row row) {
        if (row == null) return true;
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellStringValue(cell);
                if (val != null && !val.trim().isEmpty()) return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Parsing utilities
    // -------------------------------------------------------------------------

    private Integer parseInteger(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try {
            // Handle decimal strings like "500.0" from Excel
            double d = Double.parseDouble(val.trim());
            return (int) Math.round(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try {
            return new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalize(String str) {
        if (str == null) return "";
        return str.trim()
                .toLowerCase()
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("[_\\-\\s]", "");
    }
}
