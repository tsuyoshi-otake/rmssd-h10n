package dev.otake.rmssdh10n;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Minimal stateful property-based runner (no external dependency). Each case is a random
 *  command sequence from a seeded generator, executed against a fresh system; the executor
 *  throws AssertionError when a postcondition or invariant breaks. A failing sequence is
 *  shrunk (drop chunks, then single commands) and reported with the seed that produced it. */
final class StatefulPbt {
    interface Gen<C> { C next(Random random); }
    interface Exec<C> { void run(List<C> commands); }

    private StatefulPbt() {}

    static <C> void check(String name, long seed, int cases, int maxLength, Gen<C> gen, Exec<C> exec) {
        Random seeds = new Random(seed);
        for (int c = 0; c < cases; c++) {
            Random random = new Random(seeds.nextLong());
            int length = 1 + random.nextInt(maxLength);
            List<C> commands = new ArrayList<>(length);
            for (int i = 0; i < length; i++) commands.add(gen.next(random));
            if (failure(exec, commands) == null) continue;
            List<C> minimal = shrink(exec, commands);
            AssertionError why = failure(exec, minimal);
            throw new AssertionError(name + " failed (seed " + seed + ", case " + c + ")\n  minimal "
                    + minimal.size() + " commands: " + minimal + "\n  " + why.getMessage(), why);
        }
    }

    static <C> AssertionError failure(Exec<C> exec, List<C> commands) {
        try { exec.run(commands); return null; }
        catch (AssertionError error) { return error; }
    }

    private static <C> List<C> shrink(Exec<C> exec, List<C> commands) {
        List<C> current = commands;
        for (int chunk = Math.max(1, current.size() / 2); chunk >= 1; chunk /= 2) {
            boolean removed = true;
            while (removed) {
                removed = false;
                for (int i = 0; i + chunk <= current.size(); ) {
                    List<C> candidate = new ArrayList<>(current.subList(0, i));
                    candidate.addAll(current.subList(i + chunk, current.size()));
                    if (failure(exec, candidate) != null) { current = candidate; removed = true; }
                    else i += chunk;
                }
            }
        }
        return current;
    }
}
