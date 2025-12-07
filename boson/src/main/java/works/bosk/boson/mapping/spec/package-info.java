/**
 * Abstract datatype
 * that specifies what a JSON document should contain
 * and how it relates to in-memory data structures.
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
 * Interfaces with names ending in "{@code Spec}" are named after the JSON structure they represent,
 * while concrete classes ending in "{@code Node}" are named after the in-memory representation.
 * <p>
 * Each node contains enough information to validate the JSON while parsing as well
 * as to decode it. How much validation is actually performed is a tunable trade-off
 * between performance and error detection.
 * <p>
 * Fine-grained behaviour that is not JSON-specific is represented by {@link works.bosk.boson.mapping.spec.handles.TypedHandle TypedHandle}
 * objects, which are {@link java.lang.invoke.MethodHandle MethodHandle}s with additional generic type information.
 * Specifying behaviour this way allows for high flexibility and performance
 * while keeping the JSON-related aspects declarative.
 * <p>
 * Insignificant syntax such as whitespace and delimiters are not specified.
 * Any operation that wishes to process insignificant syntax is free to do so,
 * but such processing is not specified in the spec nodes.
 *
 * <h3>Wranglers</h3>
 *
 * Spec nodes that accept {@link works.bosk.boson.mapping.spec.handles.TypedHandle TypedHandle}s are powerful but cumbersome.
 * To help, we've adopted a design pattern called "wranglers".
 * A <em>wrangler</em> is an interface that you can implement, usually
 * with an anonymous inner class, from which
 * all the required handles and type information can be derived automatically.
 * Wranglers codify best practices in the sense that
 * the resulting spec node is as efficient as possible.
 * There's no performance penalty for using wranglers,
 * besides type casts that the jit compiler can often optimize away anyway;
 * and their implementation can be used as a guide to how to implement
 * the underlying handles directly, if you need to.
 * <p>
 * A few examples:
 *
 * <ul>
 *     <li>
 *         {@link works.bosk.boson.mapping.spec.RepresentAsSpec.Wrangler}
 *         is used often to describe one type's representation
 *         in terms of another that is already defined.
 *     </li>
 *     <li>
 *         {@link works.bosk.boson.mapping.spec.FixedObjectNode.Wrangler1}
 *         can specify a JSON object with one member,
 *         and {@link works.bosk.boson.mapping.spec.FixedObjectNode.Wrangler2}
 *         can specify one with two members.
 *     </li>
 *     <li>
 *         {@link works.bosk.boson.mapping.spec.handles.ArrayEmitter.Wrangler ArrayEmitter.Wrangler}
 *         can specify a JSON array corresponding to a Java object
 *         that has an {@link java.util.Iterator} or similar.
 *     </li>
 * </ul>
 */
package works.bosk.boson.mapping.spec;
