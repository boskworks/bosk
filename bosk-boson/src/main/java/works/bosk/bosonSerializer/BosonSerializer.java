package works.bosk.bosonSerializer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import org.jspecify.annotations.NonNull;
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
import works.bosk.boson.exceptions.JsonContentException;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.TypeScanner.Directive;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.FixedObjectNode;
import works.bosk.boson.mapping.spec.FixedObjectNode.TwoMemberWrangler;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.ParseCallbackSpec;
import works.bosk.boson.mapping.spec.RecognizedMember;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.UniformMapNode.MemberValueWrangler;
import works.bosk.boson.mapping.spec.UniformMapNode.OneMemberWrangler;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;
import works.bosk.boson.types.TypeVariable;
import works.bosk.exceptions.DeserializationException;
import works.bosk.exceptions.InvalidTypeException;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
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
		MethodHandles.Lookup lookup = lookup();

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

		/*
		 * Both {@link Catalog} and {@link SideTable} serialize as a list of key-value pairs
		 * to maintain the order of the entries. This type represents that structure.
		 */
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
					for (MapEntry<E> entry: representation) {
						Identifier valueID = entry.value().id();
						if (!entry.id().equals(valueID)) {
							throw new JsonContentException("Catalog entry ID mismatch: " + entry.id() + " vs " + valueID);
						}
					}
					return Catalog.of(representation.stream().map(MapEntry::value));
				}
			})
		));

		directives.add(Directive.fixed(
			FixedObjectNode.of(new TwoMemberWrangler<Listing<E>, CatalogReference<E>, List<Identifier>>(
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

		// For MapEntry, we need a MemberValueWrangler to open and close a DeserializationScope
		directives.add(Directive.fixed(UniformMapNode.oneMember(
			new OneMemberWrangler<MapEntry<T>, Identifier, T>() {
				@Override public Identifier getKey(MapEntry<T> v) { return v.id(); }
				@Override public T getValue(MapEntry<T> v) { return v.value(); }
				@Override public MapEntry<T> finish(Identifier key, T value) { return new MapEntry<>(key, value); }
			},
			new MemberValueWrangler<Identifier, T, DeserializationScope>() {
				@Override public DeserializationScope beforeValue(Identifier key) {
					return BosonSerializer.this.entryDeserializationScope(key);
				}
				@Override public void afterValue(Identifier key, T value, DeserializationScope scope) {
					// This won't get called if there's an exception after the scope is opened,
					// but there's a top-level try-finally around the parsing process that
					// will reset things properly in that case anyway.
					scope.close();
				}
			}
		)));

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
					SequencedMap<String, RecognizedMember> members = new LinkedHashMap<>();
					variantCaseMap.forEach((name, caseType) -> {
						var ifPresent = new ParseCallbackSpec(
							openVariantCaseDeserializationScope(name, lookup),
							new TypeRefNode(DataType.of(caseType)),
							closeVariantCaseDeserializationScope(ReferenceUtils.rawClass(caseType), lookup));
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
						members.put(name, new RecognizedMember(
							new MaybeAbsentSpec(
								ifPresent,
								ifAbsent,
								presenceCondition),
							accessor
						));
					});
					yield FixedObjectNode.withArrayFinisher(
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
			new TypeVariable("X", StateTreeNode.class), // Any subtype of StateTreeNode
			stateTreeNodeType -> switch (stateTreeNodeType) {
				case BoundType bt -> {
					// StateTreeNode offers some features that only work in the context of a StateTreeNode,
					// like omitting Optional fields. We can't add a directive for Optional itself
					// because there'd be no way to make that omit the member name from the containing object.

					Class<? extends Record> recordClass = bt.rawClass().asSubclass(Record.class);
					SequencedMap<String, RecognizedMember> componentsByName = new LinkedHashMap<>();
					for (var rc : recordClass.getRecordComponents()) {
						// Look for record components requiring special handling
						if (Optional.class.isAssignableFrom(rc.getType())) {
							// This is remarkably cumbersome
							var valueType = ReferenceUtils.parameterType(rc.getGenericType(), Optional.class, 0);
							var elementType = new TypeRefNode(DataType.known(valueType));
							// The element needs an appropriate scope for @Self to resolve inside it
							TypedHandle closeScope;
							try {
								MethodHandle close = lookup.findVirtual(DeserializationScope.class, "close",
									methodType(void.class));
								MethodHandle closeMh = dropArguments(close, 1, ReferenceUtils.rawClass(valueType));
								closeScope = new TypedHandle(closeMh,
									DataType.VOID,
									List.of(DataType.known(DeserializationScope.class), DataType.known(valueType)));
							} catch (NoSuchMethodException | IllegalAccessException e) {
								throw new IllegalArgumentException("Failed to create scope callback for " + rc.getName(), e);
							}
							var scopedElement = new ParseCallbackSpec(
								openRecordComponentDeserializationScope(rc, recordClass, lookup),
								elementType,
								closeScope);
							var ifPresent = RepresentAsSpec.<Optional<?>, Object>as(
								scopedElement,
								DataType.known(rc.getGenericType()),
								Optional::get,
								Optional::of
							);
							var ifAbsent = new ComputedSpec(supplier(DataType.known(rc.getGenericType()),
								Optional::empty));
							var presenceCondition = memberValue(TypedHandles.<Optional<?>>predicate(DataType.known(rc.getGenericType()),
								Optional::isPresent));
							componentsByName.put(rc.getName(), new RecognizedMember(
								new MaybeAbsentSpec(ifPresent, ifAbsent, presenceCondition),
								componentAccessor(rc, lookup)
							));
						} else if (Phantom.class.isAssignableFrom(rc.getType())) {
							componentsByName.put(rc.getName(), new RecognizedMember(
								new ComputedSpec(supplier(DataType.known(rc.getGenericType()),
									Phantom::empty)),
								componentAccessor(rc, lookup)
							));
						} else if (isImplicitParameter(recordClass, rc)) {
							componentsByName.put(rc.getName(), new RecognizedMember(
								new ComputedSpec(supplier(DataType.known(rc.getGenericType()),
									() -> {
										try {
											return implicitReference(recordClass, rc, bosk);
										} catch (DeserializationException e) {
											throw new JsonContentException(e);
										}
									})),
								componentAccessor(rc, lookup)
							));
						} else {
							// This would be just a TypeRefNode, except we also need a DeserializationScope.
							// The close won't get called if there's an exception while parsing the record component,
							// but there's a top-level try-finally around the parsing process that
							// will reset things properly in that case anyway.
							componentsByName.put(rc.getName(), new RecognizedMember(
								new ParseCallbackSpec(
									openRecordComponentDeserializationScope(rc, recordClass, lookup),
									new TypeRefNode(DataType.known(rc.getGenericType())),
									closeRecordComponentDeserializationScope(rc, lookup)
								),
								componentAccessor(rc, lookup)
							));
						}
					}
					yield new FixedObjectNode(
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

	/**
	 * @return nullary callback that opens a {@link DeserializationScope} for a variant case tag.
	 */
	private @NonNull TypedHandle openVariantCaseDeserializationScope(String tag, Lookup lookup) {
		try {
			MethodHandle variantCaseDeserializationScope = lookup.findVirtual(StateTreeSerializer.class,
				"variantCaseDeserializationScope",
				methodType(DeserializationScope.class, String.class));
			return new TypedHandle(
				insertArguments(variantCaseDeserializationScope, 0,
					this, tag
				),
				DataType.known(DeserializationScope.class), List.of());
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalArgumentException("Failed to create scope callback for variant case " + tag, e);
		}
	}

	/**
	 * @return callback that closes a {@link DeserializationScope}
	 * opened by {@link #openVariantCaseDeserializationScope(String, Lookup)}.
	 */
	private static @NonNull TypedHandle closeVariantCaseDeserializationScope(Class<?> caseClass, Lookup lookup) {
		try {
			MethodHandle close = lookup.findVirtual(DeserializationScope.class, "close",
				methodType(void.class));

			// The callback receives the parsed variant case value, but we don't use it
			MethodHandle mh = dropArguments(close, 1, caseClass);

			return new TypedHandle(mh,
				DataType.VOID,
				List.of(
					DataType.known(DeserializationScope.class),
					DataType.known(caseClass)
				));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalArgumentException("Failed to create scope callback for variant case " + caseClass.getSimpleName(), e);
		}
	}

	/**
	 * @return nullary callback that opens a {@link DeserializationScope} for a given record component.
	 */
	private @NonNull TypedHandle openRecordComponentDeserializationScope(RecordComponent rc, Class<? extends Record> recordClass, Lookup lookup) {
		try {
			MethodHandle nodeFieldDeserializationScope = lookup.findVirtual(StateTreeSerializer.class,
				"nodeFieldDeserializationScope",
				methodType(DeserializationScope.class, Class.class, String.class));
			return new TypedHandle(
				insertArguments(nodeFieldDeserializationScope, 0,
					this, recordClass, rc.getName()
				),
				DataType.known(DeserializationScope.class), List.of());
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalArgumentException("Failed to create scope callback for " + rc.getName(), e);
		}
	}

	/**
	 * @return callback that closes a {@link DeserializationScope}
	 * opened by {@link #openRecordComponentDeserializationScope(RecordComponent, Class, Lookup)}.
	 */
	private static @NonNull TypedHandle closeRecordComponentDeserializationScope(RecordComponent rc, Lookup lookup) {
		try {
			MethodHandle close = lookup.findVirtual(DeserializationScope.class, "close",
				methodType(void.class));

			// The callback receives the parsed record component value, but we don't use it
			MethodHandle mh = dropArguments(close, 1, rc.getType());

			return new TypedHandle(mh,
				DataType.VOID,
				List.of(
					DataType.known(DeserializationScope.class),
					DataType.known(rc.getGenericType())
				));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalArgumentException("Failed to create scope callback for " + rc.getName(), e);
		}
	}

}
