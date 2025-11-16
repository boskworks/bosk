/**
 * The Boson library for high-performance JSON object mapping.
 * <p>
 * Boson provides a declarative API for mapping JSON data to Java objects and vice versa,
 * using a bidirectional {@link works.bosk.boson.mapping.spec specification} tree structure.
 * From this, Boson can generate high-performance parsers and generators.
 * <p>
 * Boson also includes a reflection-based {@link works.bosk.boson.mapping.TypeScanner type scanner}
 * that can automatically create specification trees from Java types in a customizable manner.
 * The type scanner uses rich type information including generics,
 * allowing precise specification of the mapping between JSON and Java types,
 * which in turn allows Boson to generate efficient code.
 * <p>
 * The major packages are:
 *
 * <ul>
 *     <li>
 *         {@link works.bosk.boson.types},
 *         a powerful API that describes the Java type system;
 *     </li>
 *     <li>
 *         {@link works.bosk.boson.mapping},
 *         which includes the declarative {@link works.bosk.boson.mapping.spec mapping specification API}
 *         and the reflection-based {@link works.bosk.boson.mapping.TypeScanner TypeScanner}; and
 *     </li>
 *     <li>
 *         {@link works.bosk.boson.codec},
 *         which performs the actual JSON parsing and generation
 *         based on the mapping specifications.
 *     </li>
 * </ul>
 */
module works.bosk.boson {
	requires org.slf4j;
	requires works.bosk.core;

	exports works.bosk.boson.codec;
	exports works.bosk.boson.mapping;
	exports works.bosk.boson.mapping.spec;
	exports works.bosk.boson.mapping.spec.handles;
	exports works.bosk.boson.types;
	exports works.bosk.boson.exceptions;
}
