package ar.emedicapp.payments;

import static org.assertj.core.api.Assertions.*;

import ar.emedicapp.common.AppException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class FinancialRulesTest {
    @Test
    void realAmountMustFitCurrencyAndStoredPrecisionWithoutRounding() {
        for (String value : new String[] {"-1", "0", "1.001", "100000000000000"})
            assertThatThrownBy(
                            () ->
                                    FinancialRules.checkedAmount(
                                            new BigDecimal(value), "ARS", "ARS", false))
                    .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> FinancialRules.checkedAmount(BigDecimal.ONE, "USD", "ARS", false))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> FinancialRules.checkedAmount(null, "ARS", "ARS", false))
                .isInstanceOf(AppException.class);
        assertThat(
                        FinancialRules.checkedAmount(
                                new BigDecimal("99999999999999.99"), "ARS", "ARS", false))
                .isEqualByComparingTo("99999999999999.99");
        assertThat(FinancialRules.checkedAmount(BigDecimal.ZERO, "ARS", "ARS", true))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
