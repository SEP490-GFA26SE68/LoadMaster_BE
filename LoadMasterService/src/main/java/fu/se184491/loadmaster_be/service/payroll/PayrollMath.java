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

    private PayrollMath() {
    }

    public static TripAmounts calculateTripAmounts(
            BigDecimal planKg,
            BigDecimal planKm,
            BigDecimal rateVndPerKg,
            BigDecimal rateVndPerKm
    ) {
        requireNonNegative(planKg, planKm, rateVndPerKg, rateVndPerKm);
        BigDecimal kgAmount = planKg.multiply(rateVndPerKg).setScale(0, RoundingMode.HALF_UP);
        BigDecimal kmAmount = planKm.multiply(rateVndPerKm).setScale(0, RoundingMode.HALF_UP);
        return new TripAmounts(kgAmount, kmAmount, kgAmount.add(kmAmount));
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

    private static void requireNonNegative(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value == null || value.signum() < 0) {
                throw new AppException(ErrorCode.PAYROLL_INVALID_AMOUNT);
            }
        }
    }

    private record QuantityShare(int index, BigInteger wholeUnits, BigDecimal fractionalUnits) {
    }

    public record TripAmounts(BigDecimal kgAmount, BigDecimal kmAmount, BigDecimal totalAmount) {
    }
}
