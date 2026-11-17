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
    void driverGetsCommissionOnFreightPlusHandlingShareOnTheSameKg() {
        // 1000 kg, cước 40.000 đ/kg, tài xế hưởng 15% cước; bốc dỡ 150 đ/kg, tài xế hưởng 85%.
        PayrollMath.TripAmounts amounts = PayrollMath.calculateTripAmounts(
                new BigDecimal("1000"),
                new BigDecimal("40000"),
                new BigDecimal("15"),
                new BigDecimal("150"),
                new BigDecimal("85")
        );

        assertEquals(new BigDecimal("6000000"), amounts.commissionAmount());
        assertEquals(new BigDecimal("127500"), amounts.handlingAmount());
        assertEquals(new BigDecimal("6127500"), amounts.totalAmount());
    }

    @Test
    void roundsCommissionAndHandlingToWholeVndHalfUp() {
        // 0,333 kg × 150 × 85% = 42,4575 -> 42 ; 0,333 × 1000 × 15% = 49,95 -> 50
        PayrollMath.TripAmounts amounts = PayrollMath.calculateTripAmounts(
                new BigDecimal("0.333"),
                new BigDecimal("1000"),
                new BigDecimal("15"),
                new BigDecimal("150"),
                new BigDecimal("85")
        );

        assertEquals(new BigDecimal("50"), amounts.commissionAmount());
        assertEquals(new BigDecimal("42"), amounts.handlingAmount());
        assertEquals(new BigDecimal("92"), amounts.totalAmount());
    }

    @Test
    void rejectsPercentAboveOneHundred() {
        AppException error = assertThrows(AppException.class, () -> PayrollMath.calculateTripAmounts(
                new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("101"),
                new BigDecimal("150"), new BigDecimal("85")));

        assertEquals(ErrorCode.PAYROLL_INVALID_AMOUNT, error.getErrorCode());
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
                new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));

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
