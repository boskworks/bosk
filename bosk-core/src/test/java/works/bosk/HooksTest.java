package works.bosk;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import works.bosk.annotations.Hook;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class HooksTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	Refs refs;

	public interface Refs {
		@ReferencePath("/id") Reference<Identifier> rootID();
		@ReferencePath("/entities/parent") Reference<TestEntity> parent();
		@ReferencePath("/entities/parent/children") CatalogReference<TestChild> parentChildren();
		@ReferencePath("/entities/parent/oddChildren") ListingReference<TestChild> parentOddChildren();
		@ReferencePath("/entities/parent/stringSideTable") SideTableReference<TestChild, String> parentStringSideTable();
		@ReferencePath("/entities/parent/string") Reference<String> parentString();
		@ReferencePath("/entities/-parent-/children/-child-") Reference<TestChild> anyChild();
		@ReferencePath("/entities/parent/children/-child-") Reference<TestChild> child(Identifier child);
		@ReferencePath("/entities/parent/children/-child-/string") Reference<String> childString(Identifier child);
	}

	private final Identifier child1 = Identifier.from("child1");
	private final Identifier child2 = Identifier.from("child2");
	private final Identifier child3 = Identifier.from("child3");

	TestEntity originalParent;
	TestChild originalChild1, originalChild2, originalChild3;
	HookRecorder recorder;

	@BeforeEach
	void setupBosk() throws InvalidTypeException {
		bosk = setUpBosk(Bosk.simpleDriver());
		refs = bosk.rootReference().buildReferences(Refs.class);
		try (var _ = bosk.readContext()) {
			originalParent = refs.parent().value();
			originalChild1 = refs.child(child1).value();
			originalChild2 = refs.child(child2).value();
			originalChild3 = refs.child(child3).value();
		}
		recorder = new HookRecorder();
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void beforeRegistration_noHooks(Variant variant) {
		variant.submit.replacement(bosk, refs, refs.childString(child2), "Too early");
		assertEquals(emptyList(), recorder.events(), "Hook shouldn't see updates unless they are registered");
	}

	/////////////
	//
	// Basic tests: the hooks themselves do nothing but record that they were called.
	//

	@Test
	void basic_hooksRunWhenRegistered() {
		bosk.registerHook("child2", refs.child(child2), recorder.hookNamed("child2"));
		assertEquals(
			singletonList(
				new HookRecorder.Event("child2", HookRecorder.Event.Kind.CHANGED, refs.child(child2), originalChild2)),
			recorder.events(),
			"Hook should fire when it's registered");
	}

	@Test
	void basic_hooksDoNotPropagateThreadLocals() throws InterruptedException {
		ThreadLocal<String> threadLocal = ThreadLocal.withInitial(() -> "initial");
		threadLocal.set("updated");
		BlockingQueue<String> queue = new LinkedBlockingQueue<>();
		bosk.registerHook("foo", bosk.rootReference(), ref -> {
			try {
				queue.put(threadLocal.get());
			} catch (InterruptedException e) {
				throw new AssertionError("Huh?", e);
			}
		});
		String observed = queue.take();
		assertEquals("initial", observed, "Thread locals should not propagate into hooks");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_noIrrelevantHooks(Variant variant) {
		bosk.registerHook("child2", refs.child(child2), recorder.hookNamed("child2"));
		recorder.restart();
		variant.submit.replacement(bosk, refs, refs.childString(child1), "Child 1 only");
		assertEquals(emptyList(), recorder.events(), "Hook shouldn't see updates for objects that neither enclose nor are enclosed by them");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_objectReplacement(Variant variant) {
		registerInterleavedHooks();

		TestChild newChild = originalChild2.withString(originalChild2.string() + " v2");
		variant.submit.replacement(bosk, refs, refs.child(child2), newChild);

		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newChild));
		checkInterleavedHooks("Hooks for object and parent should fire when object is replaced", newParent, newChild);
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_fieldReplacement(Variant variant) {
		registerInterleavedHooks();

		String stringV3 = originalChild2.string() + " v3";
		variant.submit.replacement(bosk, refs, refs.childString(child2), stringV3);

		TestChild newChild = originalChild2.withString(stringV3);
		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newChild));
		checkInterleavedHooks("Hooks for object and parent should fire when field changes", newParent, newChild);
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_parentReplacementSameChildren(Variant variant) {
		registerInterleavedHooks();

		TestEntity newParent = originalParent.withString(originalParent.string() + " v2");
		variant.submit.replacement(bosk, refs, refs.parent(), newParent);

		assertEquals(
			asList(
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent)),
			recorder.events(),
			"Hook for parent (only) should fire when parent changes");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_parentReplacementReplacedChild(Variant variant) {
		registerInterleavedHooks();

		String replacement = "replacement";
		TestChild newChild2 = originalChild2.withString(replacement);
		TestEntity newParent = originalParent.withChild(newChild2);
		variant.submit.replacement(bosk, refs, refs.parent(), newParent);

		assertEquals(
			asList(
				new HookRecorder.Event("child2 A", HookRecorder.Event.Kind.CHANGED, refs.child(child2), newChild2),
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("child2 C", HookRecorder.Event.Kind.CHANGED, refs.child(child2), newChild2),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child2), newChild2)),
			recorder.events(),
			"Hook for object and parent should fire when parent gets new child");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_parentReplacementChildDisappearedAndModified(Variant variant) {
		registerInterleavedHooks();

		TestChild newChild1 = originalChild1.withString("replacement1");
		TestChild newChild3 = originalChild3.withString("replacement3");
		TestEntity newParent = originalParent.withChildren(Catalog.of(newChild3, newChild1));
		variant.submit.replacement(bosk, refs, refs.parent(), newParent);

		assertEquals(
			asList(
				new HookRecorder.Event("child2 A", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("child2 C", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),

				// For a given hook, absent objects are processed first, in reverse order of their
				// appearance in the prior state
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),

				// Updated objects are processed next, in order of their appearance in the new state
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child3), newChild3),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child1), newChild1)),
			recorder.events(),
			"Hook for objects and parent should fire when parent gets new child catalog");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_parentCreation(Variant variant) {
		variant.submit.deletion(bosk, refs, refs.parent());
		registerInterleavedHooks();
		variant.submit.replacement(bosk, refs, refs.parent(), originalParent); // Time travel!

		assertEquals(
			asList(
				new HookRecorder.Event("child2 A", HookRecorder.Event.Kind.CHANGED, refs.child(child2), originalChild2),
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), originalParent),
				new HookRecorder.Event("child2 C", HookRecorder.Event.Kind.CHANGED, refs.child(child2), originalChild2),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), originalParent),

				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child1), originalChild1),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child2), originalChild2),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child3), originalChild3)),
			recorder.events(),
			"Hook for objects and parent should fire when parent gets created");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_parentDeletion(Variant variant) {
		registerInterleavedHooks();
		variant.submit.deletion(bosk, refs, refs.parent());

		assertEquals(
			asList(
				new HookRecorder.Event("child2 A", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), null),
				new HookRecorder.Event("child2 C", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), null),

				// For a given hook, catalog entries are processed in reverse order
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child3), null),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child1), null)),
			recorder.events(),
			"Hook for objects and parent should fire when parent gets deleted");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_objectDeletion(Variant variant) {
		registerInterleavedHooks();

		variant.submit.deletion(bosk, refs, refs.child(child2));
		TestEntity newParent = originalParent.withChildren(originalParent.children().without(originalChild2));
		assertEquals(
			asList(
				new HookRecorder.Event("child2 A", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("child2 C", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child2), null)),
			recorder.events(),
			"Hooks for parent and child should fire when an object is deleted");
	}

	@ParameterizedTest
	@EnumSource(Variant.class)
	void basic_nonexistentObjectDeletion(Variant variant) {
		registerInterleavedHooks();

		variant.submit.deletion(bosk, refs, refs.anyChild().boundTo(Identifier.from("nonexistent"), Identifier.from("child1")));
		assertEquals(
			emptyList(),
			recorder.events(),
			"No hooks called when deleting a nonexistent object");
	}

	@Test
	void basic_creation() {
		registerInterleavedHooks();

		Identifier child4ID = Identifier.from("child4");
		TestChild newValue = originalChild1
			.withId(child4ID);
		Reference<TestChild> child4Ref = refs.anyChild().boundTo(Identifier.from("parent"), child4ID);

		bosk.driver().submitConditionalCreation(child4Ref, newValue);
		TestEntity newParent = originalParent.withChildren(originalParent.children().with(newValue));
		assertEquals(
			asList(
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, child4Ref, newValue)),
			recorder.events(),
			"Hooks for parent and child should fire when a child is initialized");
	}

	@Test
	void basic_creationAlreadyExists() {
		registerInterleavedHooks();

		TestChild newValue = originalChild1
			.withString("replacement");

		bosk.driver().submitConditionalCreation(refs.child(child1), newValue);

		assertEquals(
			emptyList(),
			recorder.events(),
			"No hooks when initializing an object that already exists");
	}

	@Test
	void basic_creationInNonexistentParent() {
		registerInterleavedHooks();

		Identifier child4ID = Identifier.from("child4");
		TestChild newValue = originalChild1
			.withId(child4ID);
		Reference<TestChild> child4Ref = refs.anyChild().boundTo(Identifier.from("nonexistent"), child4ID);

		bosk.driver().submitConditionalCreation(child4Ref, newValue);

		assertEquals(
			emptyList(),
			recorder.events(),
			"No hooks when initializing an object inside a nonexistent parent");
	}

	/**
	 * Provides a good test that hooks are run in registration order.
	 */
	private void registerInterleavedHooks() {
		bosk.registerHook("child2 A", refs.child(child2), recorder.hookNamed("child2 A"));
		bosk.registerHook("parent B", refs.parent(), recorder.hookNamed("parent B"));
		bosk.registerHook("child2 C", refs.child(child2), recorder.hookNamed("child2 C"));
		bosk.registerHook("parent D", refs.parent(), recorder.hookNamed("parent D"));
		bosk.registerHook("Any child", refs.anyChild(), recorder.hookNamed("Any child"));
		recorder.restart();
	}

	private void checkInterleavedHooks(String message, TestEntity newParent, TestChild newChild2) {
		assertEquals(
			asList(
				new HookRecorder.Event("child2 A", HookRecorder.Event.Kind.CHANGED, refs.child(child2), newChild2),
				new HookRecorder.Event("parent B", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("child2 C", HookRecorder.Event.Kind.CHANGED, refs.child(child2), newChild2),
				new HookRecorder.Event("parent D", HookRecorder.Event.Kind.CHANGED, refs.parent(), newParent),
				new HookRecorder.Event("Any child", HookRecorder.Event.Kind.CHANGED, refs.child(child2), newChild2)),
			recorder.events(),
			message);
	}

	/////////////
	//
	// Nested tests: the hooks themselves submit bosk updates
	//

	@Test
	void nested_breadthFirst() throws IOException, InterruptedException {
		AtomicBoolean initializing = new AtomicBoolean(true);

		// Child 1 update triggers A and B, and A triggers C

		bosk.registerHook("A", refs.childString(child1), recorder.hookNamed("A", ref -> {
			if (initializing.get()) {
				assertEquals("child1", ref.value(),
					"Upon registration, hooks runs on initial state");
				return;
			}

			assertEquals("newValue", ref.value(),
				"Update that triggered the hook is visible");
			assertEquals("child2", refs.childString(child2).value(),
				"Subsequent change to child2 is not visible");
			assertEquals("child3", refs.childString(child3).value(),
				"Subsequent change to child3 is not visible");
			bosk.driver().submitReplacement(refs.childString(child2), ref.value() + "_child2_hookA");
		}));
		bosk.registerHook("B", refs.childString(child1), recorder.hookNamed("B", ref -> {
			if (initializing.get()) {
				assertEquals("child1", ref.value(),
					"Upon registration, hooks runs on initial state");
				return;
			}

			assertEquals("newValue", ref.value(),
				"Update that triggered the hook is visible");
			assertEquals("child2", refs.childString(child2).value(),
				"A's update to child2 is not visible even though A runs first");
			assertEquals("child3", refs.childString(child3).value(),
				"Subsequent change to child3 is not visible");
			bosk.driver().submitReplacement(refs.childString(child3), ref.value() + "_child3_hookB");
		}));
		bosk.registerHook("C", refs.childString(child2), recorder.hookNamed("C", ref -> {
			if (initializing.get()) {
				assertEquals("child2", ref.value(),
					"Upon registration, hooks runs on initial state");
				return;
			}

			assertEquals("newValue_child2_hookA", ref.value(),
				"Update that triggered the hook is visible");
			assertEquals("newValue", refs.childString(child1).value(),
				"Prior update still visible");
			assertEquals("child3", refs.childString(child3).value(),
				"B's update to child3 is not visible even though B runs first");
		}));

		// Reset everything and submit the replacement that triggers the hooks
		recorder.restart();
		initializing.set(false);
		bosk.driver().submitReplacement(refs.childString(child1), "newValue");
		bosk.driver().flush();

		// Check for expected hook events
		List<HookRecorder.Event> expectedEvents = asList(
			new HookRecorder.Event("A", HookRecorder.Event.Kind.CHANGED, refs.childString(child1), "newValue"),
			new HookRecorder.Event("B", HookRecorder.Event.Kind.CHANGED, refs.childString(child1), "newValue"),
			new HookRecorder.Event("C", HookRecorder.Event.Kind.CHANGED, refs.childString(child2), "newValue_child2_hookA")
		);

		assertEquals(
			expectedEvents,
			recorder.events());

	}

	@Test
	void nestedMultipleUpdates_breadthFirst() {
		// Register hooks to propagate string updates from parent -> child 1 -> 2 -> 3 with a tag
		bosk.registerHook("+P", refs.parentString(), recorder.hookNamed("P", ref -> {
			bosk.driver().submitReplacement(refs.childString(child1), ref.value() + "+P");
			bosk.driver().submitReplacement(refs.childString(child2), ref.value() + "+P");
			bosk.driver().submitReplacement(refs.childString(child3), ref.value() + "+P");
		}));
		bosk.registerHook("+C1", refs.childString(child1), recorder.hookNamed("C1", ref -> {
			bosk.driver().submitReplacement(refs.childString(child2), ref.value() + "+C1");
			bosk.driver().submitReplacement(refs.childString(child3), ref.value() + "+C1");
		}));
		bosk.registerHook("+C2", refs.childString(child2), recorder.hookNamed("C2", ref -> {
			bosk.driver().submitReplacement(refs.childString(child3), ref.value() + "+C2");
		}));
		bosk.registerHook("C3", refs.childString(child3), recorder.hookNamed("C3"));

		List<HookRecorder.Event> expectedEvents = asList(
			new HookRecorder.Event("P", HookRecorder.Event.Kind.CHANGED, refs.parentString(), "replacement"),
			// P triggers C1, C2, C3
			// C1 triggers C2, C3
			// C2 triggers C3 (caused by P)
			// C2 triggers C3 (caused by C1)
			// So C2 changes twice and C3 changes four times
			new HookRecorder.Event("C1", HookRecorder.Event.Kind.CHANGED, refs.childString(child1), "replacement+P"),
			new HookRecorder.Event("C2", HookRecorder.Event.Kind.CHANGED, refs.childString(child2), "replacement+P"),
			new HookRecorder.Event("C3", HookRecorder.Event.Kind.CHANGED, refs.childString(child3), "replacement+P"),
			new HookRecorder.Event("C2", HookRecorder.Event.Kind.CHANGED, refs.childString(child2), "replacement+P+C1"),
			new HookRecorder.Event("C3", HookRecorder.Event.Kind.CHANGED, refs.childString(child3), "replacement+P+C1"),
			new HookRecorder.Event("C3", HookRecorder.Event.Kind.CHANGED, refs.childString(child3), "replacement+P+C2"), // This is interesting if you ponder it
			new HookRecorder.Event("C3", HookRecorder.Event.Kind.CHANGED, refs.childString(child3), "replacement+P+C1+C2")
		);
		TestEntity expectedParent = originalParent.withString("replacement").withChildren(Catalog.of(
			originalChild1.withString("replacement+P"),
			originalChild2.withString("replacement+P+C1"),
			originalChild3.withString("replacement+P+C1+C2")
		));

		recorder.restart();
		bosk.driver().submitReplacement(refs.parentString(), "replacement");

		assertEquals(
			expectedEvents,
			recorder.events(),
			"All hooks for an update should be called before any hooks for subsequent updates");

		try (var _ = bosk.readContext()) {
			assertEquals(expectedParent, refs.parent().value());
		}
	}

	@Test
	void nested_correctReadContext() {
		bosk.registerHook("stringCopier", refs.child(child2), recorder.hookNamed("stringCopier", ref ->
			bosk.driver().submitReplacement(refs.childString(child1), ref.value().string())));
		recorder.restart();
		String expectedString = "expected string";

		try (var _ = bosk.readContext()) {
			bosk.driver().submitReplacement(refs.childString(child2), expectedString);
			// If the hook were to run accidentally in this ReadContext, it would
			// see originalChild2.string() instead of expectedString.
		}

		assertEquals(
			singletonList(new HookRecorder.Event("stringCopier", HookRecorder.Event.Kind.CHANGED, refs.child(child2), originalChild2.withString(expectedString))),
			recorder.events(),
			"Hooks run in the right ReadContext regardless of active read scope at submission or execution time");


		try (var _ = bosk.readContext()) {
			assertEquals(expectedString, refs.childString(child2).value(), "Correct value got copied");
		}
	}

	@ParameterizedTest
	@ValueSource(classes = {HookReceiver.class, HookSubclass.class})
	void registerHooks_works(Class<? extends HookReceiver> receiverClass) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
		HookReceiver receiver = receiverClass.getConstructor(Bosk.class).newInstance(bosk);
		bosk.driver().submitReplacement(refs.childString(child1), "New value");
		List<List<Object>> expected = asList(
			// At registration time, the hook is called on all existing nodes
			asList(refs.childString(child1), BindingEnvironment.singleton("child", child1), "child1"),
			asList(refs.childString(child2), BindingEnvironment.singleton("child", child2), "child2"),
			asList(refs.childString(child3), BindingEnvironment.singleton("child", child3), "child3"),
			// Then the replacement causes another call
			asList(refs.childString(child1), BindingEnvironment.singleton("child", child1), "New value")
		);
		assertEquals(expected, receiver.hookCalls);
	}

	public static class HookReceiver {
		final List<List<Object>> hookCalls = new ArrayList<>();
		@SuppressWarnings("this-escape")
		public HookReceiver(Bosk<?> bosk) throws InvalidTypeException {
			bosk.registerHooks(this);
		}

		@Hook("/entities/parent/children/-child-/string")
		void childStringChanged(Reference<String> ref, BindingEnvironment env) {
			hookCalls.add(asList(ref, env, ref.valueIfExists()));
		}
	}

	public static class HookSubclass extends HookReceiver {
		public HookSubclass(Bosk<?> bosk) throws InvalidTypeException {
			super(bosk);
		}
	}

	@ParameterizedTest
	@ValueSource(classes = {ReferenceSubclassHooks.class})
	void registerHooks_referenceSubclasses_works(Class<? extends ReferenceSubclassHooks> receiverClass) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
		ReferenceSubclassHooks receiver = receiverClass.getConstructor(Bosk.class).newInstance(bosk);
		List<List<Object>> expected = asList(
			// At registration time, the hook is called on all existing nodes
			asList("childrenChanged", refs.parentChildren()),
			asList("oddChildrenChanged", refs.parentOddChildren()),
			asList("stringSideTableChanged", refs.parentStringSideTable())
		);
		assertEquals(expected, receiver.hookCalls);
	}

	public static final class ReferenceSubclassHooks {
		final List<List<Object>> hookCalls = new ArrayList<>();
		public ReferenceSubclassHooks(Bosk<?> bosk) throws InvalidTypeException {
			bosk.registerHooks(this);
		}

		@Hook("/entities/parent/children")
		void childrenChanged(CatalogReference<TestChild> ref) {
			hookCalls.add(asList("childrenChanged", ref));
		}

		@Hook("/entities/parent/oddChildren")
		void oddChildrenChanged(ListingReference<TestChild> ref) {
			hookCalls.add(asList("oddChildrenChanged", ref));
		}

		@Hook("/entities/parent/stringSideTable")
		void stringSideTableChanged(SideTableReference<TestChild, String> ref) {
			hookCalls.add(asList("stringSideTableChanged", ref));
		}
	}

	/**
	 * Also makes sure that the exception messages are sufficiently informative.
	 */
	@ParameterizedTest
	@ValueSource(classes = {
		InvalidParameterType.class,
		NonexistentPath.class,
		PrivateHookMethod.class,
		StaticHookMethod.class,
		WrongReferencedType.class,
	})
	void registerHooks_invalid_throws(Class<? extends Consumer<Exception>> receiverClass) throws Exception {
		Consumer<Exception> receiver = receiverClass.getConstructor().newInstance();
		InvalidTypeException e = assertThrows(InvalidTypeException.class, ()->bosk.registerHooks(receiver));
		receiver.accept(e);
	}

	public static class InvalidParameterType implements Consumer<Exception> {
		@Hook("/id")
		void hook(String wtf) {
			fail();
		}

		@Override
		public void accept(Exception e) {
			assertThat(e.getMessage(), containsString("wtf"));
			assertThat(e.getMessage(), containsString("InvalidParameterType.hook"));
			assertThat(e.getMessage(), containsString("String"));
		}
	}

	public static class NonexistentPath implements Consumer<Exception> {
		@Hook("/nonexistent")
		void hook(Reference<String> ref) {
			fail();
		}

		@Override
		public void accept(Exception e) {
			assertThat(e.getMessage(), containsString("/nonexistent"));
			assertThat(e.getMessage(), containsString("NonexistentPath.hook"));
		}
	}

	public static class PrivateHookMethod implements Consumer<Exception> {
		@Hook("/id")
		private void hook(Reference<Identifier> ref) {
			fail();
		}

		@Override
		public void accept(Exception e) {
			assertThat(e.getMessage(), containsString("private"));
			assertThat(e.getMessage(), containsString("PrivateHookMethod.hook"));
		}
	}

	public static class StaticHookMethod implements Consumer<Exception> {
		@Hook("/id")
		static void hook(Reference<Identifier> ref) {
			fail();
		}

		@Override
		public void accept(Exception e) {
			assertThat(e.getMessage(), containsString("static"));
			assertThat(e.getMessage(), containsString("StaticHookMethod.hook"));
		}
	}

	public static class WrongReferencedType implements Consumer<Exception> {
		@Hook("/id")
		void hook(Reference<String> wtf) {
			fail();
		}

		@Override
		public void accept(Exception e) {
			assertThat(e.getMessage(), containsString("wtf"));
			assertThat(e.getMessage(), containsString("WrongReferencedType.hook"));
			assertThat(e.getMessage(), containsString("Identifier")); // expected type
			assertThat(e.getMessage(), containsString("String"));     // actual type
		}
	}

	interface Submit {
		<T> void replacement(Bosk<?> bosk, Refs refs, Reference<T> target, T newValue);
		<T> void deletion(Bosk<?> bosk, Refs refs, Reference<T> target);
	}

	/**
	 * Every conditional operation has the same effect as the corresponding
	 * unconditional operation if the precondition is satisfied.
	 * Thus, every test is run using both.
	 */
	enum Variant {
		UNCONDITIONAL(new Submit() {
			@Override
			public <T> void replacement(Bosk<?> bosk, Refs refs, Reference<T> target, T newValue) {
				bosk.driver().submitReplacement(target, newValue);
			}

			@Override
			public <T> void deletion(Bosk<?> bosk, Refs refs, Reference<T> target) {
				bosk.driver().submitDeletion(target);
			}
		}),
		CONDITIONAL(new Submit() {
			final Identifier rootID = Identifier.from("root");

			@Override
			public <T> void replacement(Bosk<?> bosk, Refs refs, Reference<T> target, T newValue) {
				bosk.driver().submitConditionalReplacement(target, newValue, refs.rootID(), rootID);
			}

			@Override
			public <T> void deletion(Bosk<?> bosk, Refs refs, Reference<T> target) {
				bosk.driver().submitConditionalDeletion(target, refs.rootID(), rootID);
			}
		});


		private final Submit submit;

		Variant(Submit submit) {
			this.submit = submit;
		}
	}
}
