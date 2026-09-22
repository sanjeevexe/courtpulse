CREATE TABLE application_users (
    subject VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE followed_games (
    user_subject VARCHAR(255) NOT NULL REFERENCES application_users(subject) ON DELETE CASCADE,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    followed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_subject, game_id)
);

CREATE INDEX idx_followed_games_user_time
    ON followed_games (user_subject, followed_at DESC, game_id);
