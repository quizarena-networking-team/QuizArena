-- QuizArena MySQL 8.4 schema
--
-- Apply as a database administrator:
--   mysql -u root -p quizarena < sql/schema.sql
--
-- This file is intentionally safe to run more than once. It creates the five
-- persistent tables required by the v1 architecture without storing secrets,
-- creating database users, or inserting development accounts.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS hosts (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username            VARCHAR(64) NOT NULL,
    password_salt       VARBINARY(64) NOT NULL,
    password_hash       VARBINARY(64) NOT NULL,
    password_iterations INT UNSIGNED NOT NULL,
    created_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT uq_hosts_username UNIQUE (username),
    CONSTRAINT chk_hosts_username_not_blank
        CHECK (CHAR_LENGTH(TRIM(username)) BETWEEN 3 AND 64),
    CONSTRAINT chk_hosts_password_iterations
        CHECK (password_iterations >= 100000)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS quizzes (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title      VARCHAR(160) NOT NULL,
    category   VARCHAR(80) NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
               ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_quizzes_created_by (created_by),
    CONSTRAINT fk_quizzes_host
        FOREIGN KEY (created_by) REFERENCES hosts (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_quizzes_title_not_blank
        CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 160),
    CONSTRAINT chk_quizzes_category_not_blank
        CHECK (CHAR_LENGTH(TRIM(category)) BETWEEN 1 AND 80)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS questions (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    quiz_id          BIGINT UNSIGNED NOT NULL,
    question_order   INT UNSIGNED NOT NULL,
    question_text    VARCHAR(1000) NOT NULL,
    option_a         VARCHAR(500) NOT NULL,
    option_b         VARCHAR(500) NOT NULL,
    option_c         VARCHAR(500) NOT NULL,
    option_d         VARCHAR(500) NOT NULL,
    correct_option   CHAR(1) NOT NULL,
    points           INT UNSIGNED NOT NULL DEFAULT 100,
    duration_seconds INT UNSIGNED NOT NULL DEFAULT 20,
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT uq_questions_quiz_order UNIQUE (quiz_id, question_order),
    CONSTRAINT fk_questions_quiz
        FOREIGN KEY (quiz_id) REFERENCES quizzes (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT chk_questions_order CHECK (question_order BETWEEN 1 AND 100),
    CONSTRAINT chk_questions_text_not_blank CHECK (CHAR_LENGTH(TRIM(question_text)) > 0),
    CONSTRAINT chk_questions_option_a_not_blank CHECK (CHAR_LENGTH(TRIM(option_a)) > 0),
    CONSTRAINT chk_questions_option_b_not_blank CHECK (CHAR_LENGTH(TRIM(option_b)) > 0),
    CONSTRAINT chk_questions_option_c_not_blank CHECK (CHAR_LENGTH(TRIM(option_c)) > 0),
    CONSTRAINT chk_questions_option_d_not_blank CHECK (CHAR_LENGTH(TRIM(option_d)) > 0),
    CONSTRAINT chk_questions_correct_option CHECK (correct_option IN ('A', 'B', 'C', 'D')),
    CONSTRAINT chk_questions_points CHECK (points BETWEEN 1 AND 1000000),
    CONSTRAINT chk_questions_duration CHECK (duration_seconds BETWEEN 1 AND 3600)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS quiz_sessions (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    room_code  VARCHAR(12) NOT NULL,
    host_id    BIGINT UNSIGNED NOT NULL,
    quiz_id    BIGINT UNSIGNED NULL,
    status     ENUM('LOBBY', 'RUNNING', 'FINISHED', 'CLOSED') NOT NULL DEFAULT 'LOBBY',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at TIMESTAMP(3) NULL,
    ended_at   TIMESTAMP(3) NULL,

    PRIMARY KEY (id),
    KEY idx_quiz_sessions_room (room_code),
    KEY idx_quiz_sessions_host_status (host_id, status),
    KEY idx_quiz_sessions_quiz (quiz_id),
    CONSTRAINT fk_quiz_sessions_host
        FOREIGN KEY (host_id) REFERENCES hosts (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_quiz_sessions_quiz
        FOREIGN KEY (quiz_id) REFERENCES quizzes (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_quiz_sessions_room_code
        CHECK (CHAR_LENGTH(room_code) BETWEEN 4 AND 12
               AND room_code = UPPER(room_code)),
    CONSTRAINT chk_quiz_sessions_times
        CHECK (ended_at IS NULL OR (started_at IS NOT NULL AND ended_at >= started_at))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS quiz_results (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id     BIGINT UNSIGNED NOT NULL,
    player_key     VARCHAR(64) NOT NULL,
    player_name    VARCHAR(24) NOT NULL,
    score          INT NOT NULL,
    correct_count  INT UNSIGNED NOT NULL,
    answered_count INT UNSIGNED NOT NULL,
    final_rank     INT UNSIGNED NOT NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT uq_quiz_results_session_player UNIQUE (session_id, player_key),
    KEY idx_quiz_results_session_rank (session_id, final_rank),
    CONSTRAINT fk_quiz_results_session
        FOREIGN KEY (session_id) REFERENCES quiz_sessions (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT chk_quiz_results_player_key_not_blank CHECK (CHAR_LENGTH(TRIM(player_key)) > 0),
    CONSTRAINT chk_quiz_results_player_name_not_blank CHECK (CHAR_LENGTH(TRIM(player_name)) > 0),
    CONSTRAINT chk_quiz_results_score CHECK (score >= 0),
    CONSTRAINT chk_quiz_results_counts CHECK (correct_count <= answered_count),
    CONSTRAINT chk_quiz_results_rank CHECK (final_rank >= 1)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

