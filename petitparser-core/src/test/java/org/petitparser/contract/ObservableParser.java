package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A scripted, observable parser used by the {@code parseOn}/{@code
 * fastParseOn} contract tests.
 *
 * <p>Every invocation of either entry point is recorded as an {@link Event},
 * including the position the parser was started at. This makes it possible to
 * assert not only that both paths agree on success/failure and on the final
 * position, but also that combinators drive their children in the same order,
 * restart them at the same (rolled back) positions and trigger the same number
 * of attempts.
 *
 * <p>The parser is scripted with a list of {@link Reaction}s that are consumed
 * in order, so a single instance can model bounded repeats and backtracking.
 * If more invocations occur than reactions are configured the last reaction is
 * repeated, which conveniently models unbounded loops.
 */
public class ObservableParser extends Parser {

  /** Outcome of a single scripted invocation. */
  public enum Outcome {
    /** Succeed at the given absolute position. */
    SUCCEED,
    /** Fail at the given absolute position with a fixed message. */
    FAIL
  }

  /** A scripted reaction to an invocation. */
  public static final class Reaction {

    static Reaction succeed(int position) {
      return new Reaction(Outcome.SUCCEED, position, null);
    }

    static Reaction fail(int position, String message) {
      return new Reaction(Outcome.FAIL, position, message);
    }

    final Outcome outcome;
    final int position;
    final String message;

    private Reaction(Outcome outcome, int position, String message) {
      this.outcome = outcome;
      this.position = position;
      this.message = message;
    }

    @Override
    public String toString() {
      return outcome + "@" + position
          + (message == null ? "" : "(" + message + ")");
    }
  }

  /** Record of a single invocation on either entry point. */
  public static final class Event {

    /** Which entry point was invoked. */
    public enum Kind { SLOW, FAST }

    final Kind kind;
    final int position;

    private Event(Kind kind, int position) {
      this.kind = kind;
      this.position = position;
    }

    public Kind getKind() {
      return kind;
    }

    public int getPosition() {
      return position;
    }

    @Override
    public String toString() {
      return kind + "@" + position;
    }
  }

  private final List<Reaction> reactions = new ArrayList<>();
  private final List<Event> slowEvents = new ArrayList<>();
  private final List<Event> fastEvents = new ArrayList<>();
  private final List<Integer> fastFailurePositions = new ArrayList<>();
  private int slowInvocations;
  private int fastInvocations;

  public ObservableParser succeedAt(int position) {
    reactions.add(Reaction.succeed(position));
    return this;
  }

  public ObservableParser failAt(int position) {
    return failAt(position, "observable failure");
  }

  public ObservableParser failAt(int position, String message) {
    reactions.add(Reaction.fail(position, message));
    return this;
  }

  private Reaction reactionFor(int index) {
    if (reactions.isEmpty()) {
      // A parser without scripted reactions is never expected to be invoked;
      // return a failing reaction to surface that loudly instead of throwing.
      return Reaction.fail(0, "unscripted invocation");
    }
    return reactions.get(Math.min(index, reactions.size() - 1));
  }

  @Override
  public Result parseOn(Context context) {
    int start = context.getPosition();
    slowEvents.add(new Event(Event.Kind.SLOW, start));
    slowInvocations++;
    Reaction reaction = reactionFor(slowEvents.size() - 1);
    if (reaction.outcome == Outcome.SUCCEED) {
      int ordinal = slowEvents.size();
      return context.success(ordinal * 1000 + start,
          reaction.position);
    }
    return context.failure(reaction.message, reaction.position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    fastEvents.add(new Event(Event.Kind.FAST, position));
    fastInvocations++;
    Reaction reaction = reactionFor(fastEvents.size() - 1);
    if (reaction.outcome == Outcome.SUCCEED) {
      return reaction.position;
    }
    fastFailurePositions.add(reaction.position);
    return -1;
  }

  /**
   * Returns the failure positions reported by scripted fast invocations.
   */
  public List<Integer> fastFailurePositions() {
    return new ArrayList<>(fastFailurePositions);
  }

  /**
   * Returns the positions of the entry points that were run since the last
   * {@link #resetTrace()}; the harness executes exactly one entry point per
   * run, so exactly one of the two traces is populated.
   */
  public List<Integer> slowOrFastStartPositions() {
    return slowEvents.isEmpty() ? startPositions(fastEvents)
        : startPositions(slowEvents);
  }

  /**
   * Returns the positions the slow entry point was invoked at, in order.
   */
  public List<Integer> slowStartPositions() {
    return startPositions(slowEvents);
  }

  /**
   * Returns the positions the fast entry point was invoked at, in order.
   */
  public List<Integer> fastStartPositions() {
    return startPositions(fastEvents);
  }

  public int slowInvocationCount() {
    return slowInvocations;
  }

  public int fastInvocationCount() {
    return fastInvocations;
  }

  public void resetTrace() {
    slowEvents.clear();
    fastEvents.clear();
    fastFailurePositions.clear();
    slowInvocations = 0;
    fastInvocations = 0;
  }

  private static List<Integer> startPositions(List<Event> events) {
    List<Integer> positions = new ArrayList<>(events.size());
    for (Event event : events) {
      positions.add(event.getPosition());
    }
    return positions;
  }

  @Override
  public ObservableParser copy() {
    ObservableParser copy = new ObservableParser();
    copy.reactions.addAll(reactions);
    return copy;
  }
}
