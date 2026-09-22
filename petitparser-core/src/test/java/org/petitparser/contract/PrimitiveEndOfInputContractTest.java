package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.EndOfInputParser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.combinators.SequenceParser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.primitive.EpsilonParser;
import org.petitparser.parser.primitive.FailureParser;
import org.petitparser.parser.primitive.StringParser;

import static org.junit.Assert.assertEquals;

/**
 * Contract coverage for the primitive parsers, the end-of-input sentinel and a
 * settable delegate, exercised through real (non-scripted) parsers at the
 * boundaries of the input.
 */
public class PrimitiveEndOfInputContractTest {

  private final ObservableParser.EventLog log = new ObservableParser.EventLog();

  private void agree(Parser parser, String input, int start, int finalPos) {
    ContractProbe.run(parser, log, input, start).assertSuccess(finalPos);
  }

  private void disagree(Parser parser, String input, int start, int failPos,
      String message) {
    ContractProbe.run(parser, log, input, start)
        .assertFailure(failPos, message);
  }

  @Test
  public void characterAtStartMidAndEnd() {
    Parser parser = CharacterParser.of('a', "a expected");
    agree(parser, "a", 0, 1);
    disagree(parser, "b", 0, 0, "a expected");
    disagree(parser, "", 0, 0, "a expected");
    agree(parser, "ba", 1, 2);
    disagree(parser, "ba", 0, 0, "a expected");
  }

  @Test
  public void stringParserBoundary() {
    Parser parser = StringParser.of("abc");
    agree(parser, "abc", 0, 3);
    agree(parser, "xabc", 1, 4);
    disagree(parser, "ab", 0, 0, "abc expected");
    disagree(parser, "abd", 0, 0, "abc expected");
  }

  @Test
  public void epsilonZeroWidth() {
    Parser parser = new EpsilonParser();
    agree(parser, "", 0, 0);
    agree(parser, "xyz", 2, 2);
  }

  @Test
  public void failureAlwaysFails() {
    Parser parser = FailureParser.withMessage("boom");
    disagree(parser, "", 0, 0, "boom");
    disagree(parser, "xyz", 1, 1, "boom");
  }

  @Test
  public void endOfInputBoundaries() {
    Parser parser = new EndOfInputParser("end expected");
    agree(parser, "", 0, 0);
    agree(parser, "abc", 3, 3);
    disagree(parser, "abc", 0, 0, "end expected");
    disagree(parser, "abc", 2, 2, "end expected");
  }

  @Test
  public void endOfInputInsideSequence() {
    Parser parser = new SequenceParser(CharacterParser.of('a'),
        new EndOfInputParser("end expected"));
    agree(parser, "a", 0, 1);
    disagree(parser, "ab", 0, 1, "end expected");
  }

  @Test
  public void settableDelegatesToCurrentParser() {
    SettableParser settable = SettableParser.undefined("undefined");
    disagree(settable, "a", 0, 0, "undefined");
    settable.set(CharacterParser.of('a'));
    agree(settable, "a", 0, 1);
    disagree(settable, "b", 0, 0, "'a' expected");
  }

  @Test
  public void realNestedGrammar() {
    Parser digit = CharacterParser.digit();
    Parser number = digit.plus().flatten();
    Parser comma = CharacterParser.of(',');
    Parser parser = number.seq(comma).seq(number).pick(0);
    ContractProbe.run(parser, log, "123;45", 0)
        .assertFailure(3, "',' expected");
    Parser good = number.seq(comma).seq(number);
    ContractProbe goodProbe = ContractProbe.run(good, log, "123,45", 0);
    goodProbe.assertSuccess(6);
    assertEquals(3, ((java.util.List<?>) goodProbe.value()).size());
  }
}
