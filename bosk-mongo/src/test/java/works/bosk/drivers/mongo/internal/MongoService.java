package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import static com.mongodb.ReadPreference.secondaryPreferred;
import static eu.rekawek.toxiproxy.model.ToxicDirection.DOWNSTREAM;
import static eu.rekawek.toxiproxy.model.ToxicDirection.UPSTREAM;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * An interface to the dockerized MongoDB replica set,
 * suitable for use by a test class.
 *
 * <p>
 * Use {@link DisruptsMongoProxy} to make sure that
 * the network outage tests don't run at the same time as other tests that use the proxy.
 *
 * <p>
 * All instances use the same MongoDB container, so
 * different tests should use different database names.
 * All instances use the same proxy, so network outage tests
 * will disrupt other outage tests running in parallel.
 *
 */
public class MongoService implements Closeable {
	// We do logging in some static initializers, so this needs to be initialized first
	private static final Logger LOGGER = LoggerFactory.getLogger(MongoService.class);

	public static final DockerImageName MONGODB_IMAGE_NAME = DockerImageName.parse("mongo:8.0");

	private final MongoClient mongoClient = MongoClients.create(normalClientSettings);

	// Expensive stuff shared among instances as much as possible, hence static
	private static final Network NETWORK = Network.newNetwork();
	private static final MongoDBContainer MONGO_CONTAINER = mongoContainer();
	private static final MongoClientSettings normalClientSettings = mongoClientSettings(
		new ServerAddress(MONGO_CONTAINER.getHost(), MONGO_CONTAINER.getFirstMappedPort())
	);

	private static final ToxiproxyContainer TOXIPROXY_CONTAINER = toxiproxyContainer();
	private static final ToxiproxyClient TOXIPROXY_CLIENT = createToxiproxyClient();

	private static final Proxy MONGO_PROXY = createMongoProxy();
	private static final int PROXY_PORT = 8666;
	private static final MongoClientSettings disruptableClientSettings = mongoClientSettings(
		new ServerAddress(TOXIPROXY_CONTAINER.getHost(), TOXIPROXY_CONTAINER.getMappedPort(PROXY_PORT))
	);

	private static final String CUT_CONNECTION_DOWNSTREAM = "CUT_CONNECTION_DOWNSTREAM";
	private static final String CUT_CONNECTION_UPSTREAM = "CUT_CONNECTION_UPSTREAM";

	public void cutConnection() {
		try {
			MONGO_PROXY.toxics().bandwidth(CUT_CONNECTION_DOWNSTREAM, DOWNSTREAM, 0);
			MONGO_PROXY.toxics().bandwidth(CUT_CONNECTION_UPSTREAM, UPSTREAM, 0);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to cut connection", e);
		}
	}

	public void restoreConnection() {
		try {
			MONGO_PROXY.toxics().get(CUT_CONNECTION_DOWNSTREAM).remove();
			MONGO_PROXY.toxics().get(CUT_CONNECTION_UPSTREAM).remove();
		} catch (IOException e) {
			// The proxy offers no way to check if a toxic exists,
			// and no way to remove it without first getting it.
			LOGGER.debug("This can happen if the connection was not already cut; ignoring", e);
		}
	}

	public MongoClientSettings clientSettings(TestInfo testInfo) {
		return testInfo.getTags().contains(DisruptsMongoProxy.TAG) ? disruptableClientSettings : normalClientSettings;
	}

	public MongoClient client() {
		return mongoClient;
	}

	@Override
	public void close() {
		mongoClient.close();
	}

	private static MongoDBContainer mongoContainer() {
		MongoDBContainer container = new MongoDBContainer(MONGODB_IMAGE_NAME)
			.withReplicaSet()
			.withTmpFs(Map.of("/data/db", "rw"))
			.withNetwork(NETWORK);
		container.start();
		return container;
	}

	private static ToxiproxyContainer toxiproxyContainer() {
		ToxiproxyContainer result = new ToxiproxyContainer(
			DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
				.asCompatibleSubstituteFor("shopify/toxiproxy"))
			.withNetwork(NETWORK);
		result.start();
		return result;
	}

	private static ToxiproxyClient createToxiproxyClient() {
		return new ToxiproxyClient(
			TOXIPROXY_CONTAINER.getHost(),
			TOXIPROXY_CONTAINER.getControlPort()
		);
	}

	private static Proxy createMongoProxy() {
		try {
			return TOXIPROXY_CLIENT.createProxy(
				"mongo",
				"0.0.0.0:" + PROXY_PORT,
				MONGO_CONTAINER.getNetworkAliases().getFirst() + ":27017"
			);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create Mongo proxy", e);
		}
	}

	@NotNull
	static MongoClientSettings mongoClientSettings(ServerAddress serverAddress) {
		LOGGER.info("MongoDB: {}", serverAddress);
		int initialTimeoutMS = 60_000;
		int queryTimeoutMS = 5_000; // Don't wait an inordinately long time for network outage testing
		return MongoClientSettings.builder()
			.readPreference(secondaryPreferred())
			.writeConcern(WriteConcern.MAJORITY)
			.readConcern(ReadConcern.MAJORITY)
			.applyToClusterSettings(builder -> {
				builder.hosts(singletonList(serverAddress));
				builder.serverSelectionTimeout(initialTimeoutMS, MILLISECONDS);
			})
			.applyToSocketSettings(builder -> {
				builder.connectTimeout(initialTimeoutMS, MILLISECONDS);
				builder.readTimeout(queryTimeoutMS, MILLISECONDS);
			})
			.build();
	}
}
