package works.bosk.drivers.mongo.internal;

import java.util.Optional;
import java.util.UUID;
import works.bosk.Identifier;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.MongoDriverSettings.SequoiaFormat;
import works.bosk.drivers.mongo.PandoFormat;

import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;

/**
 * Defines the format of the manifest document, which is stored in the database
 * to describe the database contents.
 *
 * @param version the version of the format of the manifest itself; currently must be 1
 * @param formatId identifies this instance of the format; it is recorded on every
 * state document and is changed by a refurbish that changes the format, so an
 * update can tell whether the format it was planned against is still current
 * @param sequoia if present, indicates that the database is in the Sequoia format
 * @param pando if present, indicates that the database is in the Pando format and describes how it's configured
 */
public record Manifest(
	Integer version,
	Identifier formatId,
	Optional<EmptyNode> sequoia,
	Optional<PandoFormat> pando
) implements StateTreeNode {
	public Manifest {
		if (sequoia.isPresent() == pando.isPresent()) {
			throw new IllegalArgumentException("Exactly one format (sequoia or pando) must be specified in manifest");
		}
	}

	public record EmptyNode() implements StateTreeNode { }

	/**
	 * @return the format this manifest prescribes
	 */
	public DatabaseFormat format() {
		return pando.isPresent()? pando.get(): SEQUOIA;
	}

	public static Identifier newFormatId() {
		return Identifier.from(UUID.randomUUID().toString());
	}

	public static Manifest forSequoia(Identifier formatId) {
		return new Manifest(1, formatId, Optional.of(new EmptyNode()), Optional.empty());
	}

	public static Manifest forPando(PandoFormat settings, Identifier formatId) {
		return new Manifest(1, formatId, Optional.empty(), Optional.of(settings));
	}

	public static Manifest forFormat(DatabaseFormat format, Identifier formatId) {
		return switch (format) {
			case PandoFormat p -> forPando(p, formatId);
			case SequoiaFormat _ -> forSequoia(formatId);
		};
	}
}
