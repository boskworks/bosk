package works.bosk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import works.bosk.annotations.DeserializationPath;
import works.bosk.annotations.Enclosing;
import works.bosk.annotations.Self;
import works.bosk.annotations.TaggedUnionCaseMap;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.Types;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.containsStringIgnoringCase;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("unused") // The classes here are for type analysis, not to be instantiated and used
class TypeValidationTest {

	@ParameterizedTest
	@ValueSource(classes = {
		AllowedFieldNames.class,
		BooleanPrimitive.class,
		BoskyTypes.class,
		BoundedRoot.class,
		BoxedPrimitives.class,
		BytePrimitive.class,
		CharPrimitive.class,
		ConcreteGenericVariantRoot.class,
		DeepGenericRoot.class,
		DiamondVariantRoot.class,
		DoublePrimitive.class,
		EnclosingReferenceToTypeVariableRoot.class,
		ExtraStaticField.class,
		FloatPrimitive.class,
		GenericContainersRoot.class,
		GenericPairRoot.class,
		GenericSelfReferenceRoot.class,
		GenericStateRoot.class,
		GenericTaggedUnionRoot.class,
		GenericVariantRoot.class,
		ImplicitReferences_onConstructorParameters.class,
		ImplicitReferences_onFields.class,
		IntegerPrimitive.class,
		LongPrimitive.class,
		ShortPrimitive.class,
		SimpleTypes.class,
	})
	void testValidRootClasses(Class<?> rootClass) throws InvalidTypeException {
		TypeValidation.validateType(rootClass);
	}

	@Test
	void testParameterizedRootType() throws InvalidTypeException {
		// The root type itself may be a parameterized StateTreeNode, not just a field of one.
		TypeValidation.validateType(Types.parameterizedType(GenericNode.class, String.class));
	}

	@ParameterizedTest
	@ValueSource(classes = {
		String.class,
		AmbiguousTaggedUnionCaseMap.class,
		ArrayField.class,
		CatalogOfInvalidType.class,
		EnclosingNonReference.class,
		EnclosingReferenceToCatalog.class,
		EnclosingReferenceToOptional.class,
		EnclosingReferenceToString.class,
		FieldNameWithDollarSign.class,
		GenericArrayRoot.class,
		GenericNode.class,
		HasDeserializationPath.class,
		InvalidGenericRoot.class,
		ListingOfInvalidType.class,
		ListValueInvalidSubclass.class,
		ListValueMutableSubclass.class,
		ListValueOfEntity.class,
		ListValueOfIdentifier.class,
		ListValueOfInvalidType.class,
		ListValueOfOptional.class,
		ListValueOfReference.class,
		ListValueSubclassWithMutableField.class,
		ListValueSubclassWithTwoConstructors.class,
		ListValueSubclassWithWrongConstructor.class,
		MapValueOfEntity.class,
		MapValueOfIdentifier.class,
		MapValueOfOptional.class,
		MapValueOfReference.class,
		ParameterizedFieldRoot.class,
		ReferenceToReference.class,
		SelfNonReference.class,
		SelfReferenceToTypeVariableRoot.class,
		SelfWrongType.class,
		SideTableWithInvalidKey.class,
		SideTableWithInvalidValue.class,
		NestedError.class,
		OptionalOfInvalidType.class,
		RawGenericVariantRoot.class,
		ReferenceToInvalidType.class,
		ValidThenInvalidOfTheSameClass.class,
		WildcardGenericRoot.class,
		WildcardListValueRoot.class,
		WildcardReferenceRoot.class,
		WildcardTaggedUnionRoot.class,
		TaggedUnionCaseWithNoTaggedUnion.class,
		RawCatalogRoot.class,
		RawReferenceRoot.class,
		RawMapValueRoot.class,
		RawOptionalRoot.class,
		RawListValueRoot.class,
		RawListingRoot.class,
		RawSideTableRoot.class,
		RawTaggedUnionRoot.class,
	})
	void testInvalidRootClasses(Class<?> rootClass) throws Exception {
		try {
			TypeValidation.validateType(rootClass);
		} catch (InvalidTypeException e) {
			try {
				rootClass.getDeclaredMethod("testException", InvalidTypeException.class).invoke(null, e);
			} catch (NoSuchMethodException ignore) {
				// no prob
			}
			// All is well
			return;
		}
		fail("Expected exception was not thrown for " + rootClass.getSimpleName());
	}

