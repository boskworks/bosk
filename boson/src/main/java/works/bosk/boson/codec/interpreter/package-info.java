/**
 * Implements {@link works.bosk.boson.codec.Codec Codec} directly by walking
 * a given {@link works.bosk.boson.mapping.spec.SpecNode SpecNode} tree
 * and performing the indicated operations.
 * <p>
 * Simpler to implement and maintain than the {@link works.bosk.boson.codec.compiler compiler}.
 * Serves as a platform for experimenting with new features before implementing them
 * in the compiler.
 */
package works.bosk.boson.codec.interpreter;
