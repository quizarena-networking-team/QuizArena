# QuizArena database setup

QuizArena uses MySQL 8.4 and five persistent tables. Live room membership,
active polls, timers and in-progress scores remain in memory.

## Initialize the development database

From the repository root, apply the schema using an administrator account:

```bash
mysql -u root -p quizarena < sql/schema.sql
```

The schema contains `CREATE TABLE IF NOT EXISTS`, so applying it again does not
delete existing data. It does not create users or contain passwords.

Verify the result:

```bash
mysql -u quizarena_app -p quizarena
```

```sql
SHOW TABLES;
```

Expected tables:

- `hosts`
- `quizzes`
- `questions`
- `quiz_sessions`
- `quiz_results`

## Application connection settings

QuizArena reads database credentials from the server process environment. Keep
these values in a local terminal or IDE run configuration, never in a tracked
file:

```bash
export QUIZ_DB_URL='jdbc:mysql://localhost:3306/quizarena'
export QUIZ_DB_USER='quizarena_app'
export QUIZ_DB_PASSWORD='your-private-local-password'
```

Optional timeout settings use whole seconds:

```bash
export QUIZ_DB_CONNECT_TIMEOUT_SECONDS='5'
export QUIZ_DB_SOCKET_TIMEOUT_SECONDS='10'
```

Credentials must be supplied separately; embedding a user or password in the
JDBC URL is rejected. The application never prints the configured password.

## Create the first host account

After exporting the application connection settings, run the local provisioning
tool from a real terminal:

```bash
mvn compile exec:java \
  -Dexec.mainClass=com.quizarena.security.HostAccountProvisioner \
  -Dexec.args=host-admin
```

The tool prompts twice without displaying the password. It stores only a random
salt, a PBKDF2-HMAC-SHA-256 hash and the iteration count. A duplicate username is
rejected by the database. Do not create hosts with handwritten SQL hashes.

## TLS server settings

The host-control listener requires a PKCS#12 keystore and never reads private-key
passwords from source files:

```bash
export QUIZ_TLS_ENABLED='true'
export QUIZ_TLS_BIND_HOST='0.0.0.0'
export QUIZ_TLS_PORT='8443'
export QUIZ_TLS_KEYSTORE_PATH='/absolute/private/path/quizarena-server.p12'
export QUIZ_TLS_KEYSTORE_PASSWORD='your-private-keystore-password'
```

Secure host control is opt-in. When `QUIZ_TLS_ENABLED` is absent or `false`, the
gameplay server still starts but does not open the TLS host port. When enabled,
startup validates the MySQL connection and TLS key material before accepting
host connections.

`QUIZ_TLS_KEY_PASSWORD` is optional when the key and keystore use the same
password. Optional limits are `QUIZ_TLS_MAX_CONNECTIONS` (default `32`) and
`QUIZ_TLS_SOCKET_TIMEOUT_SECONDS` (default `60`). Keystores and private keys are
ignored by Git and must never be committed.

With the server running, the local verification tool prompts privately for the
keystore and QuizArena host passwords, then verifies TLS 1.3 login and room creation:

```bash
mvn compile exec:java \
  -Dexec.mainClass=com.quizarena.security.HostControlVerifier \
  -Dexec.args=host-admin
```

## Test database

Use a separate disposable database for the opt-in JDBC integration test:

```sql
CREATE DATABASE quizarena_test
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON quizarena_test.*
    TO 'quizarena_app'@'localhost';
```

Apply the same schema:

```bash
mysql -u root -p quizarena_test < sql/schema.sql
```

Set the test connection only in the current terminal session. Never commit the
password or place it in a tracked file.

```bash
export QUIZ_M3_TEST_DB_URL='jdbc:mysql://localhost:3306/quizarena_test'
export QUIZ_M3_TEST_DB_USER='quizarena_app'
export QUIZ_M3_TEST_DB_PASSWORD='your-private-local-password'
mvn -Dtest=JdbcMySqlTest test
```
