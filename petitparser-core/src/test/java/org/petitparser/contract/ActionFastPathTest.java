package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.petitparser.contract.ScriptedParser.Reaction.fail;
import static org.petitparser.contract.ScriptedParser.Reaction.succeed;

/**
 * Characterization contract for {@code map}/{@code mapWithSideEffects},
 * {@code flatten}, {@code token} and {@code trim}, including nested
 * combinations.
 *
 * <p>The side-effect cases lock down whether {@code fastParseOn} currently
 * executes the action. This is the existing, observable behavior of the
 * library and must not silently change during the refactor.
 */
public class ActionFastPathTest {

  private static ScriptedParser scripted(String name,
      ScriptedParser.Reaction... reactions) {
    return new ScriptedParser(name, reactions);
  }

  // ------------------------------------------------------------------ map()

  @Test
  public void pureMapPositionAgrees() {
    ScriptedParser child = scripted("c",
        succeed(1, 3, "ab"), succeed(1, 3, "ab"));
    Parser parser = child.map(value -> value + "!");
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "xabx", 1, 3);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        succeed(1, 3, "ab")), "xabx", 1, "ab!", 3);
  }

  @Test
  public void pureMapFailurePropagates() {
    ScriptedParser child = scripted("c", fail(0, "c"), fail(0, "c"));
    Parser parser = child.map(value -> value + "!");
    FastPathContract.assertAgree(parser, scripted("probe"), "", 0);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(0, "c")), "", 0, 0, "c");
  }

  // -------------------------------------------------------- side effect gate

  @Test
  public void sideEffectActionIsExecutedOnFastPath() {
    // CHARACTERIZATION: a side-effecting action wrapped with
    // mapWithSideEffects currently runs even on the allocation-free
    // fastParseOn path, because fastParseOn falls back to parseOn.
    ScriptedParser child = scripted("c",
        succeed(0, 2, "ab"), succeed(0, 2, "ab"));
    AtomicInteger fastSideEffects = new AtomicInteger();
    Parser parser = child.mapWithSideEffects(
        value -> fastSideEffects.incrementAndGet());
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("fast path must keep running the side effect", 1,
        fastSideEffects.get());
  }

  @Test
  public void pureActionSideEffectIsNotExecutedOnFastPath() {
    // CHARACTERIZATION: a pure map() action is skipped by fastParseOn; only
    // the delegate is consulted.
    ScriptedParser child = scripted("c",
        succeed(0, 2, "ab"), succeed(0, 2, "ab"));
    AtomicInteger fastSideEffects = new AtomicInteger();
    Parser parser = child.map(value -> {
      fastSideEffects.incrementAndGet();
      return value;
    });
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("fast path must not run a pure action", 0,
        fastSideEffects.get());
  }

  @Test
  public void sideEffectActionInsideSequenceRunsPerIteration() {
    // The sequence drives children through fastParseOn; the side-effect action
    // must still fire there even though only its position is needed.
    ScriptedParser first = scripted("f",
        succeed(0, 1, 'a'), succeed(0, 1, 'a'));
    ScriptedParser second = scripted("s",
        succeed(1, 2, 'b'), succeed(1, 2, 'b'));
    AtomicInteger effects = new AtomicInteger();
    Parser parser = first.seq(
        second.mapWithSideEffects(value -> effects.incrementAndGet()));
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertEquals("nested side effect must fire on the fast path", 1,
        effects.get());
  }

  // ------------------------------------------------------------- flatten()

  @Test
  public void flattenReportsConsumedRange() {
    ScriptedParser child = scripted("c",
        succeed(1, 4, "ignored"), succeed(1, 4, "ignored"));
    Parser parser = child.flatten();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "xabcdx", 1, 4);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        succeed(1, 4, "ignored")), "xabcdx", 1, "abc", 4);
  }

  @Test
  public void flattenZeroWidthRange() {
    ScriptedParser child = scripted("c", succeed(2, "v"), succeed(2, "v"));
    Parser parser = child.flatten();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abcd", 2, 2);
    FastPathContract.assertSlowSuccess(parser, scripted("p",
        succeed(2, "v")), "abcd", 2, "", 2);
  }

  @Test
  public void flattenWithMessageSurfacesCustomFailure() {
    ScriptedParser child = scripted("c", fail(1, "deep"), fail(1, "deep"));
    Parser parser = child.flatten("custom flat message");
    FastPathContract.assertAgree(parser, scripted("probe"), "ab", 1);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(1, "deep")), "ab", 1, 1, "custom flat message");
  }

  // --------------------------------------------------------------- token()

  @Test
  public void tokenWrapsValueRangeAndPosition() {
    ScriptedParser child = scripted("c",
        succeed(0, 3, "raw"), succeed(0, 3, "raw"));
    Parser parser = child.token();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "abc", 0, 3);
  }

  @Test
  public void tokenFailurePropagates() {
    ScriptedParser child = scripted("c", fail(2, "t"), fail(2, "t"));
    Parser parser = child.token();
    FastPathContract.assertAgree(parser, scripted("probe"), "ab", 2);
    FastPathContract.assertSlowFailure(parser, scripted("p",
        fail(2, "t")), "ab", 2, 2, "t");
  }

  // ---------------------------------------------------------------- trim()

  @Test
  public void trimConsumesBothSides() {
    // Use real primitive parsers for the trimmers and a scripted core.
    ScriptedParser core = scripted("core",
        succeed(1, 2, "x"), succeed(1, 2, "x"));
    Parser parser = core.trim(
        org.petitparser.parser.primitive.CharacterParser.of(' '));
    FastPathContract.assertAgreeAt(parser, scripted("probe"), " x ", 0, 3);
  }

  // -------------------------------------------------------------- nesting()

  @Test
  public void nestedFlattenTokenActionAndLookahead() {
    // and( map( token( flatten( scripted ) ) ) ): zero-width success, the fast
    // path must reach the child exactly once and stay at the entry position.
    ScriptedParser child = scripted("c",
        succeed(2, 5, "raw"), succeed(2, 5, "raw"));
    Parser parser = child.flatten().token().map(value -> value).and();
    FastPathContract.assertAgreeAt(parser, scripted("probe"), "xxabcyy", 2, 2);
  }
}
