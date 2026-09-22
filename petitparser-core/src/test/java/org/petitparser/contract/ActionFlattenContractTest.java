package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.context.Token;
import org.petitparser.parser.Parser;
import org.petitparser.parser.actions.ActionParser;
import org.petitparser.parser.actions.FlattenParser;
import org.petitparser.parser.actions.TokenParser;
import org.petitparser.parser.actions.TrimmingParser;
import org.petitparser.parser.combinators.SequenceParser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.petitparser.contract.ObservableParser.Answer;
import static org.petitparser.contract.ObservableParser.fail;
import static org.petitparser.contract.ObservableParser.scripted;
import static org.petitparser.contract.ObservableParser.succeed;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Characterizes the {@code parseOn} / {@code fastParseOn} contract of the
 * flatten, token, action and trimming parsers, including nested combinations.
 */
public class ActionFlattenContractTest {

  private final ObservableParser.EventLog log = new ObservableParser.EventLog();
  private final String input = "abcdefgh";

  private Answer[] table() {
    return new Answer[input.length() + 1];
  }

  private static List<String> describe(List<ObservableParser.Event> events) {
    List<String> described = new ArrayList<>();
    for (ObservableParser.Event event : events) {
      described.add(event.name + "@" + event.position);
    }
    return described;
  }

  private ContractProbe run(Parser parser, String buffer, int start) {
    return ContractProbe.run(parser, log, buffer, start);
  }

  // ----------------------------------------------------------------- flatten

  @Test
  public void flattenWithoutMessageReturnsSubstring() {
    Answer[] a = table();
    a[1] = succeed(4);
    Parser parser = new FlattenParser(scripted("a", input.length(),
        log.events(), a));
    ContractProbe probe = run(parser, input, 1);
    probe.assertSuccess(4);
    assertEquals("bcd", probe.value());
  }

  @Test
  public void flattenWithoutMessagePropagatesFailure() {
    Answer[] a = table();
    a[2] = fail(6, "inner");
    Parser parser = new FlattenParser(scripted("a", input.length(),
        log.events(), a));
    run(parser, input, 2).assertFailure(6, "inner");
  }

  @Test
  public void flattenWithMessageSuccess() {
    Answer[] a = table();
    a[0] = succeed(3);
    Parser parser = new FlattenParser(scripted("a", input.length(),
        log.events(), a), "custom");
    ContractProbe probe = run(parser, input, 0);
    probe.assertSuccess(3);
    assertEquals("abc", probe.value());
  }

  @Test
  public void flattenWithMessageReplacesFailure() {
    Answer[] a = table();
    a[1] = fail(5, "inner-ignored");
    Parser parser = new FlattenParser(scripted("a", input.length(),
        log.events(), a), "custom");
    run(parser, input, 1).assertFailure(1, "custom");
  }

  @Test
  public void flattenZeroWidthReturnsEmptyString() {
    Answer[] a = table();
    a[3] = succeed(3);
    Parser parser = new FlattenParser(scripted("a", input.length(),
        log.events(), a));
    ContractProbe probe = run(parser, input, 3);
    probe.assertSuccess(3);
    assertEquals("", probe.value());
  }

  // ------------------------------------------------------------------- token

  @Test
  public void tokenWrapsValueAndRange() {
    Answer[] a = table();
    a[1] = succeed(5, "inner");
    Parser parser = new TokenParser(scripted("a", input.length(),
        log.events(), a));
    ContractProbe probe = run(parser, input, 1);
    probe.assertSuccess(5);
    Token token = (Token) probe.value();
    assertEquals("inner", token.getValue());
    assertEquals(input, token.getBuffer());
    assertEquals("bcde", token.getInput());
    assertEquals(1, token.getStart());
    assertEquals(5, token.getStop());
  }

  @Test
  public void tokenFailurePropagates() {
    Answer[] a = table();
    a[2] = fail(2, "tok-fail");
    Parser parser = new TokenParser(scripted("a", input.length(),
        log.events(), a));
    run(parser, input, 2).assertFailure(2, "tok-fail");
  }

  // ------------------------------------------------------------------ action

  @Test
  public void actionMapsValueOnSlowPathOnly() {
    Answer[] a = table();
    a[0] = succeed(2, 'x');
    int[] applied = {0};
    Parser parser = new ActionParser<Character, String>(
        scripted("a", input.length(), log.events(), a),
        value -> {
          applied[0]++;
          return ">" + value;
        });
    ContractProbe probe = run(parser, input, 0);
    probe.assertSuccess(2);
    assertEquals(">x", probe.value());
    // Characterization: a pure map() is not invoked on the fast path.
    assertEquals("action applied only on the parseOn path", 1, applied[0]);
  }

