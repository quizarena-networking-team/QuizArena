# Member 5 - TLS host control and MySQL integration

## Security behavior

Host commands are accepted only on the TLS 1.3 listener after `HOST_LOGIN`.
Passwords are stored as PBKDF2-HMAC-SHA-256 hashes with random salts. Database,
keystore and host passwords are never committed. Failed logins are limited per
remote address and normalized username: after five failures within one minute,
authentication is blocked for 30 seconds before the database is queried again.
Successful login clears the failure record.

## End-to-end workflow verification

The integrated workflow is covered at each real boundary:

| Workflow segment | Verification |
| --- | --- |
| TLS handshake -> `HOST_LOGIN` -> `CREATE_ROOM` | `TlsHostServerIntegrationTest` plus the live `HostControlVerifier` run |
| TCP player join and connection-bound identity | `NioQuizServerIntegrationTest` |
| `START_POLL` -> `SUBMIT_VOTE` -> `CLOSE_POLL` | `HostCommandCoordinatorTest`, `PollRequestHandlerTest`, `PollSocketTest` |
| `START_QUIZ` -> gameplay requests -> `FINISH_QUIZ` | `HostCommandCoordinatorTest`, `QuizRequestHandlerTest`, `QuizSocketTest`, `QuizServiceTest` |
| MySQL quiz loading and final-result persistence | opt-in `JdbcMySqlTest`, including transaction rollback and cross-connection commit checks |

The live Member 5 verification on macOS negotiated TLS 1.3, authenticated the
MySQL-backed `host-admin`, created a room and persisted its `quiz_sessions` row.
For a disposable MySQL database, run the opt-in persistence test as documented
in `sql/README.md`.

## Development mode

Poll and quiz handlers are registered only when `QUIZ_TLS_ENABLED=true`, because
`SecureHostRuntime` owns the authenticated coordinator and creates one poll and
quiz service for each secure `CREATE_ROOM`. A room created with `--dev-room`
while TLS is disabled supports `PING`, `JOIN_ROOM` and `LEAVE_ROOM`, but poll and
quiz requests receive `BAD_REQUEST` because those handlers are intentionally
absent. This avoids a second, unauthenticated source of host/game state.

## Test results and multicast note

The GitHub Java CI run for PR #14 passes. Locally, the full non-multicast suite
completed with 159 tests, 0 failures and 0 errors (17 display/database opt-in
tests skipped). On the development Mac, six Member 4 multicast assertions fail
with `NoRouteToHost` or no received datagram when the active network does not
route multicast. TLS, NIO, UDP discovery, heartbeat, polling, quiz and database
tests are unaffected. Multicast configuration and troubleshooting are documented
in `docs/MEMBER4.md`.
