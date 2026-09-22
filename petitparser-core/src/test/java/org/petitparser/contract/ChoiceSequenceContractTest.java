package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.AndParser;
import org.petitparser.parser.combinators.ChoiceParser;
import org.petitparser.parser.combinators.NotParser;
import org.petitparser.parser.combinators.OptionalParser;
import org.petitparser.parser.combinators.SequenceParser;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.petitparser.contract.ObservableParser.Answer;
import static org.petitparser.contract.ObservableParser.Event;
import static org.petitparser.contract.ObservableParser.fail;
import static org.petitparser.contract.ObservableParser.scripted;
import static org.petitparser.contract.ObservableParser.succeed;

/**
 * Characterizes the {@code parseOn} / {@code fastParseOn} contract of choice,
 * sequence, optional and the zero-width look-ahead combinators.
 */
public class ChoiceSequenceContractTest {

  private final ObservableParser.EventLog log = new ObservableParser.EventLog();
  private final String input = "abcdefgh";

  private ObservableParser obs(String name, Answer... answers) {
    return scripted(name, input.length(), log.events(), answers);
  }

  private Answer[] table() {
    return new Answer[input.length() + 1];
  }

  // ------------------------------------------------------------------ choice

  @Test
  public void choiceEmptyInputFirstFails() {
    Answer[] a = table();
    Answer[] b = table();
    a[0] = fail(0, "a-msg");
    b[0] = fail(0, "b-msg");
    Parser parser = new ChoiceParser(obs("a", a), obs("b", b));
    FastPathContract.assertContract(parser, log, "", 0, null, 0, "b-msg");
    assertEquals(Arrays.asList(ev("a", 0), ev("b", 0)), logSnapshot());
  }

  @Test
  public void choiceRollsBackOnFirstFailure() {
    Answer[] a = table();
    Answer[] b = table();
    Answer[] c = table();
    a[2] = fail(2, "a-fail");
    b[2] = fail(4, "b-fail");
    c[2] = succeed(5);
    Parser parser = new ChoiceParser(obs("a", a), obs("b", b), obs("c", c));
    FastPathContract.assertContract(parser, log, input, 2, null, -1, null);
    assertEquals(Arrays.asList(ev("a", 2), ev("b", 2), ev("c", 2)),
        logSnapshot());
  }

  @Test
  public void choiceFirstSucceedsStops() {
    Answer[] a = table();
    Answer[] b = table();
    a[1] = succeed(3);
    b[1] = succeed(4);
    Parser parser = new ChoiceParser(obs("a", a), obs("b", b));
    FastPathContract.assertContract(parser, log, input, 1, null, -1, null);
    assertEquals(Arrays.asList(ev("a", 1)), logSnapshot());
  }

  @Test
  public void choiceSecondSucceedsMidInput() {
    Answer[] a = table();
    Answer[] b = table();
    a[3] = fail(3, "a");
    b[3] = succeed(6);
    Parser parser = new ChoiceParser(obs("a", a), obs("b", b));
    FastPathContract.assertContract(parser, log, input, 3, null, -1, null);
    assertEquals(Arrays.asList(ev("a", 3), ev("b", 3)), logSnapshot());
  }

  @Test
  public void choiceZeroWidthSuccess() {
    Answer[] a = table();
    a[4] = succeed(4);
    Parser parser = new ChoiceParser(obs("a", a));
    FastPathContract.assertContractSuccessNull(parser, log, input, 4, 4);
    assertEquals(Arrays.asList(ev("a", 4)), logSnapshot());
  }

  @Test
  public void choiceAtEndOfInput() {
    Answer[] a = table();
    Answer[] b = table();
    a[input.length()] = fail(input.length(), "a-end");
    b[input.length()] = succeed(input.length());
    Parser parser = new ChoiceParser(obs("a", a), obs("b", b));
    FastPathContract.assertContractSuccessNull(parser, log, input,
        input.length(), input.length());
    assertEquals(Arrays.asList(ev("a", 8), ev("b", 8)), logSnapshot());
  }

  // ---------------------------------------------------------------- sequence

  @Test
  public void sequenceEmptyInputFails() {
    Answer[] a = table();
    Answer[] b = table();
    a[0] = fail(0, "seq-a");
    Parser parser = new SequenceParser(obs("a", a), obs("b", b));
    FastPathContract.assertContract(parser, log, "", 0, null, 0, "seq-a");
    assertEquals(Arrays.asList(ev("a", 0)), logSnapshot());
  }

  @Test
  public void sequenceThreadsPosition() {
    Answer[] a = table();
    Answer[] b = table();
    Answer[] c = table();
    a[1] = succeed(2, 'a');
    b[2] = succeed(5, 'b');
    c[5] = succeed(7, 'c');
    Parser parser = new SequenceParser(obs("a", a), obs("b", b), obs("c", c));
    ResultProbe probe = parseAndFast(parser, input, 1);
    assertEquals(Arrays.asList('a', 'b', 'c'), probe.value);
    assertEquals(7, probe.fast);
    assertEquals(Arrays.asList(ev("a", 1), ev("b", 2), ev("c", 5)),
        describe(probe.events));
  }

  @Test
  public void sequencePartialFailureAfterConsumption() {
    Answer[] a = table();
    Answer[] b = table();
    Answer[] c = table();
    a[0] = succeed(2, 'a');
    b[2] = fail(4, "b-moved-and-failed");
    Parser parser = new SequenceParser(obs("a", a), obs("b", b), obs("c", c));
    FastPathContract.assertContract(parser, log, input, 0, null, 4,
        "b-moved-and-failed");
    assertEquals(Arrays.asList(ev("a", 0), ev("b", 2)), logSnapshot());
  }

