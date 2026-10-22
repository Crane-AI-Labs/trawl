CREATE INDEX files_kind_mtime ON files(kind, mtime DESC);

CREATE INDEX files_work ON files(state, priority, mtime DESC);

CREATE INDEX segments_file ON segments(file);

CREATE TABLE files(
      id INTEGER PRIMARY KEY,
      volume TEXT NOT NULL,
      mediastore_id INTEGER NOT NULL,
      uri TEXT NOT NULL,
      rel_path TEXT NOT NULL,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,
      mime TEXT,
      size INTEGER NOT NULL,
      mtime INTEGER NOT NULL,
      width INTEGER,
      height INTEGER,
      duration_ms INTEGER,
      pages INTEGER,
      state TEXT NOT NULL DEFAULT 'new',
      detail TEXT,
      fingerprint TEXT NOT NULL,
      priority INTEGER NOT NULL DEFAULT 9,
      info TEXT,
      indexed_at INTEGER, sensitive INTEGER NOT NULL DEFAULT 0, looks_private REAL,
      UNIQUE(volume, mediastore_id)
    );

CREATE VIRTUAL TABLE fts USING fts5(name, body, tokenize = 'porter unicode61 remove_diacritics 2');

CREATE TABLE meta(
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );

CREATE TABLE segments(
      id INTEGER PRIMARY KEY,
      file INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
      kind TEXT NOT NULL,
      modality TEXT NOT NULL DEFAULT 'none',
      loc_start INTEGER,
      loc_end INTEGER,
      late INTEGER NOT NULL DEFAULT 0,
      vec BLOB
    );

CREATE TRIGGER segments_gone AFTER DELETE ON segments BEGIN
      DELETE FROM fts WHERE rowid = old.id;
    END;
