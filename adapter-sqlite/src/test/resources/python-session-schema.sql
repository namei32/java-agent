CREATE TABLE sessions (
  key TEXT PRIMARY KEY,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  last_consolidated INTEGER NOT NULL DEFAULT 0,
  metadata TEXT,
  last_user_at TEXT,
  last_proactive_at TEXT,
  next_seq INTEGER NOT NULL DEFAULT 0,
  future_column TEXT
);
CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  session_key TEXT NOT NULL,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT,
  tool_chain TEXT,
  extra TEXT,
  ts TEXT NOT NULL,
  future_column TEXT,
  UNIQUE(session_key, seq)
);
INSERT INTO sessions VALUES (
  'python-demo', '2026-07-12T00:00:00+08:00', '2026-07-12T00:00:01+08:00',
  0, '{"unknown":"keep"}', '2026-07-12T00:00:00+08:00', NULL, 2, 'keep-session'
);
INSERT INTO messages VALUES (
  'python-demo:0', 'python-demo', 0, 'user', 'Python 问题', NULL,
  '{"unknown":"keep"}', '2026-07-12T00:00:00+08:00', 'keep-message-0'
);
INSERT INTO messages VALUES (
  'python-demo:1', 'python-demo', 1, 'assistant', 'Python 回答', NULL,
  '{"unknown":"keep"}', '2026-07-12T00:00:01+08:00', 'keep-message-1'
);
