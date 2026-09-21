package com.subreathingcode.URLShortener.util;

/**
 * Encodes non-negative longs (in practice, DB auto-increment IDs) into
 * Base62 strings using [0-9a-zA-Z]. This is what turns row id {@code 125}
 * into a short code like {@code "cb"}.
 * <p>
 * Note this makes short codes sequential/guessable, since they map 1:1 to
 * insertion order. That's a deliberate trade-off for this first milestone —
 * fine to revisit (e.g. bit-shuffling the ID before encoding) once the core
 * flow works.
 */
public final class Base62Encoder {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62Encoder() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot encode a negative value: " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            int digit = (int) (remaining % BASE);
            sb.append(ALPHABET.charAt(digit));
            remaining /= BASE;
        }
        return sb.reverse().toString();
    }
}
