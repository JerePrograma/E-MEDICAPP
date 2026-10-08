package ar.emedicapp.purchase;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RoutingPlannerTest {
    private final RoutingPlanner planner = new RoutingPlanner();
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), chain = UUID.randomUUID();

    private RoutingPlanner.Candidate candidate(
            boolean paid, UUID group, double distance, Map<UUID, Integer> stock) {
        return new RoutingPlanner.Candidate(UUID.randomUUID(), group, paid, distance, stock);
    }

    @Test
    void subscriberSplitOutranksNearNonSubscriberComplete() {
        var first = candidate(true, chain, 10, Map.of(a, 1));
        var second = candidate(true, chain, 11, Map.of(b, 1));
        var fallback = candidate(false, null, 0.1, Map.of(a, 5, b, 5));
        var plan =
                planner.plan(
                        Map.of(a, 1, b, 1),
                        List.of(first, second, fallback),
                        first.branchId(),
                        chain,
                        true);
        assertThat(plan).hasSize(2).allMatch(x -> x.candidate().subscribed());
    }

    @Test
    void wholeOtherSubscribedChainOutranksSplitInPreferredChain() {
        var first = candidate(true, chain, 1, Map.of(a, 1));
        var second = candidate(true, chain, 2, Map.of(b, 1));
        var whole = candidate(true, UUID.randomUUID(), 10, Map.of(a, 1, b, 1));
        assertThat(
                        planner.plan(
                                Map.of(a, 1, b, 1),
                                List.of(first, second, whole),
                                first.branchId(),
                                chain,
                                true))
                .singleElement()
                .satisfies(x -> assertThat(x.candidate()).isEqualTo(whole));
    }

    @Test
    void wholePreferredChainPrecedesCloserOtherChain() {
        var own = candidate(true, chain, 20, Map.of(a, 1));
        var near = candidate(true, UUID.randomUUID(), 1, Map.of(a, 1));
        assertThat(planner.plan(Map.of(a, 1), List.of(near, own), null, chain, true))
                .singleElement()
                .satisfies(x -> assertThat(x.candidate()).isEqualTo(own));
    }

    @Test
    void fallbackGetsOnlyMissingUnitsAndCannotDuplicateBasket() {
        var paid = candidate(true, chain, 10, Map.of(a, 2));
        var fallback = candidate(false, null, 1, Map.of(a, 8, b, 5));
        var plan =
                planner.plan(
                        Map.of(a, 3, b, 1), List.of(paid, fallback), paid.branchId(), chain, true);
        assertThat(plan).hasSize(2);
        assertThat(plan.getFirst().quantities()).containsExactlyEntriesOf(Map.of(a, 2));
        assertThat(plan.getLast().quantities())
                .containsExactlyInAnyOrderEntriesOf(Map.of(a, 1, b, 1));
    }

    @Test
    void noFullSolutionReturnsUnavailableAndNoImplicitPartialSale() {
        var paid = candidate(true, chain, 1, Map.of(a, 1));
        assertThat(planner.plan(Map.of(a, 1, b, 1), List.of(paid), null, null, true)).isEmpty();
    }

    @Test
    void minimizesSubscriberPartitionsAfterPriority() {
        var onlyA = candidate(true, chain, 1, Map.of(a, 1));
        var onlyB = candidate(true, chain, 2, Map.of(b, 1));
        var both = candidate(true, chain, 5, Map.of(a, 1, b, 1));
        assertThat(planner.plan(Map.of(a, 1, b, 1), List.of(onlyA, onlyB, both), null, chain, true))
                .hasSize(1);
    }
}
