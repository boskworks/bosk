package works.bosk.boson.codec.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static works.bosk.boson.codec.Token.END_TEXT;

@ParameterizedClass
@MethodSource("readerSuppliers")
public class JsonSuiteTest extends AbstractJsonReaderTest {
	static final File INPUT_DIR = new File("../../JSONTestSuite/test_parsing");

	@BeforeAll
	static void checkInputDir() {
		assumeTrue(INPUT_DIR.exists(),
			"Run this test only if JSONTestSuite is available as a peer to the bosk repo");
	}

	@Override
	protected JsonReader readerFor(String json) {
		return super.readerFor(json).withValidation();
	}

	@ParameterizedTest
	@MethodSource("yFiles")
	void testValid(File file) throws IOException {
		readAll(readerFor(Files.readString(file.toPath())));
	}

	@ParameterizedTest
	@MethodSource("nFiles")
	void testInvalid(File file) {
		assertThrows(IOException.class, () ->
			readAll(readerFor(Files.readString(file.toPath()))));
	}

	@ParameterizedTest
	@MethodSource("iFiles")
	void testIndeterminate(File file) throws IOException {
		try {
			readAll(readerFor(Files.readString(file.toPath())));
		} catch (JsonSyntaxException | MalformedInputException e) {
			// Acceptable outcome
		}
	}

	static void readAll(JsonReader reader) {
		while (reader.peekRawToken() != END_TEXT) {
			consumeValueToken(reader);
		}
	}

	public static Stream<Arguments> yFiles() {
		return filesWithPrefix("y_");
	}

	public static Stream<Arguments> nFiles() {
		return filesWithPrefix("n_").limit(1);
	}

	public static Stream<Arguments> iFiles() {
		return filesWithPrefix("i_");
	}

	private static Stream<Arguments> filesWithPrefix(String prefix) {
		return Stream.of(requireNonNull(INPUT_DIR.listFiles((_, name) -> name.startsWith(prefix))))
			.map(Arguments::of);
	}

}
