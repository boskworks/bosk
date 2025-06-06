package works.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.io.Closeable;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import static com.mongodb.ReadPreference.secondaryPreferred;
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
	private static final Logger LOGGER = LoggerFactory.getLogger(MongoService.class);

	// Expensive stuff shared among instances as much as possible
	private static final Network NETWORK = Network.newNetwork();
	private static final GenericContainer<?> MONGO_CONTAINER = mongoContainer();
	private static final MongoClientSettings normalClientSettings = mongoClientSettings(new ServerAddress(MONGO_CONTAINER.getHost(), MONGO_CONTAINER.getFirstMappedPort()));
	private static final ToxiproxyContainer TOXIPROXY_CONTAINER = toxiproxyContainer();
	private static final ToxiproxyContainer.ContainerProxy proxy = TOXIPROXY_CONTAINER.getProxy(MONGO_CONTAINER, 27017);
	private static final MongoClientSettings disruptableClientSettings = mongoClientSettings(new ServerAddress(proxy.getContainerIpAddress(), proxy.getProxyPort()));

	private final MongoClient mongoClient = MongoClients.create(normalClientSettings);

	public ToxiproxyContainer.ContainerProxy proxy() {
		return proxy;
	}

	public MongoClientSettings clientSettings(TestInfo testInfo) {
		return testInfo.getTags().contains(DisruptsMongoProxy.TAG)? disruptableClientSettings : normalClientSettings;
	}

	public MongoClient client() {
		return mongoClient;
	}

	@Override
	public void close() {
		mongoClient.close();
	}

	private static GenericContainer<?> mongoContainer() {
		// For some reason, creating a MongoDBContainer makes the Hanoi test WAY slower, like 100x
		GenericContainer<?> result = new GenericContainer<>(
			new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder
				.from("mongo:7.0")
				.run("echo \"rs.initiate()\" > /docker-entrypoint-initdb.d/rs-initiate.js")
				.cmd("mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all")
				.build()))
			.withTmpFs(Map.of("/data/db", "rw"))
			.withNetwork(NETWORK)
			.withExposedPorts(27017);
		result.start();
		return result;
	}

	private static ToxiproxyContainer toxiproxyContainer() {
		ToxiproxyContainer result = new ToxiproxyContainer(
			DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.2.0").asCompatibleSubstituteFor("shopify/toxiproxy"))
			.withNetwork(NETWORK);
		result.start();
		return result;
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
