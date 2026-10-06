package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * Concrete trace refinement for FormalChannelRendezvousModelCheckTest.
 */
final class FormalChannelRendezvousTraceRefinementTest {
    @Test
    void readThenWriteAndWriteThenReadCommitTheSameSingleHandoff() {
        ChannelRuntime.Channel<String> readFirst = new ChannelRuntime.Channel<>(0);
        OresFuture<String> read = readFirst.readAsync();
        assertFalse(read.isDone());
        OresFuture<Void> write = readFirst.writeAsync("value");
        assertEquals("value", read.join());
        write.join();
        assertTrue(readFirst.tryRead().isEmpty(),
                "rendezvous value may be consumed exactly once");

        ChannelRuntime.Channel<String> writeFirst = new ChannelRuntime.Channel<>(0);
        OresFuture<Void> pendingWrite = writeFirst.writeAsync("value");
        assertFalse(pendingWrite.isDone());
        OresFuture<String> matchingRead = writeFirst.readAsync();
        assertEquals("value", matchingRead.join());
        pendingWrite.join();
        assertTrue(writeFirst.tryRead().isEmpty(),
                "reverse arrival order must have the same one-handoff result");
    }

    @Test
    void cancelledReadAndWriteWaitersCannotConsumeLaterTraffic() {
        ChannelRuntime.Channel<String> cancelledReadChannel =
                new ChannelRuntime.Channel<>(0);
        OresFuture<String> cancelledRead = cancelledReadChannel.readAsync();
        assertTrue(cancelledRead.cancel(false));
        assertThrows(CancellationException.class, cancelledRead::join);
        assertFalse(cancelledReadChannel.tryWrite("later"),
                "cancelled read must be detached before later traffic");

        ChannelRuntime.Channel<String> cancelledWriteChannel =
                new ChannelRuntime.Channel<>(0);
        OresFuture<Void> cancelledWrite =
                cancelledWriteChannel.writeAsync("discarded");
        assertTrue(cancelledWrite.cancel(false));
        assertThrows(CancellationException.class, cancelledWrite::join);

        OresFuture<String> laterRead = cancelledWriteChannel.readAsync();
        assertFalse(laterRead.isDone(),
                "cancelled write must not deliver its old value to a later reader");
        laterRead.cancel(false);
    }

    @Test
    void closeFailsPendingWaitersAndRejectsLaterAdmission() {
        ChannelRuntime.Channel<String> readChannel = new ChannelRuntime.Channel<>(0);
        OresFuture<String> pendingRead = readChannel.readAsync();
        readChannel.close();

        assertThrows(RuntimeException.class, pendingRead::join);
        assertThrows(RuntimeException.class, () -> readChannel.tryWrite("closed"),
                "closed channel must reject later write admission");

        ChannelRuntime.Channel<String> writeChannel = new ChannelRuntime.Channel<>(0);
        OresFuture<Void> pendingWrite = writeChannel.writeAsync("pending");
        writeChannel.close();

        assertThrows(RuntimeException.class, pendingWrite::join);
        assertThrows(RuntimeException.class, writeChannel::tryRead,
                "closed channel must reject later read admission");
    }
}
