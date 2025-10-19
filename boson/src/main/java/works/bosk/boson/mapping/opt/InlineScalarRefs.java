package works.bosk.boson.mapping.opt;

import java.util.HashSet;
import java.util.LinkedHashMap;
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

import static works.bosk.boson.mapping.spec.SpecNode.transform;

/**
 * Identifies each {@link TypeRefNode} pointing to a {@link ScalarSpec} node,
 * and replaces it with that target node.
 * The idea is that the processing of such nodes is itself so simple (often a one-liner)
 * that implementing them directly doesn't require much more code than calling a method.
 */
public class InlineScalarRefs {
	final TypeMap typeMap;
	final Set<JsonValueSpec> memo = new HashSet<>();

	public InlineScalarRefs(TypeMap typeMap) {
		this.typeMap = typeMap;
	}

	JsonValueSpec optimize(JsonValueSpec node) {
		if (memo.add(node)) {
			LOGGER.debug("Optimize {}", node);
			JsonValueSpec result = switch (node) {
				case TypeRefNode n -> maybeInline(n);
				case ScalarSpec n -> n;
				case MaybeNullSpec(MaybeNullSpec n) -> optimize(n); // Redundant nested MaybeNull
				case MaybeNullSpec n -> transform(n, x ->
					new MaybeNullSpec(optimize(x.child())));
				case ParseCallbackSpec n -> transform(n, x ->
					new ParseCallbackSpec(x.before(), optimize(x.child()), x.after()));
				case RepresentAsSpec n -> transform(n, x ->
					new RepresentAsSpec(optimize(x.representation()), x.toRepresentation(), x.fromRepresentation()));
				case ArrayNode n -> transform(n, x ->
					new ArrayNode(optimize(x.elementNode()), x.accumulator(), x.emitter()));
				case UniformMapNode n -> transform(n, x ->
					new UniformMapNode(x.keyNode(), optimize(x.valueNode()), x.accumulator(), x.emitter())); // TODO: optimize keyNode?
				case FixedMapNode n -> transform(n, x ->
					new FixedMapNode(optimizeMembers(x.memberSpecs()), x.finisher()));
			};
			LOGGER.debug("Optimized {}", result);
			return result;
		} else {
			LOGGER.debug("Skipping {}", node);
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

	private SequencedMap<String, FixedMapMember> optimizeMembers(SequencedMap<String, FixedMapMember> stringSpecNodeMap) {
		var result = new LinkedHashMap<String, FixedMapMember>();
		stringSpecNodeMap.forEach((k,v) -> result.put(k, new FixedMapMember(optimize(v.valueSpec()), v.accessor())));
		return result;
	}

	/**
	 * The actual transformation
	 */
	private JsonValueSpec maybeInline(TypeRefNode original) {
		return switch (typeMap.get(original.type())) {
			case MaybeNullSpec target -> optimize(target);
			case ParseCallbackSpec target -> optimize(target);
			case RepresentAsSpec target -> optimize(target);
			case ScalarSpec target -> target;
			case ArraySpec _, ObjectSpec _ -> original;
			case TypeRefNode _ -> {
				// If the typeMap takes us to a TypeRefMode, we're in danger of
				// infinite recursion. Let's bail out for now until we have more
				// sophisticated cycle detection.
				yield original;
			}
		};
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(InlineScalarRefs.class);
}