	@Test
	void testIsBetween() {
		// <sigh> Java has no standard function for this, so to get full coverage, we need to test ours.
		assertFalse(TypeValidation.isBetween('b', 'e', 'a'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'b'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'c'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'd'));
		assertTrue (TypeValidation.isBetween('b', 'e', 'e'));
		assertFalse(TypeValidation.isBetween('b', 'e', 'f'));
	}

	//
	// OK, here come the classes...
	//

	public record BoxedPrimitives(
		Boolean booleanObject,
		Byte byteObject,
		Character charObject,
		Short shortObject,
		Integer intObject,
		Long longObject,
		Float floatObject,
		Double doubleObject
	) implements StateTreeNode { }

	public record BooleanPrimitive(boolean field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record BytePrimitive(byte field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record CharPrimitive(char field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record ShortPrimitive(short field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record IntegerPrimitive(int field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record LongPrimitive(long field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record FloatPrimitive(float field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record DoublePrimitive(double field) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsStringIgnoringCase("primitive"));
		}
	}

	public record SimpleTypes(
		Identifier id,
		String string,
		MyEnum myEnum
	) implements Entity {
		public enum MyEnum {
			LEFT, RIGHT
		}
	}

	public record BoskyTypes(
		Reference<SimpleTypes> ref,
		Optional<SimpleTypes> optional,
		Catalog<SimpleTypes> catalog,
		Listing<SimpleTypes> listing,
		SideTable<SimpleTypes, String> sideTableToString,
		SideTable<SimpleTypes, SimpleTypes> sideTableToEntity,
		ListValue<String> listValueOfStrings,
		ListValue<ValueStruct> listValueOfStructs,
		ListValueSubclass listValueSubclass
	) implements StateTreeNode { }

	public record ValueStruct(
		String string,
		ListValue<String> innerList
	) implements StateTreeNode { }

	@EqualsAndHashCode(callSuper = true)
	public static final class ListValueSubclass extends ListValue<String> {
		final String extraField;

		ListValueSubclass(String[] entries) {
			super(entries);
			this.extraField = "Hello";
		}
	}

	public record AllowedFieldNames(
		Integer justLetters,
		Integer someNumbers4U2C,
		Integer hereComesAnUnderscore_toldYouSo
	) implements StateTreeNode { }

	public record ImplicitReferences_onConstructorParameters(
		Identifier id,
		Reference<ImplicitReferences_onConstructorParameters> selfRef,
		Reference<StateTreeNode> selfSupertype,
		Reference<ImplicitReferences_onConstructorParameters> enclosingRef
	) implements Entity {
		public ImplicitReferences_onConstructorParameters(
			Identifier id,
			@Self Reference<ImplicitReferences_onConstructorParameters> selfRef,
			@Self Reference<StateTreeNode> selfSupertype,
			@Enclosing Reference<ImplicitReferences_onConstructorParameters> enclosingRef
		) {
			this.id = id;
			this.selfRef = selfRef;
			this.selfSupertype = selfSupertype;
			this.enclosingRef = enclosingRef;
		}
	}

	public record ImplicitReferences_onFields(
		Identifier id,
		@Self Reference<ImplicitReferences_onFields> selfRef,
		@Self Reference<StateTreeNode> selfSupertype,
		@Enclosing Reference<ImplicitReferences_onFields> enclosingRef
	) implements Entity { }

	public interface VariantWithExtraStaticField extends TaggedUnionCase {
		record Subtype() implements VariantWithExtraStaticField {}
		@Override default String tag() { return ""; }

		@TaggedUnionCaseMap MapValue<Class<? extends VariantWithExtraStaticField>> CASE_MAP = MapValue.copyOf(Map.of(
			"subtype", Subtype.class
		));

		/**
		 * This is not annotated with @TaggedUnionCaseMap so we expect it to be ignored by the scan.
		 */
		String EXTRA_FIELD = "ignore me";
	}

	public record ExtraStaticField(TaggedUnion<VariantWithExtraStaticField> variant) implements StateTreeNode {}

	public record NestedError(
		Identifier id,
		ReferenceToInvalidType field
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("NestedError.field"));
			assertThat(e.getMessage(), containsString("ReferenceToInvalidType.ref"));
		}
	}

	public record ArrayField(
		Identifier id,
		String[] strings
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ArrayField.strings"));
			assertThat(e.getMessage(), containsString("is not a"));
		}
	}

	public record ReferenceToInvalidType(
		Identifier id,
		Reference<ArrayField> ref
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceToInvalidType.ref"));
		}
	}

