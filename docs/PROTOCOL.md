# QuizArena Network Protocol

## Main TCP server
Default port: 5000

Transport: TCP using Java NIO.

Message encoding: UTF-8.

Message framing: One JSON object per line.
Each message must end with a newline character.

## Example request

{"type":"JOIN_ROOM","requestId":"req-001","roomCode":"ABC123","payload":{"playerName":"Amal"}}

## Example response

{"type":"JOIN_ROOM_RESULT","requestId":"req-001","status":"OK","payload":{"playerId":"player-001"}}

## Initial message types

- CREATE_ROOM
- JOIN_ROOM
- LEAVE_ROOM
- START_POLL
- SUBMIT_VOTE
- START_QUIZ
- SUBMIT_ANSWER
- PRESS_BUZZER
- GET_LEADERBOARD

Additional response and push-event types
must be agreed before implementation.

## Important rules

- The server is authoritative.
- Client-provided scores must never be trusted.
- Each request has a requestId.
- TCP messages can arrive in partial reads.
- Unauthorized host commands must be rejected.
- Sensitive host commands will use the separate
  TLS-protected host-control interface.

## Ownership

Member 1 and Member 2 coordinate protocol changes.
All members must review changes affecting their modules.

This document is an initial contract.
Finalize the remaining message schemas before
parallel feature development.
