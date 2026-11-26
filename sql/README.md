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

