import socket
import time
import threading
import json

HOST = "127.0.0.1"
PORT = 8080
CAPACITY = 1000
STRIPES = 16

def raw_client():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(3.0)
    s.connect((HOST, PORT))
    return s

def send_cmd(cmd: str, s=None) -> str:
    manage_socket = s is None
    if manage_socket:
        s = raw_client()
    try:
        s.sendall((cmd + "\n").encode())
        resp = s.recv(4096).decode()
        return resp.strip()
    finally:
        if manage_socket:
            s.close()

# ----------------------------------------------------------------------
# 1. Deterministic LRU Eviction Test
# ----------------------------------------------------------------------
def test_deterministic_lru():
    print("\n🧹 [1/5] Testing Deterministic LRU Invariant...")
    s = raw_client()
    try:
        # Fill cache to capacity (1000 items)
        for i in range(CAPACITY):
            send_cmd(f"SET,lru_test_{i},val_{i},60000", s)

        # Access lru_test_0 to promote it to Most Recently Used (MRU)
        resp_touch = send_cmd("GET,lru_test_0", s)
        assert "val_0" in resp_touch, f"Failed to read lru_test_0: {resp_touch}"

        # Insert item 1001: lru_test_1 should now be the oldest (LRU) candidate
        send_cmd(f"SET,lru_test_{CAPACITY},new_val,60000", s)

        # Assertions
        evicted_resp = send_cmd("GET,lru_test_1", s)
        promoted_resp = send_cmd("GET,lru_test_0", s)
        new_resp = send_cmd(f"GET,lru_test_{CAPACITY}", s)

        is_evicted = "NOT_FOUND" in evicted_resp or "ERR" in evicted_resp or evicted_resp == ""
        is_promoted_retained = "val_0" in promoted_resp
        is_new_retained = "new_val" in new_resp

        if is_evicted and is_promoted_retained and is_new_retained:
            print("       LRU Policy: PASS ✅ (Oldest key correctly purged; touched key preserved)")
            return True
        else:
            print(f"       LRU Policy: FAIL ❌ | Evicted key state: '{evicted_resp}', Touched key: '{promoted_resp}'")
            return False
    finally:
        s.close()

# ----------------------------------------------------------------------
# 2. TCP Stream Fragmentation & Pipelining
# ----------------------------------------------------------------------
def test_tcp_framing_and_pipelining():
    print("\n🧩 [2/5] Testing TCP Framing & Chunked Stream Delivery...")
    passed = True

    # Sub-test A: Byte-by-byte chunking
    s = raw_client()
    try:
        payload = "SET,chunk_key,chunk_val,60000\n"
        for byte_char in payload:
            s.sendall(byte_char.encode())
            time.sleep(0.005)  # Force TCP packet splitting across network stack
        resp = s.recv(1024).decode().strip()
        if "OK" not in resp:
            print(f"       Packet Chunking: FAIL ❌ (Server failed reassembly: '{resp}')")
            passed = False
        else:
            print("       Packet Chunking: PASS ✅ (Byte-stream correctly reassembled)")
    except Exception as e:
        print(f"       Packet Chunking: CRASHED ❌ ({e})")
        passed = False
    finally:
        s.close()

    # Sub-test B: Pipelined multiple commands in one TCP frame
    s = raw_client()
    try:
        batched = "SET,pipe1,val1,60000\nGET,pipe1\n"
        s.sendall(batched.encode())
        resp = s.recv(2048).decode()
        if "val1" in resp:
            print("       Pipelining:      PASS ✅ (Multiple commands in single frame processed)")
        else:
            print(f"       Pipelining:      FAIL ❌ (Response: '{resp}')")
            passed = False
    except Exception as e:
        print(f"       Pipelining: CRASHED ❌ ({e})")
        passed = False
    finally:
        s.close()

    return passed

# ----------------------------------------------------------------------
# 3. Stripe Lock Contention (Hash Collisions)
# ----------------------------------------------------------------------
def java_hash(s: str) -> int:
    """Replicates java.lang.String.hashCode()"""
    h = 0
    for c in s:
        h = (31 * h + ord(c)) & 0xFFFFFFFF
    return h if h < 0x80000000 else h - 0x100000000

