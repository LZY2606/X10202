package org.petitparser.contract;

import com.sun.management.ThreadMXBean;
import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.parser.Parser;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.any;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Allocation baselines guarding the {@code fastParseOn} hot path.
 *
 * <p>The fast entry point must not degrade into building full
 * {@link org.petitparser.context.Result} objects on every transition. This
 * test measures exact allocated bytes with the JVM's thread-allocation
 * counters after a warm-up, asserting:
 * <ul>
 *   <li>the possessive/sequence/choice fast paths allocate nothing on top of
 *       the input string (zero-allocation target);</li>
 *   <li>the slow paths allocate measurably for the same grammars (the guard
 *       is actually sensitive to a degenerate fast path);</li>
 *   <li>greedy and lazy repeating parsers keep their fast-path allocation
 *       profile strictly below their slow-path profile.</li>
 * </ul>
 *
 * <p>This is a deliberately small, warm, repeated benchmark rather than a
 * JMH run, so it stays part of the normal {@code mvn test} execution without
 * extra dependencies or plugins.
 */
public class FastParseAllocationTest {

  private static final ThreadMXBean BEAN =
      (ThreadMXBean) ManagementFactory.getThreadMXBean();
  private static final long THREAD_ID = Thread.currentThread().getId();

  private static final String INPUT;

  static {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 64; i++) {
      builder.append('a');
    }
    INPUT = builder.toString();
  }

  @Test
  public void possessiveRepeatFastPathAllocatesNothing() {
    Parser parser = of('a').star();
    assertFastAllocatesZero(parser);
    assertSlowAllocates(parser);
  }

  @Test
  public void sequenceFastPathAllocatesNothing() {
    Parser parser = of('a').seq(of('a')).seq(of('a')).seq(of('a'));
    assertFastAllocatesZero(parser.repeat(1, 16));
    assertSlowAllocates(parser.repeat(1, 16));
  }

  @Test
  public void choiceFastPathAllocatesNothing() {
    Parser parser = of('x').or(of('y')).or(of('z')).or(of('a'));
    assertFastAllocatesZero(parser.star());
    assertSlowAllocates(parser.star());
  }

  @Test
  public void greedyFastPathAllocatesLessThanSlowPath() {
    Parser parser = of('a').starGreedy(any());
    assertFastBelowSlow(parser);
  }

  @Test
  public void lazyFastPathAllocatesLessThanSlowPath() {
    Parser parser = of('a').starLazy(any());
    assertFastBelowSlow(parser);
  }

  // ------------------------------------------------------------------

  private void assertFastAllocatesZero(Parser parser) {
    long allocated = measureFast(parser);
    assertTrue("fastParseOn allocated " + allocated
        + " bytes, expected 0", allocated == 0L);
  }

  private void assertSlowAllocates(Parser parser) {
    long allocated = measureSlow(parser);
    assertTrue("parseOn allocated only " + allocated
        + " bytes, guard not sensitive", allocated > 256L);
  }

  private void assertFastBelowSlow(Parser parser) {
    long fast = measureFast(parser);
    long slow = measureSlow(parser);
    assertTrue("fast=" + fast + " slow=" + slow, fast < slow);
    assertTrue("slow path not allocating enough: " + slow, slow > 256L);
  }

  private static long measureFast(Parser parser) {
    long min = Long.MAX_VALUE;
    for (int round = 0; round < 8; round++) {
      long start = BEAN.getThreadAllocatedBytes(THREAD_ID);
      int position = 0;
      for (int i = 0; i < 1_000; i++) {
        position = parser.fastParseOn(INPUT, 0);
      }
      long delta = BEAN.getThreadAllocatedBytes(THREAD_ID) - start;
      if (position < 0) {
        throw new AssertionError("expected success");
      }
      min = Math.min(min, delta);
    }
    return min;
  }

  private static long measureSlow(Parser parser) {
    long min = Long.MAX_VALUE;
    for (int round = 0; round < 8; round++) {
      long start = BEAN.getThreadAllocatedBytes(THREAD_ID);
      int position = 0;
      for (int i = 0; i < 1_000; i++) {
        position = parser.parseOn(new Context(INPUT, 0)).getPosition();
      }
      long delta = BEAN.getThreadAllocatedBytes(THREAD_ID) - start;
      if (position < 0) {
        throw new AssertionError("expected success");
      }
      min = Math.min(min, delta);
    }
    return min;
  }
}
