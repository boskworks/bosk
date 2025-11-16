/**
 * Optimizations that improve {@link works.bosk.boson.mapping.TypeMap TypeMap}s
 * for more efficient processing of JSON data.
 * <p>
 * The execution model for {@link works.bosk.boson.mapping.spec.JsonValueSpec JsonValueSpec} processing is
 * that a {@link works.bosk.boson.mapping.spec.TypeRefNode TypeRefNode} is akin to a method call,
 * acting as a barrier to optimization,
 * but also enabling code sharing and recursion.
 * Optimizations could "inline" a {@code TypeRefNode} by replacing it
 * with its target {@code JsonValueSpec}
 * thereby exposing additional opportunities for optimization;
 * or they could carve up a spec tree into pieces and introduce
 * {@code TypeRefNode}s to share pieces that would otherwise be duplicated.
 * <p>
 * (Note that a {@code TypeRefNode} may or may not literally be implemented as a method call,
 * depending on how the codec chooses to cope with deeply nested
 * structures, but the model still holds.)
 */
package works.bosk.boson.mapping.opt;