def test_stripe_lock_collision():
    print("\n🔒 [3/5] Testing Stripe Lock Contention (Forced Hash Collisions)...")
    target_stripe = 3
    colliding_keys = []
    
    # Locate 8 keys that hash to the exact same stripe index
    candidate = 0
    while len(colliding_keys) < 8:
        k = f"stripe_key_{candidate}"
        if abs(java_hash(k)) % STRIPES == target_stripe:
            colliding_keys.append(k)
        candidate += 1

    errors = []
    def worker(tid):
        try:
            s = raw_client()
            for i in range(100):
                key = colliding_keys[i % len(colliding_keys)]
                set_res = send_cmd(f"SET,{key},t{tid}_val_{i},60000", s)
                get_res = send_cmd(f"GET,{key}", s)
                if not set_res.startswith("OK") or not get_res.startswith("VALUE"):
                    errors.append(f"T{tid} mismatch on {key}: SET={set_res}, GET={get_res}")
            s.close()
        except Exception as e:
            errors.append(f"T{tid} crashed: {e}")

    # 16 concurrent threads attacking the same stripe
    threads = [threading.Thread(target=worker, args=(i,)) for i in range(16)]
    for t in threads: t.start()
    for t in threads: t.join()

    if len(errors) == 0:
        print(f"       Stripe {target_stripe} Contention: PASS ✅ (1600 conflicting ops without deadlock)")
        return True
    else:
        print(f"       Stripe Contention: FAIL ❌ ({len(errors)} errors recorded)")
        print(f"       First error: {errors[0]}")
        return False

# ----------------------------------------------------------------------
# 4. Thread Pool Saturation & Backlog
# ----------------------------------------------------------------------
def test_thread_pool_saturation():
    print("\n🧵 [4/5] Testing Thread Pool Saturation (Fixed pool = 10)...")
    idle_sockets = []
    try:
        # Open 15 concurrent idle connections (exceeds the 10 worker threads)
        for _ in range(15):
            s = raw_client()
            idle_sockets.append(s)

        # Connection 16 tries to execute a normal request
        test_sock = raw_client()
        test_sock.settimeout(2.0)
        test_sock.sendall(b"SET,saturation_key,ok,60000\n")
        resp = test_sock.recv(1024).decode().strip()
        test_sock.close()

        if "OK" in resp:
            print("       Backlog Handling: PASS ✅ (Server queued and served beyond pool capacity)")
            return True
        else:
            print(f"       Backlog Handling: FAIL ❌ (Unexpected response: '{resp}')")
            return False
    except socket.timeout:
        print("       Backlog Handling: FAIL ❌ (Connection timed out; threads starved by idle sockets)")
        return False
    except Exception as e:
        print(f"       Backlog Handling: ERROR ❌ ({e})")
        return False
    finally:
        for s in idle_sockets:
            try: s.close()
            except: pass

# ----------------------------------------------------------------------
# 5. Payload Delimiter Integrity
# ----------------------------------------------------------------------
def test_payload_delimiter_integrity():
    print("\n🛡️ [5/5] Testing Payload Parsing & Delimiter Integrity...")
    complex_payload = json.dumps({"user": "john_doe", "roles": ["admin", "editor"], "score": 98.6})
    key = "user_json_profile"

    # Command format: SET,key,value,ttl
    cmd = f"SET,{key},{complex_payload},60000"
    res_set = send_cmd(cmd)
    res_get = send_cmd(f"GET,{key}")

    if complex_payload in res_get:
        print("       Delimiter Integrity: PASS ✅ (Commas inside payloads preserved without truncation)")
        return True
    else:
        print("       Delimiter Integrity: FAIL ❌")
        print(f"       Expected value: {complex_payload}")
        print(f"       Received value: {res_get}")
        return False

# ----------------------------------------------------------------------
# Execution Engine
# ----------------------------------------------------------------------
if __name__ == "__main__":
    print("=" * 65)
    print("🔬 AEROKV ENTERPRISE-GRADE VERIFICATION SUITE")
    print("=" * 65)

    results = [
        test_deterministic_lru(),
        test_tcp_framing_and_pipelining(),
        test_stripe_lock_collision(),
        test_thread_pool_saturation(),
        test_payload_delimiter_integrity()
    ]

    print("\n" + "=" * 65)
    passed_count = sum(1 for r in results if r)
    print(f"FINAL SCORE: {passed_count}/5 SUITES PASSED")
    print("=" * 65)