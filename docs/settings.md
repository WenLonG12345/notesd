# Settings

Settings is a list of categories in two groups. Each category opens its own page. Web, iOS and
Android use the same categories, names and order. A platform leaves out a category that has
nothing in it there, and leaves out rows it doesn't support.

When you add a setting, put it in one of these categories. Add a new category only when a feature
has several settings that don't fit any existing one. Update this file in the same change.

## Groups

- **Your account**: stored on the server. Every device signed in to the account sees the same
  values.
- **This device**: stored only on this browser, iPhone or Android phone.

A row inside a "Your account" category that applies to this device only says so in its footnote
("Only on this iPhone", "Only on this phone"). Biometric unlock is one example.

## Categories

Each row in the top-level list shows its title and a one-line summary of the current state. The
summary is never a description of the category.

| # | Group | Category | Route key | Summary in the list |
|---|-------|----------|-----------|---------------------|
| 1 | Your account | Server | `server` | `host · username`; "Signed out · host"; "Not connected" |
| 2 | Your account | Security | `security` | "No lock password"; "Locked notes · locked/unlocked"; adds " · Face ID on" (fingerprint on Android) when on |
| 3 | Your account | Spaces | `spaces` | "1 space" / "N spaces" |
| 4 | Your account | Integrations | `integrations` | "Email · <from address>"; "Email not set up" |
| 5 | This device | Appearance | `appearance` | "System" / "Light" / "Dark" |
| 6 | This device | Sync & data | `device` | sync status ("Up to date", "N notes waiting", "Offline", …) |
| 7 | This device | About | `about` | "Version X.Y.Z" |

On a wide web window, the category list sits beside the selected category's page, and Server is
selected when Settings opens. A category has its own address (`/settings/security`). On a phone,
the list is its own screen and a category opens on top of it with a back button.

## What goes where

| Setting | Category | Synced? | Web | iOS | Android |
|---------|----------|---------|-----|-----|---------|
| Server address, signed-in username | Server | account | ✓ | ✓ | ✓ |
| Connect to a server / Sign in again | Server | – | – | ✓ | ✓ |
| Sign out | Server | – | ✓ | ✓ | ✓ |
| Change password (+ `notesd reset-password` hint) | Server | account | ✓ | – | – |
| Lock password: create hint, lock now, change, forgot/reset | Security | account | ✓ | ✓ | ✓ |
| Passkeys for locked notes | Security | account | ✓ | – | – |
| Unlock with Face ID / fingerprint | Security | **device** | – | ✓ | ✓ |
| Signed-in devices (list, sign one out) | Security | account | ✓ | – | – |
| Spaces: list, new, edit, delete | Spaces | account | ✓ | ✓ | ✓ |
| Email via Resend (API key, From) | Integrations | account | ✓ | ✓ | ✓ |
| Theme | Appearance | device | ✓ | ✓ | ✓ |
| Sync status, last synced, last error, Sync now | Sync & data | device | – | ✓ | – |
| Remove all data from this device | Sync & data | device | – | ✓ | ✓ |
| App version | About | device | – | ✓ | ✓ |

The rules for when a category or row is visible don't change from before the split:

- Integrations appears only when signed in.
- Security appears when signed in or when a lock password already exists.
- Sync & data's status rows appear only when there's an account.

Because of these rules, web shows no Sync & data or About category, and a native app that isn't
connected shows only Server, Spaces, Appearance, Sync & data and About.
