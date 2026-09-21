package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * An observable parser whose transition behaviour is fully scripted, so that
 * the two evaluation paths {@link Parser#parseOn(Context)} and
 * {@link Parser#fastParseOn(String, int)} are driven by exactly the same
 * transition definition.
 *
 * <p>A {@link Reaction} describes, for a given entry position, whether the
 * parser succeeds (and where it moves to) or fails (and at which position),
 * together with the value it reports through the slow path. Every invocation
 * is recorded as a {@link Call} in {@link #calls}, which lets a contract test
 * prove that both paths invoke their children in the same order, at the same
 * positions, and roll back identically after a failed branch.
 */
public class ScriptedParser extends Parser {

  /**
   * A single scripted transition of the observable parser.
   */
  public static final class Reaction {

    final int at;
    final int next;
    final Object value;
    final String message;

    private Reaction(int at, int next, Object value, String message) {
      this.at = at;
      this.next = next;
      this.value = value;
      this.message = message;
    }

    /**
     * Creates a successful transition when entered at position {@code at}.
     */
    public static Reaction succeed(int at, int next, Object value) {
      if (next < at) {
        throw new IllegalArgumentException("next position must not regress");
      }
      return new Reaction(at, next, value, null);
    }

    /**
     * Creates a zero-width successful transition at position {@code at}.
     */
    public static Reaction succeed(int at, Object value) {
      return succeed(at, at, value);
    }

    /**
     * Creates a failing transition when entered at position {@code at}.
     */
    public static Reaction fail(int at, String message) {
      return new Reaction(at, -1, null, Objects.requireNonNull(message));
    }
  }

  /**
   * An observable invocation of the scripted parser.
   */
  public static final class Call {

    public final String path;
    public final int position;

    Call(String path, int position) {
      this.path = path;
      this.position = position;
    }

    @Override
    public String toString() {
      return path + "@" + position;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Call)) {
        return false;
      }
      Call call = (Call) other;
      return position == call.position && Objects.equals(path, call.path);
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, position);
    }
  }

  private final String name;
  private final Deque<Reaction> reactions = new ArrayDeque<>();
  private final List<Call> calls = new ArrayList<>();

  public ScriptedParser(String name, Reaction... reactions) {
    this.name = name;
    this.reactions.addAll(Arrays.asList(reactions));
  }

  /**
   * Returns the ordered list of entry positions at which the parser was
   * invoked.
   */
  public List<Call> calls() {
    return calls;
  }

  /**
   * Number of recorded invocations so far.
   */
  public int callCount() {
    return calls.size();
  }

  private Reaction nextReaction(String path, int position) {
    // Re-queue the reaction so that subsequent runs of the other evaluation
    // path replay the exact same transition definition indefinitely.
    Reaction reaction = reactions.removeFirst();
    reactions.addLast(reaction);
    if (reaction.at != position) {
      throw new AssertionError(name + ": scripted reaction expected at "
          + reaction.at + " but " + path + " entered at " + position);
    }
    calls.add(new Call(path, position));
    return reaction;
  }

  @Override
  public Result parseOn(Context context) {
    Reaction reaction = nextReaction("parseOn", context.getPosition());
    if (reaction.message == null) {
      return context.success(reaction.value, reaction.next);
    }
    return context.failure(reaction.message, reaction.at);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    Reaction reaction = nextReaction("fastParseOn", position);
    return reaction.message == null ? reaction.next : -1;
  }

  @Override
  public Parser copy() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return "ScriptedParser[" + name + "]";
  }
}
