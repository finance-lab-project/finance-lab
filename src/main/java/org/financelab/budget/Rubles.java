package org.financelab.budget;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class Rubles {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru-RU");
    private static final Pattern WHOLE_AMOUNT =
            Pattern.compile("[+-]?(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[,.]0+)?");

    static long parse(String draft) {
        String value = draft.replace('\u00a0', ' ').replace('\u202f', ' ').strip().replace('−', '-');
        if (!WHOLE_AMOUNT.matcher(value).matches()) {
            throw new IllegalArgumentException("Введите сумму в целых рублях, без копеек.");
        }
        try {
            return new BigDecimal(value.replace(" ", "").replace(',', '.')).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Сумма слишком велика. Уменьшите её и попробуйте снова.");
        }
    }

    public String number(Number value) {
        return NumberFormat.getIntegerInstance(RUSSIAN).format(value).replace('-', '−');
    }

    public String display(Number value) {
        return number(value) + "\u00a0₽";
    }
}
