package works.bosk.boson.mapping;

import org.junit.jupiter.api.Test;
import works.bosk.boson.mapping.TypeScanner.Directive.IsAssignableFrom;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.TypeVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.boson.types.DataType.OBJECT;

class TypeScannerDirectiveTest {

	final DataType CHAR_SEQUENCE = DataType.known(CharSequence.class);
	final DataType STRING = DataType.known(String.class);
	final DataType STRING_BUILDER = DataType.known(StringBuilder.class);

	@Test
	void appliesTo() {
		var directive = new TypeScanner.Directive(
			new TypeVariable("V", CharSequence.class),
			new IsAssignableFrom(STRING),
			_ -> null
		);
		assertFalse(directive.appliesTo(OBJECT));
		assertTrue(directive.appliesTo(CHAR_SEQUENCE));
		assertTrue(directive.appliesTo(STRING));
		assertFalse(directive.appliesTo(STRING_BUILDER));
	}

}
