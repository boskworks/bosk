package works.bosk.boson.mapping.opt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.MaybeNullSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.ScalarSpec;
import works.bosk.boson.mapping.spec.SpecNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.types.DataType;

public class Optimizer {

	public TypeMap optimize(TypeMap original) {
		TypeMap typeMap = TypeMap.copyOf(original);
		var optimizer = new InlineScalarRefs(typeMap); // Currently our only optimization!

		// We now begin the analysis. The typeMap initially reflects everything
		// we knew at the start, and then it gradually improves as we optimize each
		// entry.
		//
		// This is what I'd refer to as a "simplification" optimization, walking
		// the graph of IL elements in reverse postorder and doing our best at each step.
		// If the types are recursive (ie. there's a cycle in the graph we're walking),
		// then some nodes will not be fully optimized in all circumstances with this
		// approach, and a more powerful cycle-aware analysis would be required.

		postorder(typeMap).forEach(type -> {
			typeMap.put(type, optimizer.optimize(original.get(type)));
		});

		return typeMap;
	}

	private List<DataType> postorder(TypeMap typeMap) {
		List<DataType> postorder = new ArrayList<>();
		Set<DataType> checklist = new HashSet<>();
		typeMap.knownTypes().forEach(type ->
			postorderWalk(type, typeMap, checklist, postorder));
		return List.copyOf(postorder);
	}

	private static void postorderWalk(DataType type, TypeMap typeMap, Set<DataType> checklist, List<DataType> postorder) {
		if (checklist.add(type)) {
			LOGGER.debug("(Will walk {})", type);
			postorderWalk(typeMap.get(type), typeMap, checklist, postorder);
			LOGGER.debug("Walk {}", type);
			postorder.add(type);
		}
	}

	private static void postorderWalk(SpecNode node, TypeMap typeMap, Set<DataType> checklist, List<DataType> postorder) {
		switch (node) {
			case TypeRefNode(var type) -> postorderWalk(type, typeMap, checklist, postorder);
			case ScalarSpec _ -> { }
			case ComputedSpec _ -> { }
			case MaybeAbsentSpec(var c1, var c2, _) -> {
				postorderWalk(c1, typeMap, checklist, postorder);
				postorderWalk(c2, typeMap, checklist, postorder);
			}
			case MaybeNullSpec(var child) -> postorderWalk(child, typeMap, checklist, postorder);
			case ParseCallbackSpec(_, var child, _) -> postorderWalk(child, typeMap, checklist, postorder);
			case RepresentAsSpec(var child, _, _) -> postorderWalk(child, typeMap, checklist, postorder);
			case ArrayNode(var child, _, _) -> postorderWalk(child, typeMap, checklist, postorder);
			case UniformMapNode(var c1, var c2, _, _) -> {
				postorderWalk(c1, typeMap, checklist, postorder);
				postorderWalk(c2, typeMap, checklist, postorder);
			}
			case FixedMapNode(var memberSpecs, _) -> memberSpecs.values().forEach(child ->
				postorderWalk(child.valueSpec(), typeMap, checklist, postorder)
			);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Optimizer.class);
}
