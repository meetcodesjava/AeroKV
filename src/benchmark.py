import socket
import time
import threading

HOST = "localhost"
PORT = 8080
TOTAL_REQUESTS = 1000  # Number of operations
NUM_THREADS = 10       # Concurrent client connections

def send_command(cmd):
    """Sends a single TCP command to AeroKV."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.connect((HOST, PORT))
            s.sendall((cmd + "\n").encode('utf-8'))
            return s.recv(1024).decode('utf-8').strip()
    except Exception as e:
        return f"ERR:{e}"

def worker_task(thread_id, requests_per_thread, results):
    """Worker task sending SET and GET commands concurrently."""
    success_count = 0
    for i in range(requests_per_thread):
        key = f"bench_key_{thread_id}_{i}"
        val = f"value_{i}"
        
        # 1. SET
        res1 = send_command(f"SET, {key}, {val}, 60000")
        # 2. GET
        res2 = send_command(f"GET, {key}")
        
        if res1 == "OK" and res2.startswith("VALUE"):
            success_count += 2

    results[thread_id] = success_count

if __name__ == "__main__":
    print(f"***Starting AeroKV Load Test: {TOTAL_REQUESTS * 2} ops across {NUM_THREADS} threads...")
    
    threads = []
    results = {}
    requests_per_thread = TOTAL_REQUESTS // NUM_THREADS

    start_time = time.time()

    for i in range(NUM_THREADS):
        t = threading.Thread(target=worker_task, args=(i, requests_per_thread, results))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    end_time = time.time()
    total_time = end_time - start_time
    total_ops = sum(results.values())
    ops_per_sec = total_ops / total_time if total_time > 0 else 0

    print("--------------------------------------------------")
    print(f"***Load Test Completed in {total_time:.2f} seconds")
    print(f"***Total Successful Ops: {total_ops}")
    print(f"***Throughput: {ops_per_sec:.2f} ops/sec")
    print("--------------------------------------------------")