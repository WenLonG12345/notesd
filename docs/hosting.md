# Hosting notesd

notesd is one program and one data directory. This guide installs it on a
Linux machine, runs it with systemd, puts HTTPS in front of it, and creates
your account.

## What you need

- A Linux machine that stays on: a VPS, a home server or a Raspberry Pi
  with a 64-bit OS. One process with no other services, so a small machine
  is enough. macOS on Apple silicon works too, for trying it out.
- One of the following:
  - **A domain name** pointing at the machine, for access from anywhere.
    You'll put HTTPS in front of notesd (step 4).
  - **A private network only:** your home LAN, or a VPN such as Tailscale or
    WireGuard. HTTPS is optional there (see [Without a domain](#without-a-domain)).

## 1. Install the binary

Download the binary for your machine from the
[latest release](https://github.com/cassiozen/notesd/releases/latest):

| Machine | File |
| --- | --- |
| Most VPSes and PCs | `notesd-linux-amd64` |
| Raspberry Pi 4/5, ARM servers | `notesd-linux-arm64` |
| Mac with Apple silicon | `notesd-darwin-arm64` |

```bash
VERSION=v0.1.0   # the release you're installing
curl -LO https://github.com/cassiozen/notesd/releases/download/$VERSION/notesd-linux-amd64
curl -LO https://github.com/cassiozen/notesd/releases/download/$VERSION/SHA256SUMS
sha256sum --check --ignore-missing SHA256SUMS
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd
notesd version
```

The web app is inside the binary; there's nothing else to install. To build
it yourself instead, see [development.md](development.md).

## 2. Run it with systemd

Create a user for the service and the unit file
`/etc/systemd/system/notesd.service`:

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin notesd
```

```ini
[Unit]
Description=notesd
After=network.target

[Service]
Type=simple
User=notesd
Group=notesd
ExecStart=/usr/local/bin/notesd --data-dir /var/lib/notesd --listen 127.0.0.1:8080
Environment=NOTESD_PUBLIC_URL=https://notes.example.com
Restart=on-failure
RestartSec=5
StateDirectory=notesd
StateDirectoryMode=0700

[Install]
WantedBy=multi-user.target
```

Set `NOTESD_PUBLIC_URL` to the address you'll open notesd at (see
[Configuration](#configuration)). `StateDirectory` makes systemd create
`/var/lib/notesd` owned by the `notesd` user. Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now notesd
journalctl -u notesd -f
```

notesd shuts down cleanly on `systemctl stop`: requests in progress finish
first. Database changes are atomic, so even a power cut leaves the
database consistent.

## 3. Create your account

On its first start, notesd logs a **setup link**:

```text
WARN no owner account yet: open setup_url to create it, ... setup_url=https://notes.example.com/setup#k3J9… setup_token=k3J9…
```

Open the link (after step 4, if you're setting up HTTPS) and choose a
username and password. Until an account exists, nobody can create one
without this token, so a stranger can't claim a fresh server.

- The token changes every time the server restarts. To keep it fixed, set
  `--setup-token` / `NOTESD_SETUP_TOKEN`; this is useful when you can't
  read the log easily.
- You can also paste the token into the Android app's "New server" screen
  instead of using the web app.
- Or create the account from the shell, which only needs access to the
  data directory:

  ```bash
  sudo -u notesd sh -c "NOTESD_PASSWORD='…' notesd create-owner --username me --data-dir /var/lib/notesd"
  ```

notesd has one account. Sign in with it from as many browsers and phones as
you like.

## 4. HTTPS with a reverse proxy

notesd speaks plain HTTP and expects a reverse proxy to handle TLS. Keep it
listening on `127.0.0.1` so only the proxy can reach it.

**Caddy** gets and renews a certificate automatically. `/etc/caddy/Caddyfile`:

```text
notes.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

**nginx** with a certificate from certbot:

```nginx
server {
    listen 443 ssl;
    server_name notes.example.com;
    ssl_certificate     /etc/letsencrypt/live/notes.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/notes.example.com/privkey.pem;

    client_max_body_size 5m;   # notesd accepts request bodies up to 4 MiB

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }
}
```

Things to get right:

- **Set `--public-url`** to the `https://` address. That makes the session
  cookie `Secure`, and it makes the setup link point at the right place.
- **Serve notesd at the root of a host name** (`https://notes.example.com/`),
  not under a path like `/notes`.
- **Keep the `Host` header**, or set `--public-url`. notesd rejects changes
  from a page whose `Origin` doesn't match the `Host` header or the public
  URL, so a misconfigured proxy shows up as every save failing with 403.
- **Login rate limiting:** failed logins are limited to 10 per 15 minutes
  per client address. Behind a proxy, every client shares the proxy's
  address, so the limit is shared too: someone guessing passwords can lock
  you out of signing in for up to 15 minutes. Your existing sessions
  aren't affected.

## Without a domain

On a home network or a VPN you can skip the proxy and listen on the
network directly:

```bash
notesd --data-dir /var/lib/notesd --listen 0.0.0.0:8080
```

Then open `http://<server address>:8080`. Over plain HTTP, sign-in cookies
aren't marked `Secure`, and your password crosses the network unencrypted.
That's acceptable on a network you trust. The Android app only allows HTTP
to private addresses (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`,
`*.local`); every other address needs HTTPS.

**Tailscale** gives each machine an HTTPS name with `tailscale serve`:

```bash
tailscale serve --bg 8080
```

Then use `--public-url https://<machine>.<tailnet>.ts.net`.

## Configuration

Every option is a flag or an environment variable; there's no config file.

| Flag | Environment | Default | |
| --- | --- | --- | --- |
| `--data-dir` | `NOTESD_DATA_DIR` | `./data` | Where `notes.db` lives |
| `--listen` | `NOTESD_LISTEN` | `127.0.0.1:8080` | Address and port to listen on |
| `--public-url` | `NOTESD_PUBLIC_URL` | unset | The address users open; `https://` makes cookies `Secure` |
| `--log-level` | `NOTESD_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `--log-format` | `NOTESD_LOG_FORMAT` | `text` | `json` for log collectors |
| `--setup-token` | `NOTESD_SETUP_TOKEN` | generated | Fixed setup token while no account exists |

On machines with little memory, set `GOMEMLIMIT` (for example
`Environment=GOMEMLIMIT=64MiB` in the unit) so Go frees memory sooner.

## Email

Notes can be emailed from the apps through [Resend](https://resend.com).
You enter the API key and sender address in the app's settings while signed
in (web: Settings → Email; Android: Settings → Email). They're stored on the
server, so you set them up once for all your devices. The server needs
outbound HTTPS access to `api.resend.com`.

## Next

- [Install the Android app](android.md)
- [Upgrades, backups and maintenance](operations.md)
