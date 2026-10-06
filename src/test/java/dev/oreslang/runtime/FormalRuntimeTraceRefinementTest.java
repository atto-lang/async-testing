package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concrete trace-refinement checks for the finite-state formal models in the
 * compiler/runtime repository.
 *
 * <p>The model says select arbitration is single-winner: from any non-empty
 * ready subset exactly one registered case commits and all other ready cases
 * remain unconsumed. Enumerating every readiness subset for three cases gives
 * a small but exhaustive conformance check over the real ChannelRuntime.</p>
 */
@Timeout(10)
final class FormalRuntimeTraceRefinementTest {
    @Test
    void selectRefinesExactlyOneWinnerForEveryThreeCaseReadySubset() throws Exception {
        for (int readyMask = 1; readyMask < 8; readyMask++) {
            ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
            ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
            ChannelRuntime.Channel<Integer> third = new ChannelRuntime.Channel<>(1);

            if ((readyMask & 0b001) != 0) assertTrue(first.tryWrite(10));
            if ((readyMask & 0b010) != 0) assertTrue(second.tryWrite(20));
            if ((readyMask & 0b100) != 0) assertTrue(third.tryWrite(30));

            Map<String, ChannelRuntime.SelectCase> cases = new LinkedHashMap<>();
            cases.put("first", ChannelRuntime.read(first));
            cases.put("second", ChannelRuntime.read(second));
            cases.put("third", ChannelRuntime.read(third));

            ChannelRuntime.SelectResult result =
                    ChannelRuntime.SelectSet.fromMap(cases)
                            .selectAsync()
                            .get(2, TimeUnit.SECONDS);

            int selectedBit = 1 << result.index();
            assertTrue(
                    (readyMask & selectedBit) != 0,
                    "select may commit only a case that was ready");
            assertEquals(ChannelRuntime.SelectOperation.READ, result.operation());

            int expectedValue = switch (result.index()) {
                case 0 -> 10;
                case 1 -> 20;
                case 2 -> 30;
                default -> throw new AssertionError("invalid select index " + result.index());
            };
            assertEquals(expectedValue, result.value());

            int remainingReady = 0;
            remainingReady += remainsReady(first) ? 1 : 0;
            remainingReady += remainsReady(second) ? 1 : 0;
            remainingReady += remainsReady(third) ? 1 : 0;

            assertEquals(
                    Integer.bitCount(readyMask) - 1,
                    remainingReady,
                    "exactly one ready channel may be consumed by one select");
        }
    }

    @Test
    void cancellingUnreadySelectDetachesEveryRegistrationWithoutConsumingLaterValues() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);

        Map<String, ChannelRuntime.SelectCase> cases = new LinkedHashMap<>();
        cases.put("first", ChannelRuntime.read(first));
        cases.put("second", ChannelRuntime.read(second));

        OresFuture<ChannelRuntime.SelectResult> pending =
                ChannelRuntime.SelectSet.fromMap(cases).selectAsync();

        assertFalse(pending.isDone(), "empty buffered channels must leave select pending");
        assertTrue(pending.cancel(false), "cancellation must win before any case is ready");

        assertTrue(first.tryWrite("first"));
        assertTrue(second.tryWrite("second"));
        assertEquals(Optional.of("first"), first.tryRead());
        assertEquals(Optional.of("second"), second.tryRead());
        assertTrue(pending.isCancelled());
    }

    private static boolean remainsReady(ChannelRuntime.Channel<?> channel) {
        synchronized (channel) {
            if (channel.isEmpty()) return false;
            assertFalse(channel.isClosed());
            return channel.tryRead().isPresent();
        }
    }
}
