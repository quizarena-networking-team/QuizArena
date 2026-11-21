
# QuizArena — Shared Network Protocol v1

## 1. Architecture

QuizArena uses one Core Java backend process with several
network services and one shared game-state layer.

- TCP 5000: Java NIO gameplay server
- TLS 8443: Secure host-control server
- UDP 5001: LAN server discovery
- UDP 5002: Player heartbeat
- UDP multicast 239.10.10.10:5003: Announcements
- MySQL 3306: Server-side database only

All ports and the multicast group must be configurable.

## 2. TCP and TLS Message Format

Encoding: UTF-8
Format: JSON
Framing: One JSON object followed by newline (\n).
Maximum JSON frame: 16 KiB.

Envelope fields:

- type: Required message type.
- requestId: Required for requests and their replies.
- roomCode: Room identifier when relevant.
- status: OK or ERROR for responses.
- errorCode: Error identifier if status is ERROR.
- payload: JSON object containing message-specific fields.

Null fields may be omitted.

Requests and responses use the same requestId.

Server-pushed events do not need a requestId.

TCP is a byte stream: one read is NOT guaranteed
to contain one complete JSON message.

The receiver must preserve partial messages, process
multiple lines in one read, and enforce the frame limit.

## 3. Main TCP / NIO Port 5000

Client requests:

PING
JOIN_ROOM
LEAVE_ROOM
SUBMIT_VOTE
SUBMIT_ANSWER
PRESS_BUZZER
GET_LEADERBOARD

Responses:

PONG
JOIN_ROOM_RESULT
LEAVE_ROOM_RESULT
SUBMIT_VOTE_RESULT
SUBMIT_ANSWER_RESULT
PRESS_BUZZER_RESULT
LEADERBOARD_RESULT

Server-pushed events:

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

## 4. TLS Host-Control Port 8443

Commands accepted only over an authenticated TLS
host connection:

HOST_LOGIN
CREATE_ROOM
START_POLL
CLOSE_POLL
START_QUIZ
NEXT_QUESTION
OPEN_BUZZER
FINISH_QUIZ

Each command returns a matching *_RESULT response.

HOST_LOGIN must succeed before other host commands.

The server must enforce permissions; a client-provided
role is not sufficient authentication.

Host commands are not accepted on TCP port 5000.

Game events resulting from host commands are delivered
to connected players through the NIO server.

## 5. Common Message Examples

Join request:

{"type":"JOIN_ROOM","requestId":"r1","roomCode":"ABC123","payload":{"playerName":"Amal"}}

Successful join response:

{"type":"JOIN_ROOM_RESULT","requestId":"r1","roomCode":"ABC123","status":"OK","payload":{"playerId":"p-101"}}

Failed join response:

{"type":"JOIN_ROOM_RESULT","requestId":"r1","roomCode":"BAD123","status":"ERROR","errorCode":"ROOM_NOT_FOUND","payload":{}}

Poll-vote request:

{"type":"SUBMIT_VOTE","requestId":"r2","roomCode":"ABC123","payload":{"pollId":"poll-1","optionId":"B"}}

Server-pushed poll result:

{"type":"POLL_RESULTS","roomCode":"ABC123","payload":{"pollId":"poll-1","counts":{"A":2,"B":3}}}

## 6. Required Gameplay Rules

- The server is authoritative for scores and timers.
- A playerId is assigned by the server after JOIN_ROOM.
- Player identity is bound to the TCP connection.
- A player cannot vote twice in the same poll.
- A player cannot submit two answers per question.
- Only one first valid buzzer request can win a round.
- A question is rejected after the server deadline.
- Correct answers must not be sent before a question ends.
- Live rooms and scores are stored in server memory.
- Completed results and quiz content use MySQL.
- A disconnect must not crash the server.
- Database work must not block the NIO selector thread.

## 7. Common Error Codes

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

## 8. UDP Protocol

UDP datagrams use UTF-8 JSON, maximum 1200 bytes.

Discovery request on port 5001:

{"type":"DISCOVER","version":1}

The server sends a unicast discovery reply containing
its server name and configured TCP/TLS ports.

Player heartbeat on port 5002:

{"type":"HEARTBEAT","version":1,"roomCode":"ABC123","playerId":"p-101"}

Heartbeat status is advisory, not proof of identity.
TCP session state remains authoritative.

Multicast announcement to 239.10.10.10:5003:

{"type":"ANNOUNCEMENT","version":1,"roomCode":"ABC123","message":"Round starts now"}

No passwords, tokens, answers, or sensitive data
may be included in UDP or multicast messages.

Critical game events use reliable TCP, not multicast.

## 9. Ownership and Integration

Member 1:
- NIO server, dispatcher, room sessions.
- Implements selector-safe outgoing message queues.

Member 2:
- TCP client connection and incoming event handling.
- Polling and vote processing.

Member 3:
- Quiz processing, timer, scoring and buzzer logic.
- Uses server-side concurrency and synchronization.

Member 4:
- UDP discovery, heartbeat and multicast services.

Member 5:
- TLS host control, host authorization and MySQL.
- Coordinates persistence of quiz/results data.

All members:
- Reuse the shared message envelope and codec.
- Implement a handler for each assigned request type.
- Do not open a separate gameplay TCP server.
- Do not independently change message names or fields.
- Propose shared protocol changes via pull request.

## 10. Initial Scope

- One active quiz room for the first release.
- One active poll at a time.
- One active question or buzzer round at a time.
- Preset quizzes loaded from MySQL.
- Guest players join with a name and room code.
- Hosts have authenticated accounts.

Protocol changes must be reviewed by affected members.
