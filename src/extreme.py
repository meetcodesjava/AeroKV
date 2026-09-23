import socket
import time
import threading
import random
import statistics

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 8080

TOTAL_THREADS = 50
OPS_PER_THREAD = 1000  # Total = 50,000 operations
KEY_RANGE = 2000       # Chhota key range = Zyada lock contention

latencies = []
latency_lock = threading.Lock()

def extreme_worker(thread_id):
    thread_latencies = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((SERVER_HOST, SERVER_PORT))
        
        for i in range(OPS_PER_THREAD):
            key = f"key_{random.randint(1, KEY_RANGE)}"
            
            # 30% Writes (SET), 70% Reads (GET)
            if random.random() < 0.30:
                cmd = f"SET,{key},val_{thread_id}_{i},60000\n"
            else:
                cmd = f"GET,{key}\n"

            t0 = time.perf_counter()
            s.sendall(cmd.encode())
            s.recv(1024)
            t1 = time.perf_counter()

            thread_latencies.append((t1 - t0) * 1000)  # milliseconds

        s.close()
    except Exception as e:
        print(f"Thread {thread_id} crashed: {e}")

    with latency_lock:
        latencies.extend(thread_latencies)

def main():
    print(f" Starting AeroKV Extreme Stress Test...")
    print(f" Threads: {TOTAL_THREADS} | Ops/Thread: {OPS_PER_THREAD} | Total Ops: {TOTAL_THREADS * OPS_PER_THREAD}")
    print(f" Key Pool: {KEY_RANGE} keys (High Lock Contention)")
    print("-" * 55)

    threads = []
    start_total = time.perf_counter()

    for tid in range(TOTAL_THREADS):
        t = threading.Thread(target=extreme_worker, args=(tid,))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    total_time = time.perf_counter() - start_total
    total_ops = len(latencies)

    if total_ops == 0:
        print(" No successful operations recorded.")
        return

    latencies.sort()
    p50 = latencies[int(len(latencies) * 0.50)]
    p95 = latencies[int(len(latencies) * 0.95)]
    p99 = latencies[int(len(latencies) * 0.99)]
    avg_latency = statistics.mean(latencies)
    throughput = total_ops / total_time

    print(f" Completed {total_ops} ops in {total_time:.2f}s")
    print(f" Throughput:       {throughput:.2f} ops/sec")
    print(f" Avg Latency:     {avg_latency:.2f} ms")
    print(f" P50 (Median):     {p50:.2f} ms")
    print(f" P95 Latency:     {p95:.2f} ms")
    print(f" P99 (Tail Lat):  {p99:.2f} ms")
    print("-" * 55)

if __name__ == "__main__":
    main()