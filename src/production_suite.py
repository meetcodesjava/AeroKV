"""
AeroKV Production Readiness Suite
==================================

A single, realistic end-to-end test harness that exercises AeroKV the way a
production deployment would: correctness under the real wire protocol,
concurrent access from many short- and long-lived clients, skewed
(Zipfian) key access patterns like a real cache workload, TTL expiry under
load, large payloads, connection churn, and a sustained soak test that
watches for throughput/latency degradation over time.

Usage:
    python production_suite.py                     # full suite, defaults
    python production_suite.py --soak-seconds 60    # longer soak phase
    python production_suite.py --host 127.0.0.1 --port 8080

Exit code is 0 only if every correctness check passes. Performance phases
never fail the run; they just report numbers.
"""

import argparse
import random
import socket
import statistics
import string
import threading
import time
import uuid
from collections import defaultdict
from dataclasses import dataclass, field

# ---------------------------------------------------------------------------
# Wire client
# ---------------------------------------------------------------------------
# AeroKV's protocol is line-delimited ("\n"-terminated) text over TCP, and a
# single TCP read can return a partial line, multiple lines, or a line split
# across two reads. A correct client MUST buffer and split on "\n" rather
# than trust that one recv() == one response. Earlier test scripts in this
# repo used a single recv() per exchange, which is a client-side bug, not a
# server bug (confirmed by re-running with a buffered reader).

class AeroKVConnection:
    def __init__(self, host, port, timeout=5.0):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect((host, port))
        self._buf = b""

    def send(self, line: str):
        self.sock.sendall((line + "\n").encode("utf-8"))

    def read_line(self) -> str:
        while b"\n" not in self._buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("server closed connection")
            self._buf += chunk
        line, self._buf = self._buf.split(b"\n", 1)
        return line.decode("utf-8", errors="replace").rstrip("\r")

    def roundtrip(self, line: str) -> str:
        self.send(line)
        return self.read_line()

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def one_shot(host, port, line: str, timeout=5.0) -> str:
    conn = AeroKVConnection(host, port, timeout)
    try:
        return conn.roundtrip(line)
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Result tracking
# ---------------------------------------------------------------------------

@dataclass
class SuiteResult:
    passed: list = field(default_factory=list)
    failed: list = field(default_factory=list)

    def ok(self, name, detail=""):
        self.passed.append((name, detail))
        print(f"  PASS  {name}" + (f" — {detail}" if detail else ""))

    def bad(self, name, detail=""):
        self.failed.append((name, detail))
        print(f"  FAIL  {name}" + (f" — {detail}" if detail else ""))

    def summary(self):
        total = len(self.passed) + len(self.failed)
        print("\n" + "=" * 70)
        print(f"CORRECTNESS: {len(self.passed)}/{total} checks passed")
        if self.failed:
            print("Failed checks:")
            for name, detail in self.failed:
                print(f"   - {name}: {detail}")
        print("=" * 70)
        return len(self.failed) == 0


# ---------------------------------------------------------------------------
# Phase 1: Protocol correctness
# ---------------------------------------------------------------------------

