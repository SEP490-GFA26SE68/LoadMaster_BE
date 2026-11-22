package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PayrollMath {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private PayrollMath() {
    }

    /**
     * Phần tiền của tài xế cho một chuyến: hoa hồng trên cước theo kg + phần bốc dỡ theo kg.
     * Mỗi khoản làm tròn về đồng (HALF_UP) trước khi cộng.
     */
    public static TripAmounts calculateTripAmounts(
            BigDecimal planKg,
            BigDecimal freightVndPerKg,
            BigDecimal commissionPercent,
            BigDecimal handlingFeeVndPerKg,
            BigDecimal handlingDriverPercent
    ) {
        requireNonNegative(planKg, freightVndPerKg, commissionPercent, handlingFeeVndPerKg, handlingDriverPercent);
        requirePercent(commissionPercent, handlingDriverPercent);
        BigDecimal commission = planKg.multiply(freightVndPerKg).multiply(commissionPercent)
                .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP);
        BigDecimal handling = planKg.multiply(handlingFeeVndPerKg).multiply(handlingDriverPercent)
                .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP);
        return new TripAmounts(commission, handling, commission.add(handling));
    }

    /**
     * Tiền thưởng KPI của một tài xế trong tháng: bằng mức thưởng khi tổng kg không nhỏ hơn chỉ tiêu, ngược lại 0.
     * Không có chỉ tiêu (null) là công ty không dùng KPI: 0.
     */
    public static BigDecimal kpiBonus(BigDecimal totalKg, BigDecimal targetKg, BigDecimal bonusVnd) {
        requireNonNegative(totalKg);
        if (targetKg == null || totalKg.compareTo(targetKg) < 0) return BigDecimal.ZERO;
        requireNonNegative(bonusVnd);
        return bonusVnd.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Tiền theo kg đã xếp của nhân viên kho trong tháng: tổng kg × đơn giá, làm tròn đồng HALF_UP một lần trên tổng tháng
     * (như hoa hồng theo kg của tài xế làm tròn từng khoản về đồng).
     */
    public static BigDecimal loadedAmount(BigDecimal loadedKg, BigDecimal rateVndPerKg) {
        requireNonNegative(loadedKg, rateVndPerKg);
        return loadedKg.multiply(rateVndPerKg).setScale(0, RoundingMode.HALF_UP);
    }

    private static void requirePercent(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value.compareTo(ONE_HUNDRED) > 0) throw new AppException(ErrorCode.PAYROLL_INVALID_AMOUNT);
        }
    }

    public static List<BigDecimal> splitEvenly(BigDecimal amount, int participantCount) {
        requireParticipantCount(participantCount);
        requireNonNegative(amount);
        BigDecimal roundedAmount = amount.setScale(0, RoundingMode.HALF_UP);
        BigDecimal[] quotientAndRemainder = roundedAmount.divideAndRemainder(BigDecimal.valueOf(participantCount));
        int remainder = quotientAndRemainder[1].intValueExact();

        List<BigDecimal> shares = new ArrayList<>(participantCount);
        for (int i = 0; i < participantCount; i++) {
            shares.add(i < remainder ? quotientAndRemainder[0].add(BigDecimal.ONE) : quotientAndRemainder[0]);
        }
        return List.copyOf(shares);
    }

    /** Allocates a three-decimal quantity by percentage and assigns rounding units by largest remainder. */
    public static List<BigDecimal> allocateQuantityByPercent(BigDecimal quantity, List<BigDecimal> percentages) {
        requireNonNegative(quantity);
        if (percentages == null) requireParticipantCount(0);
        requireParticipantCount(percentages.size());

        BigDecimal totalPercent = BigDecimal.ZERO;
        for (BigDecimal percentage : percentages) {
            if (percentage == null || percentage.signum() <= 0) {
                throw new AppException(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT);
            }
            totalPercent = totalPercent.add(percentage);
        }
        if (totalPercent.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT);
        }

        BigInteger totalUnits = quantity.setScale(3, RoundingMode.HALF_UP).movePointRight(3).toBigIntegerExact();
        List<QuantityShare> shares = new ArrayList<>(percentages.size());
        BigInteger allocatedUnits = BigInteger.ZERO;
        for (int index = 0; index < percentages.size(); index++) {
            BigDecimal exactUnits = new BigDecimal(totalUnits)
                    .multiply(percentages.get(index))
                    .divide(BigDecimal.valueOf(100));
            BigInteger wholeUnits = exactUnits.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
            BigDecimal fractionalUnits = exactUnits.subtract(new BigDecimal(wholeUnits));
            shares.add(new QuantityShare(index, wholeUnits, fractionalUnits));
            allocatedUnits = allocatedUnits.add(wholeUnits);
        }

        BigInteger remainingUnits = totalUnits.subtract(allocatedUnits);
        List<QuantityShare> byLargestRemainder = shares.stream()
                .sorted(Comparator.comparing(QuantityShare::fractionalUnits).reversed()
                        .thenComparingInt(QuantityShare::index))
                .toList();
        int remainingCount = remainingUnits.intValueExact();
        BigInteger[] unitsByDriver = new BigInteger[percentages.size()];
        for (QuantityShare share : shares) unitsByDriver[share.index()] = share.wholeUnits();
        for (int i = 0; i < remainingCount; i++) {
            int index = byLargestRemainder.get(i).index();
            unitsByDriver[index] = unitsByDriver[index].add(BigInteger.ONE);
        }

        List<BigDecimal> result = new ArrayList<>(percentages.size());
        for (BigInteger units : unitsByDriver) result.add(new BigDecimal(units, 3));
        return List.copyOf(result);
    }

    public static List<BigDecimal> equalSharePercentages(int participantCount) {
        requireParticipantCount(participantCount);
        int basisPointsPerDriver = 10_000 / participantCount;
        int remainder = 10_000 % participantCount;
        List<BigDecimal> percentages = new ArrayList<>(participantCount);
        for (int i = 0; i < participantCount; i++) {
            int basisPoints = basisPointsPerDriver + (i < remainder ? 1 : 0);
            percentages.add(BigDecimal.valueOf(basisPoints, 2));
        }
        return List.copyOf(percentages);
    }

    private static void requireParticipantCount(int participantCount) {
        if (participantCount < 1) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT);
        }
    }

    /**
     * Tiền tăng ca: VND/giờ × giờ/ngày × số ngày, làm tròn đồng HALF_UP.
     */
    public static BigDecimal overtimeAmount(BigDecimal vndPerHour, BigDecimal hoursPerDay, int days) {
        requireNonNegative(vndPerHour, hoursPerDay);
        if (days < 0) throw new AppException(ErrorCode.PAYROLL_INVALID_OVERTIME_DAYS);
        if (hoursPerDay.compareTo(BigDecimal.valueOf(24)) > 0) {
            throw new AppException(ErrorCode.PAYROLL_INVALID_AMOUNT);
        }
        return vndPerHour.multiply(hoursPerDay)
                .multiply(BigDecimal.valueOf(days))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value == null || value.signum() < 0) {
                throw new AppException(ErrorCode.PAYROLL_INVALID_AMOUNT);
            }
        }
    }

    private record QuantityShare(int index, BigInteger wholeUnits, BigDecimal fractionalUnits) {
    }

    public record TripAmounts(BigDecimal commissionAmount, BigDecimal handlingAmount, BigDecimal totalAmount) {
    }
}
