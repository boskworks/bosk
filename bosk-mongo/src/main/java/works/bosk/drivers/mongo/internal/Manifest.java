package works.bosk.drivers.mongo.internal;

import java.util.Optional;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.MongoDriverSettings.SequoiaFormat;
import works.bosk.drivers.mongo.PandoFormat;

/**
 * Defines the format of the manifest document, which is stored in the database
 * to describe the database contents.
 */
public record Manifest(
	Integer version,
	Optional<EmptyNode> sequoia,
	Optional<PandoFormat> pando
) implements StateTreeNode {
	public Manifest {
		if (sequoia.isPresent() == pando.isPresent()) {
			throw new IllegalArgumentException("Exactly one format (sequoia or pando) must be specified in manifest");
		}
	}

	public record EmptyNode() implements StateTreeNode { }

	public static Manifest forSequoia() {
		return new Manifest(1, Optional.of(new EmptyNode()), Optional.empty());
	}

	public static Manifest forPando(PandoFormat settings) {
		return new Manifest(1, Optional.empty(), Optional.of(settings));
	}

	public static Manifest forFormat(DatabaseFormat format) {
		return switch (format) {
			case PandoFormat p -> forPando(p);
			case SequoiaFormat _ -> forSequoia();
		};
	}
}
