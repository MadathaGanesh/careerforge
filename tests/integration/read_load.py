"""A small latency and status-code check for an isolated development fixture."""
from concurrent.futures import ThreadPoolExecutor
from statistics import median
from time import perf_counter
import os
import requests

from smoke import API, create_dev

COUNT = 120
THREADS = 10
PATHS = ["/dashboard/summary", "/jobs/page", "/resumes/page", "/notifications/unread-count", "/subscription"]


def main():
    if os.environ.get("CF_DISPOSABLE_FIXTURE") != "true":
        raise SystemExit("Run against the disposable local fixture.")
    browser = create_dev("load-check")
    cookies = browser.cookies.get_dict()

    def read(i):
        start = perf_counter()
        response = requests.get(API + "/api/v1" + PATHS[i % len(PATHS)], cookies=cookies, timeout=10)
        return (perf_counter() - start) * 1000, response.status_code

    start = perf_counter()
    with ThreadPoolExecutor(max_workers=THREADS) as pool:
        timings = list(pool.map(read, range(COUNT)))
    elapsed = perf_counter() - start
    ordered = sorted(ms for ms, _ in timings)
    p95 = ordered[(COUNT * 95 + 99) // 100 - 1]
    success = sum(status == 200 for _, status in timings)
    print(f"{success}/{COUNT} GET requests succeeded, {THREADS} workers, "
          f"median={median(ordered):.1f}ms, p95={p95:.1f}ms, throughput={COUNT/elapsed:.1f}/s")
    assert success == COUNT
    assert p95 < 3000, "Local fixture p95 exceeded 3 seconds"


if __name__ == "__main__":
    main()
