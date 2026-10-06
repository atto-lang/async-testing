package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class ConcurrencySyntaxContractTest {

    @Test
    void nbReadchAndNbWritechAreFutureReturningExpressions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(
                    Channel<int> input,
                    Channel<int> output
                ): void {
                  val Future<int> pending_read = nb readch input;
                  val Future<void> pending_write = nb writech output, 42;
                  return;
                }
                """)));
    }

    @Test
    void nbSelectIsActorOwnedAndReturnsImmediately() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> input): void {
                  nb select {
                  case readch input: const value
                    stdio.println(value);
                  }
                  stdio.println("turn continues after arming select");
                  return;
                }
                """)));
    }

    @Test
    void nbCbWritechIsVoidCallbackSurfaceRatherThanFutureSurface() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("write completed");
                  };
                  return;
                }
                """)));
    }
    @Test
    void callbackWriteIsStatementOnlyAndRequiresActorOwnership() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc main(Channel<int> output): void {
                  nb cb writech output, 42 || -> { return; };
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> output): void {
                  val Future<void> wrong = nb cb writech output, 42 || -> { return; };
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<int> output): void {
                  nb cb writech output, 42 || -> { return 1; };
                  return;
                }
                """)));
    }

    @Test
    void callbackDelimiterPreservesLogicalOrPayloadsAndParenthesizedWrites() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc arm(Channel<bool> output): void {
                  nb cb writech output, false || true || -> { return; };
                  nb cb writech(output, true) || -> { return; };
                  return;
                }
                """)));
    }

}
