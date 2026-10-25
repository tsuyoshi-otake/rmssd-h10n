#!/usr/bin/env python3
"""Mutation testing for the Android BLE connection-state classes.

Mutates BleConnectionRetry and BleStageReporter one change at a time and runs their unit,
stateful PBT and model-check tests against each mutant. A mutant is killed when a test
fails or times out; one that does not compile is stillborn and not counted. Survivors are
printed with their line so each can be killed by a new test or argued equivalent.

Standard library only; it needs a JDK (JAVA_HOME or javac on PATH) and the JUnit 4 jars
already in the Gradle cache (`./gradlew :app:testDebugUnitTest` downloads them).

    python tools/ble_mutation.py            # from app/android
    python tools/ble_mutation.py --jobs 4 --only BleStageReporter
"""
import argparse
import concurrent.futures
import glob
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
PKG = "dev/otake/rmssdh10n"
MAIN = os.path.join(ANDROID, "app/src/main/java", PKG)
TEST = os.path.join(ANDROID, "app/src/test/java", PKG)
TARGETS = ["BleConnectionRetry", "BleStageReporter"]
# Cheapest first: a mutant stops at the first failing class.
TESTS = ["BleConnectionRetryTest", "BleStageReporterTest", "BleConnectionRetryStatefulTest",
         "BleStageReporterStatefulTest", "BleLinkModelCheckTest"]
SUPPORT = ["StatefulPbt"]
HEAP = "-Xmx3g"   # per mutant JVM; the model check holds its visited states in memory

RUNNER = """package dev.otake.rmssdh10n;
public class MutationRunner {
    public static void main(String[] names) throws Exception {
        for (String name : names) {
            org.junit.runner.Result r = org.junit.runner.JUnitCore.runClasses(Class.forName(name));
            if (!r.wasSuccessful()) {
                System.out.println("KILLED " + r.getFailures().get(0).getTestHeader());
                System.exit(1);
            }
        }
        System.out.println("SURVIVED");
    }
}
"""


def jdk(tool):
    home = os.environ.get("JAVA_HOME")
    exe = tool + (".exe" if os.name == "nt" else "")
    if home and os.path.exists(os.path.join(home, "bin", exe)):
        return os.path.join(home, "bin", exe)
    found = shutil.which(tool)
    if not found:
        sys.exit("no %s: set JAVA_HOME to a JDK" % tool)
    return found


def junit_classpath():
    cache = os.path.join(os.path.expanduser("~"), ".gradle/caches/modules-2/files-2.1")
    jars = []
    for pattern in ("junit/junit/4.13.2/*/junit-4.13.2.jar", "org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar"):
        hits = glob.glob(os.path.join(cache, pattern))
        if not hits:
            sys.exit("missing %s: run ./gradlew :app:testDebugUnitTest once" % pattern)
        jars.append(hits[0])
    return jars


def code_mask(src):
    """The source with comments and string/char literals blanked, offsets preserved."""
    out, i, n = list(src), 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
        elif src[i] in "\"'":
            j, quote = i + 1, src[i]
            while j < n and src[j] != quote:
                j += 2 if src[j] == "\\" else 1
            j += 1
        else:
            i += 1
            continue
        for k in range(i, j):
            if out[k] != "\n":
                out[k] = " "
        i = j
    return "".join(out)


def closing_paren(mask, open_at):
    depth = 0
    for k in range(open_at, len(mask)):
        if mask[k] == "(":
            depth += 1
        elif mask[k] == ")":
            depth -= 1
            if depth == 0:
                return k
    return -1


SWAPS = [
    ("relational", r"(?<=\s)(==|!=|<=|>=|<|>)(?=\s)",
     {"==": "!=", "!=": "==", "<": "<=", "<=": "<", ">": ">=", ">=": ">"}),
    ("logical", r"&&|\|\|", {"&&": "||", "||": "&&"}),
    ("arithmetic", r"(?<=\s)([-+*/])(?=\s)", {"+": "-", "-": "+", "*": "/", "/": "*"}),
    ("shift", r"<<|>>", {"<<": ">>", ">>": "<<"}),
    ("boolean", r"\b(true|false)\b", {"true": "false", "false": "true"}),
    ("min_max", r"\bMath\.(min|max)\b", {"Math.min": "Math.max", "Math.max": "Math.min"}),
]
CALL_OR_ASSIGN = re.compile(r"^(return\s*$|[\w.]+\s*\(|[\w.\[\]]+\s*([-+*/]?=(?!=)|\+\+|--))")


def mutants(src):
    """(start, end, replacement, operator) for every single-site mutation."""
    mask, found = code_mask(src), []
    for op, pattern, table in SWAPS:
        for m in re.finditer(pattern, mask):
            found.append((m.start(), m.end(), table[m.group(0)], op))
    for m in re.finditer(r"!(?!=)", mask):
        found.append((m.start(), m.end(), "", "remove_not"))
    for m in re.finditer(r"(?<![\w.])\d[\d_]*L?(?![\w.])", mask):
        value = int(m.group(0).rstrip("L").replace("_", ""))
        suffix = "L" if m.group(0).endswith("L") else ""
        found.append((m.start(), m.end(), str(value + 1) + suffix, "constant"))
    for m in re.finditer(r"\b(if|while)\s*\(", mask):
        close = closing_paren(mask, m.end() - 1)
        if close > 0:
            cond = src[m.end():close]
            found.append((m.end(), close, "!(" + cond + ")", "negate_condition"))
    for semi in [k for k, c in enumerate(mask) if c == ";"]:
        start, depth = semi - 1, 0
        while start >= 0:
            c = mask[start]
            if c == ")":
                depth += 1
            elif c == "(":
                if depth == 0:
                    break  # a for-header clause, not a statement
                depth -= 1
            elif c in ";{}" and depth == 0:
                break
            start -= 1
        if start >= 0 and mask[start] == "(":
            continue
        stmt = mask[start + 1:semi].strip()
        if CALL_OR_ASSIGN.match(stmt):
            begin = start + 1 + (len(mask[start + 1:semi]) - len(mask[start + 1:semi].lstrip()))
            found.append((begin, semi + 1, ";", "delete_statement"))
    return sorted(set(found))