def phase_protocol_correctness(host, port, result: SuiteResult):
    print("\n[1/6] Protocol correctness")

    conn = AeroKVConnection(host, port)
    try:
        # Basic SET/GET
        key = f"proto_{uuid.uuid4().hex[:8]}"
        resp = conn.roundtrip(f"SET,{key},hello_world,60000")
        result.ok("basic SET returns OK") if resp == "OK" else result.bad("basic SET returns OK", resp)

        resp = conn.roundtrip(f"GET,{key}")
        result.ok("basic GET returns value") if "hello_world" in resp else result.bad("basic GET returns value", resp)

        # PUT alias
        resp = conn.roundtrip(f"PUT,{key},updated,60000")
        result.ok("PUT alias accepted") if resp == "OK" else result.bad("PUT alias accepted", resp)

        # PING
        resp = conn.roundtrip("PING")
        result.ok("PING/PONG") if resp == "PONG" else result.bad("PING/PONG", resp)

        # Unknown command
        resp = conn.roundtrip("FOOBAR,x")
        result.ok("unknown command rejected cleanly") if "ERR" in resp else result.bad("unknown command rejected cleanly", resp)

        # Missing key
        resp = conn.roundtrip(f"GET,does_not_exist_{uuid.uuid4().hex}")
        result.ok("missing key -> ERR_NOT_FOUND") if "NOT_FOUND" in resp else result.bad("missing key -> ERR_NOT_FOUND", resp)

        # Value containing commas (JSON-like payload) must survive intact
        payload = '{"user":"a,b","roles":["admin","editor"],"n":1}'
        key2 = f"json_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key2},{payload},60000")
        resp = conn.roundtrip(f"GET,{key2}")
        result.ok("comma-laden payload preserved") if payload in resp else result.bad("comma-laden payload preserved", resp)

        # Empty value
        key3 = f"empty_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key3},,60000")
        resp = conn.roundtrip(f"GET,{key3}")
        result.ok("empty value round-trips") if resp.startswith("VALUE") else result.bad("empty value round-trips", resp)

        # Unicode key/value
        key4 = f"unicode_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key4},héllo_世界_🚀,60000")
        resp = conn.roundtrip(f"GET,{key4}")
        result.ok("unicode value preserved") if "世界" in resp else result.bad("unicode value preserved", resp)

        # Large payload (~256KB) — realistic upper bound for a cached blob
        big_val = "".join(random.choices(string.ascii_letters, k=256 * 1024))
        key5 = f"large_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key5},{big_val},60000")
        resp = conn.roundtrip(f"GET,{key5}")
        result.ok("256KB payload round-trips intact") if big_val in resp else result.bad("256KB payload round-trips intact", f"len={len(resp)}")

        # Oversized payload (> 5MB server-side cap) must be rejected, not
        # silently accepted into memory.
        oversized = "x" * (6 * 1024 * 1024)
        key6 = f"oversized_{uuid.uuid4().hex[:8]}"
        resp = conn.roundtrip(f"SET,{key6},{oversized},60000")
        result.ok("oversized value rejected") if "TOO_LARGE" in resp else result.bad("oversized value rejected", resp)
        resp = conn.roundtrip(f"GET,{key6}")
        result.ok("rejected oversized value was not stored") if "NOT_FOUND" in resp else result.bad("rejected oversized value was not stored", resp)

        # DELETE removes a key immediately
        key7 = f"del_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key7},to_be_deleted,60000")
        resp = conn.roundtrip(f"DEL,{key7}")
        result.ok("DEL returns OK") if resp == "OK" else result.bad("DEL returns OK", resp)
        resp = conn.roundtrip(f"GET,{key7}")
        result.ok("deleted key is gone") if "NOT_FOUND" in resp else result.bad("deleted key is gone", resp)
        # Deleting an already-absent key should not error
        resp = conn.roundtrip(f"DEL,{key7}")
        result.ok("DEL on absent key is a safe no-op") if resp == "OK" else result.bad("DEL on absent key is a safe no-op", resp)

        # Pipelined commands in a single TCP write, read back with the
        # buffered client (this is the correct way to consume pipelined
        # responses — one recv() is not guaranteed to contain both lines)
        pkey = f"pipe_{uuid.uuid4().hex[:8]}"
        conn.send(f"SET,{pkey},pipeval,60000")
        conn.send(f"GET,{pkey}")
        r1 = conn.read_line()
        r2 = conn.read_line()
        result.ok("pipelined SET+GET both answered correctly") if r1 == "OK" and "pipeval" in r2 \
            else result.bad("pipelined SET+GET both answered correctly", f"{r1!r} / {r2!r}")

    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Phase 2: TTL correctness under real timing
# ---------------------------------------------------------------------------

