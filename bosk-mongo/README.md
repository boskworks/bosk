## bosk-mongo

This is the subproject for the published `bosk-mongo` library for MongoDB support,
containing `MongoDriver` and the associated machinery.
This library is documented in the [User's Guide](../docs/USERS.md);
see also the [unit tests](src/test) for usage examples.

Add [MongoDriver](src/main/java/works/bosk/drivers/mongo/MongoDriver.java) to your
driver stack for persistence and replication.
During development, we recommend using `InitialDatabaseUnavailableMode.FAIL_FAST`
to get helpful errors when the database is misconfigured or unavailable;
in production, you should use the default `DISCONNECT` mode for better fault tolerance.

Bosk uses change streams, so it requires MongoDB to be configured as a replica set.
Our unit tests demonstrate how to do this with TestContainers.

See the [javadocs](https://javadoc.io/doc/works.bosk/bosk-mongo/latest/works.bosk.mongo/module-summary.html) for more information.
