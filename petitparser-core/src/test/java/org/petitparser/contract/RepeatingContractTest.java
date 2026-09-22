package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.parser.Parser;
import org.petitparser.parser.repeating.GreedyRepeatingParser;
import org.petitparser.parser.repeating.LazyRepeatingParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.petitparser.contract.ObservableParser.Answer;
import static org.petitparser.contract.ObservableParser.Event;
import static org.petitparser.contract.ObservableParser.fail;
import static org.petitparser.contract.ObservableParser.scripted;
import static org.petitparser.contract.ObservableParser.succeed;

/**
 * Characterizes the {@code parseOn} / {@code fastParseOn} contract of the
 * possessive, greedy and lazy repeating parsers, including boundary success,
 * zero-width repetition and backtracking around the limit parser.
 */
public class RepeatingContractTest {

  private final ObservableParser.EventLog log = new ObservableParser.EventLog();
  private final String input = "abcdefgh";

  private Answer[] table(int length) {
    return new Answer[length + 1];
  }

  private ObservableParser obs(String name, Answer[] answers, int length) {
    return scripted(name, length, log.events(), answers);
  }

  private static String ev(String name, int position) {
    return name + "@" + position;
  }

  // --------------------------------------------------------------- possessive

  @Test
  public void possessiveStarEmptyInputZeroWidth() {
    int len = 0;
    Answer[] a = table(len);
    a[0] = fail(0, "none");
    Parser parser = new PossessiveRepeatingParser(obs("a", a, len), 0,
        PossessiveRepeatingParser.UNBOUNDED);
    ContractProbe probe = ContractProbe.run(parser, log, "", 0);
    probe.assertSuccess(0);
    // Characterization: star() probes its delegate once even on empty
    // input; both paths do so at position 0.
    assertEquals(java.util.Arrays.asList(ev("a", 0)), probe.events());
  }

  @Test
  public void possessivePlusEmptyInputFails() {
    int len = 0;
    Answer[] a = table(len);
    a[0] = fail(0, "plus-fail");
    Parser parser = new PossessiveRepeatingParser(obs("a", a, len), 1,
        PossessiveRepeatingParser.UNBOUNDED);
    ContractProbe.run(parser, log, "", 0).assertFailure(0, "plus-fail");
  }

  @Test
  public void possessiveStarConsumesUntilFailure() {
    Answer[] a = table(input.length());
    a[0] = succeed(1);
    a[1] = succeed(3);
    a[3] = fail(3, "stop");
    Parser parser = new PossessiveRepeatingParser(obs("a", a, input.length()),
        0, PossessiveRepeatingParser.UNBOUNDED);
    ContractProbe probe = ContractProbe.run(parser, log, input, 0);
    probe.assertSuccess(3);
    assertEquals(java.util.Arrays.asList(ev("a", 0), ev("a", 1), ev("a", 3)),
        probe.events());
  }

  @Test
  public void possessivePlusBoundaryExact() {
    Answer[] a = table(input.length());
    a[2] = succeed(4);
    a[4] = succeed(6);
    Parser parser = new PossessiveRepeatingParser(obs("a", a, input.length()),
        2, 2);
    ContractProbe probe = ContractProbe.run(parser, log, input, 2);
    probe.assertSuccess(6);
    assertEquals(java.util.Arrays.asList(ev("a", 2), ev("a", 4)),
        probe.events());
  }

  @Test
  public void possessiveMinNotReachedFailsWithChildFailure() {
    Answer[] a = table(input.length());
    a[1] = succeed(2);
    a[2] = fail(5, "min-fail");
    Parser parser = new PossessiveRepeatingParser(obs("a", a, input.length()),
        3, 5);
    ContractProbe.run(parser, log, input, 1).assertFailure(5, "min-fail");
  }

  @Test
  public void possessiveStopsAtMaxEvenIfMoreAvailable() {
    Answer[] a = table(input.length());
    a[0] = succeed(1);
    a[1] = succeed(2);
    a[2] = succeed(3);
    Parser parser = new PossessiveRepeatingParser(obs("a", a, input.length()),
        0, 2);
    ContractProbe probe = ContractProbe.run(parser, log, input, 0);
    probe.assertSuccess(2);
    assertEquals(java.util.Arrays.asList(ev("a", 0), ev("a", 1)),
        probe.events());
  }

  // -------------------------------------------------------------------- lazy