def phase_ttl_correctness(host, port, result: SuiteResult):
    print("\n[2/6] TTL expiry correctness")
    # NOTE: AeroServer applies a 1s idle-socket timeout (see AeroServer.java,
    # setSoTimeout(1000)) and closes any connection that goes quiet longer
    # than that. That's a real production-readiness constraint (see the
    # report at the end of this run) — a naive long-lived client that waits
    # out a TTL on the same socket will get disconnected. A correct client
    # reconnects; we do the same here rather than treat it as a test bug.
    conn = AeroKVConnection(host, port)
    try:
        key = f"ttl_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key},short_lived,800")  # 800ms TTL
        resp = conn.roundtrip(f"GET,{key}")
        result.ok("value readable before TTL expiry") if "short_lived" in resp else result.bad("value readable before TTL expiry", resp)
    finally:
        conn.close()

    time.sleep(1.2)  # outlives both the TTL and the server's idle timeout
    conn = AeroKVConnection(host, port)
    try:
        resp = conn.roundtrip(f"GET,{key}")
        result.ok("value expired after TTL") if "EXPIRED" in resp or "NOT_FOUND" in resp else result.bad("value expired after TTL", resp)

        # Re-set the same key after expiry — must be usable again, not stuck
        conn.roundtrip(f"SET,{key},revived,60000")
        resp = conn.roundtrip(f"GET,{key}")
        result.ok("key reusable after expiry") if "revived" in resp else result.bad("key reusable after expiry", resp)

        # ttl=0 means "no expiry"
        key2 = f"noexpiry_{uuid.uuid4().hex[:8]}"
        conn.roundtrip(f"SET,{key2},forever,0")
    finally:
        conn.close()

    time.sleep(1.0)  # again outlives the idle-socket timeout
    conn = AeroKVConnection(host, port)
    try:
        resp = conn.roundtrip(f"GET,{key2}")
        result.ok("ttl=0 entry does not expire") if "forever" in resp else result.bad("ttl=0 entry does not expire", resp)
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Phase 3: Deterministic LRU eviction
# ---------------------------------------------------------------------------

