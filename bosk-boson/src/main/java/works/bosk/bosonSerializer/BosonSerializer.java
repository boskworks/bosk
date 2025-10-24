package works.bosk.bosonSerializer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;
import works.bosk.BoskInfo;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Phantom;
import works.bosk.Reference;
import works.bosk.ReferenceUtils;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.TypeScanner.Directive;
import works.bosk.boson.mapping.spec.BooleanNode;
import works.bosk.boson.mapping.spec.ComputedSpec;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.MaybeAbsentSpec;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;
import works.bosk.boson.types.UpperBoundedWildcardType;
import works.bosk.exceptions.InvalidTypeException;

import static java.lang.invoke.MethodType.methodType;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.boson.mapping.spec.handles.MemberPresenceCondition.memberValue;

public class BosonSerializer extends StateTreeSerializer {

	public TypeScanner.Bundle bundleFor(BoskInfo<?> bosk) {
		MethodHandles.Lookup lookup = MethodHandles.lookup();

		var directives = new ArrayList<Directive>();

		directives.add(new Directive(
			DataType.of(new TypeReference<Reference<?>>(){}),
			referenceType -> {
				switch (referenceType) {
					case BoundType bt -> {
						// TODO: This wouldn't be necessary if 'then' would return the appropriate subtype
						ReferenceParser parser =
							switch (bt.parameterType(Reference.class, 0)) {
								case BoundType t when Catalog.class.isAssignableFrom(t.rawClass()) -> str ->
									bosk.rootReference().thenCatalog(Entity.class, Path.parse(str));
								case BoundType t when Listing.class.isAssignableFrom(t.rawClass()) -> str ->
									bosk.rootReference().thenListing(Entity.class, Path.parse(str));
								case BoundType t when SideTable.class.isAssignableFrom(t.rawClass()) -> str ->
									bosk.rootReference().thenSideTable(Entity.class, Object.class, Path.parse(str));
								default -> str ->
									bosk.rootReference().then(Object.class, Path.parse(str));
							};
						Function<String, Reference<?>> fromRepresentation = str -> {
							try {
								return parser.parse(str);
							} catch (InvalidTypeException e) {
								throw new IllegalArgumentException("Failed to parse Reference path: " + str, e);
							}
						};
						return RepresentAsSpec.as(
							new StringNode(),
							referenceType,
							(Reference<?> ref) -> ref.path().urlEncoded(),
							fromRepresentation
						);
					}
					default -> throw new IllegalStateException("Unexpected Reference type: " + referenceType);
				}
			}
		));

		directives.add(new Directive(
			DataType.known(Identifier.class),
			identifierType -> RepresentAsSpec.as(
				new StringNode(),
				identifierType,
				Identifier::toString,
				Identifier::from
			)
		));

		// TODO: I wonder why this is necessary? When do we ever actually encounter a ListingEntry?
		directives.add(new Directive(
			DataType.of(ListingEntry.class),
			listingEntryType -> RepresentAsSpec.as(
				new BooleanNode(),
				listingEntryType,
				(ListingEntry _) -> true,
				(Boolean _) -> LISTING_ENTRY
			)
		));

		directives.add(new Directive(
			DataType.of(char.class),
			charType -> RepresentAsSpec.as(
				new StringNode(),
				charType,
				Object::toString,
				BosonSerializer::stringToChar
			)
		));

		// At this point, we have a few simple types available.
		// The remainder are more complex, and so they benefit from leveraging the TypeScanner
		// to do an initial data structure scan which we then modify.
		// We call this the "pre-scan", and it benefits from having the simple types above available.
		TypeScanner.Bundle simpleScanBundle = new TypeScanner.Bundle(
			List.of(DataType.of(ListingEntry.class)),
			List.of(lookup),
			List.copyOf(directives)
		);

		directives.add(new Directive(
			DataType.of(new TypeReference<Catalog<? extends Entity>>(){}),
			catalogType -> switch (catalogType) {
				case BoundType bt -> {
					KnownType representation = new BoundType(
						Map.class,
						List.of(
							DataType.known(Identifier.class),
							bt.parameterType(Catalog.class, 0)
						));
					yield RepresentAsSpec.<Catalog<?>, Map<Identifier, ? extends Entity>>as(
						preScan(representation, simpleScanBundle),
						catalogType,
						Catalog::asMap,
						map -> Catalog.of(map.values()) // TODO: validate ids?
					);
				}
				default -> throw new IllegalStateException("Unexpected Catalog type: " + catalogType);
			}
		));

		directives.add(new Directive(
			DataType.of(new TypeReference<Listing<?>>(){}),
			listingType -> switch (listingType) {
				case BoundType bt -> {
					var memberSpecs = new LinkedHashMap<String, FixedMapMember>();

					var entryType = (KnownType) bt.parameterType(Listing.class, 0);
					var domainRefType = new BoundType(
						Reference.class,
						new BoundType(Catalog.class, entryType));
					TypedHandle domainAccessor = TypedHandles.<Listing<?>, Reference<?>>function(
						DataType.known(Listing.class),
						domainRefType,
						Listing::domain);
					memberSpecs.put("domain", new FixedMapMember(new TypeRefNode(domainRefType), domainAccessor));

					var idsType = new BoundType(List.class, DataType.known(Identifier.class));
					TypedHandle idsAccessor = TypedHandles.<Listing<?>, List<?>>function(
						DataType.known(Listing.class),
						idsType,
						listing -> List.copyOf(listing.ids()));
					memberSpecs.put("ids", new FixedMapMember(new TypeRefNode(idsType), idsAccessor));

					yield new FixedMapNode(
						memberSpecs,
						new TypedHandle(
							LISTING_OF.asType(methodType(
								listingType.rawClass(),
								domainRefType.rawClass(),
								idsType.rawClass()
							)),
							listingType, List.of(domainRefType, idsType)
						)
					);
				}
				default -> throw new IllegalStateException("Unexpected Listing type: " + listingType);
			}
		));

		directives.add(new Directive(
			DataType.of(new TypeReference<SideTable<?,?>>() { }),
			sideTableType -> switch (sideTableType) {
				case BoundType bt -> {
					var representation = new BoundType(SideTableRepresentation.class, bt.bindings());
					yield RepresentAsSpec.<SideTable<?,?>, SideTableRepresentation<?,?>>as(
						preScan(representation, simpleScanBundle),
						sideTableType,
						SideTableRepresentation::fromSideTable,
						SideTableRepresentation::toSideTable
					);
				}

				default -> throw new IllegalStateException("Unexpected SideTable type: " + sideTableType);
			}
		));

		directives.add(new Directive(
			DataType.of(new TypeReference<TaggedUnion<?>>(){}),
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
						var ifPresent = preScan(caseType, simpleScanBundle);
						var ifAbsent = new ComputedSpec(TypedHandles.supplier(
							DataType.known(caseType),
							() -> null)); // This is a signal to the finisher that the case is absent
						var presenceCondition = MemberPresenceCondition.enclosingObject(
							TypedHandles.<TaggedUnion<?>, Boolean>function(
								taggedUnionType,
								DataType.BOOLEAN,
								tu -> name.equals(tu.variant().tag())));
						var accessor = TypedHandles.<TaggedUnion<?>, Object>function(
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
			new UpperBoundedWildcardType(DataType.of(StateTreeNode.class)),
			stateTreeNodeType -> switch (stateTreeNodeType) {
				case BoundType bt -> {
					// Configure the preScan so it does the right thing with Optional and Phantom components
					Class<? extends Record> recordClass = bt.rawClass().asSubclass(Record.class);
					Map<String, FixedMapMember> componentsByName = new HashMap<>();
					for (var rc: recordClass.getRecordComponents()) {
						if (Optional.class.isAssignableFrom(rc.getType())) {
							var valueType = ReferenceUtils.parameterType(rc.getGenericType(), Optional.class, 0);
							var elementType = new TypeRefNode(DataType.known(valueType));
							var ifPresent = RepresentAsSpec.<Optional<?>,Object>as(
								elementType,
								DataType.known(rc.getGenericType()),
								Optional::get,
								Optional::of
							);
							var ifAbsent = new ComputedSpec(TypedHandles.supplier(DataType.known(rc.getGenericType()),
								Optional::empty));
							var presenceCondition = memberValue(TypedHandles.<Optional<?>>predicate(DataType.known(rc.getGenericType()),
								Optional::isPresent));
							componentsByName.put(rc.getName(), new FixedMapMember(
								new MaybeAbsentSpec(ifPresent, ifAbsent, presenceCondition),
								TypedHandles.componentAccessor(rc, lookup)
							));
						} else if (Phantom.class.isAssignableFrom(rc.getType())) {
							componentsByName.put(rc.getName(), new FixedMapMember(
								new ComputedSpec(TypedHandles.supplier(DataType.known(rc.getGenericType()),
									Phantom::empty)),
								TypedHandles.componentAccessor(rc, lookup)
							));
						}
					}

					// Now, with this in place, a shallow preScan returns the right thing
					yield preScan(bt,
						new TypeScanner(TypeMap.Settings.SHALLOW)
							.specifyRecordFields(recordClass, componentsByName),
						simpleScanBundle
					);
				}
				default -> throw new IllegalStateException("Unexpected StateTreeNode type: " + stateTreeNodeType);
			}
		));

		directives.add(new Directive(
			DataType.of(new TypeReference<ListValue<?>>(){}),
			listValueType -> switch (listValueType) {
				case BoundType bt -> {
					KnownType elementType = (KnownType) bt.parameterType(ListValue.class, 0);
					@SuppressWarnings("unchecked")
					var factory = listValueFactory((Class<ListValue<Object>>)listValueType.rawClass());
					Object[] arrayArchetype = (Object[]) Array.newInstance(elementType.rawClass(), 0);
					var listSpec = preScan(new BoundType(List.class, List.of(elementType)), simpleScanBundle);
					yield RepresentAsSpec.<ListValue<?>,List<?>>as(
						listSpec,
						listValueType,
						lv -> lv,
						list -> factory.apply(list.toArray(arrayArchetype))
					);
				}
				default -> throw new IllegalStateException("Unexpected ListValue type: " + listValueType);
			}
		));

		directives.add(new Directive(
			DataType.of(new TypeReference<MapValue<?>>(){}),
			mapValueType -> switch (mapValueType) {
				case BoundType bt -> {
					KnownType representation = new BoundType(
						Map.class,
						List.of(
							DataType.known(String.class),
							bt.parameterType(MapValue.class, 0)
						)
					);
					yield RepresentAsSpec.as(
						preScan(representation, simpleScanBundle),
						mapValueType,
						(MapValue<?> mv) -> (Map<String,?>) mv,
						MapValue::copyOf
					);
				}
				default -> throw new IllegalStateException("Unexpected MapValue type: " + mapValueType);
			}));

		return new TypeScanner.Bundle(
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

	interface ReferenceParser {
		Reference<?> parse(String path) throws InvalidTypeException;
	}

	private JsonValueSpec preScan(Type type, TypeScanner.Bundle prescanBundle) {
		return preScan(DataType.of(type), prescanBundle);
	}

	private static JsonValueSpec preScan(DataType dataType, TypeScanner.Bundle prescanBundle) {
		return preScan(dataType, new TypeScanner(TypeMap.Settings.SHALLOW), prescanBundle);
	}

	private static JsonValueSpec preScan(DataType dataType, TypeScanner typeScanner, TypeScanner.Bundle prescanBundle) {
		return typeScanner
			.addLast(prescanBundle)
			.scan(dataType)
			.build()
			.get(dataType);
	}

	record SideTableRepresentation<K extends Entity, V>(CatalogReference<K> domain, Map<Identifier, V> valuesById){
		public static <KK extends Entity, VV> SideTableRepresentation<KK,VV> fromSideTable(SideTable<KK,VV> sideTable) {
			return new SideTableRepresentation<>(
				sideTable.domain(),
				sideTable.asMap()
			);
		}

		@SuppressWarnings("rawtypes")
		public SideTable toSideTable() {
			return SideTable.copyOf(domain, this.valuesById());
		}
	}

	private static final MethodHandle LISTING_OF;

	static {
		try {
			LISTING_OF = MethodHandles.lookup().findStatic(Listing.class, "of", methodType(Listing.class, Reference.class, Collection.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
