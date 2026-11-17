package org.financelab.budget;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Exact ratios keep an editable ruble amount such as one third of a pool lossless on reopening. */
record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {
    static final Fraction ZERO = of(0, 1);
    static final Fraction ONE = of(1, 1);

    Fraction {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("Неверный знаменатель доли.");
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
    }

    static Fraction of(long numerator, long denominator) {
        return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    static Fraction read(String value) {
        if (value == null || !value.matches("[0-9]+/[1-9][0-9]*")) {
            throw new IllegalArgumentException("Укажите долю категории.");
        }
        String[] parts = value.split("/");
        return new Fraction(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    static Fraction percent(String text) {
        String value = text == null ? "" : text.strip().replace(',', '.');
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("Введите неотрицательный процент, например 12,5.");
        }
        BigDecimal decimal = new BigDecimal(value);
        return new Fraction(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale() + 2));
    }

    Fraction times(Fraction factor) {
        return new Fraction(numerator.multiply(factor.numerator), denominator.multiply(factor.denominator));
    }

    Fraction plus(Fraction other) {
        return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    Fraction minusOne() {
        return new Fraction(numerator.subtract(denominator), denominator);
    }

    Fraction absolute() {
        return new Fraction(numerator.abs(), denominator);
    }

    BigInteger floor() {
        return numerator.divide(denominator);
    }

    Fraction remainder() {
        return new Fraction(numerator.remainder(denominator), denominator);
    }

    BigInteger rounded() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP).toBigIntegerExact();
    }

    String asPercent() {
        BigDecimal top = new BigDecimal(numerator).movePointRight(2);
        BigDecimal bottom = new BigDecimal(denominator);
        BigDecimal percent;
        try {
            percent = top.divide(bottom);
        } catch (ArithmeticException repeating) {
            percent = top.divide(bottom, new MathContext(5, RoundingMode.HALF_UP));
        }
        return percent.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    String stored() {
        return numerator + "/" + denominator;
    }

    @Override public int compareTo(Fraction other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
}
