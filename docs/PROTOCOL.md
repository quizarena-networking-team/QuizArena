# QuizArena - Shared Network Protocol v1

## 1. Architecture

QuizArena uses one Core Java backend process with several
network services and one shared game-state layer.

### Network Services

| Service | Transport | Port |
|---|---|---|
| Main Gameplay Server | Java NIO / TCP | 5000 |
| Secure Host Service | TLS / TCP | 8443 |
| LAN Server Discovery | UDP Broadcast | 5001 |
| Player Heartbeat | UDP | 5002 |
| Quiz Announcements | UDP Multicast | 5003 |
| MySQL Database | JDBC | 3306 |

Multicast group: 239.10.10.10

All ports and the multicast group must be configurable.

The backend owns room state, quiz state, scores,
poll votes, authentication, and database operations.

JavaFX clients communicate with the backend.

## 2. TCP and TLS Message Format

Encoding: UTF-8

Format: JSON

Framing: One JSON object followed by newline (\n).

Maximum JSON frame size: 16 KiB (16384 bytes),
excluding the newline delimiter.

### Shared Message Envelope

All TCP and TLS messages use these fields:

- type: Required message type.
- requestId: Required for client requests and replies.
- roomCode: Room identifier when relevant.
- status: OK or ERROR for direct responses.
- errorCode: Error identifier for failed responses.
- payload: JSON object containing message-specific data.

Optional fields may be omitted.

Requests and corresponding responses use
the same requestId.

Server-pushed events do not need a requestId.

### TCP Framing Rules

TCP is a byte stream.

One TCP read is NOT guaranteed to contain one
complete JSON message.

A receiver must:

1. Accumulate incoming bytes.
2. Find complete newline-delimited frames.
3. Preserve incomplete frames.
4. Handle multiple messages in a single read.
5. Enforce the maximum message size.
6. Decode complete UTF-8 JSON frames.

Outgoing TCP writes may also be partial.

The Java NIO server must use proper write queues
and selector interest management.

## 3. TCP Gameplay Protocol - Port 5000

### Client Requests

PING
JOIN_ROOM
LEAVE_ROOM
SUBMIT_VOTE
SUBMIT_ANSWER
PRESS_BUZZER
GET_LEADERBOARD

### Direct Responses

PONG
JOIN_ROOM_RESULT
LEAVE_ROOM_RESULT
SUBMIT_VOTE_RESULT
SUBMIT_ANSWER_RESULT
PRESS_BUZZER_RESULT
LEADERBOARD_RESULT

### Response Naming Convention

Most requests receive a response whose name is
the request type followed by _RESULT.

Examples:

- JOIN_ROOM -> JOIN_ROOM_RESULT
- LEAVE_ROOM -> LEAVE_ROOM_RESULT
- SUBMIT_VOTE -> SUBMIT_VOTE_RESULT
- SUBMIT_ANSWER -> SUBMIT_ANSWER_RESULT
- PRESS_BUZZER -> PRESS_BUZZER_RESULT

Exceptions:

- PING -> PONG
- GET_LEADERBOARD -> LEADERBOARD_RESULT

All direct responses must preserve the
requestId from the original request.

### Server-Pushed Events

LOBBY_UPDATED
POLL_STARTED
POLL_RESULTS
POLL_CLOSED
QUESTION_STARTED
QUESTION_ENDED
BUZZER_OPENED
BUZZER_WINNER
LEADERBOARD_UPDATED
QUIZ_FINISHED
ROOM_CLOSED

Server-pushed events are sent independently
of client requests.

They may omit requestId.

## 4. TLS Host-Control Protocol - Port 8443

The following commands are accepted only through
an authenticated TLS host-control connection.

### Secure Host Requests

HOST_LOGIN
CREATE_ROOM
START_POLL
CLOSE_POLL
START_QUIZ
NEXT_QUESTION
OPEN_BUZZER
FINISH_QUIZ

### Secure Host Responses

HOST_LOGIN_RESULT
CREATE_ROOM_RESULT
START_POLL_RESULT
CLOSE_POLL_RESULT
START_QUIZ_RESULT
NEXT_QUESTION_RESULT
OPEN_BUZZER_RESULT
FINISH_QUIZ_RESULT

### Authentication Rules

HOST_LOGIN must succeed before other host commands.

The server must enforce host permissions.

A client-provided role is not proof of authentication.

Host-only commands must not be accepted on
the unencrypted gameplay TCP port 5000.

Game events resulting from host commands are
broadcast to players through the NIO server.

TLS credentials, passwords, and session secrets
must not be included in public events.

## 5. Example TCP Messages

### Join Room Request

{"type":"JOIN_ROOM","requestId":"r1","roomCode":"ABC123","payload":{"playerName":"Amal"}}

### Successful Join Response

{"type":"JOIN_ROOM_RESULT","requestId":"r1","roomCode":"ABC123","status":"OK","payload":{"playerId":"p-101"}}

### Failed Join Response

{"type":"JOIN_ROOM_RESULT","requestId":"r1","roomCode":"BAD123","status":"ERROR","errorCode":"ROOM_NOT_FOUND","payload":{}}

### Submit Poll Vote

{"type":"SUBMIT_VOTE","requestId":"r2","roomCode":"ABC123","payload":{"pollId":"poll-1","optionId":"B"}}

### Poll Results Event

{"type":"POLL_RESULTS","roomCode":"ABC123","payload":{"pollId":"poll-1","counts":{"A":2,"B":3}}}

### Get Leaderboard Request

{"type":"GET_LEADERBOARD","requestId":"r3","roomCode":"ABC123","payload":{}}

### Leaderboard Response

