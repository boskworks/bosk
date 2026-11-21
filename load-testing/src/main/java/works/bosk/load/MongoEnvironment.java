package works.bosk.load;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Map;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A disposable MongoDB replica set for a load run.
 *
 * <p>The driver needs change streams, so the container is started as a single-node replica
 * set. Each run gets its own container, which keeps runs independent and makes teardown
 * reliable.
 */
final class MongoEnvironment implements AutoCloseable {
	private final MongoDBContainer container;
	private final MongoClientSettings clientSettings;

	static MongoEnvironment start() {
		MongoDBContainer container = new MongoDBContainer(DockerImageName.parse(MONGO_IMAGE))
			.withTmpFs(Map.of("/data/db", "rw"))
			.withReplicaSet();
		container.start();
		MongoClientSettings clientSettings = MongoClientSettings.builder()
			.applyConnectionString(new ConnectionString(container.getConnectionString()))
			.build();
		return new MongoEnvironment(container, clientSettings);
	}

	private MongoEnvironment(MongoDBContainer container, MongoClientSettings clientSettings) {
		this.container = container;
		this.clientSettings = clientSettings;
	}

	MongoClientSettings clientSettings() {
		return clientSettings;
	}

	void dropCollection(String database, String collection) {
		try (MongoClient client = MongoClients.create(clientSettings)) {
			client.getDatabase(database).getCollection(collection).drop();
		}
	}

	@Override
	public void close() {
		container.stop();
	}

	private static final String MONGO_IMAGE = "mongo:9.0.4";
}
