package com.stocktracker.instrument;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical instrument keys (multi-asset P1, issue #8).
 *
 * <p>Java twin of src/lib/instruments.ts; both sides must parse every key the
 * same way, so InstrumentKeyTest mirrors tests/instruments.test.mjs. Shapes:
 * <pre>
 *   equity / ETF     AAPL, BRK.B, BF-B
 *   index            ^SPX
 *   option           AAPL261218C00200000   (compact OCC; padded 21-char OCC accepted)
 *   future           ESZ26@CME             (exchange suffix required)
 *   continuous fut.  ES1!                  ("ES1!@CME" accepted, exchange dropped)
 *   crypto           BTC/USD
 * </pre>
 */
public sealed interface InstrumentKey {

    /** Canonical key string. */
    String key();

    record Equity(String key) implements InstrumentKey {}

    record Index(String key, String symbol) implements InstrumentKey {}

    record Option(String key, String underlying, LocalDate expiry, char right, BigDecimal strike)
            implements InstrumentKey {}

    record Future(String key, String root, int month, int year, String exchange) implements InstrumentKey {
        /** 'YYYY-MM', the {@code instruments.future_contract_month} format. */
        public String contractMonth() {
            return String.format("%04d-%02d", year, month);
        }
    }

    record ContinuousFuture(String key, String root, int rank, String exchange) implements InstrumentKey {}

    record Crypto(String key, String base, String quote) implements InstrumentKey {}

    String MONTH_CODES = "FGHJKMNQUVXZ";

    Pattern EQUITY = Pattern.compile("^[A-Z]{1,6}(?:[.-][A-Z]{1,2})?$");
    Pattern INDEX = Pattern.compile("^\\^[A-Z0-9.]{1,10}$");
    Pattern OPTION = Pattern.compile("^([A-Z][A-Z0-9.]{0,5})(\\d{2})(\\d{2})(\\d{2})([CP])(\\d{8})$");
    Pattern PADDED_OCC = Pattern.compile("^[A-Z][A-Z0-9. ]{5}\\d{6}[CP]\\d{8}$");
    Pattern FUTURE = Pattern.compile("^([A-Z0-9]{1,4}?)([FGHJKMNQUVXZ])(\\d{2})@([A-Z]{2,8})$");
    Pattern CONTINUOUS = Pattern.compile("^([A-Z0-9]{1,4})([1-9])!(?:@([A-Z]{2,8}))?$");
    Pattern CRYPTO = Pattern.compile("^([A-Z0-9]{2,10})/([A-Z0-9]{2,6})$");

    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return PADDED_OCC.matcher(s).matches() ? s.replace(" ", "") : s;
    }

    static Optional<InstrumentKey> parse(String raw) {
        String key = normalize(raw);
        if (key.isEmpty()) return Optional.empty();

        Matcher m = OPTION.matcher(key);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(3));
            int day = Integer.parseInt(m.group(4));
            if (month < 1 || month > 12 || day < 1 || day > 31) return Optional.empty();
            LocalDate expiry;
            try {
                expiry = LocalDate.of(2000 + Integer.parseInt(m.group(2)), month, day);
            } catch (java.time.DateTimeException e) {
                return Optional.empty(); // e.g. Feb 30
            }
            BigDecimal strike = new BigDecimal(m.group(6)).movePointLeft(3).stripTrailingZeros();
            if (strike.scale() < 0) strike = strike.setScale(0); // 100, never 1E+2
            return Optional.of(new Option(key, m.group(1), expiry, m.group(5).charAt(0), strike));
        }
        m = FUTURE.matcher(key);
        if (m.matches()) {
            int month = MONTH_CODES.indexOf(m.group(2).charAt(0)) + 1;
            return Optional.of(new Future(key, m.group(1), month, 2000 + Integer.parseInt(m.group(3)), m.group(4)));
        }
        m = CONTINUOUS.matcher(key);
        if (m.matches()) {
            String root = m.group(1);
            int rank = Integer.parseInt(m.group(2));
            return Optional.of(new ContinuousFuture(root + rank + "!", root, rank, m.group(3)));
        }
        m = CRYPTO.matcher(key);
        if (m.matches()) return Optional.of(new Crypto(key, m.group(1), m.group(2)));
        if (INDEX.matcher(key).matches()) return Optional.of(new Index(key, key.substring(1)));
        if (EQUITY.matcher(key).matches()) return Optional.of(new Equity(key));
        return Optional.empty();
    }

    /** Parse or throw {@link IllegalArgumentException} (mapped to HTTP 400). */
    static InstrumentKey parseOrThrow(String raw) {
        return parse(raw).orElseThrow(() ->
                new IllegalArgumentException("Not a valid instrument key: '" + raw + "'"));
    }

    static String futureKey(String root, int month, int year, String exchange) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("futures month out of range: " + month);
        return String.format("%s%c%02d@%s",
                root.toUpperCase(Locale.ROOT), MONTH_CODES.charAt(month - 1), year % 100,
                exchange.toUpperCase(Locale.ROOT));
    }

    static String optionKey(String underlying, LocalDate expiry, char right, BigDecimal strike) {
        long milli = strike.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        if (milli < 0 || milli > 99_999_999L) throw new IllegalArgumentException("strike out of OCC range: " + strike);
        return String.format("%s%02d%02d%02d%c%08d",
                underlying.toUpperCase(Locale.ROOT), expiry.getYear() % 100, expiry.getMonthValue(),
                expiry.getDayOfMonth(), Character.toUpperCase(right), milli);
    }
}
