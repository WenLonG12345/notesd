-- Server-wide settings set from the apps, such as the Resend key and sender
-- used to email notes.
CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;
