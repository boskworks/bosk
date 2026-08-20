package works.bosk.boson.codec.io;

import java.util.stream.LongStream;

public class Util {
	private static final long INSIGNIFICANT_CHARS = LongStream
		.of(0x20, 0x0A, 0x0D, 0x09, ',', ':')
		.map(n -> 1L << n)
		.sum();

	private static final long WHITESPACE_CHARS = LongStream
		.of(0x20, 0x0A, 0x0D, 0x09)
		.map(n -> 1L << n)
		.sum();

	/**
	 * The parameter need not be an actual code point: it can also be a surrogate character.
	 * This correctly returns true in that case. All significant characters are ASCII.
	 */
	public static boolean fast_isInsignificant(int codePoint) {
		// The position to check in INSIGNIFICANT_CHARS
		long bit = 1L << codePoint;

		// Zero if definitely significant
		// Can have false positives
		long bitIsSet = INSIGNIFICANT_CHARS & bit;

		// All ones if codePoint is greater than the largest insignificant char
		long isNegative = (long)codePoint >> 63; // Note: -1 represents EOF
		long isTooBig = (63L - codePoint) >> 63;

		// Zero if significant
		long answer = bitIsSet & ~(isNegative | isTooBig);

		boolean result = (answer != 0);
		// assert result == Token.startingWith(codePoint).isInsignificant();
		return result;
	}

	public static boolean fast_isWhitespace(int codePoint) {
		// The position to check in WHITESPACE_CHARS
		long bit = 1L << codePoint;

		// Zero if definitely not whitespace
		// Can have false positives
		long bitIsSet = WHITESPACE_CHARS & bit;

		// All ones if codePoint is greater than the largest whitespace char
		long isNegative = (long)codePoint >> 63; // Note: -1 represents EOF
		long isTooBig = (63L - codePoint) >> 63;

		// Zero if not whitespace
		long answer = bitIsSet & ~(isNegative | isTooBig);

		boolean result = (answer != 0);
		// assert result == (Token.startingWith(codePoint) == WHITESPACE);
		return result;
	}

	public static boolean isNumberChar(int b) {
		return (b >= '0' && b <= '9') || b == '.' || b == '-' || b == '+' || b == 'e' || b == 'E';
	}

	/**
	 * Parses the given number text without creating an intermediate {@link String}.
	 */
	public static byte parseByte(CharSequence s) {
		int value = Integer.parseInt(s, 0, s.length(), 10);
		if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
			throw new NumberFormatException("Value out of range for byte: " + s);
		}
		return (byte) value;
	}

	/**
	 * Parses the given number text without creating an intermediate {@link String}.
	 */
	public static short parseShort(CharSequence s) {
		int value = Integer.parseInt(s, 0, s.length(), 10);
		if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
			throw new NumberFormatException("Value out of range for short: " + s);
		}
		return (short) value;
	}

	public static int parseInt(CharSequence s) {
		return Integer.parseInt(s, 0, s.length(), 10);
	}

	public static long parseLong(CharSequence s) {
		return Long.parseLong(s, 0, s.length(), 10);
	}

	public static float parseFloat(CharSequence s) {
		return Float.parseFloat(s.toString());
	}

	public static double parseDouble(CharSequence s) {
		return Double.parseDouble(s.toString());
	}

	/**
	 * Zero is a special case that the caller must handle separately.
	 */
	public static boolean isNumberLeadingChar(int b) {
		return (b >= '1' && b <= '9') || b == '-';
	}
}
