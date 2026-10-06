package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.graalvm.polyglot.PolyglotException;

/** Executes guest source through the real compiler and Truffle evaluator. */
@Timeout(10)
final class ConcurrencySourceExecutionTest {
    private long run(String source) {
        try (Context context = Context.newBuilder("ores")
                .option("engine.WarnInterpreterOnly", "false").build()) {
            return context.eval("ores", source).asLong();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "shared actor", "isoactor"})
    void callbackWriteRunsAfterActorBodyOnFreshTurn(String actorKind) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").out(output)
                .option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("ores", """
                    %s fnc arm(): void {
                      val Channel<int> channel = Channel.new<int>(0);
                      nb cb writech channel, 42 || -> {
                        stdio.println("callback");
                      };
                      stdio.println(readch channel);
                      stdio.println("body finished");
                      return;
                    }
                    fnc main(): int { arm(); return 0; }
                    """.formatted(actorKind));
            assertEquals("42\nbody finished\ncallback\n",
                    output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }

    @Test
    void asyncAwaitComposesNestedSourceFutures() {
        assertEquals(42, run("""
                async fnc increment(int value): int { return value + 1; }
                async fnc nested(int value): int { return await increment(value); }
                fnc main(): int { return await nested(41); }
                """));
    }

    @Test
    void asyncSourceCombinesChannelFuturesWithDynamicSelect() {
        assertEquals(42, run("""
                async fnc pipe(): int {
                  val Channel<int> channel = Channel.new<int>(0);
                  val Future<void> write = nb writech channel, 42;
                  val result = await nb select from [SelectCase.read(channel)];
                  await write;
                  return result.value;
                }
                fnc main(): int { return await pipe(); }
                """));
    }

    @Test
    void staticNbSelectArmRunsAfterBodyInsteadOfBeingDiscarded() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").out(output)
                .option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("ores", """
                    shared actor fnc arm(): void {
                      val Channel<int> channel = Channel.new<int>(0);
                      nb select { case readch channel: const value stdio.println(value); }
                      writech channel, 42;
                      stdio.println("body finished");
                      return;
                    }
                    fnc main(): int { arm(); return 0; }
                    """);
            assertEquals("body finished\n42\n", output.toString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n"));
        }
    }

    /** Executable boundary checks: these remain unsupported, not passing semantic proofs. */
    @ParameterizedTest
    @ValueSource(strings = {"readch input", "writech input, 42",
            "await nb readch input", "select { case readch input: const value return; }"})
    void pendingBlockingSourceActorOperationsFailClosed(String operation) {
        try (Context context = Context.newBuilder("ores")
                .option("engine.WarnInterpreterOnly", "false").build()) {
            PolyglotException failure = assertThrows(PolyglotException.class, () -> context.eval("ores", """
                    actor fnc pending(): void {
                      val Channel<int> input = Channel.new<int>(0);
                      %s
                      return;
                    }
                    fnc main(): int { pending(); return 0; }
                    """.formatted(operation.startsWith("select") ? operation : operation + ";")));
            assertTrue(failure.getMessage().contains("resumable"), failure.getMessage());
        }
    }

    @Test
    void pendingNbWriteAndNbReadReturnAwaitableSourceFutures() {
        assertEquals(42, run("""
                fnc main(): int {
                  val Channel<int> output = Channel.new<int>(0);
                  val Future<void> write = nb writech output, 42;
                  val Future<int> read = nb readch output;
                  await write;
                  return await read;
                }
                """));
    }

    @Test
    void sourceBlockingSelectCommitsReadyWriteAndRead() {
        assertEquals(42, run("""
                fnc main(): int {
                  val Channel<int> output = Channel.new<int>(1);
                  select { case writech output, 42: return readch output; }
                  return 0;
                }
                """));
    }

    @Test
    void sourceDynamicNbSelectReturnsFuture() {
        assertEquals(0, run("""
                fnc main(): int {
                  val Channel<int> input = Channel.new<int>(0);
                  val Future<void> write = nb writech input, 42;
                  val cases = [SelectCase.read(input)];
                  val result = await nb select from cases;
                  await write;
                  return result.index;
                }
                """));
    }

    @Test
    void sharedAndIsoActorKeywordsExecuteGuestBodies() {
        assertEquals(42, run("""
                shared actor fnc first(int value): int { return value + 1; }
                isoactor fnc second(int value): int { return value + 1; }
                fnc main(): int { return second(first(40)); }
                """));
    }
}
