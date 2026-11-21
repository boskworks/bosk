package works.bosk.drivers.mongo;

import java.util.Collection;
import works.bosk.Catalog;
import works.bosk.ListValue;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.exceptions.FormatMisconfigurationException;

import static java.util.Arrays.asList;
import static works.bosk.drivers.mongo.PandoFormat.OrphanDocumentMode.EARNEST;
import static works.bosk.drivers.mongo.PandoFormat.OrphanDocumentMode.HASTY;

/**
 * A scalable format that stores the bosk state in multiple documents,
 * thereby overcoming MongoDB's 16MB document size limit.
 * <p>
 * Reconstructing the state tree from the documents is not dependent
 * on the graft point configuration because the documents are self-describing:
 * whatever way the state happens to be divided into documents,
 * there is an unambiguous way to reconstruct the full state tree.
 * However, modifying the state requires correct graft point configuration,
 * or else different updates to the same node could end up in different documents,
 * leading to inconsistencies and contradictions.
 * <p>
 * The orphan-document mode is part of the format because it says what a deletion
 * must clean up, which is a property of the collection rather than of the bosk
 * doing the deleting. The mode is recorded in the manifest when the collection is
 * initialized; thereafter the manifest's mode governs, whichever mode other bosks
 * prefer.
 * <p>
 * Named after Pando: looks like a forest, but it's actually one tree with one root system.
 *
 * @param graftPoints A list of path strings pointing to {@link Catalog Catalog}
 *                   or {@link SideTable SideTable} tree nodes whose entries (children)
 *                   are to be stored in their own documents.
 * @param orphanDocumentMode How deletions treat documents that the state tree no longer references.
 */
public record PandoFormat(
	ListValue<String> graftPoints,
	OrphanDocumentMode orphanDocumentMode
) implements StateTreeNode, DatabaseFormat {
	public PandoFormat {
		if (orphanDocumentMode == EARNEST) {
			throw new FormatMisconfigurationException("The EARNEST orphan-document mode is not implemented yet");
		}
	}

	@Override public String name() { return "Pando"; }

	/**
	 * Differs from Sequoia in that (1) the root document has a different ID, rendering the formats incompatible;
	 * and (2) Sequoia is designed not to need multi-document transactions.
	 */
	public static PandoFormat oneBigDocument() {
		return new PandoFormat(ListValue.empty(), HASTY);
	}

	public static PandoFormat withGraftPoints(Collection<String> pathStrings) {
		return new PandoFormat(ListValue.from(pathStrings), HASTY);
	}

	public static PandoFormat withGraftPoints(String... pathStrings) {
		return withGraftPoints(asList(pathStrings));
	}

	public enum OrphanDocumentMode {
		/**
		 * Unused documents are always deleted before the end of the transaction.
		 * <p>
		 * Not implemented; a format with this mode is rejected for now.
		 */
		EARNEST,

		/**
		 * Unused documents may be left behind, to be cleaned up later.
		 */
		HASTY,
	}

}