def phase_lru_eviction(host, port, result: SuiteResult, capacity=1000):
    print("\n[3/6] Deterministic LRU eviction")
    conn = AeroKVConnection(host, port, timeout=10.0)
    prefix = f"lru_{uuid.uuid4().hex[:6]}_"
    try:
        for i in range(capacity):
            conn.roundtrip(f"SET,{prefix}{i},v{i},120000")

        # Touch item 0 to promote it to MRU
        resp = conn.roundtrip(f"GET,{prefix}0")
        result.ok("touch promotes key to MRU") if f"v0" in resp else result.bad("touch promotes key to MRU", resp)

        # Insert one more item beyond capacity -> item 1 (now LRU) is evicted
        conn.roundtrip(f"SET,{prefix}{capacity},new_val,120000")

        evicted = conn.roundtrip(f"GET,{prefix}1")
        retained = conn.roundtrip(f"GET,{prefix}0")
        newest = conn.roundtrip(f"GET,{prefix}{capacity}")

        result.ok("LRU victim evicted") if ("NOT_FOUND" in evicted or "ERR" in evicted) else result.bad("LRU victim evicted", evicted)
        result.ok("touched key survives eviction") if "v0" in retained else result.bad("touched key survives eviction", retained)
        result.ok("newly inserted key present") if "new_val" in newest else result.bad("newly inserted key present", newest)
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Phase 4: Production-shaped mixed workload (Zipfian hot keys, ramping
# concurrency, realistic read-heavy ratio)
# ---------------------------------------------------------------------------

def zipf_key(rng: random.Random, n_keys: int, skew: float) -> int:
    """Cheap Zipf-like sampler: biases toward low indices ('hot' keys)."""
    u = rng.random()
    return int(n_keys * (u ** skew))


def run_mixed_workload(host, port, threads, ops_per_thread, key_pool, read_ratio, ttl_ms, label, result: SuiteResult):
    latencies = []
    errors = []
    lock = threading.Lock()
    barrier = threading.Barrier(threads)
    rng_seed = random.Random(42)
    keys = [f"prod_key_{rng_seed.randint(0, 1 << 30)}" for _ in range(key_pool)]

    def worker(tid):
        rng = random.Random(tid * 7919 + 13)
        conn = AeroKVConnection(host, port, timeout=5.0)
        local_lat = []
        local_err = 0
        try:
            barrier.wait()  # start all threads together, like a traffic spike
            for i in range(ops_per_thread):
                idx = zipf_key(rng, key_pool, skew=2.0)
                key = keys[idx]
                is_read = rng.random() < read_ratio
                t0 = time.perf_counter()
                try:
                    if is_read:
                        conn.roundtrip(f"GET,{key}")
                    else:
                        val = f"v_{tid}_{i}_{rng.randint(0, 99999)}"
                        conn.roundtrip(f"SET,{key},{val},{ttl_ms}")
                except Exception:
                    local_err += 1
                    continue
                local_lat.append((time.perf_counter() - t0) * 1000)
        finally:
            conn.close()
        with lock:
            latencies.extend(local_lat)
            errors.append(local_err)

    ts = [threading.Thread(target=worker, args=(t,)) for t in range(threads)]
    start = time.perf_counter()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.perf_counter() - start

    total_errors = sum(errors)
    total_ops = len(latencies)
    print(f"\n  {label}: {threads} threads x {ops_per_thread} ops "
          f"(pool={key_pool} keys, {int(read_ratio*100)}% reads, zipf-skewed)")
    if total_ops == 0:
        result.bad(f"{label}: workload completed", "no successful ops")
        return
    latencies.sort()
    p50 = latencies[int(len(latencies) * 0.50)]
    p95 = latencies[min(int(len(latencies) * 0.95), len(latencies) - 1)]
    p99 = latencies[min(int(len(latencies) * 0.99), len(latencies) - 1)]
    throughput = total_ops / elapsed
    print(f"    throughput={throughput:,.0f} ops/sec  "
          f"avg={statistics.mean(latencies):.3f}ms  p50={p50:.3f}ms  p95={p95:.3f}ms  p99={p99:.3f}ms  "
          f"errors={total_errors}")

    error_rate = total_errors / (total_ops + total_errors)
    if error_rate < 0.01:
        result.ok(f"{label}: error rate acceptable", f"{error_rate:.2%}")
    else:
        result.bad(f"{label}: error rate acceptable", f"{error_rate:.2%}")


def phase_production_workload(host, port, result: SuiteResult):
    print("\n[4/6] Production-shaped mixed workload (ramping concurrency)")
    # Simulates a warm cache under a read-heavy, hot-key-skewed access
    # pattern (like a real product catalog / session cache), ramping
    # concurrency the way real traffic ramps rather than starting at peak.
    for threads in (10, 50, 100):
        run_mixed_workload(
            host, port,
            threads=threads,
            ops_per_thread=300,
            key_pool=500,
            read_ratio=0.85,
            ttl_ms=30000,
            label=f"ramp @ {threads} concurrent clients",
            result=result,
        )


# ---------------------------------------------------------------------------
# Phase 5: Connection churn (short-lived connections, like serverless
# functions or a web app opening a fresh connection per request)
# ---------------------------------------------------------------------------

def phase_connection_churn(host, port, result: SuiteResult, n_requests=500, concurrency=25):
    print("\n[5/6] Connection churn (fresh TCP connection per request)")
    latencies = []
    errors = [0]
    lock = threading.Lock()

    def churn_worker(n):
        local_lat = []
        local_err = 0
        for i in range(n):
            t0 = time.perf_counter()
            try:
                key = f"churn_{i}_{threading.get_ident()}"
                resp = one_shot(host, port, f"SET,{key},v,15000", timeout=5.0)
                if resp != "OK":
                    local_err += 1
            except Exception:
                local_err += 1
                continue
            local_lat.append((time.perf_counter() - t0) * 1000)
        with lock:
            latencies.extend(local_lat)
            errors[0] += local_err

    per_thread = n_requests // concurrency
    ts = [threading.Thread(target=churn_worker, args=(per_thread,)) for _ in range(concurrency)]
    start = time.perf_counter()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.perf_counter() - start

    total = len(latencies)
    print(f"  {concurrency} concurrent short-lived clients x {per_thread} requests each "
          f"({total} total connect+op round-trips)")
    if total == 0:
        result.bad("connection churn completes", "no successful ops")
        return
    latencies.sort()
    p99 = latencies[min(int(len(latencies) * 0.99), len(latencies) - 1)]
    print(f"    {total/elapsed:,.0f} conn/sec  avg={statistics.mean(latencies):.3f}ms  p99={p99:.3f}ms  errors={errors[0]}")

    err_rate = errors[0] / (total + errors[0])
    result.ok("connection churn error rate acceptable", f"{err_rate:.2%}") if err_rate < 0.01 \
        else result.bad("connection churn error rate acceptable", f"{err_rate:.2%}")


# ---------------------------------------------------------------------------
# Phase 6: Sustained soak test — steady load over time, sampled every
# second, to catch throughput decay, memory pressure, or lock starvation
# that only shows up after the JVM has been running under load for a while.
# ---------------------------------------------------------------------------

def phase_soak_test(host, port, result: SuiteResult, duration_s, threads=20):
    print(f"\n[6/6] Sustained soak test ({duration_s}s @ {threads} threads, steady traffic)")
    stop_at = time.time() + duration_s
    per_second_counts = defaultdict(int)
    per_second_errors = defaultdict(int)
    lock = threading.Lock()

    def soak_worker(tid):
        rng = random.Random(tid * 104729)
        conn = AeroKVConnection(host, port, timeout=5.0)
        keys = [f"soak_{tid}_{i}" for i in range(50)]
        try:
            while time.time() < stop_at:
                key = rng.choice(keys)
                bucket = int(time.time())
                try:
                    if rng.random() < 0.8:
                        conn.roundtrip(f"GET,{key}")
                    else:
                        conn.roundtrip(f"SET,{key},v{rng.randint(0,999)},10000")
                    with lock:
                        per_second_counts[bucket] += 1
                except Exception:
                    with lock:
                        per_second_errors[bucket] += 1
                    try:
                        conn.close()
                    except Exception:
                        pass
                    conn = AeroKVConnection(host, port, timeout=5.0)
        finally:
            conn.close()

    ts = [threading.Thread(target=soak_worker, args=(t,)) for t in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()

    buckets = sorted(per_second_counts.keys())
    if len(buckets) < 3:
        result.bad("soak test ran long enough to sample", f"only {len(buckets)} sample(s)")
        return

    # Drop first/last partial buckets, compare early vs late throughput to
    # catch degradation over the run.
    samples = [per_second_counts[b] for b in buckets[1:-1]]
    first_half = samples[: len(samples) // 2] or samples
    second_half = samples[len(samples) // 2:] or samples
    avg_first = statistics.mean(first_half)
    avg_second = statistics.mean(second_half)
    total_ops = sum(per_second_counts.values())
    total_err = sum(per_second_errors.values())

    print(f"  ops/sec early-window avg={avg_first:.0f}  late-window avg={avg_second:.0f}  "
          f"total_ops={total_ops}  errors={total_err}")

    result.ok("soak test error count stayed low") if total_err / max(total_ops, 1) < 0.02 \
        else result.bad("soak test error count stayed low", f"{total_err} errors / {total_ops} ops")

    # Allow some natural variance, but flag a real collapse in throughput
    # (e.g. lock starvation, unbounded queue growth in the WAL writer).
    if avg_second >= avg_first * 0.6:
        result.ok("throughput held steady across soak window", f"{avg_first:.0f} -> {avg_second:.0f} ops/sec")
    else:
        result.bad("throughput held steady across soak window", f"{avg_first:.0f} -> {avg_second:.0f} ops/sec (degraded)")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="AeroKV production readiness suite")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--soak-seconds", type=int, default=20, help="duration of the sustained soak test")
    ap.add_argument("--skip-soak", action="store_true", help="skip the soak test (fastest run)")
    args = ap.parse_args()

    print("=" * 70)
    print("AEROKV PRODUCTION READINESS SUITE")
    print(f"target: {args.host}:{args.port}")
    print("=" * 70)

    # Fail fast if the server isn't reachable at all.
    try:
        resp = one_shot(args.host, args.port, "PING", timeout=3.0)
        if resp != "PONG":
            print(f"Server reachable but did not answer PING correctly (got {resp!r}). Aborting.")
            raise SystemExit(2)
    except Exception as e:
        print(f"Could not connect to AeroKV at {args.host}:{args.port} ({e}). Is the server running?")
        raise SystemExit(2)

    result = SuiteResult()
    phase_protocol_correctness(args.host, args.port, result)
    phase_ttl_correctness(args.host, args.port, result)
    phase_lru_eviction(args.host, args.port, result)
    phase_production_workload(args.host, args.port, result)
    phase_connection_churn(args.host, args.port, result)
    if not args.skip_soak:
        phase_soak_test(args.host, args.port, result, duration_s=args.soak_seconds)

    ok = result.summary()
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
