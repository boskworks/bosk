package works.bosk.testing.drivers.fuzz;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import works.bosk.Bosk;
import works.bosk.BoskContext;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.FlushOperation;
import works.bosk.testing.drivers.operations.SubmitConditionalDeletion;
import works.bosk.testing.drivers.operations.SubmitConditionalReplacement;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * Produces a pseudorandom sequence of {@link DriverOperation}s for a bosk whose root is a
 * {@link TestEntity}, the state type used throughout the driver conformance suite.
 *
 * <p>Generation is <em>structure-aware</em>. Before producing each operation, the generator
 * consults a model bosk to discover which entities currently exist, and it only targets paths
 * that make sense in that state. That is what separates this from unstructured fuzzing, most of
 * whose effort would be wasted on updates that are silently ignored. The generator is also
 * deliberately willing to produce no-op operations, such as a conditional update whose
 * precondition is not satisfied, because those are exactly the cases a driver is most likely to
 * get wrong.
 *
 * <p>Two bosks are involved, for different reasons:
 *
 * <ul>
 *     <li>The <em>reference bosk</em> is the bosk under test. Every {@link Reference} the generator
 *     emits must belong to it, since a bosk's driver refuses updates whose references point
 *     somewhere else.</li>
 *     <li>The <em>model bosk</em> supplies the state the generator consults. In the conformance
 *     harness this is the canonical (known-correct) bosk, so that the sequence keeps making sense
 *     even when the driver under test misbehaves. The model must be allowed to settle (for
 *     example, by flushing the driver) before it is read, since an operation submitted to an
 *     asynchronous driver may not be reflected immediately.</li>
 * </ul>
 *
 * <p>Given a seed and an {@link OperationChooser}, the generator is deterministic: the same model
 * state and the same seed always yield the same sequence.
 *
 * <p>This generator is written against {@code TestEntity} to match the rest of the conformance
 * machinery; the general notion of a structure-aware operation generator need not be specific to
 * that root type, but nothing else in the suite needs such generality yet.
 */
public final class OperationSequenceGenerator {
	private final Random random;
	private final Bosk<TestEntity> modelBosk;
	private final OperationChooser chooser;
	private final CatalogReference<TestEntity> targetCatalogRef;
	private final CatalogReference<TestEntity> modelCatalogRef;
	private final BoskContext.Context context = BoskContext.Context.empty();
	private int entityCounter = 0;

	public OperationSequenceGenerator(Random random, Bosk<TestEntity> referenceBosk, Bosk<TestEntity> modelBosk, OperationChooser chooser) {
		this.random = random;
		this.modelBosk = modelBosk;
		this.chooser = chooser;
		try {
			this.targetCatalogRef = referenceBosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
			this.modelCatalogRef = modelBosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		} catch (InvalidTypeException e) {
			throw new IllegalStateException("Bosk must have a TestEntity root with a catalog", e);
		}
	}

	/**
	 * Produces an operation appropriate to the model bosk's current state.
	 *
	 * @return the next operation to submit
	 */
	public DriverOperation nextOperation() throws InvalidTypeException {
		Catalog<TestEntity> catalog = catalog();
		OperationKind kind = chooser.choose(random, applicableKinds(catalog));
		return switch (kind) {
			case CREATE -> conditionalCreation();
			case REPLACE_FIELD -> replaceField(catalog);
			case REPLACE_ENTITY -> replaceEntity(catalog);
			case DELETE -> deletion(catalog);
			case CONDITIONAL_REPLACE -> conditionalReplacement(catalog);
			case CONDITIONAL_DELETE -> conditionalDeletion(catalog);
			case FLUSH -> new FlushOperation(context);
		};
	}

	/**
	 * When the catalog is empty there is nothing to mutate or delete, so we offer only
	 * building up (or flushing). Otherwise every kind of change is fair game.
	 */
	private static List<OperationKind> applicableKinds(Catalog<TestEntity> catalog) {
		if (catalog.isEmpty()) {
			return List.of(OperationKind.CREATE, OperationKind.FLUSH);
		} else {
			return List.copyOf(EnumSet.allOf(OperationKind.class));
		}
	}

	private ConditionalCreation<TestEntity> conditionalCreation() {
		Identifier id = newIdentifier();
		return new ConditionalCreation<>(targetCatalogRef.then(id), newEntity(id), context);
	}

	private SubmitReplacement<String> replaceField(Catalog<TestEntity> catalog) throws InvalidTypeException {
		Identifier id = randomExistingId(catalog);
		Reference<String> target = targetCatalogRef.then(id).then(String.class, TestEntity.Fields.string);
		return new SubmitReplacement<>(target, randomString(), context);
	}

	private SubmitReplacement<TestEntity> replaceEntity(Catalog<TestEntity> catalog) {
		Identifier id = randomExistingId(catalog);
		return new SubmitReplacement<>(targetCatalogRef.then(id), newEntity(id), context);
	}

	private SubmitDeletion<TestEntity> deletion(Catalog<TestEntity> catalog) {
		Identifier id = randomExistingId(catalog);
		return new SubmitDeletion<>(targetCatalogRef.then(id), context);
	}

	private SubmitConditionalReplacement<TestEntity> conditionalReplacement(Catalog<TestEntity> catalog) throws InvalidTypeException {
		Identifier id = randomExistingId(catalog);
		return new SubmitConditionalReplacement<>(
			targetCatalogRef.then(id),
			newEntity(id),
			identifierReference(id),
			randomMaybeWrongId(id),
			context);
	}

	private SubmitConditionalDeletion<TestEntity> conditionalDeletion(Catalog<TestEntity> catalog) throws InvalidTypeException {
		Identifier id = randomExistingId(catalog);
		return new SubmitConditionalDeletion<>(
			targetCatalogRef.then(id),
			identifierReference(id),
			randomMaybeWrongId(id),
			context);
	}

	private TestEntity newEntity(Identifier id) {
		return TestEntity.empty(id, targetCatalogRef).withString(randomString());
	}

	private Reference<Identifier> identifierReference(Identifier id) throws InvalidTypeException {
		return targetCatalogRef.then(id).then(Identifier.class, TestEntity.Fields.id);
	}

	/**
	 * Half the time the precondition matches and the operation takes effect; the other half it
	 * does not, so the driver has to handle a no-op correctly.
	 */
	private Identifier randomMaybeWrongId(Identifier id) {
		if (random.nextBoolean()) {
			return id;
		} else {
			return newIdentifier();
		}
	}

	private Identifier randomExistingId(Catalog<TestEntity> catalog) {
		List<Identifier> ids = catalog.ids();
		return ids.get(random.nextInt(ids.size()));
	}

	private Catalog<TestEntity> catalog() {
		try (var _ = modelBosk.readSession()) {
			return modelCatalogRef.value();
		}
	}

	private Identifier newIdentifier() {
		return Identifier.from("entity" + (entityCounter++));
	}

	private String randomString() {
		return INTERESTING_STRINGS.get(random.nextInt(INTERESTING_STRINGS.size()));
	}

	private static final List<String> INTERESTING_STRINGS = List.of(
		"",
		"value",
		"with.dots",
		"with/slashes",
		"with spaces",
		"unicode\u263A"
	);
}
