# AeroKV

> **A High-Performance Concurrent In-Memory Key-Value Storage Engine Built from Scratch in Java**

<p align="center">

![Java](https://img.shields.io/badge/Java-21-blue?style=for-the-badge)
![Maven](https://img.shields.io/badge/Build-Maven-C71A36?style=for-the-badge)
![Platform](https://img.shields.io/badge/Platform-Windows-lightgrey?style=for-the-badge)
![Protocol](https://img.shields.io/badge/Protocol-TCP-success?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)

</p>

---

AeroKV is a **high-performance, multi-threaded, in-memory key-value storage engine** developed entirely from scratch in Java. It is designed to explore the core concepts behind modern caching systems and in-memory databases by implementing essential storage engine components rather than relying on existing frameworks or libraries.

The project combines **custom LRU eviction**, **segmented lock striping for concurrent access**, **lazy TTL expiration**, and **Write-Ahead Logging (WAL)** to provide fast data access while ensuring durability and crash recovery. Communication is performed over a lightweight **TCP text-based protocol**, allowing clients to interact with the server using simple `SET`, `PUT`, and `GET` commands.

AeroKV demonstrates how concurrency control, memory management, persistence, and network communication work together to build a reliable storage engine capable of serving multiple clients simultaneously.


## Project Overview

Modern applications such as e-commerce platforms, financial systems, gaming servers, and social media applications rely heavily on in-memory data stores to deliver responses with minimal latency. While databases provide durable storage, repeatedly accessing disk-based systems for frequently requested data introduces significant performance overhead.

AeroKV was built to explore the internal architecture of modern in-memory storage engines by implementing their core building blocks from scratch in Java. Instead of relying on existing caching frameworks or libraries, the project focuses on understanding how high-performance storage systems manage concurrency, memory, persistence, and data lifecycle.

The storage engine supports concurrent client access over raw TCP sockets using a custom lock-striping mechanism to minimize thread contention. A custom LRU cache manages memory efficiently by evicting the least recently used entries when capacity limits are reached. To improve reliability, every write operation is first persisted using Write-Ahead Logging (WAL), allowing the engine to recover its in-memory state after an unexpected shutdown. Additionally, lazy Time-To-Live (TTL) expiration ensures that expired entries are automatically removed when accessed without requiring continuous background cleanup.

By combining networking, concurrent programming, custom data structures, and persistence techniques, AeroKV demonstrates many of the fundamental concepts used in the design of modern high-performance storage systems.

## Tech Stack

| Category                 | Technology                                      | Purpose                                                                                                  |
| ------------------------ | ----------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| **Programming Language** | Java 21 (Compatible with Java 11+)              | Core application development and concurrency implementation                                              |
| **Build Tool**           | Maven                                           | Dependency management, project build, and execution                                                      |
| **Networking**           | Java TCP Sockets                                | Enables client-server communication over a lightweight text-based protocol                               |
| **Concurrency**          | Custom Segmented Lock Striping                  | Reduces lock contention by allowing multiple threads to operate on different cache segments concurrently |
| **Caching**              | Custom LRU Cache (HashMap + Doubly Linked List) | Provides constant-time (`O(1)`) lookup, insertion, and least recently used eviction                      |
| **Expiration**           | Lazy Time-To-Live (TTL)                         | Removes expired entries during access without requiring a background cleanup thread                      |
| **Persistence**          | Write-Ahead Logging (WAL)                       | Ensures durability by persisting write operations before updating the in-memory cache                    |
| **Recovery**             | WAL Replay                                      | Restores the cache state by replaying log records during server startup                                  |
| **Storage**              | In-Memory                                       | Delivers low-latency data access by storing entries in main memory                                       |
| **Protocol**             | TCP Text Protocol                               | Supports simple line-based `SET`, `PUT`, and `GET` commands for client interaction                       |

## System Architecture

AeroKV follows a layered architecture where each client request passes through a series of well-defined components before a response is returned. The server listens for incoming TCP connections, parses client commands, applies concurrency control using segmented lock striping, persists write operations through Write-Ahead Logging (WAL), and finally stores or retrieves data from the in-memory cache.

This separation of responsibilities keeps the storage engine modular, improves maintainability, and allows individual components to evolve independently.

### Architecture Components

| Component                 | Responsibility                                                                                                  |
| ------------------------- | --------------------------------------------------------------------------------------------------------------- |
| **TCP Server**            | Accepts incoming client connections and manages request processing.                                             |
| **Command Parser**        | Parses incoming text-based commands (`SET`, `PUT`, and `GET`) and validates request syntax.                     |
| **Lock Striping Layer**   | Maps keys to lock segments, allowing multiple threads to operate concurrently while minimizing lock contention. |
| **LRU Cache Engine**      | Stores key-value pairs in memory and performs least recently used eviction when capacity limits are reached.    |
| **TTL Manager**           | Validates the expiration time of entries during read operations and removes expired keys when accessed.         |
| **Write-Ahead Log (WAL)** | Persists every write operation to disk before updating the in-memory cache to ensure durability.                |
| **Recovery Engine**       | Rebuilds the cache during server startup by replaying all entries from the WAL file.                            |

### Request Flow

#### Write Operation (`SET` / `PUT`)

1. Client sends a write request to the TCP server.
2. The command parser validates and extracts the request parameters.
3. The corresponding lock stripe is acquired based on the key.
4. The operation is synchronously appended to the Write-Ahead Log (WAL).
5. The in-memory LRU cache is updated.
6. The lock is released.
7. The server returns `OK` to the client.

#### Read Operation (`GET`)

1. Client sends a read request.
2. The command parser validates the request.
3. The corresponding lock stripe is acquired.
4. The cache is searched for the requested key.
5. If the key has expired, it is removed immediately and `ERR_EXPIRED` is returned.
6. If the key is not present, `ERR_NOT_FOUND` is returned.
7. Otherwise, the stored value is returned to the client.
8. The lock is released.

### Architecture Diagram

```mermaid
flowchart LR

    Client --> TCP["TCP Server"]
    TCP --> Parser["Command Parser"]
    Parser --> Lock["Lock Striping Layer"]
    Lock --> Cache["LRU Cache Engine"]
    Lock --> WAL["Write-Ahead Log"]
    WAL --> Disk["Log File"]
    Cache --> Response["Response to Client"]

    Disk --> Recovery["Recovery Engine"]
    Recovery --> Cache
```

## Project Structure

The project follows the standard Maven directory layout and is organized into incremental modules. Each package represents a distinct development phase, gradually evolving AeroKV from a basic key-value storage abstraction into a concurrent, network-accessible in-memory storage engine with persistence and recovery capabilities.

```text
AeroKV/
├── pom.xml
├── README.md
│
├── src/
│   ├── app.py                  # Sample Python client
│   ├── benchmark.py            # Multi-threaded benchmark utility
│   │
│   └── main/
│       └── java/
│           ├── day01/          # Core abstractions and logging
│           ├── day02/          # Custom HashMap implementation
│           ├── day03/          # Custom LRU cache
│           ├── day04/          # Concurrent cache with lock striping
│           ├── day05/          # TCP server, persistence, TTL, and recovery
│           └── day06/          # Application entry point
│
└── target/                     # Maven build output (generated)
```

### Package Overview

| Package   | Responsibility                                                                                                      |
| --------- | ------------------------------------------------------------------------------------------------------------------- |
| **day01** | Defines the core storage abstraction and logging utilities used throughout the project.                             |
| **day02** | Implements a custom HashMap with separate chaining to provide efficient key-value storage.                          |
| **day03** | Builds a custom LRU cache using the HashMap and a Doubly Linked List for constant-time cache operations.            |
| **day04** | Introduces thread safety through segmented lock striping, enabling concurrent cache access with reduced contention. |
| **day05** | Implements the TCP server, asynchronous log persistence, crash recovery, and TTL-based cache entries.               |
| **day06** | Contains the application entry point responsible for initializing and starting the AeroKV server.                   |

## Getting Started

### Prerequisites

Before running AeroKV, ensure the following software is installed on your system:

* Java Development Kit (JDK) 21 (Compatible with Java 11 or later)
* Apache Maven 3.9 or later
* Python 3.x (Optional, for running the sample client and benchmark utility)

### Clone the Repository

```bash
git clone https://github.com/meetcodesjava/AeroKV.git
cd AeroKV
```

### Build the Project

Compile the project using Maven:

```bash
mvn clean compile
```

### Run the Server

Start the AeroKV server:

```bash
mvn exec:java -Dexec.mainClass="day06.AeroKVServerApp"
```

The server starts on **port 8080**, restores previously persisted data (if available), and begins accepting TCP client connections.

### Run the Sample Python Client

Open a new terminal and execute:

```bash
cd src
python app.py
```

The sample client demonstrates storing and retrieving data from the AeroKV server using the TCP protocol.

### Run the Benchmark

To evaluate concurrent request throughput, execute:

```bash
cd src
python benchmark.py
```

The benchmark creates multiple concurrent client threads that perform `SET` and `GET` operations and reports the total execution time, successful operations, and throughput.

## Core Features

### Custom HashMap Implementation

AeroKV implements a custom HashMap using separate chaining for collision handling instead of relying on Java's built-in collections. This serves as the core storage layer and provides efficient average-case constant-time key lookup and insertion.

### Custom LRU Cache

The storage engine uses a custom Least Recently Used (LRU) cache built with a Doubly Linked List and the custom HashMap. Recently accessed entries are promoted to the head of the list, while the least recently used entry is automatically evicted when the configured cache capacity is reached.

### Thread-Safe Concurrent Access

To support multiple simultaneous clients, AeroKV employs segmented lock striping. Keys are mapped to independent lock stripes using their hash values, allowing concurrent operations on different keys while reducing lock contention.

### Lazy TTL Expiration

Each cached entry can be assigned a configurable Time-To-Live (TTL). Expiration is evaluated during read operations, ensuring expired entries are removed when accessed without requiring a background cleanup thread.

### Asynchronous Log Persistence

Write operations are queued and persisted to disk by a dedicated background writer thread. During server startup, the persisted log is replayed to restore previously stored entries into the in-memory cache.

### Multi-Threaded TCP Server

AeroKV exposes its storage engine through a lightweight TCP server backed by a fixed-size thread pool. This enables multiple clients to perform concurrent cache operations using a simple text-based protocol.

### Text-Based Command Protocol

Clients communicate with AeroKV using a lightweight, line-delimited TCP protocol. The current implementation supports `SET` for storing data with an optional TTL and `GET` for retrieving cached values.

### Crash Recovery

On startup, AeroKV scans the persisted log file and replays stored operations to reconstruct the in-memory cache. This allows previously persisted data to be restored automatically after a server restart.

## Performance Benchmark

The following benchmark was performed on a local development machine using the included multi-threaded Python benchmarking utility (`benchmark.py`). The test measures the server's ability to process concurrent `SET` and `GET` operations over TCP sockets.

### Test Environment

| Component            | Specification                              |
| -------------------- | ------------------------------------------ |
| **Processor**        | 11th Gen Intel® Core™ i3-1115G4 @ 3.00 GHz |
| **Memory**           | 8 GB RAM                                   |
| **Storage**          | 256 GB SSD                                 |
| **Operating System** | Windows 11                                 |
| **Java Version**     | JDK 21.0.12                                |
| **Benchmark Tool**   | Custom Python Multi-Threaded TCP Client    |

### Benchmark Configuration

| Parameter                     | Value |
| ----------------------------- | ----: |
| **Concurrent Client Threads** |    10 |
| **SET Operations**            | 1,000 |
| **GET Operations**            | 1,000 |
| **Total Operations**          | 2,000 |

### Benchmark Result

| Metric                    |                   Result |
| ------------------------- | -----------------------: |
| **Execution Time**        |            ~0.97 seconds |
| **Successful Operations** |                    2,000 |
| **Average Throughput**    | ~2,051 operations/second |

> **Note:** These results were obtained in a local development environment. Actual performance may vary depending on hardware configuration, operating system, JVM settings, workload characteristics, and network conditions.

## Future Improvements

* **Support Additional Commands** – Extend the command protocol by adding operations such as `PUT`, `DELETE`, `MGET`, and `MSET`.
* **Active TTL Cleanup** – Introduce a background cleanup thread to proactively remove expired cache entries instead of relying solely on lazy expiration.
* **TTL Persistence** – Persist TTL metadata in the log file so that expiration information is preserved across server restarts.

## Configuration

The current implementation initializes the server using the following default configuration:

| Parameter            |    Default Value | Description                                                                           |
| -------------------- | ---------------: | ------------------------------------------------------------------------------------- |
| **Server Port**      |           `8080` | TCP port used by the AeroKV server to accept client connections.                      |
| **Cache Capacity**   |           `1000` | Maximum number of entries that can be stored in the in-memory LRU cache.              |
| **Lock Stripes**     |             `16` | Number of lock segments used to reduce contention during concurrent cache operations. |
| **Log File Path**    | `D:\AeroKV_logs` | File used to persist write operations and restore cached data during server startup.  |
| **Thread Pool Size** |             `10` | Number of worker threads available to process concurrent client connections.          |
| **Java Version**     |         `JDK 21` | Recommended Java version used for development and testing.                            |


## Time Complexity

The following table summarizes the average-case time complexity of the primary operations performed by AeroKV.

| Operation                     | Average Time Complexity |
| ----------------------------- | ----------------------: |
| **SET**                       |                  `O(1)` |
| **GET**                       |                  `O(1)` |
| **HashMap Lookup**            |                  `O(1)` |
| **HashMap Insertion**         |                  `O(1)` |
| **LRU Update (Move to Head)** |                  `O(1)` |
| **LRU Eviction**              |                  `O(1)` |
| **Lock Stripe Selection**     |                  `O(1)` |
| **TTL Validation**            |                  `O(1)` |
| **Log Queue Insertion**       |                  `O(1)` |

> **Note:** The above complexities represent the average case. HashMap operations may degrade in the presence of excessive hash collisions, while log persistence time depends on the underlying storage device and operating system.

## Author

**Meet Limbachiya**

Backend Developer | Java | Spring Boot | Data Structures & Algorithms | Concurrent Systems

* **GitHub:** [github.com/meetcodesjava](https://github.com/meetcodesjava)
* **LinkedIn:** [linkedin.com/in/meetlimbachiya](https://www.linkedin.com/in/meetlimbachiya/)
* **Project Repository:** [AeroKV Repository](https://github.com/meetcodesjava/AeroKV)
