package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Concrete trace refinement for FormalBufferedChannelModelCheckTest.
 */
final class FormalBufferedChannelTraceRefinementTest {
    @Test
    void everyThreeMessagePermutationPreservesCapacityRetryAndFifo() {
        for (List<String> order : permutations("a", "b", "c")) {
            ChannelRuntime.Channel<String> channel =
                    new ChannelRuntime.Channel<>(2);

            assertTrue(channel.tryWrite(order.get(0)));
            assertTrue(channel.tryWrite(order.get(1)));
            assertFalse(channel.tryWrite(order.get(2)),
                    "third immediate write must be rejected at capacity two");

            assertEquals(order.get(0), channel.tryRead().orElseThrow(),
                    "first delivered value must be FIFO head");

            assertTrue(channel.tryWrite(order.get(2)),
                    "previously rejected write may succeed after a read frees capacity");

            assertEquals(order.get(1), channel.tryRead().orElseThrow());
            assertEquals(order.get(2), channel.tryRead().orElseThrow());
            assertTrue(channel.tryRead().isEmpty(),
                    "exactly three admitted values must be delivered exactly once");
        }
    }

    @Test
    void closeFencesBufferedChannelPublicAdmissionWithoutInventingDelivery() {
        ChannelRuntime.Channel<String> channel =
                new ChannelRuntime.Channel<>(2);

        assertTrue(channel.tryWrite("buffered"));
        channel.close();

        assertThrows(RuntimeException.class, () -> channel.tryWrite("later"));
        assertThrows(RuntimeException.class, channel::tryRead,
                "public reads are fenced after close even if runtime teardown may drain internally");
    }

    private static List<List<String>> permutations(String a, String b, String c) {
        return List.of(
                List.of(a, b, c),
                List.of(a, c, b),
                List.of(b, a, c),
                List.of(b, c, a),
                List.of(c, a, b),
                List.of(c, b, a));
    }
}
