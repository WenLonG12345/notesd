# Releasing

Pushing a `v*` tag runs `.github/workflows/release.yml`, which checks
everything, builds the release, and publishes a GitHub Release with:

- `notesd-linux-amd64`, `notesd-linux-arm64`, `notesd-darwin-arm64`
- `notesd-<version>.apk`, signed with the release key
- `SHA256SUMS`

```bash
git tag -a v0.2.0 -m v0.2.0
git push origin v0.2.0
```

Tags with a suffix (`v0.2.0-rc1`) are published as pre-releases.

## Versions

The version comes from `git describe --tags`, both in the server binary
(`notesd version`) and in the APK. Android also needs an increasing
integer `versionCode`, derived from the tag by `mise run release:android`:

| Tag | versionCode |
| --- | --- |
| `vX.Y.Z-rcN` | `XXYYZZNN`, e.g. `v0.2.0-rc1` → `20001` |
| `vX.Y.Z` | `XXYYZZ99`, e.g. `v0.2.0` → `20099` |

So release candidates install as updates of the previous release, and the
release installs over its candidates. Minor and patch numbers must stay
below 100, and there can be at most 98 release candidates.

## Signing key

Android only installs an update when it's signed with the same key as the
installed app, so **the release keystore must never be lost or changed**:
without it, every user has to uninstall (losing unsynced notes) to update.
Keep a copy in your password manager as well as in the repository secrets.

Create it once, with Java 9 or later so it's a PKCS12 keystore, which has a
single password for the store and the key:

```bash
keytool -genkeypair -v -keystore notesd-release.jks -alias notesd \
    -keyalg RSA -keysize 4096 -validity 36500
```

The build reads it from the environment. Without `NOTESD_KEYSTORE`, the
release APK is built unsigned (and the release workflow refuses to publish
it).

| Environment variable | Repository secret |
| --- | --- |
| `NOTESD_KEYSTORE` (path to the `.jks`) | `NOTESD_KEYSTORE_BASE64` (`base64 -i notesd-release.jks`) |
| `NOTESD_KEYSTORE_PASSWORD` | `NOTESD_KEYSTORE_PASSWORD` |
| `NOTESD_KEY_ALIAS` | `NOTESD_KEY_ALIAS` |

To build a signed APK locally:

```bash
NOTESD_KEYSTORE=$PWD/notesd-release.jks NOTESD_KEYSTORE_PASSWORD=… \
NOTESD_KEY_ALIAS=notesd mise run release:android
```

## Checklist

1. `mise run check` and `mise run test:android` pass on `main`.
2. If anything changed the data model or sync protocol, `docs/architecture.md`
   says so, and older apps still sync.
3. Tag and push. When the workflow finishes, edit the generated release
   notes to describe what changed for users.
