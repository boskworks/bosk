package works.bosk.testing.drivers.fuzz;

import java.util.List;
import java.util.Random;

/**
 * Decides which {@link OperationKind} an {@link OperationSequenceGenerator} produces next.
 *
 * <p>This is the seam through which the behaviour of randomized generation can be influenced
 * without changing the generator or the test harness. The default implementation,
 * {@link #random()}, chooses uniformly from the applicable kinds. A future profile-guided
 * discovery mode could supply a chooser that uses feedback (for example, code coverage)
 * to favour kinds that have historically reached unexplored behaviour.
 *
 * <p>The chooser must not assume that every kind is applicable: the generator passes only the
 * kinds that make sense for the model's current state, and the chooser is expected to pick
 * one of those. In particular, the list is never empty.
 */
@FunctionalInterface
public interface OperationChooser {

	/**
	 * @param random     the generator's seeded random source
	 * @param applicable the kinds that are valid for the model's current state, never empty
	 * @return one of the {@code applicable} kinds
	 */
	OperationKind choose(Random random, List<OperationKind> applicable);

	/**
	 * @return a chooser that selects uniformly at random from the applicable kinds
	 */
	static OperationChooser random() {
		return (random, applicable) -> applicable.get(random.nextInt(applicable.size()));
	}
}
