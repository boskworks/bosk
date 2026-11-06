package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionCheckOutStartedEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionClosedEvent;
import com.mongodb.event.ConnectionCreatedEvent;
import com.mongodb.event.ConnectionPoolListener;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.Binary;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;
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

	/**
	 * How long to keep looking for a transaction that may be in the process of
	 * closing, and how many times to look, before deciding it is really open.
	 * A transaction that was aborted disappears from the server's list promptly
	 * but not instantaneously; a leaked one stays for a minute.
	 */
	private static final int OPEN_TRANSACTION_SETTLING_CHECKS = 4;
	private static final long OPEN_TRANSACTION_SETTLING_TIME_MS = 250;

	private final MongoClient mongoClient = MongoClients.create(normalClientSettings);

	// We need this in static initializers too
	private static final ConnectionPoolListener CONNECTION_POOL_LOGGER = new ConnectionPoolListener() {
		@Override
		public void connectionCheckOutStarted(ConnectionCheckOutStartedEvent event) {
			LOGGER.debug("Pool checkout started: {}", event.getServerId());
		}

		@Override
		public void connectionCheckedOut(ConnectionCheckedOutEvent event) {
			LOGGER.debug("Pool checkout: {}", event.getConnectionId());
		}

		@Override
		public void connectionCheckOutFailed(ConnectionCheckOutFailedEvent event) {
			LOGGER.debug("Pool checkout FAILED: {} reason={}", event.getServerId(), event.getReason());
		}

		@Override
		public void connectionCreated(ConnectionCreatedEvent event) {
			LOGGER.debug("Connection created: {}", event.getConnectionId());
		}

		@Override
		public void connectionClosed(ConnectionClosedEvent event) {
			LOGGER.debug("Connection closed: {} reason={}", event.getConnectionId(), event.getReason());
		}
	};


	// Expensive stuff shared among instances as much as possible, hence static
	private static final Network NETWORK = Network.newNetwork();
	private static final DockerImageName MONGO_IMAGE = newImageFromDockerfile(
		"mongo",
		"src/test/resources/mongo.dockerfile");
	private static final MongoDBContainer MONGO_CONTAINER = mongoContainer();
	private static final MongoClientSettings normalClientSettings = mongoClientSettings(
		new ServerAddress(MONGO_CONTAINER.getHost(), MONGO_CONTAINER.getFirstMappedPort())
	);

	public static final DockerImageName TOXIPROXY_IMAGE = newImageFromDockerfile(
		"shopify/toxiproxy",
		"src/test/resources/toxiproxy.dockerfile");

	private static final ToxiproxyContainer TOXIPROXY_CONTAINER = toxiproxyContainer();
	private static final ToxiproxyClient TOXIPROXY_CLIENT = createToxiproxyClient();

	private static final Proxy MONGO_PROXY = createMongoProxy();
	private static final int PROXY_PORT = 8666;
	private static final String PARTITION_UPSTREAM_TOXIC = "partitionUpstream";
	private static final String PARTITION_DOWNSTREAM_TOXIC = "partitionDownstream";
	private static final ServerAddress DISRUPTABLE_SERVER_ADDRESS = new ServerAddress(TOXIPROXY_CONTAINER.getHost(), TOXIPROXY_CONTAINER.getMappedPort(PROXY_PORT));
	private static final int TCP_CONNECTION_TIMEOUT_MS = 1000;
	private static final MongoClientSettings disruptableClientSettings = mongoClientSettings(DISRUPTABLE_SERVER_ADDRESS);

	/**
	 * The way in which a test disrupts the database connection.
	 * Each mode is implemented with a Toxiproxy feature: {@link #CLOSE} takes the
	 * proxy down ({@code enabled=false}), and {@link #PARTITION} applies a
	 * {@code timeout} toxic with a timeout of 0.
	 */
	public enum FailureMode {
		/**
		 * Closes the open connections, as when the server restarts or a load balancer resets them.
		 */
		CLOSE,
		/**
		 * Drops all packets in both directions without closing the connections,
		 * as in a network partition.
		 */
		PARTITION,
	}

	/**
	 * Disrupts the database connection in the given way.
	 */
	public void disruptConnection(FailureMode mode) {
		switch (mode) {
			case CLOSE -> {
				try {
					MONGO_PROXY.disable();
				} catch (IOException e) {
					throw new IllegalStateException("Failed to close the database connection", e);
				}
			}
			case PARTITION -> {
				try {
					MONGO_PROXY.toxics().timeout(PARTITION_UPSTREAM_TOXIC, ToxicDirection.UPSTREAM, 0);
					MONGO_PROXY.toxics().timeout(PARTITION_DOWNSTREAM_TOXIC, ToxicDirection.DOWNSTREAM, 0);
				} catch (IOException e) {
					throw new IllegalStateException("Failed to partition the database connection", e);
				}
			}
		}
	}

	/**
	 * Restores the database connection after {@link #disruptConnection(FailureMode)}.
	 */
	public void restoreConnection(FailureMode mode) {
		switch (mode) {
			case CLOSE -> {
				try {
					MONGO_PROXY.enable();
					awaitTcpConnection();
				} catch (IOException e) {
					throw new IllegalStateException("Failed to restore the database connection", e);
				}
			}
			case PARTITION -> {
				try {
					removeToxic(PARTITION_UPSTREAM_TOXIC);
					removeToxic(PARTITION_DOWNSTREAM_TOXIC);
				} catch (IOException e) {
					throw new IllegalStateException("Failed to heal the database partition", e);
				}
			}
		}
	}

	/**
	 * Removes the named toxic if it is present.
	 * Removing a {@code timeout} toxic also closes the connection it was applied to,
	 * so the client notices that the disruption is over.
	 */
	private static void removeToxic(String name) throws IOException {
		for (Toxic toxic : MONGO_PROXY.toxics().getAll()) {
			if (toxic.getName().equals(name)) {
				toxic.remove();
			}
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
		MongoClientSettings base = testInfo.getTags().contains(DisruptsMongoProxy.TAG) ? disruptableClientSettings : normalClientSettings;
		return MongoClientSettings.builder(base)
			.applicationName(clientApplicationName(testInfo))
			.build();
	}

	/**
	 * The {@code applicationName} given to the clients a test creates, so that
	 * {@link #assertNoOpenTransactions} can tell their transactions apart from
	 * those of tests running in parallel.
	 * <p>
	 * Test methods in a class run one at a time (see
	 * {@code junit-platform.properties}), so a class name is enough to attribute a
	 * transaction to the class that is currently running. (It has to be a per-class
	 * name rather than a per-client one, because {@code MongoDriverConformanceTest}
	 * deliberately shares one client among all its tests to avoid opening a
	 * connection pool per test method.)
	 * <p>
	 * MongoDB limits application names to 128 bytes; the simple class names in
	 * this module are far shorter than that.
	 */
	static String clientApplicationName(TestInfo testInfo) {
		return "bosk-test:" + testInfo.getTestClass()
			.map(Class::getSimpleName)
			.orElse("unknown");
	}

	/**
	 * Fails if the server still has a transaction open for a client with the
	 * given application name.
	 * <p>
	 * Call this after closing the clients that used that name. A transaction that
	 * was just aborted takes a moment to disappear from the server's list, so this
	 * allows it time to settle before reporting it. A transaction that was never
	 * aborted, by contrast, stays open until the server reaps it
	 * ({@code transactionLifetimeLimitSeconds}, 60&nbsp;s by default), holding
	 * collection locks the whole time; that is what makes a later test's
	 * {@code drop()} hang.
	 */
	void assertNoOpenTransactions(String applicationName) throws InterruptedException {
		List<Document> open;
		try {
			open = openTransactions(applicationName);
			for (int checks = 0; checks < OPEN_TRANSACTION_SETTLING_CHECKS && !open.isEmpty(); checks++) {
				Thread.sleep(OPEN_TRANSACTION_SETTLING_TIME_MS);
				open = openTransactions(applicationName);
			}
		} catch (MongoException e) {
			// A test that broke the database connection may still be restoring it.
			// That test has already failed for its own reasons, so don't pile on.
			LOGGER.warn("Unable to check for transactions left open by {}", applicationName, e);
			return;
		}
		if (!open.isEmpty()) {
			throw new AssertionError(describeOpenTransactions(applicationName, open));
		}
	}

	/**
	 * Returns the transactions currently open on the server for a client with the
	 * given application name, whether they are active or sitting idle holding
	 * locks.
	 */
	private List<Document> openTransactions(String applicationName) {
		return mongoClient.getDatabase("admin")
			.aggregate(List.of(
				new Document("$currentOp", new Document("allUsers", true)
					.append("idleSessions", true)),
				new Document("$match", new Document("appName", applicationName)
					.append("transaction", new Document("$exists", true)))
			))
			.into(new ArrayList<>());
	}

	private static String describeOpenTransactions(String applicationName, List<Document> open) {
		StringBuilder message = new StringBuilder()
			.append(open.size())
			.append(open.size() == 1 ? " transaction is" : " transactions are")
			.append(" still open after teardown for a client with appName=")
			.append(applicationName)
			.append(". The server holds the transaction's collection locks until it reaps the")
			.append(" transaction (transactionLifetimeLimitSeconds, 60s by default),")
			.append(" so later tests can hang. Details:\n");
		for (Document op : open) {
			Document transaction = op.get("transaction", Document.class);
			Document parameters = transaction.get("parameters", Document.class);
			Document lsid = op.get("lsid", Document.class);
			Binary id = lsid.get("id", Binary.class);
			message.append("  lsid=")
				.append(id == null ? "?" : HexFormat.of().formatHex(id.getData()))
				.append(" txnNumber=")
				.append(parameters.get("txnNumber"))
				.append(" openFor=")
				.append(((Number) transaction.get("timeOpenMicros", 0)).longValue() / 1.0e6)
				.append("s readConcern=")
				.append(parameters.get("readConcern"))
				.append(" locks=")
				.append(op.get("locks"))
				.append('\n');
		}
		return message.toString();
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
		return new MongoDBContainer(MONGO_IMAGE);
	}

	private static ToxiproxyContainer toxiproxyContainer() {
		ToxiproxyContainer result = new ToxiproxyContainer(TOXIPROXY_IMAGE)
			.withNetwork(NETWORK);
		result.start();
		return result;
	}

	private static DockerImageName newImageFromDockerfile(String otherImageName, String filePath) {
		return DockerImageName.parse(new ImageFromDockerfile()
			.withDockerfile(Paths.get(filePath)).get()
		).asCompatibleSubstituteFor(otherImageName);
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
			throw new IllegalStateException("Failed to create Mongo proxy", e);
		}
	}

	@NonNull
	static MongoClientSettings mongoClientSettings(ServerAddress serverAddress) {
		LOGGER.info("MongoDB: {}", serverAddress);
		return MongoClientSettings.builder()
			.applyToClusterSettings(builder ->
				builder.hosts(singletonList(serverAddress))
			)
			.applyToConnectionPoolSettings(builder ->
				builder.addConnectionPoolListener(CONNECTION_POOL_LOGGER))
			.build();
	}

}