{"type":"LEADERBOARD_RESULT","requestId":"r3","roomCode":"ABC123","status":"OK","payload":{"entries":[{"playerId":"p-101","playerName":"Amal","score":100}]}}

## 6. Shared Payload Conventions

Use consistent field names across modules.

### Room Fields

roomCode
playerId
playerName

### Quiz Fields

quizId
questionId
optionId
deadlineEpochMs

### Poll Fields

pollId
optionId
counts

### Leaderboard Fields

entries
playerId
playerName
score

### Common Rules

- IDs are strings.
- Scores and vote counts are JSON numbers.
- Deadline timestamps use Unix milliseconds.
- Do not include Java object serialization in messages.
- Do not send database entity objects directly.
- Do not send correct answers before a question ends.
- Do not trust client-provided score values.

Each feature owner must document the complete payload
schema of every request and event they implement.

Shared payload changes must be reviewed before merge.

## 7. Required Gameplay Rules

- The server is authoritative for scores and timers.
- A playerId is assigned after JOIN_ROOM.
- Player identity is bound to the TCP connection.
- A player cannot vote twice in the same poll.
- A player cannot answer a question twice.
- Only the first valid buzzer request wins a round.
- Answers after the server deadline are rejected.
- Correct answers remain hidden until a round ends.
- Live rooms and scores are stored in memory.
- Completed results and quiz content use MySQL.
- A disconnect must not crash the backend.
- Database operations must not block the NIO selector.
- Shared game state must be thread-safe.

## 8. Common Error Codes

BAD_REQUEST
ROOM_NOT_FOUND
ROOM_CLOSED
INVALID_PLAYER
ALREADY_VOTED
POLL_CLOSED
ANSWER_ALREADY_SUBMITTED
QUESTION_CLOSED
BUZZER_CLOSED
UNAUTHORIZED
INTERNAL_ERROR

Direct error responses use:

- status: ERROR
- errorCode: One of the defined codes
- payload: JSON object

## 9. UDP and Multicast Protocol

UDP messages are independent datagrams.

Unlike TCP messages, UDP messages do not use
newline-delimited framing.

Encoding: UTF-8

Format: JSON

Maximum datagram payload: 1200 bytes.

### LAN Discovery - UDP Port 5001

Discovery request:

{"type":"DISCOVER","version":1}

The server replies by UDP unicast.

Example discovery response:

{"type":"DISCOVER_RESULT","version":1,"serverName":"QuizArena Server","tcpPort":5000,"tlsPort":8443}

The server response is sent to the address
and port from which the discovery request came.

### Player Heartbeat - UDP Port 5002

Example:

{"type":"HEARTBEAT","version":1,"roomCode":"ABC123","playerId":"p-101"}

Heartbeat information is advisory only.

It does not prove player identity.

TCP connection state remains authoritative.

### Multicast Announcements - UDP Port 5003

Multicast group: 239.10.10.10

Example:

{"type":"ANNOUNCEMENT","version":1,"roomCode":"ABC123","message":"Round starts now"}

### UDP Security and Reliability Rules

- UDP delivery is not guaranteed.
- Multicast may not work on every network.
- Never send passwords or authentication tokens.
- Never send hidden quiz answers.
- UDP data must not directly award points.
- Critical game events must use reliable TCP.
- UDP heartbeat identity must not be trusted.

## 10. Shared Java Protocol Components

All applicable TCP and TLS modules reuse:

ProtocolMessage
MessageCodec
MessageTypes
MessageHandler
RequestContext

### ProtocolMessage

Represents the shared JSON message envelope.

### MessageCodec

Serializes and deserializes complete TCP/TLS
JSON messages.

It does not manage SocketChannel reads or writes.

### MessageTypes

Defines shared message type constants.

Members must avoid creating alternate names.

### MessageHandler

Defines the contract for processing requests.

### RequestContext

Provides connection identity and methods
to reply or broadcast events.

Network delivery must be thread-safe.

## 11. Team Ownership

### Member 1 - Java NIO

- Main NIO gameplay server.
- ServerSocketChannel and SocketChannel.
- Selector and ByteBuffer.
- TCP framing and message dispatcher.
- Connection and room management.
- Thread-safe outgoing message queues.

### Member 2 - TCP and Polling

- TCP JavaFX client connection.
- TCP message sending and receiving.
- Poll creation logic and vote handling.
- Poll result processing.
- Collaboration with Member 1 on protocol behavior.

### Member 3 - Multithreading

- Multiplayer quiz engine.
- Server-side answer validation.
- Quiz timers.
- Thread pools and synchronization.
- Fastest-finger buzzer.
- Score calculations.

### Member 4 - UDP and Multicast

- LAN discovery.
- UDP heartbeats.
- Multicast announcements.
- Network inspection features.

### Member 5 - TLS and Database

- JSSE/TLS host-control service.
- Host authentication and authorization.
- MySQL JDBC integration.
- Persistent quiz data.
- Completed session results.

## 12. Integration Rules

- Use the shared message envelope.
- Use MessageTypes constants.
- Follow the agreed port assignments.
- Do not create another gameplay TCP server.
- Do not perform blocking work on the selector thread.
- Do not independently rename protocol messages.
- Communicate across modules using agreed interfaces.
- Submit pull requests to develop.
- Review changes affecting other members' modules.

## 13. Initial Project Scope

- One active quiz room for the first release.
- One active poll at a time.
- One active quiz question or buzzer round at a time.
- Preset quizzes loaded from MySQL.
- Guest players join using room codes.
- Hosts log in using authenticated accounts.
- The backend manages shared application state.

This protocol is the initial integration contract.

The message envelope, message names, common field
names, and transport rules are fixed for version 1.

Feature-specific payload schemas must be agreed
and documented before those features are merged.