	public record CatalogOfInvalidType(
		Identifier id,
		Catalog<ArrayField> catalog
	) implements Entity {

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("CatalogOfInvalidType.catalog"));
		}
	}

	public record ListingOfInvalidType(
		Identifier id,
		Listing<ArrayField> listing
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListingOfInvalidType.listing"));
		}
	}

	public record OptionalOfInvalidType(
		Identifier id,
		Optional<ArrayField> optional
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("OptionalOfInvalidType.optional"));
		}
	}

	public record SideTableWithInvalidKey(
		Identifier id,
		SideTable<ArrayField,String> sideTable
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SideTableWithInvalidKey.sideTable"));
		}
	}

	public record SideTableWithInvalidValue(
		Identifier id,
		SideTable<SimpleTypes,ArrayField> sideTable
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SideTableWithInvalidValue.sideTable"));
		}
	}

	public record FieldNameWithDollarSign(
		Identifier id,
		int weird$name
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("FieldNameWithDollarSign.weird$name"));
		}
	}

	/*
	 * According to JLS 3.1, Java identifiers comprise only ASCII characters.
	 * https://docs.oracle.com/javase/specs/jls/se14/html/jls-3.html#jls-3.1
	 *
	public record FieldNameWithNonAsciiLetters(
		Identifier id,
		int trèsCassé
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("FieldNameWithNonAsciiLetters.trèsCassé"));
		}
	}
	 */

	public record EnclosingNonReference(
		Identifier id,
		@Enclosing String enclosingString
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingNonReference.enclosingString"));
		}
	}

	public record EnclosingReferenceToString(
		Identifier id,
		@Enclosing Reference<String> enclosingStringReference
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingReferenceToString.enclosingStringReference"));
		}
	}

	public record EnclosingReferenceToCatalog(
		Identifier id,
		@Enclosing Reference<Catalog<SimpleTypes>> enclosingCatalogReference
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingReferenceToCatalog.enclosingCatalogReference"));
		}
	}

	public record EnclosingReferenceToOptional(
		Identifier id,
		@Enclosing Reference<Optional<SimpleTypes>> enclosingOptionalReference
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("EnclosingReferenceToOptional.enclosingOptionalReference"));
		}
	}

	public record SelfNonReference(
		Identifier id,
		@Self String str
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SelfNonReference.str"));
		}
	}

	public record SelfWrongType(
		Identifier id,
		@Self Reference<SimpleTypes> ref
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("SelfWrongType.ref"));
		}
	}

	public record HasDeserializationPath(
		Identifier id,
		@DeserializationPath("") SimpleTypes badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("HasDeserializationPath.badField"));
		}
	}

	public record ListValueOfIdentifier(
		Identifier id,
		ListValue<Identifier> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfIdentifier.badField"));
		}
	}

	public record ListValueOfReference(
		Identifier id,
		ListValue<Reference<String>> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfReference.badField"));
		}
	}

	public record ListValueOfEntity(
		Identifier id,
		ListValue<SimpleTypes> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfEntity.badField"));
		}
	}

	public record ListValueOfOptional(
		Identifier id,
		ListValue<Optional<SimpleTypes>> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfOptional.badField"));
		}
	}

	public record MapValueOfIdentifier(
		Identifier id,
		MapValue<Identifier> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("MapValueOfIdentifier.badField"));
			assertThat(e.getMessage(), containsString("MapValue"));
			assertThat(e.getMessage(), not(containsString("ListValue")));
		}
	}

	public record MapValueOfReference(
		Identifier id,
		MapValue<Reference<String>> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("MapValueOfReference.badField"));
			assertThat(e.getMessage(), containsString("MapValue"));
			assertThat(e.getMessage(), not(containsString("ListValue")));
		}
	}

	public record MapValueOfEntity(
		Identifier id,
		MapValue<SimpleTypes> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("MapValueOfEntity.badField"));
			assertThat(e.getMessage(), containsString("MapValue"));
			assertThat(e.getMessage(), not(containsString("ListValue")));
		}
	}

	public record MapValueOfOptional(
		Identifier id,
		MapValue<Optional<SimpleTypes>> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("MapValueOfOptional.badField"));
			assertThat(e.getMessage(), containsString("MapValue"));
			assertThat(e.getMessage(), not(containsString("ListValue")));
		}
	}

	public record ListValueInvalidSubclass(
		Identifier id,
		InvalidSubclass badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueInvalidSubclass.badField"));
		}

		@EqualsAndHashCode(callSuper = true)
		public static class InvalidSubclass extends ListValue<Identifier> {
			protected InvalidSubclass(Identifier[] entries) {
				super(entries);
			}
		}
	}

	public record ListValueMutableSubclass(
		Identifier id,
		MutableSubclass badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueMutableSubclass.badField"));
			assertThat(e.getMessage(), containsString("MutableSubclass.mutableField"));
		}

		@EqualsAndHashCode(callSuper = true)
		public static class MutableSubclass extends ListValue<String> {
			String mutableField;

			protected MutableSubclass(String[] entries, String mutableField) {
				super(entries);
				this.mutableField = mutableField;
			}
		}
	}

	public record ListValueOfInvalidType(
		Identifier id,
		ListValue<ArrayList<Object>> badField
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueOfInvalidType.badField"));
		}
	}

	public record ListValueSubclassWithMutableField(
		Identifier id,
		Subclass badField
	) implements Entity {
		@EqualsAndHashCode(callSuper = true)
		public static final class Subclass extends ListValue<String> {
			private int mutableField;

			Subclass(String[] entries) {
				super(entries);
			}

			public int mutableField() {
				return this.mutableField;
			}
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueSubclassWithMutableField.badField"));
			assertThat(e.getMessage(), containsString("Subclass.mutableField"));
		}
	}

	public record ListValueSubclassWithTwoConstructors(
		Identifier id,
		Subclass badField
	) implements Entity {
		@EqualsAndHashCode(callSuper = true)
		public static final class Subclass extends ListValue<String> {
			Subclass(String[] entries) {
				super(entries);
			}

			Subclass() {
				super(new String[]{"Hello"});
			}
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueSubclassWithTwoConstructors.badField"));
			assertThat(e.getMessage(), containsStringIgnoringCase("ambiguous"));
			assertThat(e.getMessage(), containsStringIgnoringCase("constructor"));
		}
	}

	public record ListValueSubclassWithWrongConstructor(
		Identifier id,
		Subclass badField
	) implements Entity {
		@EqualsAndHashCode(callSuper = true)
		public static final class Subclass extends ListValue<String> {
			Subclass() {
				super(new String[]{"Hello"});
			}
		}

		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ListValueSubclassWithWrongConstructor.badField"));
			assertThat(e.getMessage(), containsStringIgnoringCase("constructor"));
			assertThat(e.getMessage(), not(containsStringIgnoringCase("ambiguous")));
		}
	}

	public record ReferenceToReference(
		Identifier id,
		Reference<Reference<String>> ref
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ReferenceToReference.ref"));
		}
	}

	/**
	 * Catches a case of over-exuberant memoization we were doing, where we'd
	 * only validate each class once.
	 *
	 * @author Patrick Doyle
	 */
	public record ValidThenInvalidOfTheSameClass(
		Identifier id,
		ListValue<String> good,
		ListValue<Identifier> bad
	) implements Entity {
		public static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ValidThenInvalidOfTheSameClass.bad"));
		}
	}

	public interface Variant1 extends TaggedUnionCase {
		@TaggedUnionCaseMap MapValue<Type> MAP1 = MapValue.empty();
	}

	public interface Variant2 extends TaggedUnionCase {
		@TaggedUnionCaseMap MapValue<Type> MAP2 = MapValue.empty();
	}

	public record VariantWithAmbiguousMaps(String tag) implements Variant1, Variant2 {}

	public record AmbiguousTaggedUnionCaseMap(TaggedUnion<VariantWithAmbiguousMaps> variant) implements StateTreeNode {}

	/** A single case map reachable by two inheritance paths must not be treated as ambiguous. */
	public interface DiamondBase extends TaggedUnionCase {
		@TaggedUnionCaseMap MapValue<Type> CASES = MapValue.singleton("diamondCase", DiamondCase.class);
	}

	public interface DiamondLeft extends DiamondBase {}

	public interface DiamondRight extends DiamondBase {}

	public interface DiamondVariant extends DiamondLeft, DiamondRight {}

	public record DiamondCase(String value) implements DiamondVariant {
		@Override public String tag() { return "diamondCase"; }
	}

	public record DiamondVariantRoot(TaggedUnion<DiamondVariant> variant) implements StateTreeNode {}

	public record TaggedUnionCaseWithNoTaggedUnion(
		Variant1 variant
	) implements StateTreeNode {}

	/**
	 * A parameterized type that does not itself implement StateTreeNode is still
	 * not allowed in a bosk tree. Parameterized StateTreeNode types are supported;
	 * see {@link GenericNode}.
	 */
	public record ParameterizedFieldRoot(
		ParameterizedField<SimpleTypes> field
	) implements StateTreeNode {}

	public record ParameterizedField<T extends StateTreeNode>(
		T field
	) {}

	/** A parameterized StateTreeNode, used as a field and, in the test, as a root type. */
	public record GenericNode<T>(T value) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("type parameters"));
		}
	}

	public record GenericStateRoot(
		GenericNode<String> node
	) implements StateTreeNode {}

	public record GenericPair<A, B>(
		A first,
		B second
	) implements StateTreeNode {}

	public record GenericPairRoot(
		GenericPair<String, Integer> pair
	) implements StateTreeNode {}

	public record Bounded<T extends Entity>(T value) implements StateTreeNode {}

	public record BoundedRoot(
		Bounded<SimpleTypes> bounded
	) implements StateTreeNode {}

	public record GenericContainersRoot(
		Optional<GenericNode<String>> optional,
		ListValue<GenericNode<String>> listValue,
		MapValue<GenericNode<String>> mapValue,
		SideTable<SimpleTypes, GenericNode<String>> sideTable
	) implements StateTreeNode {}

	public record DeepGenericRoot(
		GenericNode<Catalog<SimpleTypes>> node
	) implements StateTreeNode {}

	/** The type argument is validated after substitution, so this is invalid. */
	public record InvalidGenericRoot(
		GenericNode<ArrayList<Object>> node
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("ArrayList"));
		}
	}

	/**
	 * A self-referential parameterized node. Validating it resolves a type variable that
	 * leads back to the node itself, which must terminate.
	 */
	public record GenericSelfReference<T>(
		@Self Reference<GenericSelfReference<T>> self
	) implements StateTreeNode {}

	public record GenericSelfReferenceRoot(
		GenericSelfReference<String> node
	) implements StateTreeNode {}

	/**
	 * The @Self check reads the reference's target type, which is a type variable here and
	 * must be substituted before it can be inspected.
	 */
	public record SelfReferenceToTypeVariable<T>(
		@Self Reference<T> self
	) implements StateTreeNode {}

	public record SelfReferenceToTypeVariableRoot(
		SelfReferenceToTypeVariable<String> node
	) implements StateTreeNode {}

	/** The @Enclosing check reads the reference's target type in the same way. */
	public record EnclosingReferenceToTypeVariable<T extends Entity>(
		@Enclosing Reference<T> enclosing
	) implements StateTreeNode {}

	public record EnclosingReferenceToTypeVariableRoot(
		EnclosingReferenceToTypeVariable<SimpleTypes> node
	) implements StateTreeNode {}

	/**
	 * A variant whose case map names an explicitly parameterized case type.
	 */
	public interface GenericVariant extends TaggedUnionCase {
		@TaggedUnionCaseMap
		MapValue<Type> CASES = MapValue.singleton("generic", Types.parameterizedType(GenericTaggedUnionCase.class, String.class));
	}

	public record GenericTaggedUnionCase<T>(T value) implements GenericVariant {
		public String tag() {
			return "generic";
		}
	}

	public record GenericVariantRoot(
		TaggedUnion<GenericVariant> variant
	) implements StateTreeNode {}

	/** A TaggedUnion<T> inside a generic node, resolved when the node is parameterized. */
	public record GenericTaggedUnionHolder<T extends TaggedUnionCase>(
		TaggedUnion<T> variant
	) implements StateTreeNode {}

	public record GenericTaggedUnionRoot(
		GenericTaggedUnionHolder<GenericVariant> holder
	) implements StateTreeNode {}

	/**
	 * A generic variant used directly as the union's type argument. Validating the concrete
	 * variant needs the type arguments that the TaggedUnion supplies.
	 */
	public record ConcreteGenericVariant<T>(T value) implements TaggedUnionCase {
		public String tag() {
			return "concreteGeneric";
		}

		@TaggedUnionCaseMap
		static final MapValue<Type> CASES = MapValue.singleton("concreteGeneric", Types.parameterizedType(ConcreteGenericVariant.class, String.class));
	}

	public record ConcreteGenericVariantRoot(
		TaggedUnion<ConcreteGenericVariant<String>> variant
	) implements StateTreeNode {}

	/** The case map names a raw generic case, which cannot be validated without type arguments. */
	public interface RawGenericVariant extends TaggedUnionCase {
		@TaggedUnionCaseMap
		MapValue<Type> CASES = MapValue.singleton("generic", GenericTaggedUnionCase.class);
	}

	public record RawGenericVariantRoot(
		TaggedUnion<RawGenericVariant> variant
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("type parameters"));
		}
	}

	/** A wildcard type argument has no raw class to validate. */
	public record WildcardGenericRoot(
		GenericNode<?> node
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Unsupported type"));
		}
	}

	/** A generic array type argument has no raw class to validate. */
	public record GenericArrayNode<T>(T[] items) implements StateTreeNode {}

	public record GenericArrayRoot(
		GenericArrayNode<String> node
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Unsupported type"));
		}
	}

	/** A wildcard Reference target has no raw class to validate. */
	public record WildcardReferenceRoot(
		Reference<?> reference
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Unsupported type"));
		}
	}

	/** A wildcard tagged union case supertype has no raw class to validate. */
	public record WildcardTaggedUnionRoot(
		TaggedUnion<?> variant
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Unsupported type"));
		}
	}

	/** A wildcard ListValue entry type has no raw class to validate. */
	public record WildcardListValueRoot(
		ListValue<?> items
	) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("Unsupported type"));
		}
	}

	// A raw generic class has type parameters that must be supplied, so it's rejected
	// uniformly rather than passing (Catalog, Listing, SideTable, Optional) or throwing
	// a ClassCastException (Reference, TaggedUnion, MapValue, ListValue).

	@SuppressWarnings("rawtypes")
	public record RawCatalogRoot(Catalog contents) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawReferenceRoot(Reference reference) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawMapValueRoot(MapValue contents) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawOptionalRoot(Optional contents) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawListValueRoot(ListValue contents) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawListingRoot(Listing listing) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawSideTableRoot(SideTable table) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}

	@SuppressWarnings("rawtypes")
	public record RawTaggedUnionRoot(TaggedUnion variant) implements StateTreeNode {
		static void testException(InvalidTypeException e) {
			assertThat(e.getMessage(), containsString("must be used with type arguments"));
		}
	}
}
