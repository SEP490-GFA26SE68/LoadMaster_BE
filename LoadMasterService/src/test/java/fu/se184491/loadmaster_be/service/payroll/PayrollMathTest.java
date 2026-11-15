package fu.se184491.loadmaster_be.service.payroll;

import org.junit.jupiter.api.Test;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayrollMathTest {

    @Test
    void calculatesKgAndPlannedKmAsSeparateAdditiveAmounts() {
        PayrollMath.TripAmounts amounts = PayrollMath.calculateTripAmounts(
                new BigDecimal("1000"),
                new BigDecimal("120"),
                new BigDecimal("100"),
                new BigDecimal("2000")
        );

        assertEquals(new BigDecimal("100000"), amounts.kgAmount());
        assertEquals(new BigDecimal("240000"), amounts.kmAmount());
        assertEquals(new BigDecimal("340000"), amounts.totalAmount());
    }

    @Test
    void splitsTripAmountEvenlyWithoutLosingRemainderVnd() {
        List<BigDecimal> shares = PayrollMath.splitEvenly(new BigDecimal("340001"), 2);

        assertEquals(List.of(new BigDecimal("170001"), new BigDecimal("170000")), shares);
        assertEquals(new BigDecimal("340001"), shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void makesEqualDriverPercentagesAddUpToExactlyOneHundred() {
        assertEquals(
                List.of(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33")),
                PayrollMath.equalSharePercentages(3)
        );
    }

    @Test
    void reportsInvalidDriverCountUsingPayrollErrorCode() {
        AppException exception = assertThrows(AppException.class, () -> PayrollMath.equalSharePercentages(0));

        assertEquals(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT, exception.getErrorCode());
    }

    @Test
    void reportsNegativeWeightUsingPayrollErrorCode() {
        AppException exception = assertThrows(AppException.class, () -> PayrollMath.calculateTripAmounts(
                new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));

        assertEquals(ErrorCode.PAYROLL_INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void reportsNullAmountWhenSplittingUsingPayrollErrorCode() {
        AppException exception = assertThrows(AppException.class, () -> PayrollMath.splitEvenly(null, 2));

        assertEquals(ErrorCode.PAYROLL_INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void allocatesQuantityByLargestRemainderSoDriverAmountsReconcile() {
        List<BigDecimal> allocated = PayrollMath.allocateQuantityByPercent(
                new BigDecimal("1.000"),
                List.of(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33")));

        assertEquals(List.of(new BigDecimal("0.334"), new BigDecimal("0.333"), new BigDecimal("0.333")), allocated);
        assertEquals(new BigDecimal("1.000"), allocated.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void rejectsPercentagesThatDoNotSumToOneHundredWithPayrollErrorCode() {
        AppException exception = assertThrows(AppException.class, () -> PayrollMath.allocateQuantityByPercent(
                BigDecimal.ONE, List.of(new BigDecimal("60"), new BigDecimal("30"))));

        assertEquals(ErrorCode.PAYROLL_INVALID_DRIVER_ASSIGNMENT, exception.getErrorCode());
    }
}
