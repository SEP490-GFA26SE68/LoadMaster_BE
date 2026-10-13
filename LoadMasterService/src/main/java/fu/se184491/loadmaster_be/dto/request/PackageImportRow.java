package fu.se184491.loadmaster_be.dto.request;

import java.math.BigDecimal;

/**
 * Parsed raw package import row from CSV or Excel file.
 *
 * @param rowIndex      1-based row index in the source file
 * @param packageCode   Company internal package code
 * @param lengthCm      Length in centimeters
 * @param widthCm       Width in centimeters
 * @param heightCm      Height in centimeters
 * @param weightKg      Weight in kilograms
 * @param handlingClass Handling class name (e.g. STANDARD, FRAGILE, etc.)
 * @param destination   Delivery stop / destination address or code
 */
public record PackageImportRow(
        int rowIndex,
        String packageCode,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        BigDecimal weightKg,
        String handlingClass,
        String destination
) {}
