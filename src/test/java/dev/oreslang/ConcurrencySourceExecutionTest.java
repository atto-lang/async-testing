package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Additional guest execution proofs using the converged compiler grammar.
 * Pending source actor waits are positively tested by SourceActorWaitProofTest.
 */
@Timeout(10)
final class ConcurrencySourceExecutionTest {
    @Test
    void nestedAsyncCallsCompose() throws Exception {
        assertEquals("42", run("""
                async fnc increment(int value): int { return value + 1; }
                async fnc nested(int value): int { return await increment(value); }
                pub async fnc main(): void {
                  stdio.stdout.write(await nested(41));
                  return;
                }
                """));
    }

    @Test
    void actorCallbackAndBodyBothFinishBeforeCallerObservesResult() throws Exception {
        String output = run("""
                actor fnc worker(): int {
                  val Channel<int> channel = Channel.new<int>(0);
                  nb cb writech channel, 42 || -> {
                    stdio.stdout.write("callback");
                  };
                  val int answer = readch channel;
                  stdio.stdout.write("body");
                  return answer;
                }
                pub routine main(): void {
                  stdio.stdout.write(worker());
                  return;
                }
                """);
        org.junit.jupiter.api.Assertions.assertTrue(output.equals("bodycallback42") || output.equals("callbackbody42"), output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "concurrency-source.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
