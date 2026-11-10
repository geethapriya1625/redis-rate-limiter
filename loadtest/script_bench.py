"""Exercise token_bucket.lua directly against a real Redis.

1. correctness: burst, refill, retry-after
2. atomicity:   N threads hammer one bucket; exactly `capacity` requests may pass
3. throughput:  sustained EVALSHA calls across many keys from parallel processes

Usage: python loadtest/script_bench.py [--port 6379] [--seconds 10] [--procs 4]
"""

import argparse
import multiprocessing as mp
import statistics
import threading
import time
import uuid
from pathlib import Path

import redis

SCRIPT = (Path(__file__).resolve().parents[1] / "src/main/resources/scripts/token_bucket.lua").read_text()


def correctness(r: redis.Redis) -> None:
    sha = r.script_load(SCRIPT)
    key = f"test:{uuid.uuid4()}"
    results = [r.evalsha(sha, 1, key, 5, 10, 1) for _ in range(7)]
    allowed = [x[0] for x in results]
    assert allowed == [1, 1, 1, 1, 1, 0, 0], allowed
    assert results[4][1] == 0, results[4]
    assert 0 < results[5][2] <= 100, f"retry_after should be <=100ms at 10 tok/s, got {results[5][2]}"
    time.sleep(0.25)  # 10 tok/s * 0.25 s = 2.5 tokens
    after = [r.evalsha(sha, 1, key, 5, 10, 1)[0] for _ in range(3)]
    assert after == [1, 1, 0], after
    ttl = r.pttl(key)
    assert 0 < ttl <= 1000 + 500, ttl
    print("correctness: burst of 5 then deny, refill 2 tokens in 250 ms, TTL set  ✓")


def atomicity(port: int, capacity: int = 50, threads: int = 32, per_thread: int = 20) -> None:
    key = f"race:{uuid.uuid4()}"
    passed = 0
    lock = threading.Lock()
    barrier = threading.Barrier(threads)

    def worker():
        nonlocal passed
        c = redis.Redis(port=port)
        sha = c.script_load(SCRIPT)
        barrier.wait()
        mine = 0
        for _ in range(per_thread):
            # refill rate is tiny so nothing refills during the race
            mine += c.evalsha(sha, 1, key, capacity, 0.001, 1)[0]
        with lock:
            passed += mine

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    total = threads * per_thread
    assert passed == capacity, f"expected exactly {capacity} of {total} to pass, got {passed}"
    print(f"atomicity:   {threads} threads x {per_thread} calls on one bucket (cap {capacity}) -> exactly {passed} allowed  ✓")


def naive_race(port: int, capacity: int = 50, threads: int = 32, per_thread: int = 20) -> None:
    """The same race with a read-modify-write done in the client instead of in Lua."""
    key = f"naive:{uuid.uuid4()}"
    redis.Redis(port=port).set(key, capacity)
    passed = 0
    lock = threading.Lock()
    barrier = threading.Barrier(threads)

    def worker():
        nonlocal passed
        c = redis.Redis(port=port)
        barrier.wait()
        mine = 0
        for _ in range(per_thread):
            tokens = int(c.get(key))
            if tokens > 0:
                c.set(key, tokens - 1)
                mine += 1
        with lock:
            passed += mine

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    print(f"naive GET/SET in the client, same race -> {passed} allowed (should be {capacity})")


def _bench_proc(port: int, seconds: float, q: mp.Queue) -> None:
    c = redis.Redis(port=port)
    sha = c.script_load(SCRIPT)
    keys = [f"bench:{uuid.uuid4()}" for _ in range(1000)]
    lat = []
    n = 0
    end = time.perf_counter() + seconds
    while time.perf_counter() < end:
        t0 = time.perf_counter()
        c.evalsha(sha, 1, keys[n % 1000], 100, 50, 1)
        lat.append((time.perf_counter() - t0) * 1e6)
        n += 1
    q.put((n, lat))


def throughput(port: int, seconds: float, procs: int) -> None:
    q: mp.Queue = mp.Queue()
    ps = [mp.Process(target=_bench_proc, args=(port, seconds, q)) for _ in range(procs)]
    for p in ps:
        p.start()
    results = [q.get() for _ in ps]
    for p in ps:
        p.join()
    total = sum(n for n, _ in results)
    lat = sorted(x for _, l in results for x in l)
    p50 = lat[len(lat) // 2]
    p99 = lat[int(len(lat) * 0.99)]
    print(
        f"throughput:  {procs} client processes, {seconds:.0f}s, 1,000 keys -> "
        f"{total / seconds:,.0f} decisions/s, round-trip p50 {p50:.0f} µs, p99 {p99:.0f} µs "
        f"(mean {statistics.fmean(lat):.0f} µs)"
    )


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=6379)
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--procs", type=int, default=4)
    args = ap.parse_args()
    r = redis.Redis(port=args.port)
    correctness(r)
    atomicity(args.port)
    naive_race(args.port)
    throughput(args.port, args.seconds, args.procs)
