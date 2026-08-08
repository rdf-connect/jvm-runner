package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The gate between a connection coming up and its deadline running out.
 *
 * Tested here rather than through a server, and on purpose. What has to hold is
 * that exactly one of the two sides ever wins, including when they arrive at the
 * same instant — and the instant is the point. Driving that through a real
 * connection would mean lining a gRPC channel reaching READY up against a
 * scheduled task to within microseconds, over and over, which is a test that
 * passes for reasons nobody can name and fails on a loaded machine. Two threads
 * on a barrier hit the window every round.
 *
 * The mechanism this protects: {@code cancel(false)} does not stop a task that is
 * already running, so a READY landing after the check started but before it
 * closed the bridge used to evict a connection that had just established itself.
 */
@Timeout(60)
class EstablishmentTest {
    /**
     * Enough rounds that the barrier release lands inside the window on some of
     * them, on any machine this runs on.
     */
    private static final int ROUNDS = 20_000;

    @Test
    void theStreamComingUpFirstWins() {
        RunnerServer.Establishment establishment = new RunnerServer.Establishment();

        assertTrue(establishment.establish(), "the first caller did not win the gate");
        assertFalse(establishment.evict(), "a connection that had come up was evicted anyway");
    }

    @Test
    void theDeadlineFirstWins() {
        RunnerServer.Establishment establishment = new RunnerServer.Establishment();

        assertTrue(establishment.evict(), "the deadline did not win an uncontested gate");
        assertFalse(establishment.establish(),
                "a connection that was already being dropped reported itself established");
    }

    /** Neither side may win twice: the READY callback fires on every observation. */
    @Test
    void neitherSideWinsTwice() {
        RunnerServer.Establishment establishment = new RunnerServer.Establishment();

        assertTrue(establishment.establish());
        assertFalse(establishment.establish(), "the gate was won twice by the same side");

        RunnerServer.Establishment other = new RunnerServer.Establishment();
        assertTrue(other.evict());
        assertFalse(other.evict(), "the gate was won twice by the same side");
    }

    /**
     * The real case: both arrive at once, many times over.
     *
     * Exactly one winner per round is the whole contract — the eviction only
     * closes the bridge when its own call won, so a round with two winners is a
     * connection that came up and was dropped for not coming up, and a round with
     * none is a slot nobody ever hands back.
     */
    @Test
    void exactlyOneSideWinsWhenTheyRaceHeadOn() throws Exception {
        AtomicInteger established = new AtomicInteger();
        AtomicInteger evicted = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();

        // Three parties: the two racers and this thread, which uses the barrier to
        // hand out the next round only once both have finished the last one
        CyclicBarrier start = new CyclicBarrier(3);
        CyclicBarrier done = new CyclicBarrier(3);

        RunnerServer.Establishment[] gate = new RunnerServer.Establishment[1];

        Thread establisher = new Thread(() -> race(start, done, () -> {
            if (gate[0].establish()) {
                established.incrementAndGet();
            }
        }), "establisher");
        Thread evicter = new Thread(() -> race(start, done, () -> {
            if (gate[0].evict()) {
                evicted.incrementAndGet();
            }
        }), "evicter");

        establisher.setDaemon(true);
        evicter.setDaemon(true);
        establisher.start();
        evicter.start();

        for (int round = 0; round < ROUNDS; round++) {
            gate[0] = new RunnerServer.Establishment();
            int before = established.get() + evicted.get();

            start.await();
            done.await();

            assertEquals(before + 1, established.get() + evicted.get(),
                    "round " + round + " did not have exactly one winner");
            rounds.incrementAndGet();
        }

        establisher.interrupt();
        evicter.interrupt();

        assertEquals(ROUNDS, rounds.get());
        // Not asserting a distribution — the point is the invariant above, and a
        // scheduler that happens to favour one thread is not a failure
        assertEquals(ROUNDS, established.get() + evicted.get());
    }

    /**
     * One racer: wait for the round to start, take its shot, report that it is
     * done, and go round again until the test interrupts it.
     */
    private static void race(CyclicBarrier start, CyclicBarrier done, Runnable shot) {
        while (true) {
            try {
                start.await();
                shot.run();
                done.await();
            } catch (Exception e) {
                // The test is over and has interrupted or broken the barriers
                return;
            }
        }
    }
}
