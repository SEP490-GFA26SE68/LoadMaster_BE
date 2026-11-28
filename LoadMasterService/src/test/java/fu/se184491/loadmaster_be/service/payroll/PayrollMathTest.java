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
    void overtimeIsHourlyRateTimesHoursPerDayTimesDaysRoundedHalfUp() {
        assertEquals(new BigDecimal("300000"), PayrollMath.overtimeAmount(
                new BigDecimal("50000"), new BigDecimal("2.00"), 3));
        assertEquals(new BigDecimal("75001"), PayrollMath.overtimeAmount(
                new BigDecimal("50000.50"), new BigDecimal("1.50"), 1));
    }

    @Test
    void rejectsNegativeOvertimeDays() {
        AppException error = assertThrows(AppException.class, () -> PayrollMath.overtimeAmount(
                BigDecimal.ONE, BigDecimal.ONE, -1));
        assertEquals(ErrorCode.PAYROLL_INVALID_OVERTIME_DAYS, error.getErrorCode());
    }

    @Test
    void rejectsHoursPerDayAboveTwentyFour() {
        AppException error = assertThrows(AppException.class, () -> PayrollMath.overtimeAmount(
                BigDecimal.ONE, new BigDecimal("24.01"), 1));
        assertEquals(ErrorCode.PAYROLL_INVALID_AMOUNT, error.getErrorCode());
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

    @Test
    void netSalarySubtractsPenaltyFromEveryPositiveItem() {
        // 8.000.000 + 27.937 + 18.625 + 150 - 50.000 = 7.996.712
        assertEquals(new BigDecimal("7996712"), PayrollMath.netSalary(
                new BigDecimal("8000000"), new BigDecimal("27937"), new BigDecimal("18625"),
                new BigDecimal("150"), new BigDecimal("50000")));
    }

    @Test
    void netSalaryWithoutPenaltyIsTheSumOfItems() {
        assertEquals(new BigDecimal("8046712"), PayrollMath.netSalary(
                new BigDecimal("8000000"), new BigDecimal("27937"), new BigDecimal("18625"),
                new BigDecimal("150"), BigDecimal.ZERO));
    }

    @Test
    void netSalaryNeverGoesBelowZero() {
        assertEquals(BigDecimal.ZERO, PayrollMath.netSalary(
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500")));
    }

    @Test
    void kpiBonusIsPaidWhenTotalKgReachesTheTarget() {
        assertEquals(new BigDecimal("500000"),
                PayrollMath.kpiBonus(new BigDecimal("1200.500"), new BigDecimal("1000"), new BigDecimal("500000")));
    }

    @Test
    void kpiBonusIsPaidWhenTotalKgEqualsTheTargetExactly() {
        assertEquals(new BigDecimal("500000"),
                PayrollMath.kpiBonus(new BigDecimal("1000.000"), new BigDecimal("1000"), new BigDecimal("500000")));
    }

    @Test
    void kpiBonusIsZeroWhenTotalKgIsBelowTheTarget() {
        assertEquals(new BigDecimal("0"),
                PayrollMath.kpiBonus(new BigDecimal("999.999"), new BigDecimal("1000"), new BigDecimal("500000")));
    }

    @Test
    void kpiBonusIsZeroWhenTheCompanyDoesNotUseKpi() {
        assertEquals(new BigDecimal("0"),
                PayrollMath.kpiBonus(new BigDecimal("5000"), null, new BigDecimal("500000")));
    }

    @Test
    void kpiBonusIsRoundedToWholeDong() {
        assertEquals(new BigDecimal("500001"),
                PayrollMath.kpiBonus(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("500000.50")));
    }

    @Test
    void kpiBonusRejectsNegativeKg() {
        AppException exception = assertThrows(AppException.class,
                () -> PayrollMath.kpiBonus(new BigDecimal("-1"), new BigDecimal("1000"), new BigDecimal("500000")));

        assertEquals(ErrorCode.PAYROLL_INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void loadedAmountRoundsTheMonthTotalToWholeDongOnce() {
        // 22,750 kg × 150,50 đ = 3.423,875 đ → 3.424 đ.
        assertEquals(new BigDecimal("3424"),
                PayrollMath.loadedAmount(new BigDecimal("22.750"), new BigDecimal("150.50")));
    }

    @Test
    void loadedAmountIsZeroWithoutLoadedKg() {
        assertEquals(new BigDecimal("0"), PayrollMath.loadedAmount(new BigDecimal("0.000"), new BigDecimal("150.50")));
    }

    @Test
    void loadedAmountRejectsNegativeValues() {
        AppException exception = assertThrows(AppException.class,
                () -> PayrollMath.loadedAmount(new BigDecimal("-0.001"), new BigDecimal("150")));

        assertEquals(ErrorCode.PAYROLL_INVALID_AMOUNT, exception.getErrorCode());
    }
}
