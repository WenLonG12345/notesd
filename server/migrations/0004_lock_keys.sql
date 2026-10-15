-- Locked notes keyring (§10.1): the locked-notes key wrapped under the lock
-- password, an optional recovery code and any passkeys. The server can open
-- none of them.
CREATE TABLE lock_keys (
    id            TEXT    PRIMARY KEY,       -- 'password', 'recovery', or a passkey entry id
    kind          TEXT    NOT NULL CHECK (kind IN ('password', 'recovery', 'passkey')),
    credential_id TEXT,                      -- passkeys: WebAuthn credential id, base64url
    salt          TEXT    NOT NULL,          -- base64url
    iterations    INTEGER NOT NULL DEFAULT 0, -- PBKDF2 kinds; 0 for passkeys (HKDF)
    wrapped       TEXT    NOT NULL,          -- base64url(nonce ‖ ciphertext ‖ tag)
    created_at    INTEGER NOT NULL
) WITHOUT ROWID;

-- SHA-256 of HMAC(locked-notes key, "notesd:verifier:v1"). Changing the
-- keyring needs the verifier, so a stolen session alone can't replace or
-- delete the wraps and strand the locked notes.
CREATE TABLE lock_state (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    verifier_hash TEXT    NOT NULL
);
