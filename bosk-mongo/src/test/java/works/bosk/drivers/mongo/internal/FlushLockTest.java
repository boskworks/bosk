package works.bosk.drivers.mongo.internal;

import java.util.Optional;
import org.bson.BsonInt64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FlushLockTest {

	@Test
	void awaitRevision_alreadySeenRevision_doesNotEnqueueWaiter() throws Exception {
		FlushLock lock = new FlushLock(Optional.empty(), 100, 5000);
		lock.awaitRevision(new BsonInt64(50)); // already seen; returns immediately
		assertEquals(0, lock.queue.size(), "Waiting on an already-seen revision must not enqueue a waiter");
	}

	@Test
	void awaitRevision_newRevision_wakesWhenFinished() throws Exception {
		FlushLock lock = new FlushLock(Optional.empty(), 0, 5000);
		Thread awaiting = new Thread(() -> {
			try {
				lock.awaitRevision(new BsonInt64(10));
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		awaiting.start();
		lock.finishedRevision(new BsonInt64(10));
		awaiting.join(5000);
		assertFalse(awaiting.isAlive(), "Waiting thread must wake when the revision finishes");
		assertEquals(0, lock.queue.size(), "Queue must drain after the revision finishes");
	}
}