  @Test
  public void lazyStopsAsEarlyAsPossible() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = succeed(1);
    a[1] = succeed(2);
    a[2] = succeed(3);
    limit[0] = fail(0, "l0");
    limit[1] = fail(1, "l1");
    limit[2] = fail(2, "l2");
    limit[3] = succeed(5);
    Parser parser = new LazyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0,
        LazyRepeatingParser.UNBOUNDED);
    ContractProbe probe = ContractProbe.run(parser, log, input, 0);
    probe.assertSuccess(3);
    assertEquals(java.util.Arrays.asList(
        ev("limit", 0), ev("a", 0),
        ev("limit", 1), ev("a", 1),
        ev("limit", 2), ev("a", 2),
        ev("limit", 3)), probe.events());
  }

  @Test
  public void lazyMinNotReachedPropagatesChildFailure() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[2] = fail(2, "lazy-min");
    limit[2] = fail(2, "limit-fail");
    Parser parser = new LazyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 1,
        LazyRepeatingParser.UNBOUNDED);
    ContractProbe.run(parser, log, input, 2).assertFailure(2, "lazy-min");
    assertEquals(java.util.Arrays.asList(ev("a", 2)),
        probeEvents());
  }

  @Test
  public void lazyLimitNeverSucceedsFailsWithLimitFailure() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = succeed(1);
    a[1] = fail(1, "a-done");
    limit[0] = fail(0, "L0");
    limit[1] = fail(7, "L1-farthest");
    Parser parser = new LazyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0,
        LazyRepeatingParser.UNBOUNDED);
    ContractProbe.run(parser, log, input, 0)
        .assertFailure(7, "L1-farthest");
  }

  @Test
  public void lazyMaxReachedChecksLimitOnceMore() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = succeed(1);
    a[1] = succeed(2);
    limit[0] = fail(0, "L0");
    limit[1] = fail(1, "L1");
    limit[2] = fail(6, "L2");
    Parser parser = new LazyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0, 2);
    ContractProbe.run(parser, log, input, 0).assertFailure(6, "L2");
  }

  // ------------------------------------------------------------------ greedy

  @Test
  public void greedySucceedsImmediatelyWhenLimitMatches() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = fail(0, "none");
    limit[0] = succeed(2);
    Parser parser = new GreedyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0,
        GreedyRepeatingParser.UNBOUNDED);
    ContractProbe probe = ContractProbe.run(parser, log, input, 0);
    probe.assertSuccess(0);
    assertEquals(java.util.Arrays.asList(ev("a", 0), ev("limit", 0)),
        probe.events());
  }

  @Test
  public void greedyBacktracksToLimit() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = succeed(1);
    a[1] = succeed(2);
    a[2] = fail(2, "a-stop");
    limit[0] = succeed(4);
    limit[1] = fail(1, "L1");
    limit[2] = fail(2, "L2");
    Parser parser = new GreedyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0,
        GreedyRepeatingParser.UNBOUNDED);
    ContractProbe probe = ContractProbe.run(parser, log, input, 0);
    probe.assertSuccess(0);
    assertEquals(java.util.Arrays.asList(
        ev("a", 0), ev("a", 1), ev("a", 2),
        ev("limit", 2), ev("limit", 1), ev("limit", 0)),
        probe.events());
  }

  @Test
  public void greedyMinNotReachedFailsWithChild() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[1] = fail(5, "g-min");
    limit[1] = succeed(9);
    Parser parser = new GreedyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 2,
        GreedyRepeatingParser.UNBOUNDED);
    ContractProbe.run(parser, log, input, 1).assertFailure(5, "g-min");
  }

  @Test
  public void greedyLimitNeverMatchesReturnsLimitFailure() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = succeed(1);
    a[1] = fail(1, "stop");
    limit[0] = fail(3, "L0");
    limit[1] = fail(3, "L1");
    Parser parser = new GreedyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0,
        GreedyRepeatingParser.UNBOUNDED);
    ContractProbe.run(parser, log, input, 0).assertFailure(3, "L0");
    assertEquals(java.util.Arrays.asList(
        ev("a", 0), ev("a", 1),
        ev("limit", 1), ev("limit", 0)), probeEvents());
  }

  @Test
  public void greedyRespectsMaxAndBacktracks() {
    Answer[] a = table(input.length());
    Answer[] limit = table(input.length());
    a[0] = succeed(1);
    a[1] = succeed(2);
    a[2] = succeed(3);
    limit[0] = fail(0, "L0");
    limit[1] = succeed(9);
    limit[2] = fail(2, "L2");
    limit[3] = fail(3, "L3");
    Parser parser = new GreedyRepeatingParser(obs("a", a, input.length()),
        obs("limit", limit, input.length()), 0, 3);
    ContractProbe probe = ContractProbe.run(parser, log, input, 0);
    probe.assertSuccess(1);
    assertEquals(java.util.Arrays.asList(
        ev("a", 0), ev("a", 1), ev("a", 2),
        ev("limit", 3), ev("limit", 2), ev("limit", 1)),
        probe.events());
  }

  private List<String> probeEvents() {
    java.util.List<String> described = new java.util.ArrayList<>();
    for (Event event : log.snapshot()) {
      described.add(event.name + "@" + event.position);
    }
    return described;
  }
}
