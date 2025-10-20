package works.bosk.boson.mapping.spec;

import java.util.Iterator;
import java.util.SequencedMap;
import java.util.function.Function;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;

import static java.lang.invoke.MethodType.methodType;

// TODO: Find a way to deal with unrecognized members
public record FixedMapNode(
	SequencedMap<String, FixedMapMember> memberSpecs,
	TypedHandle finisher
) implements ObjectSpec {
	public FixedMapNode {
		assert finisher.parameterTypes().size() == memberSpecs.size();
		Iterator<KnownType> iter = finisher.parameterTypes().iterator();
		memberSpecs.values().forEach(member -> {
			assert iter.next().isAssignableFrom(member.valueSpec().dataType());
		});
	}

	/**
	 * Less stringent way to make a {@link FixedMapNode}, where the finisher
	 * accepts a single array of {@link Object} instead of the individual parameters.
	 * This will naturally be less efficient, as it requires an array allocation.
	 */
	public static FixedMapNode withArrayFinisher(
		SequencedMap<String, FixedMapMember> memberSpecs,
		Function<Object[], ?> arrayFinisher
	) {
		KnownType objectArray = DataType.known(new TypeReference<Object[]>() {});
		var finisherHandle = TypedHandles.function(
			objectArray,
			DataType.OBJECT,
			arrayFinisher);
		var collectorMH = finisherHandle.handle()
			.asCollector(Object[].class, memberSpecs.size());
		var castMH = collectorMH.asType(
			methodType(Object.class,
				memberSpecs.values().stream()
					.map(FixedMapMember::valueSpec)
					.map(SpecNode::dataType)
					.map(KnownType::rawClass)
					.toArray(Class<?>[]::new)
			)
		);
		var collectorHandle = new TypedHandle(
			castMH,
			finisherHandle.returnType(),
			memberSpecs.values().stream()
				.map(m -> m.valueSpec().dataType())
				.toList()
		);
		return new FixedMapNode(memberSpecs, collectorHandle);
	}

	@Override
	public KnownType dataType() {
		return finisher.returnType();
	}

	@Override
	public String briefIdentifier() {
		return "Fixed_" + dataType().rawClass().getSimpleName();
	}

}
