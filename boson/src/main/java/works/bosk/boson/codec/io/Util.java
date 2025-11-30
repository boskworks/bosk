package works.bosk.boson.codec.io;

import java.util.stream.LongStream;
import works.bosk.boson.codec.Token;

public class Util {
	private static final long INSIGNIFICANT_CHARS = LongStream
		.of(0x20, 0x0A, 0x0D, 0x09, ',', ':')
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
		assert result == Token.startingWith(codePoint).isInsignificant();
		return result;
	}

	public static boolean isNumberChar(int b) {
		return (b >= '0' && b <= '9') || b == '.' || b == '-' || b == '+' || b == 'e' || b == 'E';
	}

	/**
	 * Zero is a special case that the caller must handle separately.
	 */
	public static boolean isNumberLeadingChar(int b) {
		return (b >= '1' && b <= '9') || b == '-';
	}
}
