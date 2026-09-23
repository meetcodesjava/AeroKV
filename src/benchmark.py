import socket
import time
import threading

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 8080
TOTAL_OPS = 2000
NUM_THREADS = 10
OPS_PER_THREAD = TOTAL_OPS // NUM_THREADS

def worker(thread_id, results):
    success_count = 0
    try:
        # Har thread sirf EK baar connection banayega
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((SERVER_HOST, SERVER_PORT))
        
        for i in range(OPS_PER_THREAD):
            key = f"key_{thread_id}_{i}"
            val = f"val_{i}"
            # Single persistent connection se send aur receive
            msg = f"SET,{key},{val},60000\n"
            s.sendall(msg.encode())
            resp = s.recv(1024).decode()
            if "OK" in resp:
                success_count += 1
                
        s.close()
    except Exception as e:
        print(f"Error in thread {thread_id}: {e}")
        
    results.append(success_count)

def run_benchmark():
    print(f"*** Starting AeroKV Persistent Load Test: {TOTAL_OPS} ops across {NUM_THREADS} threads...")
    threads = []
    results = []
    
    start_time = time.time()
    
    for t_id in range(NUM_THREADS):
        t = threading.Thread(target=worker, args=(t_id, results))
        threads.append(t)
        t.start()
        
    for t in threads:
        t.join()
        
    total_time = time.time() - start_time
    total_successful = sum(results)
    ops_per_sec = total_successful / total_time
    
    print("-" * 50)
    print(f"*** Load Test Completed in {total_time:.2f} seconds")
    print(f"*** Total Successful Ops: {total_successful}")
    print(f"*** Throughput: {ops_per_sec:.2f} ops/sec")
    print("-" * 50)

if __name__ == "__main__":
    run_benchmark()