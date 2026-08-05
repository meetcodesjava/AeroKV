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
