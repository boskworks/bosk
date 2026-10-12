package works.bosk.libtesting.fuzz;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.BoskContext;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.jackson.JacksonSerializer;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.FlushOperation;
import works.bosk.testing.drivers.operations.SubmitConditionalDeletion;
import works.bosk.testing.drivers.operations.SubmitConditionalReplacement;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * An {@link OperationScript} stored as a list of {@link OperationSpec}s, which rebuilds its
 * operations against whichever bosk it is asked to run on.
 */
public record SpecOperationScript(String name, List<OperationSpec> operations) implements OperationScript {

	@Override
	public List<DriverOperation> operationsFor(Bosk<TestEntity> referenceBosk) throws InvalidTypeException {
		ObjectMapper mapper = mapperFor(referenceBosk);
		List<DriverOperation> result = new ArrayList<>(operations.size());
		for (OperationSpec spec : operations) {
			result.add(bind(spec, referenceBosk, mapper));
		}
		return result;
	}

	private static DriverOperation bind(OperationSpec spec, Bosk<TestEntity> bosk, ObjectMapper mapper) throws InvalidTypeException {
		BoskContext.Context context = BoskContext.Context.empty();
		return switch (spec.kind()) {
			case CREATE -> new ConditionalCreation<>(ref(bosk, spec.target()), value(mapper, spec.value(), TestEntity.class), context);
			case REPLACE_ENTITY -> new SubmitReplacement<>(ref(bosk, spec.target()), value(mapper, spec.value(), TestEntity.class), context);
			case REPLACE_FIELD -> new SubmitReplacement<>(ref(bosk, spec.target()), value(mapper, spec.value(), String.class), context);
			case DELETE -> new SubmitDeletion<>(ref(bosk, spec.target()), context);
			case CONDITIONAL_REPLACE -> new SubmitConditionalReplacement<>(
				ref(bosk, spec.target()),
				value(mapper, spec.value(), TestEntity.class),
				idRef(bosk, spec.precondition()),
				Identifier.from(spec.requiredValue()),
				context);
			case CONDITIONAL_DELETE -> new SubmitConditionalDeletion<>(
				ref(bosk, spec.target()),
				idRef(bosk, spec.precondition()),
				Identifier.from(spec.requiredValue()),
				context);
			case FLUSH -> new FlushOperation(context);
		};
	}

	private static <T> T value(ObjectMapper mapper, JsonNode node, Class<T> type) {
		return mapper.treeToValue(node, type);
	}

	@SuppressWarnings("unchecked")
	private static <T> Reference<T> ref(Bosk<TestEntity> bosk, String path) throws InvalidTypeException {
		return (Reference<T>) bosk.rootReference().then(Object.class, Path.parse(path));
	}

	private static Reference<Identifier> idRef(Bosk<TestEntity> bosk, String path) throws InvalidTypeException {
		return ref(bosk, path);
	}

	static ObjectMapper mapperFor(Bosk<?> bosk) {
		return JsonMapper.builder().addModule(new JacksonSerializer().moduleFor(bosk)).build();
	}
}
