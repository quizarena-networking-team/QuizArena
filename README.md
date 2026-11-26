# QuizArena

Real-Time Multiplayer Quiz & Competition Platform

## Project Overview

QuizArena is a Java desktop application that allows a host
to conduct real-time multiplayer quizzes with multiple players.

The project demonstrates Java Network Programming concepts,
including TCP, Java NIO, multithreading, UDP, multicast and TLS.

## Technology Stack

- Java 21
- JavaFX + CSS
- Core Java + Java NIO
- TCP / UDP / Multicast
- JSSE / TLS
- MySQL + JDBC
- Maven
- Git and GitHub

## User Roles

1. Host - manages quiz sessions and competitions.
2. Player - joins rooms, answers questions and earns points.

## Core Features

1. Quiz rooms and player lobby
2. Real-time multiplayer quizzes
3. Fastest-finger buzzer
4. Live polls and voting
5. Leaderboard and results
6. LAN discovery and player presence
7. Multicast quiz announcements
8. Secure host controls and network inspector

## Requirements

- JDK 21
- Apache Maven 3.9+
- IntelliJ IDEA or another Java IDE
- MySQL Server (required when DB features are integrated)

## Run the JavaFX Client

```bash
mvn clean javafx:run
```

## Run the Server

Run `com.quizarena.server.ServerMain` from IntelliJ (or `mvn compile exec:java -Dexec.mainClass=com.quizarena.server.ServerMain`).

It starts the Java NIO gameplay server on TCP 5000. To create a room before
the TLS host service exists, pass `--dev-room=DEMO01` (or set `QUIZ_DEV_ROOM`).
Other options: `--port=N`, `--host=H`. See `docs/MEMBER1.md`.

## Development Workflow

- main: stable release branch
- develop: integration branch
- feature/*: individual development branches

Create feature branches from `develop`.
Submit pull requests into `develop`.

## Team Contributions

- Member 1: Java NIO server and rooms
- Member 2: TCP client and polling
- Member 3: Multiplayer quiz and concurrency
- Member 4: UDP discovery and multicast
- Member 5: TLS security and MySQL integration

Each member must implement and demonstrate
their assigned Network Programming concept.
