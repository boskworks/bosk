package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import static java.util.Collections.singletonList;

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

	private static final DockerImageName MONGODB_IMAGE_NAME = DockerImageName.parse("mongo:8.0");

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
	private static final ServerAddress DISRUPTABLE_SERVER_ADDRESS = new ServerAddress(TOXIPROXY_CONTAINER.getHost(), TOXIPROXY_CONTAINER.getMappedPort(PROXY_PORT));
	private static final int TCP_CONNECTION_TIMEOUT_MS = 1000;
	private static final MongoClientSettings disruptableClientSettings = mongoClientSettings(DISRUPTABLE_SERVER_ADDRESS);

	public void cutConnection() {
		try {
			MONGO_PROXY.disable();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to cut connection", e);
		}
	}

	public void restoreConnection() {
		try {
			MONGO_PROXY.enable();
			awaitTcpConnection();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to restore connection", e);
		}
	}

	void awaitTcpConnection() throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(
				new InetSocketAddress(DISRUPTABLE_SERVER_ADDRESS.getHost(), DISRUPTABLE_SERVER_ADDRESS.getPort()),
				TCP_CONNECTION_TIMEOUT_MS
			);
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
		MongoDBContainer container = newPlainMongoContainer()
			.withTmpFs(Map.of("/data/db", "rw"))
			.withNetwork(NETWORK)
			.withReplicaSet();
		container.start();
		return container;
	}

	/**
	 * @return just the {@link MongoDBContainer} with no options, and not yet started
	 */
	static MongoDBContainer newPlainMongoContainer() {
		return new MongoDBContainer(MONGODB_IMAGE_NAME);
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
		return MongoClientSettings.builder()
			.applyToClusterSettings(builder ->
				builder.hosts(singletonList(serverAddress))
			)
			.build();
	}
}