  @Test
  public void actionFailureIsNotMapped() {
    Answer[] a = table();
    a[0] = fail(3, "act-fail");
    int[] applied = {0};
    Parser parser = new ActionParser<Character, String>(
        scripted("a", input.length(), log.events(), a),
        value -> {
          applied[0]++;
          return "mapped";
        });
    run(parser, input, 0).assertFailure(3, "act-fail");
    assertEquals(0, applied[0]);
  }

  @Test
  public void sideEffectActionRunsOnBothPaths() {
    Answer[] delegateAnswers = table();
    delegateAnswers[1] = succeed(4, "v");
    ObservableParser delegate = scripted("d", input.length(),
        log.events(), delegateAnswers);
    List<String> recorded = new ArrayList<>();
    Parser parser = new ActionParser<Object, String>(delegate,
        value -> {
          recorded.add("effect");
          return value == null ? null : value.toString();
        }, true);

    log.clear();
    Result result = parser.parseOn(new Context(input, 1));
    assertTrue(result.isSuccess());
    assertEquals(4, result.getPosition());
    assertEquals(1, recorded.size());

    log.clear();
    recorded.clear();
    int fast = parser.fastParseOn(input, 1);
    assertEquals(4, fast);
    assertEquals("mapWithSideEffects must execute on the fast path", 1,
        recorded.size());
  }

  @Test
  public void pureActionDoesNotRunSideEffectOnFastPath() {
    Answer[] a = table();
    a[0] = succeed(1, 'z');
    List<String> recorded = new ArrayList<>();
    Parser parser = new ActionParser<Character, Character>(
        scripted("a", input.length(), log.events(), a),
        value -> {
          recorded.add("effect");
          return value;
        }, false);

    parser.fastParseOn(input, 0);
    assertEquals("pure map() must not execute on the fast path", 0,
        recorded.size());

    Result result = parser.parseOn(new Context(input, 0));
    assertTrue(result.isSuccess());
    assertEquals(1, recorded.size());
  }

  // --------------------------------------------------------------- trimming

  @Test
  public void trimmingConsumesBothSides() {
    Answer[] delegateAnswers = table();
    delegateAnswers[2] = succeed(3, "v");
    ObservableParser body = scripted("body", input.length(),
        log.events(), delegateAnswers);
    Parser parser = new TrimmingParser(body, of(' '), of(' '));
    String spaced = "  b def";
    ContractProbe probe = run(parser, spaced, 0);
    probe.assertSuccess(4);
    assertEquals("v", probe.value());
  }

  @Test
  public void trimmingBodyFailureKeepsFailurePosition() {
    Answer[] delegateAnswers = table();
    delegateAnswers[0] = fail(2, "body-fail");
    ObservableParser body = scripted("body", input.length(),
        log.events(), delegateAnswers);
    Parser parser = new TrimmingParser(body, of(' '), of(' '));
    run(parser, input, 0).assertFailure(2, "body-fail");
  }

  // ----------------------------------------------------------------- nesting

  @Test
  public void nestedFlattenTokenActionSequence() {
    // seq(a, b).token().flatten().map(...)
    Answer[] a = table();
    Answer[] b = table();
    a[1] = succeed(2, 'a');
    b[2] = succeed(5, 'b');
    Parser seq = new SequenceParser(
        scripted("a", input.length(), log.events(), a),
        scripted("b", input.length(), log.events(), b));
    Parser nested = new TokenParser(seq).flatten()
        .map(value -> "got:" + value);
    ContractProbe probe = run(nested, input, 1);
    probe.assertSuccess(5);
    assertEquals("got:bcde", probe.value());
    assertEquals(java.util.Arrays.asList("a@1", "b@2"), probe.events());
  }

  @Test
  public void nestedFailurePositionFlowsThrough() {
    Answer[] a = table();
    Answer[] b = table();
    a[0] = succeed(2, 'a');
    b[2] = fail(7, "deep-fail");
    Parser seq = new SequenceParser(
        scripted("a", input.length(), log.events(), a),
        scripted("b", input.length(), log.events(), b));
    Parser nested = new TokenParser(seq).flatten("wrapped");
    // flatten(message) replaces the failure message but keeps position 0.
    ContractProbe probe = run(nested, input, 0);
    probe.assertFailure(0, "wrapped");
  }

  @Test
  public void fastParseOnAgreesOnNestedChoiceInSequence() {
    Answer[] c1 = table();
    Answer[] c2 = table();
    Answer[] tail = table();
    c1[0] = fail(0, "c1");
    c2[0] = succeed(2, "c2");
    tail[2] = succeed(4, "tail");
    Parser choice = new org.petitparser.parser.combinators.ChoiceParser(
        scripted("c1", input.length(), log.events(), c1),
        scripted("c2", input.length(), log.events(), c2));
    Parser seq = new SequenceParser(choice,
        scripted("tail", input.length(), log.events(), tail));
    ContractProbe probe = run(seq, input, 0);
    probe.assertSuccess(4);
    assertEquals(java.util.Arrays.asList("c1@0", "c2@0", "tail@2"),
        describe(log.snapshot()));
  }
}
