package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Downstream acceptance matrix, executed against the pinned compiler artifact. */
final class BindingMailboxContractTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 0;
              pub bump(mut self)(): void { self.value = self.value + 1; return; }
            end
            """;

    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
    }

    private static String body(String body) {
        return BOX + "fnc subject(): void { " + body + " return; }";
    }

    @Test
    void bindingAndReferentCapabilitiesRemainIndependent() {
        accepts(body("const Box x = new Box();"));
        rejects(body("const Box x = new Box(); x.value = 1;"));
        rejects(body("const Box x = new Box(); x = new Box();"));
        for (String kind : new String[]{"val", "const mut"}) {
            accepts(body(kind + " Box x = new Box(); x.bump();"));
            rejects(body(kind + " Box x = new Box(); x = new Box();"));
        }
        accepts(body("let Box x = new Box(); x = new Box();"));
        rejects(body("let Box x = new Box(); x.bump();"));
        accepts(body("let mut Box x = new Box(); x.bump(); x = new Box();"));
    }

    @Test
    void inferredStructCannotGainMutableAuthorityThroughBindingsOrNarrowing() {
        accepts("fnc good(): string { const x = infer struct{foo: \"ok\"}; return x.foo; }");
        for (String kind : new String[]{"val", "const mut", "let mut"}) {
            rejects("fnc bad(): void { " + kind + " x = infer struct{foo: 1}; return; }");
        }
        rejects("""
                fnc bad(): void {
                  const x = infer struct{inside: infer struct{foo: 1}};
                  x.inside.foo = 2;
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  const x = infer struct{foo: 1};
                  match x over
                    on structural {foo: int} view -> { view.foo = 2; }
                    on _ -> { return; }
                  end
                  return;
                }
                """);
    }

    @Test
    void readonlyClosureCaptureAndBorrowAcrossAwaitAreRejected() {
        rejects(body("const Box x = new Box(); const callback = || -> { x.bump(); return; };"));
        rejects(BOX + """
                async fnc ready(): int { return 1; }
                async fnc bad(): void {
                  val Box x = new Box();
                  const view = rt borrow x;
                  await ready();
                  stdio.println(view.value);
                  return;
                }
                """);
    }

    @Test
    void localChannelTransfersOwnedCallableAndRejectsSenderReuse() {
        String setup = """
                fnc subject(): void {
                  val Channel<Fnc<int, int>> channel = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |value| -> { return value * 2; };
                  writech channel, callback;
                """;
        accepts(setup + "return; }");
        rejects(setup + "callback(1); return; }");
    }

    @Test
    void actorMailRejectsNestedFunctionPayloads() {
        rejects("actor fnc bad(Option<Fnc<int, int>> value): void { return; }");
        rejects("""
                define class Payload as
                  val Fnc<int, int> callback;
                end
                actor fnc bad(Payload value): void { return; }
                """);
    }

    @Test
    void selectArmBindingsPreserveReadonlyAndMutableCapabilities() {
        String setup = "val Channel<Box> channel = Channel.new<Box>(1); ";
        rejects(body(setup + "select { when readch channel: const value { value.bump(); } }"));
        accepts(body(setup + "select { when readch channel: val value { value.bump(); } }"));
        accepts(body(setup + "select { when readch channel: let mut value { value.bump(); value = new Box(); } }"));
    }

    @Test
    void contextualNamesAndRemovedStructFormsHaveStableBoundaries() {
        accepts("fnc subject(): int { const case = 1; const when = 2; return case + when; }");
        rejects("fnc bad(): void { const x = obj{foo: 1}; return; }");
        rejects("fnc bad(): void { const x = struct infer{foo: 1}; return; }");
        rejects("fnc bad(DynamicStruct<int> value): void { return; }");
    }
}
