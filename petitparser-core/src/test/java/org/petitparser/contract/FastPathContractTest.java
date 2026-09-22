package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Executable contract pinning the relation between the two parser entry
 * points: {@link Parser#parseOn(Context)} constructs the full value/failure,
 * while {@link Parser#fastParseOn(String, int)} only reports the resulting
 * position (or {@code -1} on failure).
 *
 * <p>For every combinator the contract requires that both paths, given the
 * same buffer and start position, agree on (a) success vs. failure and (b)
 * the resulting position. The slow path additionally pins the produced
 * value, the failure position and the failure message. Both paths must also
 * drive their child parsers in the identical order and at the identical
 * positions, including choice rollback, sequence short-circuiting, repeat
 * boundaries and zero-width lookahead.
 *
 * <p>The {@link Observable} child parser records each invocation together
 * with the path that made it. Independently changing the position
 * computation or the rollback/backtracking order of just one path changes
 * that path's trace and fails these tests.
 */
public class FastPathContractTest {

  private static final String BUFFER = "abac";

  /** A single invocation observed on an {@link Observable} child parser. */
  private static final class Event {
    final boolean fast;
    final int position;

    Event(boolean fast, int position) {
      this.fast = fast;
      this.position = position;
    }

    @Override
    public String toString() {
      return (fast ? "fast@" : "parse@") + position;
    }
  }

  /** The transition an observable parser performs for one invocation. */
  private static final class Outcome {
    final boolean success;
    final int advance;
    final Object value;
    final String message;
    final int failureOffset;

    private Outcome(boolean success, int advance, Object value,
        String message, int failureOffset) {
      this.success = success;
      this.advance = advance;
      this.value = value;
      this.message = message;
      this.failureOffset = failureOffset;
    }

    static Outcome succeed() {
      return succeed(0, null);
    }

    static Outcome succeed(int advance, Object value) {
      return new Outcome(true, advance, value, null, 0);
    }

    static Outcome fail() {
      return fail("observed failure", 0);
    }

    static Outcome fail(String message) {
      return fail(message, 0);
    }

    /** Fails, possibly reporting a position different from the entry one. */
    static Outcome fail(String message, int failureOffset) {
      return new Outcome(false, 0, null, message, failureOffset);
    }
  }

  /** A scripted transition, selected by the zero-based invocation index. */
  @FunctionalInterface
  interface Rule {
    Outcome transition(int index, int position);
  }

  /**
   * A child parser with fully scripted, observable transitions. Both paths
   * consult the same rules, so divergence of control flow is directly
   * observable through the recorded {@link Event} trace.
   */
  private static final class Observable extends Parser {
    private final String name;
    private final Rule rule;
    private final List<Event> trace;
    private boolean expectingFast;
    private int calls;

    Observable(String name, Rule rule, List<Event> trace) {
      this.name = name;
      this.rule = rule;
      this.trace = trace;
    }

    @Override
    public Result parseOn(Context context) {
      int position = context.getPosition();
      Outcome outcome = rule.transition(calls++, position);
      trace.add(new Event(expectingFast, position));
      if (outcome.success) {
        return context.success(outcome.value, position + outcome.advance);
      }
      return context.failure(outcome.message,
          position + outcome.failureOffset);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      Outcome outcome = rule.transition(calls++, position);
      trace.add(new Event(expectingFast, position));
      return outcome.success ? position + outcome.advance : -1;
    }

    @Override
    public Parser copy() {
      return this;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** Replays the listed outcomes, repeating the last one once exhausted. */
  private static Rule script(Outcome... outcomes) {
    return (index, position) ->
        outcomes[Math.min(index, outcomes.length - 1)];
  }

  private static Rule always(Outcome outcome) {
    return (index, position) -> outcome;
  }

  /** Collects every {@link Observable} child of a parser tree. */
  private static final class Harness {
    Parser parser;
    final List<Observable> observables = new ArrayList<>();
    final List<Event> trace = new ArrayList<>();
    final List<IntConsumer> resets = new ArrayList<>();

    Harness() {
    }

    private void discover(Parser parser) {
      if (parser instanceof Observable) {
        observables.add((Observable) parser);
      }
      for (Parser child : parser.getChildren()) {
        discover(child);
      }
    }

    Observable observable(String name, Rule rule) {
      Observable observable = new Observable(name, rule, trace);
      observables.add(observable);
      return observable;
    }

    void track(AtomicInteger counter) {
      resets.add(value -> counter.set(0));
    }

    void reset(boolean fast) {
      trace.clear();
      for (Observable observable : observables) {
        observable.calls = 0;
        observable.expectingFast = fast;
      }
      for (IntConsumer reset : resets) {
        reset.accept(0);
      }
    }
  }

  private static void assertContractSuccess(Harness harness, String buffer,
      int start, Object expectedValue, int expectedPosition) {
    harness.reset(true);
    int fastPosition = harness.parser.fastParseOn(buffer, start);
    assertTrue("fast path expected success but returned -1",
        fastPosition >= 0);
    assertEquals("fast path final position", expectedPosition, fastPosition);
    List<Event> fastTrace = new ArrayList<>(harness.trace);

    harness.reset(false);
    Result result = harness.parser.parseOn(new Context(buffer, start));
    assertTrue("parse path expected success but got: " + result,
        result.isSuccess());
    assertEquals("parse path final position", expectedPosition,
        result.getPosition());
    assertEquals("parse path value", expectedValue, result.get());
    assertNull("no failure message expected", result.getMessage());
    List<Event> parseTrace = new ArrayList<>(harness.trace);

    assertTracesAgree(fastTrace, parseTrace);
  }

  private static void assertContractFailure(Harness harness, String buffer,
      int start, int expectedFailurePosition, String expectedMessage) {
    harness.reset(true);
    int fastPosition = harness.parser.fastParseOn(buffer, start);
    assertEquals("fast path expected failure but got " + fastPosition,
        -1, fastPosition);
    List<Event> fastTrace = new ArrayList<>(harness.trace);

    harness.reset(false);
    Result result = harness.parser.parseOn(new Context(buffer, start));
    assertTrue("parse path expected failure but got: " + result,
        result.isFailure());
    assertEquals("failure position", expectedFailurePosition,
        result.getPosition());
    if (expectedMessage != null) {
      assertEquals("failure message", expectedMessage, result.getMessage());
    }
    List<Event> parseTrace = new ArrayList<>(harness.trace);

    assertTracesAgree(fastTrace, parseTrace);
  }

  private static void assertTracesAgree(List<Event> fastTrace,
      List<Event> parseTrace) {
    assertEquals("number of child invocations; fast=" + fastTrace +
        " parse=" + parseTrace, parseTrace.size(), fastTrace.size());
    for (int i = 0; i < fastTrace.size(); i++) {
      assertEquals("invocation #" + i + " position; fast=" + fastTrace +
          " parse=" + parseTrace,
          parseTrace.get(i).position, fastTrace.get(i).position);
    }
  }

  // ------------------------------------------------------------------
  // Sequence: short-circuit position, partial failure, zero width
  // ------------------------------------------------------------------

  @Test
  public void sequenceAllSucceedFromStart() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.succeed(1, "A")));
    Parser second = h.observable("second",
        script(Outcome.succeed(2, "B")));
    h.parser = first.seq(second);
    assertContractSuccess(h, BUFFER, 0, Arrays.asList("A", "B"), 3);
  }

  @Test
  public void sequenceStartsInMiddleOfInput() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.succeed(1, "A")));
    Parser second = h.observable("second",
        script(Outcome.succeed(1, "B")));
    h.parser = first.seq(second);
    assertContractSuccess(h, BUFFER, 2, Arrays.asList("A", "B"), 4);
  }

  @Test
  public void sequenceFailsAtFirstOnEmptyInput() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.fail("no first")));
    Parser second = h.observable("second",
        script(Outcome.succeed(1, "B")));
    h.parser = first.seq(second);
    assertContractFailure(h, "", 0, 0, "no first");
  }

  @Test
  public void sequencePartialFailureAfterConsumption() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.succeed(2, "A")));
    Parser second = h.observable("second",
        script(Outcome.fail("second gone", 1)));
    h.parser = first.seq(second);
    // Second is entered at 2 and reports the failure at 3; the first child's
    // consumption must be rolled back for the reported fast-path result.
    assertContractFailure(h, BUFFER, 0, 3, "second gone");
  }

  @Test
  public void sequenceWithZeroWidthSuccess() {
    Harness h = new Harness();
    Parser first = h.observable("first", script(Outcome.succeed()));
    Parser second = h.observable("second",
        script(Outcome.succeed(1, "B")));
    Parser third = h.observable("third", script(Outcome.succeed()));
    h.parser = first.seq(second).seq(third);
    assertContractSuccess(h, BUFFER, 1,
        Arrays.asList(Arrays.asList(null, "B"), null), 2);
  }

  @Test
  public void sequenceFailsAfterZeroWidthThenConsumingChild() {
    Harness h = new Harness();
    Parser first = h.observable("first", script(Outcome.succeed()));
    Parser second = h.observable("second",
        script(Outcome.fail("boom", 2)));
    h.parser = first.seq(second);
    assertContractFailure(h, BUFFER, 1, 3, "boom");
  }

  // ------------------------------------------------------------------
  // Choice: rollback order, failure joining is slow-path only
  // ------------------------------------------------------------------

  @Test
  public void choiceFirstWinsWithoutTryingRest() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.succeed(1, "A")));
    Parser second = h.observable("second",
        script(Outcome.succeed(2, "B")));
    h.parser = first.or(second);
    assertContractSuccess(h, BUFFER, 0, "A", 1);
  }

  @Test
  public void choiceRollsBackAndTriesSecondFromSamePosition() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.fail("first no", 2)));
    Parser second = h.observable("second",
        script(Outcome.succeed(1, "B")));
    h.parser = first.or(second);
    assertContractSuccess(h, BUFFER, 0, "B", 1);
  }

  @Test
  public void choiceStartsInMiddle() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.fail("first no")));
    Parser second = h.observable("second",
        script(Outcome.succeed(2, "B")));
    Parser third = h.observable("third",
        script(Outcome.succeed(1, "C")));
    h.parser = first.or(second).or(third);
    assertContractSuccess(h, BUFFER, 2, "B", 4);
  }

  @Test
  public void choiceAllFailReportsLastFailure() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.fail("err-1", 1)));
    Parser second = h.observable("second",
        script(Outcome.fail("err-2", 2)));
    h.parser = first.or(second);
    assertContractFailure(h, BUFFER, 0, 2, "err-2");
  }

  @Test
  public void choiceAllFailOnEmptyInput() {
    Harness h = new Harness();
    Parser first = h.observable("first",
        script(Outcome.fail("empty-1")));
    Parser second = h.observable("second",
        script(Outcome.fail("empty-2")));
    h.parser = first.or(second);
    assertContractFailure(h, "", 0, 0, "empty-2");
  }

  // ------------------------------------------------------------------
  // Optional
  // ------------------------------------------------------------------

  @Test
  public void optionalSuccessConsumes() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.succeed(2, "X")));
    h.parser = delegate.optional("NONE");
    assertContractSuccess(h, BUFFER, 1, "X", 3);
  }

  @Test
  public void optionalFailureBecomesZeroWidthSuccess() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.fail("absent", 1)));
    h.parser = delegate.optional("NONE");
    assertContractSuccess(h, BUFFER, 0, "NONE", 0);
  }

  @Test
  public void optionalZeroWidthSuccess() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.succeed(0, "EPS")));
    h.parser = delegate.optional();
    assertContractSuccess(h, BUFFER, 2, "EPS", 2);
  }

  // ------------------------------------------------------------------
  // And / Not lookahead: never consume, regardless of the child position
  // ------------------------------------------------------------------

  @Test
  public void andLookaheadSucceedsWithoutConsuming() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.succeed(3, "deep")));
    h.parser = delegate.and();
    assertContractSuccess(h, BUFFER, 0, "deep", 0);
  }

  @Test
  public void andLookaheadStartsInMiddleAndFails() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.fail("no ahead", 1)));
    h.parser = delegate.and();
    assertContractFailure(h, BUFFER, 2, 3, "no ahead");
  }

  @Test
  public void andLookaheadZeroWidthSuccess() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate", script(Outcome.succeed()));
    h.parser = delegate.and();
    assertContractSuccess(h, BUFFER, 1, null, 1);
  }

  @Test
  public void notLookaheadSucceedsWhenChildFails() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.fail("child failure", 2)));
    h.parser = delegate.not("stop");
    assertContractSuccess(h, BUFFER, 0, null, 0);
  }

  @Test
  public void notLookaheadFailsWhenChildSucceeds() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate",
        script(Outcome.succeed(1, "X")));
    h.parser = delegate.not("unwanted");
    assertContractFailure(h, BUFFER, 2, 2, "unwanted");
  }

  @Test
  public void notLookaheadZeroWidthChildSuccess() {
    Harness h = new Harness();
    Parser delegate = h.observable("delegate", script(Outcome.succeed()));
    h.parser = delegate.not("unexpected");
    // Zero-width success of the child still counts as success for not().
    assertContractFailure(h, "", 0, 0, "unexpected");
  }
