/**
 * The declarative API for mapping JSON data to Java objects and vice versa.
 * <p>
 * The major components are:
 *
 * <ul>
 *     <li>
 *         the {@link works.bosk.boson.mapping.spec spec package},
 *         with its {@link works.bosk.boson.mapping.spec.SpecNode} hierarchy,
 *         that describes the mapping between JSON and Java values;
 *     </li>
 *     <li>
 *         the {@link works.bosk.boson.mapping.TypeScanner} object,
 *         which automates the creation of {@link works.bosk.boson.mapping.spec.SpecNode} trees
 *         from Java types using reflection, guided by {@link works.bosk.boson.mapping.TypeScanner.Directive}s; and
 *     </li>
 *     <li>
 *         the {@link works.bosk.boson.mapping.TypeMap} object,
 *         which holds the final mapping between Java types and their corresponding
 *         {@link works.bosk.boson.mapping.spec.SpecNode} trees for use during
 *         JSON parsing and generation.
 *     </li>
 * </ul>
 *
 * There is also an {@link works.bosk.boson.mapping.opt} package
 * containing an optimizer that can improve the performance of
 * a given {@link works.bosk.boson.mapping.TypeMap} once all the mappings have been defined.
 */
package works.bosk.boson.mapping;
