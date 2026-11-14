CREATE TABLE msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, name TEXT, text TEXT, uuid TEXT, q_from TEXT, q_text TEXT, q_ts INTEGER, sys INTEGER DEFAULT 0, uid INTEGER, q_uid INTEGER);
CREATE TABLE sessions (
        token     TEXT PRIMARY KEY,
        uid       INTEGER NOT NULL,
        last_ts   INTEGER NOT NULL,
        created   INTEGER NOT NULL
    );
CREATE TABLE sqlite_sequence(name,seq);
CREATE TABLE tokens (token TEXT PRIMARY KEY, username TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'user', banned INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL);
CREATE TABLE users (
        uid       INTEGER PRIMARY KEY AUTOINCREMENT,
        uuid      TEXT UNIQUE,
        name      TEXT,
        email     TEXT UNIQUE,
        pw_salt   TEXT,
        pw_hash   TEXT,
        banned    INTEGER NOT NULL DEFAULT 0,
        uuid_ts   INTEGER NOT NULL DEFAULT 0,
        last_ts   INTEGER NOT NULL DEFAULT 0,
        created   INTEGER NOT NULL
    , archived INTEGER NOT NULL DEFAULT 0);