def run(cmd, timeout):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
        return p.returncode, p.stdout + p.stderr
    except subprocess.TimeoutExpired:
        return None, "timeout"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--jobs", type=int, default=max(1, min(8, (os.cpu_count() or 2) // 2)))
    ap.add_argument("--only", choices=TARGETS, action="append", help="mutate only this class")
    ap.add_argument("--work", default=os.path.join(ANDROID, "build/ble-mutation"))
    ap.add_argument("--min-score", type=float, default=0.0, help="fail below this kill ratio (0-1)")
    args = ap.parse_args()
    javac, java = jdk("javac"), jdk("java")
    jars = junit_classpath()
    sep = os.pathsep

    shutil.rmtree(args.work, ignore_errors=True)
    base = os.path.join(args.work, "base")
    os.makedirs(base)
    runner = os.path.join(args.work, "MutationRunner.java")
    with open(runner, "w", encoding="utf-8") as f:
        f.write(RUNNER)
    sources = [os.path.join(MAIN, t + ".java") for t in TARGETS] + \
              [os.path.join(TEST, t + ".java") for t in TESTS + SUPPORT] + [runner]
    javac_args = ["-J-Duser.language=en", "-encoding", "UTF-8", "-nowarn", "-proc:none"]
    code, out = run([javac] + javac_args + ["-d", base, "-cp", sep.join(jars)] + sources, 300)
    if code != 0:
        sys.exit("baseline does not compile:\n" + out)
    test_names = ["dev.otake.rmssdh10n." + t for t in TESTS]
    started = time.time()
    code, out = run([java, HEAP, "-cp", sep.join([base] + jars), "dev.otake.rmssdh10n.MutationRunner"] + test_names, 600)
    if code != 0:
        sys.exit("baseline tests fail:\n" + out)
    timeout = max(60.0, 6 * (time.time() - started))
    print("baseline green in %.1fs; mutant timeout %.0fs" % (time.time() - started, timeout), flush=True)

    jobs = []
    for target in args.only or TARGETS:
        with open(os.path.join(MAIN, target + ".java"), encoding="utf-8") as f:
            src = f.read()
        for start, end, repl, op in mutants(src):
            line = src.count("\n", 0, start) + 1
            jobs.append((target, len(jobs), op, line, src[start:end], repl, src[:start] + repl + src[end:]))

    def attempt(job):
        target, n, op, line, before, after, mutated = job
        home = os.path.join(args.work, "m%04d" % n)
        os.makedirs(os.path.join(home, "out"))
        path = os.path.join(home, target + ".java")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(mutated)
        code, _ = run([javac] + javac_args + ["-d", os.path.join(home, "out"), "-cp", sep.join([base] + jars), path], 120)
        if code != 0:
            result, detail = "stillborn", ""
        else:
            cp = sep.join([os.path.join(home, "out"), base] + jars)
            code, out = run([java, HEAP, "-cp", cp, "dev.otake.rmssdh10n.MutationRunner"] + test_names, timeout)
            if code is None:
                result, detail = "killed", "timeout"
            elif "SURVIVED" in out and code == 0:
                result, detail = "survived", ""
            else:
                m = re.search(r"KILLED (\S+)", out)
                result, detail = "killed", m.group(1) if m else "crash"
        shutil.rmtree(home, ignore_errors=True)
        return target, op, line, before, after, result, detail

    print("%d mutants, %d jobs" % (len(jobs), args.jobs), flush=True)
    results = []
    with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        for i, r in enumerate(pool.map(attempt, jobs), 1):
            results.append(r)
            if i % 25 == 0:
                print("  %d/%d" % (i, len(jobs)), flush=True)

    survivors = 0
    killed_total = 0
    for target in args.only or TARGETS:
        mine = [r for r in results if r[0] == target]
        killed = sum(1 for r in mine if r[5] == "killed")
        alive = [r for r in mine if r[5] == "survived"]
        stillborn = sum(1 for r in mine if r[5] == "stillborn")
        survivors += len(alive)
        killed_total += killed
        viable = killed + len(alive)
        print("%s: %d killed / %d viable (%.1f%%), %d stillborn" %
              (target, killed, viable, 100.0 * killed / max(1, viable), stillborn))
        for _, op, line, before, after, _, _ in sorted(alive, key=lambda r: r[2]):
            print("  SURVIVED %s.java:%d %s: %r -> %r" % (target, line, op, before.strip(), after))
    by_test = {}
    for r in results:
        if r[5] == "killed":
            name = r[6].split("(")[-1].rstrip(")").split(".")[-1] if "(" in r[6] else r[6]
            by_test[name] = by_test.get(name, 0) + 1
    print("first kill by: " + ", ".join("%s %d" % kv for kv in sorted(by_test.items(), key=lambda kv: -kv[1])))
    shutil.rmtree(args.work, ignore_errors=True)
    score = killed_total / max(1, killed_total + survivors)
    sys.exit(1 if score < args.min_score else 0)


if __name__ == "__main__":
    main()
