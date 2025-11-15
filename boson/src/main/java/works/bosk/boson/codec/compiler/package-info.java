/**
 * Generates bytecode to implement {@link works.bosk.boson.codec.Codec Codec}.
 * <p>
 * In compiler terms, this is a "back end",
 * translating the intermediate representation composed of
 * {@link works.bosk.boson.mapping.spec.SpecNode SpecNodes} into JVM bytecode,
 * with the "front end" being the {@link works.bosk.boson.mapping.TypeScanner TypeScanner},
 * and the "source code" being the data structures.
 * The translation is intended to be as direct as possible,
 * with any optimizations having already been applied to the specification being compiled.
 */
package works.bosk.boson.codec.compiler;
