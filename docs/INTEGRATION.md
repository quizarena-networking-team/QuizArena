# Full networking integration test

`FullNetworkingIntegrationTest` assembles the same NIO server, room registry,
secure host runtime, JDBC repositories, and UDP services used by the backend.
One authenticated JSSE client and two production `TcpQuizClient` instances
exchange real messages on loopback. Both TCP listeners use automatically
assigned ports. All application writes use an isolated MySQL database.

## Run on Windows

1. Install JDK 21, Maven, and a local MySQL instance. The project schema
   targets MySQL 8.4. Run these two commands from the repository root with a
   MySQL administrator account to create and initialize the separate schema.

   ```powershell
   mysql --host=127.0.0.1 --user=root --password --execute='CREATE DATABASE IF NOT EXISTS quizarena_e2e CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'
   mysql --host=127.0.0.1 --user=root --password --database=quizarena_e2e --execute='source sql/schema.sql'
   ```

2. In MySQL Workbench's **Users and Privileges**, create a dedicated local test
   account with a unique password. Give it `SELECT`, `INSERT`, `UPDATE`, and
   `DELETE` privileges on `quizarena_e2e.*` only. Select a host restriction that
   matches your loopback JDBC connection, such as `127.0.0.1`.

3. In PowerShell, enter the test account's username and password when prompted.
   The test uses JDK `keytool` to generate temporary TLS key material and
   provisions its own one-time QuizArena host account. No server, GUI, manual
   host provisioning, or preexisting keystore is required.

   ```powershell
   $env:QUIZ_E2E_DB_URL = 'jdbc:mysql://127.0.0.1:3306/quizarena_e2e'
   $env:QUIZ_E2E_DB_USER = Read-Host 'Disposable MySQL username'
   $env:QUIZ_E2E_DB_PASSWORD = [Net.NetworkCredential]::new(
       '', (Read-Host 'Disposable MySQL password' -AsSecureString)
   ).Password
   try {
       mvn -B -ntp "-Dtest=FullNetworkingIntegrationTest" test
   } finally {
       Remove-Item Env:QUIZ_E2E_DB_URL, Env:QUIZ_E2E_DB_USER, Env:QUIZ_E2E_DB_PASSWORD
   }
   ```

   Replace `3306` if your MySQL instance uses a different port. The test
   accepts a direct loopback JDBC URL with a database named `quizarena_e2e`
   or `quizarena_e2e_<suffix>`. Keep credentials in the separate environment
   variables. An unset `QUIZ_E2E_DB_URL` skips this test. With that variable
   set, missing credentials, a bad connection, schema setup errors, and
   failures in the networking flow fail the test.

Run the standard Maven suite with JavaFX tests from the same PowerShell session.

```powershell
mvn -B -ntp "-Dquizarena.uiTests=true" clean test
```

The separate DAO test, `JdbcMySqlTest`, has its own environment configuration
in [sql/README.md](../sql/README.md). Surefire writes test results under
`target/surefire-reports`.

## What the test checks

- UDP discovery advertises the actual TCP and TLS ports. A heartbeat carrying
  the player identity issued during TCP join is accepted by the UDP monitor.
- The host client trusts the generated certificate, verifies the loopback
  identity, negotiates TLS 1.3, logs in as the provisioned host, and creates a
  room and session through the secure host channel.
- Alice and Bob join through the production TCP client. The room broadcasts
  their server-assigned IDs and display names.
- The host starts and closes a poll over TLS. Players vote over TCP, receive
  acknowledgements and counts, and a second vote from the same player is
  rejected. The host reply and the TCP close event contain the final counts.
- The host starts a quiz loaded from MySQL with a standard question and a
  reserved buzzer question. The test checks question and answer messages,
  deadline-driven review, first-buzzer ownership, rejection of the other
  player's buzzer answer, and the live leaderboard and leaderboard reply.
- `FINISH_QUIZ_RESULT` and `QUIZ_FINISHED` report persistence. A separately
  opened JDBC connection confirms the `FINISHED` session and both result rows,
  including player IDs, scores, answer counts, and ranks. The fixtures give
  Alice 150 points and Bob 0.
- Players leave and disconnect. Server resources close, the secure runtime
  removes its room, and the test removes the database rows owned by its
  generated host. The schema cascades to the fixture questions and results.

## Protocol and release scope

The TCP and TLS payloads exercised by this flow agree across the production
components. Some earlier handoff notes describe the pre-integration state.
The full test uses the following secure runtime behavior.

- `CREATE_ROOM` takes an empty payload and allocates the room code and
  session ID. `START_POLL` takes an `options` object keyed by option ID.
- `START_QUIZ` takes string `quizId` and optional string `buzzerQuestionId`.
  Player answer and buzzer requests identify the currently open round with
  both `questionId` and `roundId`. `OPEN_BUZZER` takes integer
  `buzzDurationSeconds` and `answerDurationSeconds`.
- The authenticated flow creates its room using `CREATE_ROOM`. The
  `--dev-room` startup flag creates a lobby directly through `RoomRegistry`;
  it does not create the secure runtime's room-scoped poll and quiz services.
- Automatic multicast announcements for poll and quiz phases are still a
  separate integration gap. `LanServices.wrap(...)` is available, but
  `HostCommandCoordinator` currently gives both services the unwrapped room
  event sink. Gameplay messages continue over TCP. The test covers UDP
  discovery and presence and leaves multicast delivery to the existing
  multicast tests.

The network and persistence checks were exercised on Windows with Java 21 and
a disposable MySQL 8.0.45 instance. MySQL 8.4 is the documented deployment
target, so repeat the live test there before signing off on that deployment.
