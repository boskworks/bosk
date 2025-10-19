/**
 * An abstract datatype
 * that specifies what a JSON document should contain
 * and how it relates to in-memory data structures.
 * The abstractions are suitable for both parsing and JSON generation.
 * <p>
 * A spec is composed of a tree of {@link works.bosk.boson.mapping.spec.JsonValueSpec} nodes,
 * each of which describes how a particular JSON value corresponds to an in-memory representation.
 * Usually, the parent-child relationship represents syntactic nesting,
 * as with {@link works.bosk.boson.mapping.spec.ArrayNode}'s {@link works.bosk.boson.mapping.spec.ArrayNode#elementNode() elementNode} child,
 * but it can also represent other codec logic augmentation,
 * as with {@link works.bosk.boson.mapping.spec.MaybeNullSpec}.
 * <p>
 * Semantically, the tree is akin to an expression tree,
 * where each node "returns" a parsed value.
 * Parent nodes can cause their children to be parsed zero, one, or several times,
 * but must not alter the semantics of the child node,
 * which must be self-contained, and parseable by itself.
 * <p>
 * Interfaces with names ending in {@code Spec} are named after the JSON structure they represent,
 * while concrete classes ending in {@code Node} are named after the in-memory representation.
 * <p>
 * Each node contains enough information to validate the JSON while parsing as well
 * as to decode it. How much validation is actually performed is a tunable trade-off
 * between performance and error detection.
 * <p>
 * Insignificant syntax such as whitespace and delimiters are not specified.
 * Any operation that wishes to process insignificant syntax is free to do so,
 * but such processing is not specified in the spec nodes.
 */
package works.bosk.boson.mapping.spec;
