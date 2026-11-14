package org.financelab.budget;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Parses signed whole-ruble form inputs and formats saved figures for Russian budget pages. */
@Component
public class Rubles {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru-RU");
    private static final Pattern WHOLE_AMOUNT =
            Pattern.compile("[+-]?(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[,.]0+)?");

    /** Creates the Russian whole-ruble formatter used by the budget views. */
    public Rubles() {}

    /**
     * Parses an amount exactly. A fractional part is accepted only if all its digits are zero.
     * @param draft text from the amount field, optionally grouped with spaces
     * @return amount in whole rubles
     * @throws IllegalArgumentException with a Russian validation message if the amount is missing,
     *         malformed, fractional, or outside the signed 64-bit range
     */
    static long parse(@Nullable String draft) {
        String value = Objects.requireNonNullElse(draft, "")
                .replace('\u00a0', ' ').replace('\u202f', ' ').strip().replace('−', '-');
        if (!WHOLE_AMOUNT.matcher(value).matches()) {
            throw new IllegalArgumentException("Введите сумму в целых рублях, без копеек.");
        }
        try {
            return new BigDecimal(value.replace(" ", "").replace(',', '.')).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Сумма слишком велика. Уменьшите её и попробуйте снова.");
        }
    }

    /**
     * Formats a saved figure for the numeric input.
     * @param value amount in whole rubles
     * @return grouped digits with a typographic minus for negative amounts
     */
    public String number(Number value) {
        Objects.requireNonNull(value, "value");
        return NumberFormat.getIntegerInstance(RUSSIAN).format(value).replace('-', '−');
    }

    /**
     * Formats a saved figure for display.
     * @param value amount in whole rubles
     * @return formatted amount followed by a nonbreaking space and the ruble sign
     */
    public String display(Number value) {
        return number(value) + "\u00a0₽";
    }
}
