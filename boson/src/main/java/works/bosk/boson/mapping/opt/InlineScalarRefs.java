package works.bosk.boson.mapping.opt;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.ArraySpec;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.ObjectSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.ScalarSpec;
import works.bosk.boson.mapping.spec.SpecNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;

import static java.util.Collections.newSetFromMap;
import static works.bosk.boson.mapping.spec.SpecNode.transform;

/**
 * Identifies each {@link TypeRefNode} pointing to a {@link ScalarSpec} node,
 * and replaces it with that target node.
 * The idea is that the processing of such nodes is itself so simple (often a one-liner)
 * that implementing them directly doesn't require much more code than calling a method.
 */
public class InlineScalarRefs {
	final TypeMap typeMap;
	final Set<JsonValueSpec> inProgress = newSetFromMap(new IdentityHashMap<>());
	final Map<JsonValueSpec, JsonValueSpec> memo = new HashMap<>();

	public InlineScalarRefs(TypeMap typeMap) {
		this.typeMap = typeMap;
	}

	JsonValueSpec optimize(JsonValueSpec node) {
		JsonValueSpec memoized = memo.get(node);
		if (memoized != null) {
			LOGGER.debug("Reusing {}", node);
			return memoized;
		}
		if (inProgress.add(node)) {
			LOGGER.debug("Optimize {}", node);
			JsonValueSpec result = switch (node) {
				case TypeRefNode n -> maybeInline(n); // Here's where an optimization might happen
				case MaybeNullSpec(MaybeNullSpec n) -> optimize(n); // Okay, this is an optimization too

				// The rest of these cases just recurse to scan the whole graph.
				// TODO: Extract this sort of logic so every optimization doesn't have to reimplement it.
				case ScalarSpec n -> n;
				case MaybeNullSpec n -> transform(n, x ->
					new MaybeNullSpec(optimize(x.child())));
				case ParseCallbackSpec n -> transform(n, x ->
					new ParseCallbackSpec(x.before(), optimize(x.child()), x.after()));
				case RepresentAsSpec n -> transform(n, x ->
					new RepresentAsSpec(optimize(x.representation()), x.toRepresentation(), x.fromRepresentation()));
				case ArrayNode n -> transform(n, x ->
					new ArrayNode(optimize(x.elementNode()), x.accumulator(), x.emitter()));
				case UniformMapNode n -> transform(n, x ->
					new UniformMapNode(optimize(x.keyNode()), optimize(x.valueNode()), x.accumulator(), x.emitter())); // TODO: optimize keyNode?
				case FixedMapNode n -> transform(n, x ->
					new FixedMapNode(optimizeMembers(x.memberSpecs()), x.finisher()));
			};
			LOGGER.debug("Optimized {}", result);
			inProgress.remove(node);
			memo.put(node, result);
			return result;
		} else {
			LOGGER.debug("Skipping recursive node {}", node);
			// We don't store it in memo, because the caller
			// is already processing it and may produce a better result.
			return node;
		}
	}

	SpecNode optimize(SpecNode node) {
		return switch (node) {
			case JsonValueSpec n -> optimize(n);
			case ComputedSpec n -> n;
			case MaybeAbsentSpec n -> transform(n, x ->
				new MaybeAbsentSpec(optimize(x.ifPresent()), x.ifAbsent(), x.presenceCondition()));
		};
	}

	private SequencedMap<String, FixedMapMember> optimizeMembers(SequencedMap<String, FixedMapMember> memberSpecs) {
		var result = new LinkedHashMap<String, FixedMapMember>();
		memberSpecs.forEach((k,v) ->
			result.put(k, new FixedMapMember(optimize(v.valueSpec()), v.accessor())));
		return result;
	}

	/**
	 * The actual transformation.
	 * <p>
	 * At this point, we have identified a TypeRefNode and we're considering
	 * replacing it with the spec associated with its type in the {@link #typeMap}.
	 * Whether we do so depends on what that spec is:
	 *
	 * <ul>
	 *     <li>
	 *         For a {@link ScalarSpec}, we inline it; after all, that's what this optimization is for.
	 *     </li>
	 *     <li>
	 *         For structured types like {@link ArraySpec} and {@link ObjectSpec},
	 *         we leave the TypeRefNode in place to avoid quadratic code explosion.
	 *     </li>
	 *     <li>
	 *         For other types like {@link MaybeNullSpec} that have no code explosion risk,
	 *         we recursively optimize them and inline the result.
	 *         Same for {@link TypeRefNode}, which would represent a reference to a reference;
	 *         we can rely on {@link #optimize} to handle that.
	 *     </li>
	 * </ul>
	 *
	 * In all cases, we're safe when we call {@link #optimize} here,
	 * because that handles any cycles and memoization.
	 */
	private JsonValueSpec maybeInline(TypeRefNode original) {
		return switch (typeMap.get(original.type())) {
			case ScalarSpec target -> target;
			case ArraySpec _, ObjectSpec _ -> original; // Could cause quadratic code growth
			case TypeRefNode target -> optimize(target);
			case MaybeNullSpec target -> optimize(target);
			case ParseCallbackSpec target -> optimize(target);
			case RepresentAsSpec target -> optimize(target);
		};
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(InlineScalarRefs.class);
}
