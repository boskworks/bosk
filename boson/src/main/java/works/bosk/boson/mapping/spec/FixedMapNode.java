package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Function;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.BoundType;
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
		Iterator<? extends DataType> iter = finisher.parameterTypes().iterator();
		memberSpecs.forEach((name, member) -> {
			DataType finisherParameter = iter.next();
			assert finisherParameter.isAssignableFrom(member.valueSpec().dataType()):
				"Finisher parameter type " + finisherParameter +
				" is not assignable from member '" + name +
				"' value type " + member.valueSpec().dataType();
		});
	}

	/**
	 * Less stringent way to make a {@link FixedMapNode}, where the finisher
	 * accepts a single array of {@link Object} instead of the individual parameters.
	 * This will naturally be less efficient, as it requires an array allocation.
	 */
	public static FixedMapNode withArrayFinisher(
		DataType resultType,
		SequencedMap<String, FixedMapMember> memberSpecs,
		Function<Object[], ?> arrayFinisher
	) {
		KnownType objectArray = DataType.known(new TypeReference<Object[]>() {});
		var finisherHandle = TypedHandles.function(
			objectArray,
			resultType,
			arrayFinisher);
		var collectorMH = finisherHandle.handle()
			.asCollector(Object[].class, memberSpecs.size());
		var castMH = collectorMH.asType(
			methodType(resultType.leastUpperBoundClass(),
				memberSpecs.values().stream()
					.map(FixedMapMember::valueSpec)
					.map(SpecNode::dataType)
					.map(DataType::leastUpperBoundClass)
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
	public DataType dataType() {
		return finisher.returnType();
	}

	@Override
	public String briefIdentifier() {
		return "Fixed_" + dataType().leastUpperBoundClass().getSimpleName();
	}

	@Override
	public FixedMapNode substitute(Map<String, DataType> actualArguments) {
		var memberSpecs = new LinkedHashMap<String, FixedMapMember>();
		this.memberSpecs.forEach((name, member) ->
			memberSpecs.put(name, member.substitute(actualArguments)));
		return new FixedMapNode(
			memberSpecs,
			this.finisher.substitute(actualArguments)
		);
	}

	public abstract static class Wrangler1<V, T1> {
		final String member1Name;

		protected Wrangler1(String member1Name) {
			this.member1Name = member1Name;
		}

		public abstract T1 accessor1(V value);
		public abstract V finish(T1 member1);
	}

	public abstract static class Wrangler2<V, T1, T2> {
		final String member1Name;
		final String member2Name;

		protected Wrangler2(String member1Name, String member2Name) {
			this.member1Name = member1Name;
			this.member2Name = member2Name;
		}

		public abstract T1 accessor1(V value);
		public abstract T2 accessor2(V value);
		public abstract V finish(T1 member1, T2 member2);
	}

	public static FixedMapNode of(Wrangler1<?, ?> wrangler) {
		BoundType wranglerType = (BoundType) DataType.known(wrangler.getClass());
		DataType valueType = wranglerType.parameterType(Wrangler1.class, 0);

		var memberSpecs = new LinkedHashMap<String, FixedMapMember>();
		DataType arg1Type = wranglerType.parameterType(Wrangler1.class, 1);
		memberSpecs.put(wrangler.member1Name, new FixedMapMember(
			new TypeRefNode(arg1Type),
			new TypedHandle(
				WRANGLER1_ACCESSOR1.bindTo(wrangler).asType(
					methodType(arg1Type.leastUpperBoundClass(), valueType.leastUpperBoundClass())
				),
				arg1Type, List.of(valueType)
			)
		));
		var finisher = new TypedHandle(
			WRANGLER1_FINISH.bindTo(wrangler).asType(
				methodType(
					valueType.leastUpperBoundClass(),
					arg1Type.leastUpperBoundClass()
				)
			),
			valueType, List.of(arg1Type)
		);
		return new FixedMapNode(memberSpecs, finisher);
	}

	public static FixedMapNode of(Wrangler2<?, ?,?> wrangler) {
		BoundType wranglerType = (BoundType) DataType.known(wrangler.getClass());
		DataType valueType = wranglerType.parameterType(Wrangler2.class, 0);

		var memberSpecs = new LinkedHashMap<String, FixedMapMember>();
		DataType arg1Type = wranglerType.parameterType(Wrangler2.class, 1);
		DataType arg2Type = wranglerType.parameterType(Wrangler2.class, 2);
		memberSpecs.put(wrangler.member1Name, new FixedMapMember(
			new TypeRefNode(arg1Type),
			new TypedHandle(
				WRANGLER2_ACCESSOR1.bindTo(wrangler).asType(
					methodType(arg1Type.leastUpperBoundClass(), valueType.leastUpperBoundClass())
				),
				arg1Type, List.of(valueType)
			)
		));
		memberSpecs.put(wrangler.member2Name, new FixedMapMember(
			new TypeRefNode(arg2Type),
			new TypedHandle(
				WRANGLER2_ACCESSOR2.bindTo(wrangler).asType(
					methodType(arg2Type.leastUpperBoundClass(), valueType.leastUpperBoundClass())
				),
				arg2Type, List.of(valueType)
			)
		));
		var finisher = new TypedHandle(
			WRANGLER2_FINISH.bindTo(wrangler).asType(
				methodType(
					valueType.leastUpperBoundClass(),
					arg1Type.leastUpperBoundClass(),
					arg2Type.leastUpperBoundClass()
				)
			),
			valueType, List.of(arg1Type, arg2Type)
		);
		return new FixedMapNode(memberSpecs, finisher);
	}

	private static final MethodHandle WRANGLER1_ACCESSOR1;
	private static final MethodHandle WRANGLER1_FINISH;
	private static final MethodHandle WRANGLER2_ACCESSOR1;
	private static final MethodHandle WRANGLER2_ACCESSOR2;
	private static final MethodHandle WRANGLER2_FINISH;

	static {
		var lookup = MethodHandles.lookup();
		try {
			WRANGLER1_ACCESSOR1 = lookup.findVirtual(Wrangler1.class, "accessor1",
				methodType(Object.class, Object.class));
			WRANGLER1_FINISH = lookup.findVirtual(Wrangler1.class, "finish",
				methodType(Object.class, Object.class));
			WRANGLER2_ACCESSOR1 = lookup.findVirtual(Wrangler2.class, "accessor1",
				methodType(Object.class, Object.class));
			WRANGLER2_ACCESSOR2 = lookup.findVirtual(Wrangler2.class, "accessor2",
				methodType(Object.class, Object.class));
			WRANGLER2_FINISH = lookup.findVirtual(Wrangler2.class, "finish",
				methodType(Object.class, Object.class, Object.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