  @Test
  public void sequenceZeroWidthSuccess() {
    Answer[] a = table();
    Answer[] b = table();
    a[2] = succeed(2, 'a');
    b[2] = succeed(3, 'b');
    Parser parser = new SequenceParser(obs("a", a), obs("b", b));
    ResultProbe probe = parseAndFast(parser, input, 2);
    assertEquals(Arrays.asList('a', 'b'), probe.value);
    assertEquals(3, probe.fast);
    assertEquals(Arrays.asList(ev("a", 2), ev("b", 2)),
        describe(probe.events));
  }

  @Test
  public void sequenceBoundaryExactSuccess() {
    Answer[] a = table();
    Answer[] b = table();
    a[6] = succeed(7, 'a');
    b[7] = succeed(8, 'b');
    Parser parser = new SequenceParser(obs("a", a), obs("b", b));
    ResultProbe probe = parseAndFast(parser, input, 6);
    assertEquals(Arrays.asList('a', 'b'), probe.value);
    assertEquals(8, probe.fast);
  }

  // ---------------------------------------------------------------- optional

  @Test
  public void optionalSuccessConsumes() {
    Answer[] a = table();
    a[1] = succeed(4, "x");
    Parser parser = new OptionalParser(obs("a", a), "otherwise");
    ResultProbe probe = parseAndFast(parser, input, 1);
    assertEquals("x", probe.value);
    assertEquals(4, probe.fast);
  }

  @Test
  public void optionalFailureRecoversAtSamePosition() {
    Answer[] a = table();
    a[3] = fail(5, "ignored");
    Parser parser = new OptionalParser(obs("a", a), "otherwise");
    ResultProbe probe = parseAndFast(parser, input, 3);
    assertEquals("otherwise", probe.value);
    assertEquals(3, probe.fast);
    assertEquals(Arrays.asList(ev("a", 3)),
        describe(probe.events));
  }

  @Test
  public void optionalZeroWidthSuccess() {
    Answer[] a = table();
    a[3] = succeed(3, "x");
    Parser parser = new OptionalParser(obs("a", a), "otherwise");
    ResultProbe probe = parseAndFast(parser, input, 3);
    assertEquals("x", probe.value);
    assertEquals(3, probe.fast);
  }

  // ------------------------------------------------------------- and / not()

  @Test
  public void andPredicateSuccessIsZeroWidth() {
    Answer[] a = table();
    a[2] = succeed(7, "v");
    Parser parser = new AndParser(obs("a", a));
    ResultProbe probe = parseAndFast(parser, input, 2);
    assertEquals("v", probe.value);
    assertEquals(2, probe.fast);
    assertEquals(Arrays.asList(ev("a", 2)),
        describe(probe.events));
  }

  @Test
  public void andPredicateFailurePropagates() {
    Answer[] a = table();
    a[2] = fail(6, "and-fail");
    Parser parser = new AndParser(obs("a", a));
    FastPathContract.assertContract(parser, log, input, 2, null, 6,
        "and-fail");
    assertEquals(Arrays.asList(ev("a", 2)), logSnapshot());
  }

  @Test
  public void notPredicateSuccessIsZeroWidth() {
    Answer[] a = table();
    a[2] = fail(4, "delegate-fail");
    Parser parser = new NotParser(obs("a", a), "unexpected");
    ResultProbe probe = parseAndFast(parser, input, 2);
    assertEquals(null, probe.value);
    assertEquals(2, probe.fast);
    assertEquals(Arrays.asList(ev("a", 2)),
        describe(probe.events));
  }

  @Test
  public void notPredicateFailureMessageAndPosition() {
    Answer[] a = table();
    a[2] = succeed(5, "v");
    Parser parser = new NotParser(obs("a", a), "unexpected");
    FastPathContract.assertContract(parser, log, input, 2, null, 2,
        "unexpected");
    assertEquals(Arrays.asList(ev("a", 2)), logSnapshot());
  }

  @Test
  public void notPredicateAtEndOfInput() {
    Answer[] a = table();
    a[input.length()] = fail(input.length(), "eof-delegate");
    Parser parser = new NotParser(obs("a", a), "unexpected");
    ResultProbe probe = parseAndFast(parser, input, input.length());
    assertEquals(input.length(), probe.fast);
  }

  // ---------------------------------------------------------------- helpers

  private static String ev(String name, int position) {
    return name + "@" + position;
  }

  private List<String> logSnapshot() {
    return describe(log.snapshot());
  }

  private ResultProbe parseAndFast(Parser parser, String buffer, int start) {
    log.clear();
    org.petitparser.context.Result result =
        parser.parseOn(new org.petitparser.context.Context(buffer, start));
    List<Event> events = log.snapshot();

    log.clear();
    int fast = parser.fastParseOn(buffer, start);
    List<Event> fastEvents = log.snapshot();

    assertEquals("success at " + start, true, result.isSuccess());
    assertEquals("fast position at " + start, result.getPosition(), fast);
    assertEquals("transition order at " + start + ": parseOn=" + events
        + " fastParseOn=" + fastEvents, describe(events),
        describe(fastEvents));
    ResultProbe probe = new ResultProbe();
    probe.value = result.get();
    probe.fast = fast;
    probe.events = events;
    return probe;
  }

  private static List<String> describe(List<Event> events) {
    java.util.List<String> described = new java.util.ArrayList<>();
    for (Event event : events) {
      described.add(event.name + "@" + event.position);
    }
    return described;
  }

  private static final class ResultProbe {
    Object value;
    int fast;
    List<Event> events;
  }
}
