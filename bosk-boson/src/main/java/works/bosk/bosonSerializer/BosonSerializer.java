package works.bosk.bosonSerializer;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import works.bosk.BoskInfo;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Phantom;
import works.bosk.Reference;
import works.bosk.ReferenceUtils;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.TypeScanner.Directive;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;
import works.bosk.boson.types.TypeVariable;
import works.bosk.exceptions.InvalidTypeException;

import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.memberValue;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.canonicalConstructor;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.componentAccessor;
import static works.bosk.boson.mapping.spec.handles.TypedHandles.supplier;

public class BosonSerializer extends StateTreeSerializer {

	public <
		// Some type variables to use in directives
		T,
		E extends Entity,
		V extends VariantCase
	> TypeScanner.Bundle bundleFor(BoskInfo<?> bosk) {
		MethodHandles.Lookup lookup = MethodHandles.lookup();

		var directives = new ArrayList<Directive>();

		directives.add(Directive.fixed(
			RepresentAsSpec.as(
				new StringNode(),
				DataType.known(Identifier.class),
				Identifier::toString,
				Identifier::from
			)
		));

		// Usually we don't need a mapping for ListingEntry because Listing takes care of it,
		// but we want to support people serializing a ListingEntry directly.
		directives.add(Directive.fixed(
			RepresentAsSpec.as(
				new BooleanNode(),
				DataType.of(ListingEntry.class),
				(ListingEntry _) -> true,
				(Boolean _) -> LISTING_ENTRY
			)
		));

		// TODO: Is this really a bosk thing? Should this be built into boson?
		directives.add(Directive.fixed(
			RepresentAsSpec.as(
				new StringNode(),
				DataType.CHAR,
				Object::toString,
				BosonSerializer::stringToChar
			)
		));

		record MapEntry<V>(Identifier id, V value) {}

		// This probably should be a SequencedCollection, but pcollections doesn't have that
		directives.add(Directive.fixed(
			RepresentAsSpec.of(new RepresentAsSpec.Wrangler<Catalog<E>, Collection<MapEntry<E>>>() {
				@Override
				public Collection<MapEntry<E>> toRepresentation(Catalog<E> value) {
					return value.stream().map(e -> new MapEntry<>(e.id(), e)).toList();
				}

				@Override
				public Catalog<E> fromRepresentation(Collection<MapEntry<E>> representation) {
					// TODO: validate ids?
					return Catalog.of(representation.stream().map(MapEntry::value));
				}
			})
		));

		directives.add(Directive.fixed(
			FixedMapNode.of(new FixedMapNode.Wrangler2<Listing<E>, CatalogReference<E>, List<Identifier>>(
				"domain",
				"ids"
			) {
				@Override
				public CatalogReference<E> accessor1(Listing<E> value) {
					return value.domain();
				}

				@Override
				public List<Identifier> accessor2(Listing<E> value) {
					return List.copyOf(value.ids());
				}

				@Override
				public Listing<E> finish(CatalogReference<E> domain, List<Identifier> ids) {
					return Listing.of(domain, ids);
				}
			})
		));

		record SideTableRepresentation<K extends Entity, V>(
			CatalogReference<K> domain,
			List<MapEntry<V>> valuesById
		) {}

		directives.add(Directive.fixed(
			RepresentAsSpec.of(new RepresentAsSpec.Wrangler<SideTable<E,T>, SideTableRepresentation<E,T>>() {
				@Override
				public SideTableRepresentation<E, T> toRepresentation(SideTable<E, T> value) {
					return new SideTableRepresentation<>(
						value.domain(),
						value.idEntrySet().stream().map(e -> new MapEntry<>(e.getKey(), e.getValue())).toList()
					);
				}

				@Override
				public SideTable<E, T> fromRepresentation(SideTableRepresentation<E, T> representation) {
					SideTable.Builder <E, T> builder = SideTable.builder(representation.domain);
					for (var entry : representation.valuesById) {
						builder.put(entry.id(), entry.value());
					}
					return builder.build();
				}
			})
		));

		directives.add(Directive.fixed(
			UniformMapNode.singleton(new UniformMapNode.SingletonWrangler<MapEntry<T>, Identifier, T>(){
				@Override
				public Identifier getKey(MapEntry<T> value) {
					return value.id();
				}

				@Override
				public T getValue(MapEntry<T> value) {
					return value.value();
				}

				@Override
				public MapEntry<T> finish(Identifier id, T value) {
					return new MapEntry<>(id, value);
				}
			})
		));

		// It's remarkable how cumbersome this one is
		directives.add(new Directive(
			DataType.of(new TypeReference<TaggedUnion<V>>(){}),
			taggedUnionType -> switch (taggedUnionType) {
				case BoundType bt -> {
					var caseStaticType = (KnownType) bt.parameterType(TaggedUnion.class, 0);
					MapValue<Type> variantCaseMap;
					try {
						variantCaseMap = StateTreeSerializer.getVariantCaseMap(caseStaticType.rawClass());
					} catch (InvalidTypeException e) {
						throw new IllegalArgumentException(e);
					}
					SequencedMap<String, FixedMapMember> members = new LinkedHashMap<>();
					variantCaseMap.forEach((name, caseType) -> {
						var ifPresent = new TypeRefNode(DataType.of(caseType));
						var ifAbsent = new ComputedSpec(supplier(
							DataType.known(caseType),
							() -> null)); // This is a signal to the finisher that the case is absent
						var presenceCondition = MemberPresenceCondition.enclosingObject(
							TypedHandles.<TaggedUnion<V>, Boolean>function(
								taggedUnionType,
								DataType.BOOLEAN,
								tu -> name.equals(tu.variant().tag())));
						var accessor = TypedHandles.<TaggedUnion<V>, Object>function(
							taggedUnionType,
							DataType.known(caseType),
							TaggedUnion::variant);
						members.put(name, new FixedMapMember(
							new MaybeAbsentSpec(
								ifPresent,
								ifAbsent,
								presenceCondition),
							accessor
						));
					});
					yield FixedMapNode.withArrayFinisher(
						taggedUnionType,
						members,
						(Object[] args) -> {
							for (var arg: args) {
								if (arg instanceof VariantCase vc) {
									return TaggedUnion.of(vc);
								}
							}
							throw new IllegalStateException("Hey, no variant");
						}
					);
				}
				default -> throw new IllegalStateException("Unexpected value: " + taggedUnionType);
			}
		));

		directives.add(new Directive(
			DataType.of(new TypeReference<ListValue<T>>(){}),
			listValueType -> switch (listValueType) {
				case BoundType bt -> {
					KnownType elementType = (KnownType) bt.parameterType(ListValue.class, 0);
					@SuppressWarnings("unchecked")
					var factory = listValueFactory((Class<? extends ListValue<T>>)listValueType.leastUpperBoundClass());
					Object[] arrayArchetype = (Object[]) Array.newInstance(elementType.rawClass(), 0);
					yield RepresentAsSpec.of(new RepresentAsSpec.Wrangler<ListValue<T>,List<T>>() {
						@Override
						public List<T> toRepresentation(ListValue<T> value) {
							return value; // ListValue is a List
						}

						@Override
						public ListValue<T> fromRepresentation(List<T> representation) {
							return factory.apply(representation.toArray(arrayArchetype));
						}
					});
				}
				default -> throw new IllegalStateException("Unexpected ListValue type: " + listValueType);
			}
		));

		directives.add(Directive.fixed(
			RepresentAsSpec.of(new RepresentAsSpec.Wrangler<MapValue<T>, Map<String, T>>() {
				@Override
				public Map<String, T> toRepresentation(MapValue<T> value) {
					return value; // MapValue is a Map
				}

				@Override
				public MapValue<T> fromRepresentation(Map<String, T> representation) {
					return MapValue.copyOf(representation);
				}
			})));

		directives.add(new Directive(
			new TypeVariable("X", StateTreeNode.class),
			stateTreeNodeType -> switch (stateTreeNodeType) {
				case BoundType bt -> {
					// StateTreeNode offers some features that only work in the context of a StateTreeNode,
					// like omitting Optional fields. We can't add a directive for Optional itself
					// because there'd be no way to make that omit the member name from the containing object.

					Class<? extends Record> recordClass = bt.rawClass().asSubclass(Record.class);
					SequencedMap<String, FixedMapMember> componentsByName = new LinkedHashMap<>();
					for (var rc : recordClass.getRecordComponents()) {
						// Look for record components requiring special handling
						if (Optional.class.isAssignableFrom(rc.getType())) {
							// This is remarkably cumbersome
							var valueType = ReferenceUtils.parameterType(rc.getGenericType(), Optional.class, 0);
							var elementType = new TypeRefNode(DataType.known(valueType));
							var ifPresent = RepresentAsSpec.<Optional<?>, Object>as(
								elementType,
								DataType.known(rc.getGenericType()),
								Optional::get,
								Optional::of
							);
							var ifAbsent = new ComputedSpec(supplier(DataType.known(rc.getGenericType()),
								Optional::empty));
							var presenceCondition = memberValue(TypedHandles.<Optional<?>>predicate(DataType.known(rc.getGenericType()),
								Optional::isPresent));
							componentsByName.put(rc.getName(), new FixedMapMember(
								new MaybeAbsentSpec(ifPresent, ifAbsent, presenceCondition),
								componentAccessor(rc, lookup)
							));
						} else if (Phantom.class.isAssignableFrom(rc.getType())) {
							componentsByName.put(rc.getName(), new FixedMapMember(
								new ComputedSpec(supplier(DataType.known(rc.getGenericType()),
									Phantom::empty)),
								componentAccessor(rc, lookup)
							));
						} else {
							// A simple TypeRefNode will do
							componentsByName.put(rc.getName(), new FixedMapMember(
								new TypeRefNode(DataType.known(rc.getGenericType())),
								componentAccessor(rc, lookup)
							));
						}
					}
					yield new FixedMapNode(
						componentsByName,
						canonicalConstructor(recordClass, lookup)
					);
				}
				default -> throw new IllegalStateException("Unexpected StateTreeNode type: " + stateTreeNodeType);
			}
		));

		// References are a bit repetitive because we have four
		// different kinds, and they're all very similar.

		directives.add(Directive.fixed(
			RepresentAsSpec.of(
				new RepresentAsSpec.Wrangler<CatalogReference<E>,String>() {
					@Override
					public String toRepresentation(CatalogReference<E> ref) {
						return ref.path().urlEncoded();
					}

					@Override
					@SuppressWarnings("unchecked")
					public CatalogReference<E> fromRepresentation(String str) {
						try {
							return (CatalogReference<E>) bosk.rootReference().thenCatalog(Entity.class, Path.parse(str));
						} catch (InvalidTypeException e) {
							throw new IllegalArgumentException("Failed to parse Reference path: " + str, e);
						}
					}
				}
			)
		));

		directives.add(Directive.fixed(
			RepresentAsSpec.of(
				new RepresentAsSpec.Wrangler<ListingReference<E>,String>() {
					@Override
					public String toRepresentation(ListingReference<E> ref) {
						return ref.path().urlEncoded();
					}

					@Override
					@SuppressWarnings("unchecked")
					public ListingReference<E> fromRepresentation(String str) {
						try {
							return (ListingReference<E>) bosk.rootReference().thenListing(Entity.class, Path.parse(str));
						} catch (InvalidTypeException e) {
							throw new IllegalArgumentException("Failed to parse Reference path: " + str, e);
						}
					}
				}
			)
		));

		directives.add(Directive.fixed(
			RepresentAsSpec.of(
				new RepresentAsSpec.Wrangler<SideTableReference<E,T>,String>() {
					@Override
					public String toRepresentation(SideTableReference<E,T> ref) {
						return ref.path().urlEncoded();
					}

					@Override
					@SuppressWarnings("unchecked")
					public SideTableReference<E,T> fromRepresentation(String str) {
						try {
							return (SideTableReference<E,T>) bosk.rootReference().thenSideTable(Entity.class, Object.class, Path.parse(str));
						} catch (InvalidTypeException e) {
							throw new IllegalArgumentException("Failed to parse Reference path: " + str, e);
						}
					}
				}
			)
		));

		// If it's not one of the other kinds of Reference, it's a plain Reference<E>
		directives.add(Directive.fixed(
			RepresentAsSpec.of(
				new RepresentAsSpec.Wrangler<Reference<T>,String>() {
					@Override
					public String toRepresentation(Reference<T> ref) {
						return ref.path().urlEncoded();
					}

					@Override
					@SuppressWarnings("unchecked")
					public Reference<T> fromRepresentation(String str) {
						try {
							return (Reference<T>) bosk.rootReference().then(Object.class, Path.parse(str));
						} catch (InvalidTypeException e) {
							throw new IllegalArgumentException("Failed to parse Reference path: " + str, e);
						}
					}
				}
			)
		));

		return new TypeScanner.Bundle(
			"Bosk [" + bosk.name() + "]",
			List.of(DataType.of(ListingEntry.class)),
			List.of(lookup),
			List.copyOf(directives)
		);
	}

	private static char stringToChar(String s) {
		if (s.length() == 1) {
			return s.charAt(0);
		} else {
			throw new IllegalArgumentException("Expected single-character string, got: " + s);
		}
	}

}
