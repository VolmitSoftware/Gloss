# Gloss relay

The Gloss relay connects the Gloss web editor to Gloss servers that accept no inbound connections. A server connects out to `/agent`; a browser connects to `/app` and subscribes to one server by its fingerprint. The relay forwards the browser's editor API requests to that server and the answers back. `GET /healthz` reports connection counts.

The plugin serves the editor API itself, on its own listener (`/gloss/v1/...`) and through this relay (`/v1/...` inside relay frames). It answers the links `/gloss editor pair` prints: a link carries the server's `relayUrl` and fingerprint, and the plugin connects to the relay at the first pairing and at every start after that while a token or an unused link exists. [Editor API protocol](#editor-api-protocol) below describes the editor API.

Everything is in memory. The relay stores no documents, tokens or frames, and logs no payloads. A restart drops every connection; servers and browsers reconnect.

## What the relay can see

The relay is a trusted pass-through. TLS protects traffic on the wire, but the relay process reads every frame it forwards, including the editor's bearer tokens and the documents being edited. There is no end-to-end encryption between browser and server, so whoever operates the relay can read the traffic it forwards. Operators who mind run their own relay (`relayUrl` under `[editor]` in gloss.toml) or turn it off (`relayEnabled = false`). Tokens are scoped, end 7 days after their last use by default and can be revoked with `/gloss editor revoke`.

Over the relay the editor checks that it reached the paired server before it sends a token or pairing secret. It sends a random 32-byte `X-Gloss-Challenge` (base64url) with `GET /v1/hello`; the server answers with `X-Gloss-Server-Key` and `X-Gloss-Proof`, an Ed25519 signature over the UTF-8 text `gloss-editor-proof/1 ` followed by the challenge. The editor needs the key's SHA-256 to equal the fingerprint from the link and the signature to verify. The prefix differs from the registration prefix `gloss-relay/1 `, so neither signature can stand in for the other. This stops a relay from posing as a server that is not connected to it. It does not stop a relay that forwards to the real server from reading the traffic.

The relay does not check tokens. The server checks each forwarded request's token, scope, folders and `X-Gloss-Counter` the same way as a direct one. The browser origin of a relayed request is checked here, against `ALLOWED_APP_ORIGINS`, not against the plugin's `allowedOrigins`.

## Relay protocol

Frames are JSON text messages, one object each. Golden frames live in `schema/editor/fixtures/relay` (listed in its `index.json`), and `schema/editor/fixtures/relay-handshake.json` is the registration golden. The relay closes a connection that breaks a rule with an `error` frame and close code 4008. A message whose frame headers announce more than `MAX_FRAME_BYTES` (one frame, or continuation frames together) ends the TCP connection at once, before the payload is read.

| From | To | Frame types |
| --- | --- | --- |
| Server | Relay | `register`, `response`, `event`, `close` |
| Relay | Server | `challenge`, `registered`, `request`, `close`, `error` |
| Browser | Relay | `subscribe`, `request`, `close` |
| Relay | Browser | `subscribed`, `status`, `response`, `event`, `close`, `error` |

### Addresses

The caller's address comes from `X-Forwarded-For` only when `TRUSTED_PROXY_HOPS` says proxies sit in front of the relay. With the default of 1 (one front end, such as a container host's load balancer), it is the last entry, the one that front end appends. With N hops it is the Nth entry from the end. With 0 it is the socket peer, and the header is ignored. When the header has fewer entries than trusted hops, or that entry is not an IP address, the socket peer is used. Earlier entries are set by the client and ignored. The address is used for the per-address limits, which count an IPv6 address by its /64, and forwarded to the server, which uses it for its per-address pairing limit and its log lines.

A relay that browsers reach directly must set `TRUSTED_PROXY_HOPS=0`; otherwise any client can choose its own address. Behind a reverse proxy that appends to `X-Forwarded-For`, set it to the number of such proxies.

Each address may hold `MAX_SERVER_CONNECTIONS_PER_ADDRESS` `/agent` connections and `MAX_APP_CONNECTIONS_PER_ADDRESS` `/app` connections, registered or not. Past that the upgrade gets 503.

### Server, `/agent`

1. The relay sends `{"type":"challenge","protocol":1,"nonce":"<32 bytes, base64url>"}`.
2. The server answers within 10 s with `{"type":"register","protocol":1,"serverKey":"<raw 32-byte Ed25519 key, base64url>","signature":"<base64url>"}`. The signature is Ed25519 over the UTF-8 text `gloss-relay/1 ` followed by the nonce.
3. The relay replies `{"type":"registered","fingerprint":"sha256:<hex>"}`, the SHA-256 of the raw key. This is the same fingerprint as in pairing links and `GET /v1/hello`. A newer registration with the same key replaces the older connection.
4. The relay sends `request` frames with its own ids. The server answers each with one `response` frame. For `GET /v1/events` a 2xx response opens a stream: the server sends `{"type":"event","id":N,"event":{...}}` frames until it sends `{"type":"close","id":N}` or the relay sends `close` for that id (the browser stopped the stream or left, or the request timed out).

The server's handshake must carry no `Origin` header.

### Browser, `/app`

1. The handshake's `Origin` must equal one of `ALLOWED_APP_ORIGINS` exactly. Otherwise the relay answers 403.
2. Within 10 s the browser sends `{"type":"subscribe","protocol":1,"fingerprint":"sha256:<hex>"}`. The relay answers `{"type":"subscribed","fingerprint":...,"online":true|false}`, and later `{"type":"status","online":...}` when the server connects or disconnects. One address may hold at most `MAX_APPS_PER_ADDRESS_PER_SERVER` subscriptions to one server.
3. Within 30 s of subscribing the browser must have an event stream the server accepted (a 2xx answer to `GET /v1/events`, which needs a valid token). Otherwise the relay closes it with a `timeout` error. Pairing (`hello`, then `POST /v1/pair`) fits in that time and then closes. At most `MAX_APPS_SIGNING_IN_PER_SERVER` browsers, and `MAX_APPS_SIGNING_IN_PER_ADDRESS_PER_SERVER` from one address, may be subscribed to one server without having signed in; past that the subscription is closed with a `capacity` error. The rest of `MAX_APPS_PER_SERVER` is kept for signed-in browsers.
4. The browser sends `request` frames: `{"type":"request","id":N,"method":"GET|POST|DELETE","path":"/v1/...","headers":{...},"body":...}`. Ids are positive integers unique among the browser's open requests. The relay answers with the same id. Before forwarding, the relay replaces `Origin` with the handshake's origin and `X-Forwarded-For` with the browser's address, and removes `Forwarded` and `Host`.
5. Event streams use `GET /v1/events` with the token in `Authorization`. The direct path's `auth` frame is not used here. The browser sends `{"type":"close","id":N}` to end a stream.

The relay answers some requests itself, with an error body (`{"error":{"code":...,"message":...}}`):

| Status | Code | When |
| ---: | --- | --- |
| 400 | `invalid-request` | The id is already in use on this connection |
| 413 | `too-large` | The request with the relay's headers would exceed the frame limit |
| 429 | `rate-limited` | Too many pending requests or streams on this connection |
| 503 | `busy` | The relay's total pending-request limit is reached |
| 503 | `server-offline` | The server is not connected, or disconnected before it answered |
| 504 | `timeout` | The server did not answer within the request timeout |

A frame holds at most 1 MiB by default, the same as the plugin's `relayFrameBytes`; keep the two equal. The plugin cuts catalog pages and batch reads to fit and answers `413 too-large` for anything larger, such as a big image upload or publish. The editor does not retry on its own: it says the request is too large for the relay and asks for a direct connection or fewer documents at once.

## Running locally

```sh
cd gloss-web/sync-relay
dart pub get
PORT=9801 BIND_ADDRESS=127.0.0.1 TRUSTED_PROXY_HOPS=0 ALLOWED_APP_ORIGINS=http://127.0.0.1:8800 dart run bin/relay.dart
```

Point a test server at it in gloss.toml:

```toml
[editor]
relayUrl = "ws://127.0.0.1:9801"
```

Plain `ws://` is accepted only on loopback; servers in production use `wss://`. When the editor also runs locally on port 8800, set `editorUrl = "http://127.0.0.1:8800"` too, so the links `/gloss editor pair` prints open it. The server connects to the relay at that first pairing.

SIGTERM or SIGINT stops the relay: it stops accepting, closes every server and browser connection with code 1001 (going away), and exits within about 2 seconds. Servers dial the new relay as soon as it listens. A server whose relay vanished without closing its socket pings it and dials again after 30 seconds without an answer.

Checks, from this folder (the tests read `../../schema/editor/fixtures`):

```sh
dart analyze
dart test
```

## Settings

All settings are environment variables.

| Variable | Default | Purpose |
| --- | ---: | --- |
| `PORT` | `8080` | Listener port |
| `BIND_ADDRESS` | `0.0.0.0` | Listener address |
| `TRUSTED_PROXY_HOPS` | `1` | Proxies in front of the relay that append to `X-Forwarded-For`; 0 when browsers connect directly |
| `ALLOWED_APP_ORIGINS` | `https://gloss.volmitsoftware.com,https://gloss-editor.web.app,https://gloss-editor.firebaseapp.com` | Comma-separated exact browser origins (`scheme://host[:port]`, no wildcards) |
| `MAX_FRAME_BYTES` | `1048576` | Largest text frame in UTF-8 bytes |
| `MAX_APP_MESSAGES_PER_SECOND` | `120` | Frames per second from one browser |
| `MAX_AGENT_MESSAGES_PER_SECOND` | `2000` | Frames per second from one server, not counting responses to waiting requests |
| `MAX_PENDING_PER_APP` | `64` | Unanswered requests per browser |
| `MAX_STREAMS_PER_APP` | `4` | Event streams per browser |
| `MAX_PENDING_GLOBAL` | `4096` | Unanswered requests in total |
| `MAX_APPS_PER_SERVER` | `64` | Browsers subscribed to one server |
| `MAX_APPS_PER_ADDRESS_PER_SERVER` | `8` | Browsers from one address subscribed to one server |
| `MAX_APPS_SIGNING_IN_PER_SERVER` | `16` | Browsers subscribed to one server that have not signed in yet |
| `MAX_APPS_SIGNING_IN_PER_ADDRESS_PER_SERVER` | `2` | Browsers from one address subscribed to one server that have not signed in yet |
| `MAX_SERVER_CONNECTIONS_PER_ADDRESS` | `32` | `/agent` connections from one address |
| `MAX_APP_CONNECTIONS_PER_ADDRESS` | `64` | `/app` connections from one address |
| `MAX_SERVERS` | `1000` | Registered servers |
| `MAX_APPS` | `4096` | Browser connections |
| `HANDSHAKE_TIMEOUT_SECONDS` | `10` | Time for a server to register |
| `SUBSCRIPTION_TIMEOUT_SECONDS` | `10` | Time for a browser to subscribe |
| `SIGN_IN_TIMEOUT_SECONDS` | `30` | Time after subscribing for a browser to open an accepted event stream |
| `REQUEST_TIMEOUT_SECONDS` | `10` | Time for a server to answer |
| `PING_INTERVAL_SECONDS` | `30` | WebSocket ping interval; a peer that misses a pong is closed |

## Container hosting

The image is a compiled Dart binary, `gloss-relay`, on distroless, running as the non-root user. It needs no volume.

```sh
cd gloss-web/sync-relay
docker build -t gloss-relay .
docker run --rm -p 8080:8080 -e ALLOWED_APP_ORIGINS=https://gloss.volmitsoftware.com gloss-relay
```

- Run exactly one instance. Routing is in memory, so a second instance would not see the first one's servers.
- Keep CPU allocated between frames (on Cloud Run, `--no-cpu-throttling`) so pings and timeouts keep running.
- Hosts that cap request time end WebSocket connections at that cap. Set it as high as the host allows; the plugin and the editor reconnect.
- Serve the relay on its own hostname. Do not route it through a Firebase Hosting rewrite.
- Set `TRUSTED_PROXY_HOPS` to the number of proxies in front of the relay that append to `X-Forwarded-For`.
- Put the `wss://` URL in each server's `relayUrl` (default `wss://gloss-relay-437930007071.us-central1.run.app`). Pairing links carry it to the browser.

### Cloud Run

The default relay runs on Cloud Run and is deployed by hand; the Gloss Hosting workflow analyzes and tests this package but does not build or ship the image. It was deployed with these commands (replace the project, project number, region and repository to deploy your own):

```sh
cd gloss-web/sync-relay
gcloud artifacts repositories create gloss --repository-format=docker --location=us-central1
gcloud builds submit --region us-central1 \
  --tag us-central1-docker.pkg.dev/PROJECT/gloss/gloss-relay:1 \
  --service-account=projects/PROJECT/serviceAccounts/PROJECT_NUMBER-compute@developer.gserviceaccount.com \
  --default-buckets-behavior=REGIONAL_USER_OWNED_BUCKET .
gcloud run deploy gloss-relay \
  --image us-central1-docker.pkg.dev/PROJECT/gloss/gloss-relay:1 \
  --region us-central1 \
  --allow-unauthenticated \
  --max-instances 1 \
  --no-cpu-throttling --cpu-boost \
  --timeout 3600 \
  --concurrency 1000 \
  --cpu 1 --memory 512Mi \
  --set-env-vars '^;^ALLOWED_APP_ORIGINS=https://gloss.volmitsoftware.com,https://gloss-editor.web.app,https://gloss-editor.firebaseapp.com'
```

- `--service-account` and `--default-buckets-behavior` are for projects where Cloud Build's default service account gets 403; elsewhere leave them out.
- `^;^` makes `;` gcloud's list separator, so the commas stay in the origin list.
- Cloud Run's front end answers paths ending in `z` itself, so `/healthz` on the `run.app` URL returns Google's 404 and never reaches the relay; only the container port serves it. From outside, check with a WebSocket handshake: `/app` with an allowed `Origin` upgrades (101), any other origin gets 403.

## Editor API protocol

The Gloss plugin serves the web editor API itself. A browser pairs once with a one-time link that opens
within 10 minutes and gets a token that lasts 7 days after its last use. The API is reachable two ways:

- the plugin's own HTTP listener, `http://127.0.0.1:9799/gloss/v1/...` by default, with the browser
  origin checked against `allowedOrigins` on every request;
- the same requests tunnelled through a relay the server dials out to over a WebSocket, so no port has to
  be opened. The relay stores nothing; the sections above document it.

The plugin serves the paged catalog with summaries, byte-bounded body pages, batch reads by key, images
by content hash, changes pushed as events within about a second, changeset publish with base hashes,
per-document and per-image conflicts, and kinds carried by reference.

Times are ISO-8601 UTC. Bodies are UTF-8 JSON.

### Document kinds

Document kinds are open slugs. The table below is generated from `EditorSyncDocumentKind` in the parent
Gloss repository and is pinned by `test/protocol_kinds_pin_test.dart` in the `gloss-web` package. The
plugin sends the same list, with the `publish:` scope of each kind, in `GET /v1/meta` (`meta.kinds`), so
an editor never has to guess which kinds exist.

| Wire kind | Storage | Layout | Versioned | Singleton id |
|---|---|---|---|---|
| `animation` | `animations` | FOLDER | yes | - |
| `behavior` | `behaviors` | FOLDER | yes | - |
| `bubble-style` | `bubbles` | FOLDER | yes | - |
| `channel` | `channels` | FOLDER | yes | - |
| `connections` | `connections.json` | SINGLE | yes | `ConnectionsDoc.KIND` |
| `container-preview` | `previews` | FOLDER | no | - |
| `damage-indicators` | `damage-indicators` | FOLDER | yes | `default` |
| `emoji` | `emoji` | FOLDER | yes | - |
| `entity-overlays` | `entity-overlays` | FOLDER | yes | `default` |
| `glyph` | `glyphs` | FOLDER | yes | - |
| `hologram` | `holograms` | FOLDER | yes | - |
| `inventory` | `inventories` | FOLDER | yes | - |
| `leaderboard` | `leaderboards` | FOLDER | yes | - |
| `marker` | `markers` | FOLDER | yes | - |
| `menu` | `menus` | TREE | no | - |
| `motd` | `motd.json` | SINGLE | yes | `motd` |
| `nameplate` | `nameplates` | FOLDER | yes | - |
| `names` | `names.json` | SINGLE | yes | `names` |
| `nametag` | `nametags` | FOLDER | yes | - |
| `panel` | `panels` | TREE | yes | - |
| `presets` | `presets.json` | SINGLE | yes | `presets` |
| `real-drops` | `real-drops` | REAL_DROPS | yes | `default` |
| `scoreboard` | `boards` | FOLDER | yes | - |
| `strings` | `strings` | FOLDER | yes | - |
| `surface` | `surfaces` | FOLDER | yes | - |
| `tablist` | `tablist.json` | SINGLE | yes | `tablist` |
| `waypoint` | `waypoints` | FOLDER | yes | - |

A document key is `(kind, id)`. Menu and panel ids are tree ids (`shop/main`), other kinds use flat ids,
and singletons use their fixed id.

### 1. Commands

Root `/gloss editor`, in game or from the console. Optional values are keyed (`key=value`). All four need
`gloss.editor.pair` (section 16).

```none
/gloss editor pair [label=] [scopes=a,b] [folders=a,b] [expires=7d]
/gloss editor status
/gloss editor tokens [page=1]
/gloss editor revoke <token|all>
```

#### 1.1 `pair`

| Option | Default | Meaning |
|---|---|---|
| `label=` | none | Name shown by `/gloss editor tokens` and on the pairing card; at most 64 characters |
| `scopes=` | every scope except `publish:commands` | What the token may do (section 6); list fewer to narrow it |
| `folders=` | all folders | Limit the token to documents and images in these folders (section 6.3), such as `folders=shop,lobby` |
| `expires=` | `tokenDays` (7) as days | How long the pairing lasts without use: a number with `d`, `h` or `m`, 1 minute to 365 days. Each use starts it again |

In game the reply is one line with three click targets: **[Open editor]** opens the link, **[Copy]**
copies it, **[Revoke]** puts `/gloss editor revoke tok_...` in the chat box. Revoking before the link is
opened cancels it.

```none
[Gloss]: Link ready. Open it within 10 minutes [Open editor] [Copy] [Revoke]
```

From the console the reply is two lines:

```none
[Gloss]: Link ready. Open it within 10 minutes: https://gloss.volmitsoftware.com/#/pair/GLS1.eyJ2Ijox...
[Gloss]: Scopes read history publish:screens publish:hud publish:chat publish:world publish:players publish:shared publish:images; all folders; ends after 7d unused; token tok_Ab3dEf6hIj9kLm2n
```

With `folders=shop,lobby` the second line says `folders shop,lobby` instead of `all folders`.

Refusals are one red line, and nothing is issued:

| Cause | Line |
|---|---|
| editor disabled | `Editor not running; set [editor] enabled = true in gloss.toml` |
| unknown scope | `Unknown scope x; scopes are read, history, publish:screens, ...` (the full list) |
| `publish:commands` from a player without `gloss.editor.commands` | `publish:commands lets the editor publish documents that run console commands; it needs gloss.editor.commands or the console` |
| bad folder | `folders take folder names such as shop or shop/sub; x is not one` |
| bad expires | `expires takes a number with d, h or m, such as 7d` |
| 64 links waiting | `Too many pairing links are waiting; let some expire or revoke them first` |
| 256 tokens | `Token limit of 256 reached; revoke unused tokens first` |
| no path | `Nothing can reach this server: enable [editor] listenerEnabled or relayEnabled` |

#### 1.2 `status`

Two lines from the console. In game the first line shows, with **[Details]** holding both in its hover.

```none
[Gloss]: Editor ready | listener 127.0.0.1:9799 (http) 1/64 connections | relay wss://gloss-relay-437930007071.us-central1.run.app registered | 2 tokens, 1 pairing | 1 streams
[Gloss]: Fingerprint sha256:5f0c...e1 | <first problem, if any>
```

- Listener part: `listener <address>:<port> (http|https) n/m connections`, `listener off`, or
  `listener failed: <why>`.
- Relay part: `relay <url> registered`, `relay <url> connecting`, `relay waits for the first pairing`, or
  `relay off`.
- Problems: `listener failed: cannot bind 127.0.0.1:9799 (Address already in use); set [editor] port to a
  free port or listenerEnabled = false`, or `no listener or relay; turn one on in [editor]`.

Compare the fingerprint with the one under **Details** on the pairing card.

#### 1.3 `tokens`

One line per token, 8 per page:

```none
tok_Ab3dEf6hIj9kLm2n "laptop" | read history publish:screens ... | folders shop | expires 2026-11-20T10:00:00Z | last used 2026-11-13T09:00:00Z | Chrome on macOS | streams 1
```

`folders ...` appears only when the token is limited, `last used never` before the first use, and the
browser name (the pair request's `client`) only when one was sent. With no tokens: `No editor tokens.`

#### 1.4 `revoke`

`revoke <token>` or `revoke all` revokes tokens and cancels links not opened yet. Their event streams get
`closing` with reason `token-revoked`, relayed streams close, and requests already queued for the token
are refused without reading or writing anything. Replies: `Revoked 1 token; its connections closed`,
`Revoked 3 tokens; their connections closed`, or `No token tok_x`. Tab completion lists `all` and the
token ids.

### 2. The link and the one-time code

The link is `<editorUrl>/#/pair/<code>`, with `editorUrl` from `[editor] editorUrl` (default
`https://gloss.volmitsoftware.com`, trailing slash removed).

The code is `GLS1.` followed by base64url (no padding) of one UTF-8 JSON object, fields in this order, at
most 4096 characters in all:

```json
{"v":1,
 "directUrl":"http://127.0.0.1:9799",
 "relayUrl":"wss://gloss-relay-437930007071.us-central1.run.app",
 "fingerprint":"sha256:5f0c...64 hex",
 "serverKey":"<raw 32-byte Ed25519 public key, base64url, 43 chars>",
 "tokenId":"tok_Ab3dEf6hIj9kLm2n",
 "secret":"<32 random bytes, base64url, 43 chars>",
 "expiresAt":"2026-11-13T10:10:00Z",
 "label":"laptop",
 "serverName":"Twin Peaks",
 "scopes":["read","history","publish:screens","publish:hud","publish:chat","publish:world","publish:players","publish:shared","publish:images"],
 "folders":["shop"]}
```

- At least one of `directUrl` and `relayUrl`. `directUrl` is `https://...`, or `http://` only on
  `127.0.0.1` or `localhost`. `relayUrl` is `wss://...`, or `ws://` only on loopback. Either is absent when
  that path is off or a browser cannot reach it (section 8).
- `label` is absent when none was given; `folders` is absent when the token reaches every folder.
- The secret works once, for 10 minutes. The server keeps only its SHA-256, in memory, so a restart
  forgets links not opened yet (their browsers get `pairing-invalid`). An expired link is remembered for
  an hour so a late browser hears `pairing-expired` instead.
- The editor reads the code from the fragment, then removes it from the address bar. It accepts the full
  link or the bare `GLS1.` code pasted into its pairing fields.

### 3. The pairing card

Opening the link shows one card: the server name, when the link stops working, what the editor may do
(the scopes in plain words, section 6.1, and the folders), a browser name field (sent as `client`), a
**Remember this browser** switch, **Pair** and **Cancel**. **Details** shows the fingerprint to compare
with `/gloss editor status`, the direct and relay addresses and the link name.

The server name (`hello.serverName`, the code's `serverName`, the pair answer) is `[editor] serverName`
when set; else the MOTD's first line with colour codes removed, unless it is empty or `A Minecraft Server`;
else the server folder's name. Plain text, at most 64 characters, never empty.

### 4. Pairing exchange

On the direct path the editor reads `GET /v1/hello` first and checks its `fingerprint` equals the code's.
Over the relay it also checks the server proof (section 9.2) before it sends the secret.

`GET /v1/hello` (no token):

```json
{"protocol":1,"protocols":[1],"fingerprint":"sha256:...","gloss":"3.3.0","platform":"paper","minecraft":"26.3","serverName":"Twin Peaks"}
```

`platform` is `paper`, `folia`, `spigot`, `purpur` or `other`.

`POST /v1/pair` (no token), request at most 4 KiB, `client` optional and at most 128 characters:

```json
{"tokenId":"tok_Ab3dEf6hIj9kLm2n","secret":"<43 chars>","client":"Chrome on macOS"}
```

Answer 200:

```json
{"token":"gls_tok_Ab3dEf6hIj9kLm2n.<43 chars>",
 "tokenId":"tok_Ab3dEf6hIj9kLm2n",
 "scopes":["read","history","publish:screens","publish:hud","publish:chat","publish:world","publish:players","publish:shared","publish:images"],
 "folders":["shop"],
 "expiresAt":"2026-11-20T10:03:12Z",
 "lifetimeSeconds":604800,
 "fingerprint":"sha256:...",
 "counter":0,
 "admin":"0f6c...uuid",
 "serverName":"Twin Peaks"}
```

- `token` is shown once; the server stores only its SHA-256. Format `gls_<tokenId>.<43 base64url chars>`.
  Every other request sends it as `Authorization: Bearer <token>`.
- `folders` is absent when unlimited; `admin` (the player who made the link) is absent when the link came
  from the console.
- `counter` is the last accepted `X-Gloss-Counter` (section 12.1), 0 for a new token.
- Refusals: `401 pairing-invalid`, `410 pairing-expired`, `403 origin-not-allowed` (direct path),
  `429 rate-limited` (10 attempts per clock minute per address; `retryAfterMillis` is what is left of that
  minute and the message says it: `Too many pairing attempts from this address. Try again in 41 s.`),
  `400 invalid-request`.

### 5. Tokens

- A token lasts `lifetimeSeconds` after its last use: `expires=` when given, else `[editor] tokenDays`
  days. Every authenticated request and every event-stream open moves `expiresAt` to now plus the
  lifetime. The token file is written when the expiry moved by more than an hour, so a restart loses at
  most an hour of the slide.
- An expired token answers `401 token-expired` and its streams get `closing` reason `token-expired`. A
  revoked one answers `401 token-revoked`. Unknown or malformed: `401 unauthorized`. Expired and revoked
  ids are remembered for a day, then read as `unauthorized`.
- The event stream's `open` frame carries the current expiry; the editor says "Pairing ends in n days
  unless used." when 3 days or less remain.
- At most 256 tokens and 64 waiting links per server.

`GET /v1/token` (any valid token) lets a reloaded tab see what its pairing allows now:

```json
{"tokenId":"tok_...","label":"laptop","scopes":[...],"folders":["shop"],"expiresAt":"...","lifetimeSeconds":604800,"serverName":"Twin Peaks","fingerprint":"sha256:..."}
```

### 6. Scopes and folders

#### 6.1 Scopes

In the order shown everywhere. Plain words are what the pairing card shows.

| Scope | Allows | Plain words |
|---|---|---|
| `read` | the catalog, document bodies, images, meta, notes, the event stream | See documents and images |
| `history` | `GET /v1/history` | See stored versions |
| `publish:screens` | menus, container previews, inventories and flow maps (`panel`) | Publish menus and screens |
| `publish:hud` | scoreboards, tab lists, surfaces, MOTDs and leaderboards | Publish HUD and server list |
| `publish:chat` | chat channels, connections, emoji and bubble styles | Publish chat |
| `publish:world` | holograms, markers, waypoints, damage indicators, real drops and entity overlays | Publish world displays |
| `publish:players` | nameplates and nametags | Publish player labels |
| `publish:shared` | animations, strings, names, glyphs, behaviors and presets | Publish shared resources |
| `publish:images` | uploading images and putting or deleting image paths | Upload and delete images |
| `publish:commands` | publishing a document that runs a console command (section 6.2) | Publish documents that run console commands |

The `publish:` groups match the editor's library groups. The server states each kind's scope in
`meta.kinds[].scope`. Restoring a stored version is a publish of its body and needs that kind's scope.

#### 6.2 `publish:commands`

A Gloss action `{"type":"command","source":"server", ...}` runs its command as the console, so a document
that contains one anywhere (menus, flow maps, inventories, behaviors, any action list) is as good as
console access.

- A publish that puts a document whose new body contains such an action needs `publish:commands` as well
  as the kind's scope, even when the commands did not change. Deleting the document, or a put whose new
  body has none left, does not need it.
- Without it the whole publish is refused `403 scope-missing` naming `publish:commands` and the documents.
  Nothing is written.
- Catalog summaries mark such documents with `summary.commands: true`.
- Only the console, or a player with `gloss.editor.commands`, may pair it:
  `/gloss editor pair scopes=read,publish:screens,publish:commands`.
- A player who loses `gloss.editor.commands` loses `publish:commands` from their tokens at the next check
  (every 60 seconds); the rest of each token keeps working.

#### 6.3 Folders

- A folder is the part of a document id or image path before its last `/`: menu `shop/main` is in `shop`,
  menu `shop/sub/a` in `shop/sub`, image `shop/coin.png` in `shop`. Flat ids (`welcome`) and singletons
  are in no folder.
- A token with `folders=a,b` reaches documents and images whose folder is `a`, `b`, or inside one
  (`a/x`). Flat-id documents and singletons are invisible to it.
- Reads leave invisible entries out (catalog, pages, events, history rows, warnings). Batch reads answer
  them per ref with `folder-denied`. A publish that touches one is refused whole with `403 folder-denied`.
- Folder names are 1 to 8 segments of `[a-z0-9_-]{1,64}` joined by `/`.

### 7. Identity

- `plugins/Gloss/editor/identity.json` holds an Ed25519 key created at first start
  (`{"v":1,"seed","serverKey","fingerprint"}`, owner read and write only). It persists across restarts and
  upgrades. The fingerprint is `sha256:` and the hex SHA-256 of the raw 32-byte public key.
- A damaged file stops the editor with `editor/identity.json is damaged (<why>); delete it and restart to
  create a new editor identity, then pair the editor again`. A new key means a new fingerprint, so every
  browser pairs again.
- `plugins/Gloss/editor/tokens.json` holds the tokens: hashes only, plus label, admin, scopes, folders,
  created, expires, lifetime, last use, counter, client and admin check time.
- At start the old link folder `plugins/Gloss/editor-sync/` is deleted with one INFO line:
  `Removed N old web editor links; pair the editor again with /gloss editor pair`.

### 8. Direct listener

- Base path `/gloss/v1/`, for example `GET http://127.0.0.1:9799/gloss/v1/catalog`.
- `directUrl` in links: `advertisedUrl` when set; else `https://<listenAddress>:<port>` with TLS on a
  concrete address; else `http://127.0.0.1:<port>` on IPv4 loopback and `http://localhost:<port>` on
  `::1` or `localhost`; else none (relay only).
- Browsers accept plain `http://` only for `127.0.0.1` and `localhost`. To reach the listener from another
  machine, give it a TLS certificate (`tlsCertificate`, `tlsPrivateKey`) or put a TLS proxy in front and
  set `advertisedUrl`.
- A request with an `Origin` header must carry an allowed origin, else `403 origin-not-allowed`. The
  console logs once an hour per origin: `Editor refused origin http://localhost:8800: it is not in
  [editor] allowedOrigins`.
- CORS: `OPTIONS` answers 204. Allowed origins get `Access-Control-Allow-Origin` (the origin),
  `Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS`, `Access-Control-Allow-Headers:
  Authorization, Content-Type, X-Gloss-Counter, X-Gloss-Challenge, If-None-Match`,
  `Access-Control-Expose-Headers: ETag, Retry-After, X-Gloss-Server-Key, X-Gloss-Proof`, `Access-Control-Max-Age: 600`,
  `Access-Control-Allow-Private-Network: true` and `Vary: Origin`. Any other origin gets the same headers
  on the preflight and on its `403 origin-not-allowed`, so the editor can read the refusal; it never gets
  an answer with data.
- The token and scope are checked from the request head before any body is read. A head is at most
  16 KiB and must arrive within 5 s of its first byte. A body without a valid token (pair and the open
  endpoints) is at most 4 KiB, within 5 s; an authenticated body at most `requestBodyBytes`. Only
  `Content-Length` bodies, no chunked bodies. Idle connections close after 15 s. One address holds at
  most 16 connections (IPv6 counted by /64), `openConnections` in all.
- Bind failure: `Editor listener failed: cannot bind 127.0.0.1:9799 (Address already in use); set [editor]
  port to a free port or listenerEnabled = false. The relay still works`. The relay keeps working.
- Event stream: `GET /gloss/v1/events` upgraded to a WebSocket. The browser's first text frame must be
  `{"type":"auth","token":"gls_..."}` within 10 s. Refusals close with code `4000 + HTTP status` and the
  error code as reason, such as `4401 token-revoked`. The server pings every 30 s.

### 9. Relay tunnel

#### 9.1 Connection

- The server connects to `<relayUrl>/agent`, browsers to `<relayUrl>/app`.
- The relay connection opens at the first `/gloss editor pair`, so a server nobody pairs makes no
  outbound connection. After that it opens at every start while a token or a waiting link exists, with no
  delay on the first attempt. Until then `status` says `relay waits for the first pairing`.
- Reconnects back off from `relayReconnectMinSeconds` to `relayReconnectMaxSeconds`, doubling, each delay
  drawn from the upper half.
- Registration: the relay sends a `challenge` nonce; the server answers within 10 s with `register`,
  its `serverKey` and an Ed25519 signature over the UTF-8 text `gloss-relay/1 ` followed by the nonce; the
  relay replies `registered` with the fingerprint and routes browsers that subscribe with it to this
  server. A newer registration with the same key replaces the older one. [Relay protocol](#relay-protocol) has
  the frames.
- Requests arrive as `request` frames with paths under `/v1/...` and are answered with `response` frames.
  A 2xx answer to `GET /v1/events` keeps the stream open with `event` frames until either side sends
  `close`. Events over the relay authenticate with `Authorization`, not the `auth` frame.
- The relay sets `Origin` to the browser's handshake origin, checked against the relay's
  `ALLOWED_APP_ORIGINS` rather than the plugin's `allowedOrigins`, and `X-Forwarded-For` to the browser's
  address. The server checks token, scope, folders and counter exactly as on the direct path.
- Every frame is at most `relayFrameBytes` (1 MiB). A request body over the relay is therefore a little
  under 1 MiB, and an answer that would not fit is refused `413 too-large`; the editor then says so and
  asks for a direct connection or a smaller publish. Pages and batch answers are cut to fit (section 10). At most
  `relayConcurrentRequests` relayed requests run at once; past that the server answers `503 busy`.
- The relay can read what it forwards; there is no end-to-end encryption. Operators who mind run their
  own relay (`relayUrl`) or turn it off (`relayEnabled = false`).
- Console: `Editor relay connecting to <url> as <fingerprint>`, `Editor relay registered <fingerprint> at
  <url>`, and once per outage `Editor relay unreachable at <url>: <why>; retrying in the background`.

#### 9.2 Server proof

Before it sends a pairing secret or a token over the relay, the editor sends `GET /v1/hello` with
`X-Gloss-Challenge: <32 random bytes, base64url, 43 chars>`. The server adds `X-Gloss-Server-Key: <its
serverKey>` and `X-Gloss-Proof: <Ed25519 signature, base64url, 86 chars>` over the UTF-8 text
`gloss-editor-proof/1 ` followed by the challenge. The editor requires `sha256(serverKey)` to equal the
code's fingerprint and the signature to verify, else it says "A different server answered." and sends
nothing. The two signature prefixes differ, so neither signature passes as the other. The direct path
needs no proof, but `hello.fingerprint` must still match.

#### 9.3 Path choice

The editor tries the last path that worked, the first time direct before relay. A path counts once
`hello` (with the proof on the relay) and the event stream's `open` frame arrived; requests follow the
stream. While on the relay with a direct path paired it probes direct after 15 s, 30 s, then every 60 s,
and moves over without going offline. It keeps at most 6 requests in flight (the server allows 8 per
token). `429 rate-limited` and `503 busy` carry `retryAfterMillis` and `Retry-After`; the editor waits
that long plus jitter and retries the same request, at most 5 times.

### 10. Endpoints

Base `/gloss/v1` on the direct path, `/v1` inside relay frames. "Counted" requests need
`X-Gloss-Counter` (section 12.1).

| Method and path | Scope | Counted | Section |
|---|---|---|---|
| `GET /v1/hello` | none | no | 4 |
| `POST /v1/pair` | none | no | 4 |
| `GET /v1/token` | any valid token | no | 5 |
| `GET /v1/catalog?after=` | `read` | no | 10.1 |
| `GET /v1/documents?after=&maxBytes=` | `read` | no | 10.2 |
| `POST /v1/documents/read` | `read` | no | 10.3 |
| `GET /v1/documents/{kind}/{id}` | `read` | no | 10.3 |
| `POST /v1/images/read` | `read` | no | 10.4 |
| `GET /v1/images/{hex}` | `read` | no | 10.4 |
| `POST /v1/images` | `publish:images` | no | 10.5 |
| `GET /v1/meta` | `read` | no | 10.6 |
| `GET /v1/notes` | `read` | no | 10.6 |
| `GET /v1/history?kind=&id=&version=` | `history` | no | 10.7 |
| `POST /v1/changes` | `publish:` per entry | yes | 12 |
| `GET /v1/events` | `read` | no | 11 |

Unknown path: `404 not-found`. A path under another version (`/gloss/v2/...`): `400 unsupported-protocol`.

#### 10.1 Catalog: `GET /v1/catalog?after=<cursor>`

Every document and image the token can see: documents first (by kind, then id, code-unit order), then
images (by path). The editor reads it after pairing and after every reconnect.

```json
{"documents":[
   {"kind":"hologram","id":"seed-h005","path":"holograms/seed-h005.json","hash":"sha256:5d1e...","size":812,"revision":4,
    "summary":{"name":"Welcome"}},
   {"kind":"menu","id":"shop/main","path":"menus/shop/main.json","hash":"sha256:...","size":4410,
    "summary":{"name":"Shop","folder":"shop","commands":true}}
 ],
 "images":[{"path":"shop/coin.png","hash":"sha256:9f2c...","size":41234,"mediaType":"image/png","width":64,"height":64}],
 "metaHash":"sha256:aa01...","notesHash":"sha256:bb02...",
 "next":"ZApob2xvZ3JhbQpzZWVkLWgwMDU"}
```

- Document `hash`: `sha256:` of the canonical JSON of the body (canonical form pinned by
  `editor-sync-canonical-v3.json`). Image `hash`: `sha256:` of the raw bytes.
- `size`: UTF-8 bytes of the body. `revision` only for versioned kinds.
- `summary` is always present. `name`: the top-level `name`, `title` or `displayName` with `&x`/`§x`
  codes removed, else the last id segment, at most 80 characters. `folder` when the id has one.
  `commands: true` when the body runs a console command.
- A page ends at 1000 entries or 512 KiB of entries, whichever comes first (on the relay, never more than
  the frame limit less 16 KiB). `next` is an opaque cursor for `after=`, `null` on the last page. 1,575
  documents and 200 images fit in 2 pages.
- `metaHash` and `notesHash` are on every page; read meta or notes again only when they moved.
- A document file that cannot be read or parsed is left out and named in `notes.warnings`
  (`unreadable|<kind>|<id>||<why>`).

#### 10.2 Bodies by page: `GET /v1/documents?after=<cursor>&maxBytes=<n>`

Sequential pages in catalog order.

```json
{"documents":[{"kind":"animation","id":"marquee","hash":"sha256:12ab...","revision":2,"json":"{...exact text...}"}],
 "next":"YW5pbWF0aW9uAG1hcnF1ZWU"}
```

- `maxBytes` defaults to 1 MiB, at most 4 MiB on the direct path; on the relay the page is also cut at
  the frame limit less 16 KiB. A page always holds at least one document. A single body larger than the
  relay limit comes alone and, on the relay, is refused `413 too-large`.
- `json` is the exact text the hash is computed from (canonical form for panels).
- The body is the current one at read time. If its `hash` differs from the catalog the editor holds, a
  `catalog` event for it is on its way.

#### 10.3 Batch and single reads

`POST /v1/documents/read`, at most 200 refs, each once:

```json
{"refs":[{"kind":"hologram","id":"seed-h005"},{"kind":"menu","id":"shop/gone"}]}
```

```json
{"documents":[{"kind":"hologram","id":"seed-h005","hash":"sha256:...","revision":4,"json":"{...}","summary":{"name":"Welcome"}}],
 "errors":[{"kind":"menu","id":"shop/gone","code":"not-found","message":"No menu shop/gone."}],
 "remaining":[]}
```

- `errors[].code`: `not-found`, `folder-denied`, `invalid-request` (unknown kind or malformed id),
  `too-large` (larger than the relay frame; read it on the direct path).
- `remaining`: refs left out by the answer budget (1 MiB direct, frame less 16 KiB on the relay); send
  them again.
- Whole-call refusals: `400 invalid-request` (no refs, over 200, a duplicate, an unknown field), 401, 403,
  429, 503.

`GET /v1/documents/{kind}/{id}` returns one entry in the same shape, or `404 not-found` or
`403 folder-denied`. Each `id` segment is URL-encoded; the id may contain `/`.

#### 10.4 Image reads

`POST /v1/images/read`, at most 64 hashes:

```json
{"hashes":["sha256:9f2c...","sha256:0ab1..."]}
```

```json
{"images":[{"hash":"sha256:9f2c...","mediaType":"image/png","data":"<base64, no data: prefix>"}],
 "missing":["sha256:0ab1..."],
 "remaining":[]}
```

`missing`: no visible image has that hash now. `remaining`: left out by the answer budget, ask again. One
image is at most 512 KiB raw, so it always fits a relay frame alone.

`GET /v1/images/<64 hex>` answers `{"hash","mediaType","data"}` with `ETag: "<hex>"` and
`Cache-Control: private, max-age=31536000, immutable`. `If-None-Match` gives 304.

#### 10.5 Upload: `POST /v1/images`

```json
{"mediaType":"image/png","data":"<base64 of the raw bytes>"}
```

201 for a new image, 200 when it is already staged or on the server:

```json
{"hash":"sha256:9f2c...","size":41234,"mediaType":"image/png","width":64,"height":64}
```

- png, jpeg, gif, webp or bmp. The server checks the magic bytes against `mediaType`. At most 512 KiB,
  4,096 px per side, 16,777,216 px. Refusals: `413 too-large`, `400 invalid-request` (`unsupported image
  type`, `bytes do not match image/png`).
- Uploads are staged in memory for 10 minutes, at most `maxProjectMiB` per server (oldest dropped first),
  until a publish names the hash. Uploads are content-addressed and idempotent, so they are not counted.

#### 10.6 Meta and notes

`GET /v1/meta`, with `ETag: "<metaHash>"`; `If-None-Match` gives 304:

```json
{"metaHash":"sha256:aa01...",
 "meta":{"format":"gloss-editor-meta","version":1,
   "kinds":[{"kind":"animation","storage":"animations","layout":"folder","versioned":true,"singletonId":null,"scope":"publish:shared"}],
   "schemas":{"hologram":{"...":"JSON Schema"}},
   "defaults":{"hologram":[{"id":"welcome","json":"{...}"}]}}}
```

`kinds` is every kind this plugin build handles, `layout` one of `folder`, `tree`, `single`, `real-drops`,
with the `publish:` scope it needs. A catalog document whose kind the editor cannot edit is still listed
and kept read-only; the editor never names it in a publish, so it survives untouched.

`GET /v1/notes`, with `ETag: "<notesHash>"`:

```json
{"notesHash":"sha256:bb02...",
 "notes":{"format":"gloss-editor-notes","version":1,
   "warnings":["schema-skipped|hologram|old||declares an unsupported schemaVersion","Referenced menu is not loaded: shop/x"],
   "history":[{"kind":"holograms","id":"seed-h005","version":1731450000000,"source":"watchdog","bytes":812}]}}
```

Warnings use the `code|kind|id|pointer|message` shape; a string without a pipe is a plain sentence.
History rows are at most 20 per document and 2,000 in all. Both are filtered by the token's folders.

#### 10.7 History: `GET /v1/history?kind=<collection>&id=<id>&version=<n>`

`kind` is the history row's `kind` (the collection, as in `notes.history`). Answers at once:

```json
{"kind":"holograms","id":"seed-h005","version":1731450000000,"found":true,"json":"{...}"}
```

`found:false` and `json:null` when the server no longer keeps that version.

### 11. Events: `GET /v1/events`

The editor opens the stream right after the catalog read and keeps it open. Direct: a WebSocket with the
`auth` frame (section 8). Relay: a `request` frame with `Authorization` (section 9.1). Each frame is one
JSON object.

- `open`, the first frame once the stream is accepted:
  `{"type":"open","tokenId":"tok_...","expiresAt":"...","scopes":[...],"metaHash":"...","notesHash":"..."}`.
- `catalog`, whenever documents or images change, whatever changed them:

  ```json
  {"type":"catalog",
   "documents":[{"kind":"hologram","id":"seed-h005","path":"holograms/seed-h005.json","hash":"sha256:...","size":830,"revision":5,"summary":{"name":"Welcome"},"source":"server"}],
   "deleted":[{"kind":"menu","id":"shop/old","source":"file"}],
   "images":[{"path":"shop/coin.png","hash":"sha256:...","size":41234,"mediaType":"image/png","width":64,"height":64,"source":"editor"}],
   "removedImages":[{"path":"shop/old.png","source":"file"}],
   "metaHash":"sha256:...","notesHash":"sha256:..."}
  ```

  `documents` and `images` hold new versions (catalog entry shape plus `source`); `deleted` and
  `removedImages` hold removals. Any list may be empty or absent. `metaHash` and `notesHash` appear when
  they moved. `source` is `editor` (a publish through this API), `server` (Gloss wrote it: commands,
  menus, hotload, history restore, pack installs, imports) or `file` (changed on disk outside Gloss).
  It is decided per entry: `server` when Gloss wrote that file or its folder in the last 3 seconds.
  Entries outside the token's folders are left out.
- `change`, after every publish that wrote something: `{"type":"change","result":<the publish answer>}`,
  sent before the `catalog` frame for the same documents.
- `closing`, then the stream closes: `{"type":"closing","reason":"..."}` with reason `token-revoked`,
  `token-expired`, `server-stopping` (a restart is likely; reconnect fast) or `limit` (the stream fell 256
  frames behind; reconnect and read the catalog again).

Timing: Gloss's own writes (commands, publishes, hotload of its own files) reach open streams within about
250 ms; the catalog refreshes 50 ms after the last write notice. Files changed by hand come from the
platform's file events (Linux, Windows), so they arrive within about 100 ms and nothing runs while nothing
changes. Where the JDK has no file events (macOS), the server compares file sizes and times once a second
while a stream is open, so they arrive within about 1 s. Either way only changed files are read and hashed,
and reads never wait for a refresh or for the notes, which are built on their own thread.

Pacing: at most `eventFramesPerSecond` frames a second per stream. Queued changes merge per document (a
newer version replaces an older queued one; a delete replaces a version and the other way round). One
`catalog` frame holds at most 48 KiB of entries, so a burst touching 1,575 files sends about 8 frames.

### 12. Publish: `POST /v1/changes`

```json
{"idempotencyKey":"pub_4kB2f0Qx9w1mZr8c",
 "documents":[
   {"op":"put","kind":"hologram","id":"seed-h005","baseHash":"sha256:5d1e...","json":"{\"schemaVersion\":1,\"revision\":4,...}"},
   {"op":"put","kind":"hologram","id":"brand-new","baseHash":null,"json":"{\"schemaVersion\":1,\"revision\":1,...}"},
   {"op":"delete","kind":"menu","id":"shop/m0002","baseHash":"sha256:0c3d..."}],
 "images":[
   {"op":"put","path":"shop/new.png","baseHash":null,"hash":"sha256:9f2c...","mediaType":"image/png"},
   {"op":"delete","path":"shop/old.png","baseHash":"sha256:4e4e..."}]}
```

- `idempotencyKey`: `[A-Za-z0-9_-]{16,64}`, new per publish. Sending the same key again returns the
  first answer without applying anything, for 24 hours or until a restart.
- Each key appears at most once. `baseHash` is the catalog hash the editor last saw, `null` for a create.
- Versioned kinds: `json.revision` equals the revision the editor last saw; a create uses 1. The server
  bumps revisions when it applies.
- Image puts name a hash uploaded with `POST /v1/images` or already on the server, else
  `400 invalid-request` with `missing`.
- Anything the changeset does not mention is untouched. Deletes are always explicit. Kinds the editor
  cannot edit are never named.
- Scopes are checked for the whole call before anything is read: each document entry needs its kind's
  `publish:` scope, each image entry `publish:images`, and a put whose body runs a console command also
  `publish:commands`. Every entry must be inside the token's folders. Refusals are `403 scope-missing` or
  `403 folder-denied` and write nothing.
- At most 2,000 document entries and 4,096 image entries, and the body within `requestBodyBytes` on the
  direct path or one relay frame. The editor splits a larger changeset into several publishes, images
  first.
- Publishes run one at a time; a second one waits for the first.
- A publish deleting more than 25 documents and images, and more than 20 percent of those the token
  reaches, is refused with `428 confirm-deletes` and writes nothing, unless the body carries
  `"confirmDeletes": <that count>`. The error carries `deletes` (the count) and `total` (what the token
  reaches), and the message says it: `Publishing would delete 30 of the 45 documents and images on this
  server. Nothing was published.` The editor asks, then sends the same publish with `confirmDeletes`.
- Every document a publish deletes is also kept in history (source `editor:<changeId>`), so
  `/gloss history` can restore it after its publish backup is pruned.

Answer 200, whatever the outcome:

```json
{"changeId":"chg_7f3c...","idempotencyKey":"pub_4kB2f0Qx9w1mZr8c",
 "status":"applied",
 "message":"Published 2 changes; 1 kept the server copy: seed-h018.",
 "conflicts":[{"kind":"hologram","id":"seed-h018","name":"seed-h018","reason":"changed"},
              {"kind":"image","id":"shop/coin.png","name":"shop/coin.png","reason":"changed"}],
 "documents":[{"kind":"hologram","id":"seed-h005","path":"holograms/seed-h005.json","hash":"sha256:e9e9...","size":840,"revision":5,"summary":{"name":"Welcome"}}],
 "deleted":[{"kind":"menu","id":"shop/m0002"}],
 "images":[{"path":"shop/new.png","hash":"sha256:9f2c...","size":41234,"mediaType":"image/png","width":64,"height":64}],
 "removedImages":[{"path":"shop/old.png"}]}
```

- `status`: `applied` (at least one change landed; entries in `conflicts` did not), `conflict` (nothing
  landed because every change conflicted), or `rejected` (nothing landed because the changeset is
  invalid; `message` names the document and the reason, such as `hologram seed-h005: line 3 must be
  text`).
- `conflicts[].reason`: `changed` (the server copy differs from `baseHash`), `deleted` (the server deleted
  it), `exists` (a create whose key now exists). Images use `kind: "image"` with the path as `id`, and
  conflict per path. A put whose body equals what the server already holds counts as landed, so a retry
  after a restart is safe.
- `documents`, `deleted`, `images` and `removedImages` are the catalog delta of the apply, revision bumps
  included. The editor replaces exactly those entries and keeps its own version of each conflicted key as
  a draft next to the server copy.
- The console logs one INFO line per publish, such as `Web editor publish by tok_x (laptop): Published 2
  changes; 1 kept the server copy: seed-h018.`; holders of `gloss.editor.notify` see it in chat.

#### 12.1 `X-Gloss-Counter`

Every counted request carries an integer greater than every earlier one for the token. The server stores
it before the request runs, so a relay cannot replay a publish. The pair answer gives the starting value
(`counter`). Missing or not an integer: `400 invalid-request`. Not greater: `409 counter-replayed` with
the current value in `error.counter`; the editor sets its counter above it and retries once.

### 13. Errors

Every refusal is an HTTP status plus:

```json
{"error":{"code":"scope-missing","message":"This token lacks publish:world.","retryAfterMillis":250}}
```

`retryAfterMillis` (with `Retry-After` in whole seconds) appears only on `rate-limited` and `busy`. Extra
fields: `counter` on `counter-replayed`, `deletes` and `total` on `confirm-deletes`, `missing` (image
hashes) on `invalid-request`, `scope` on
`scope-missing` (and `documents` when the missing scope is `publish:commands`), `folders` on
`folder-denied`.

| HTTP | Code | When |
|---|---|---|
| 400 | `invalid-request` | Malformed request, missing counter, or image hashes not uploaded (`missing`) |
| 400 | `unsupported-protocol` | A path under another API version |
| 401 | `pairing-invalid` | The link was used, revoked, unknown or not a link |
| 401 | `unauthorized` | Token missing, malformed or unknown |
| 401 | `token-expired` | The token went unused past its lifetime |
| 401 | `token-revoked` | `/gloss editor revoke`, or the player who made it lost `gloss.editor.pair` |
| 403 | `origin-not-allowed` | Direct path: the browser origin is not in `allowedOrigins` |
| 403 | `scope-missing` | The token lacks a scope (`error.scope`) |
| 403 | `folder-denied` | The entry is outside the token's folders (`error.folders`) |
| 404 | `not-found` | No such document, image or route |
| 409 | `counter-replayed` | `X-Gloss-Counter` not greater than the last one (`error.counter`) |
| 410 | `pairing-expired` | The link is older than 10 minutes |
| 428 | `confirm-deletes` | A publish deletes more than 25 and more than 20 percent without `confirmDeletes` naming the count (`error.deletes`, `error.total`) |
| 413 | `too-large` | A body or answer over a limit, including the relay frame |
| 429 | `rate-limited` | Over `requestsPerSecond`, `concurrentRequestsPerToken` or `pairAttemptsPerMinute` |
| 500 | `internal` | Server bug, logged once with a stack trace |
| 503 | `busy` | The file lane or `relayConcurrentRequests` is full |
| 503 | `server-offline` | Relay: the server is not connected |
| 504 | `timeout` | Relay: no answer within 10 s |

When a browser cannot use a link, the player who made it (and holders of `gloss.editor.notify`) see one of
`<player>'s editor could not pair: origin <origin> is not in allowedOrigins`, `...: the link expired; make
a new one`, or `...: the relay <url> is unreachable` (only when no listener runs, after 15 s). The console
logs `Editor pairing tok_x failed: the link expired before it was opened; run /gloss editor pair again`
once per link, and `Editor pairing from <address> failed: the link is unknown, used or revoked` once a
minute per address.

### 14. Limits

Defaults, with the `[editor]` key in brackets.

| Limit | Value |
|---|---|
| Link | 10 minutes, once; 64 waiting; 256 tokens |
| Requests per token | 20 per second, bursts of 40 [`requestsPerSecond`]; 8 at once [`concurrentRequestsPerToken`] |
| Pair attempts | 10 per minute per address [`pairAttemptsPerMinute`]; failed event-stream authentication counts too |
| Connections | 64 [`openConnections`], 16 per address |
| Direct bodies | 4 KiB without a token; 4 MiB with one [`requestBodyBytes`] |
| Relay frames | 1 MiB [`relayFrameBytes`]; 64 relayed requests at once [`relayConcurrentRequests`] |
| Catalog page | 1000 entries or 512 KiB |
| Body page | `maxBytes` 1 MiB default, 4 MiB most (relay: frame less 16 KiB) |
| Batch reads | 200 refs, 64 image hashes; answer 1 MiB direct, frame less 16 KiB on the relay |
| Documents | 20,000; one body 2 MiB |
| Images | 4,096; one image 512 KiB, 4,096 px a side, 16,777,216 px; staged uploads 10 minutes, 64 MiB [`maxProjectMiB`] |
| Publish | 2,000 document and 4,096 image entries; more than 25 and 20 percent deletions need `confirmDeletes` |
| Events | 20 frames a second [`eventFramesPerSecond`], 48 KiB of entries a frame, 256 frames queued |

### 15. Restarts

- Stopping: every stream gets `closing` with `server-stopping`, the relay connection closes normally, and
  the relay tells subscribed browsers `status` `online:false`.
- Starting: the listener binds while Gloss enables; the relay client connects at once when a token exists
  and registers within about 100 ms, and the relay pushes `status` `online:true`. On the direct path the
  editor retries `hello` every 250 ms for the first 30 s after a `server-stopping` close.
- On reconnect the editor reads the catalog again and the bodies whose hash moved; drafts stay. Tokens,
  identity and fingerprint survive a restart. Links not opened yet do not (section 2).
- Relay restarts: on SIGTERM or SIGINT the relay closes every server and browser connection with 1001
  (going away) and exits within about 2 s; servers dial the new relay at once. The server pings the relay
  after 10 s of silence and dials again after 30 s without any frame, ping or pong, so a relay that
  vanished without closing the socket is left in about 30 s.

### 16. Permissions

| Node | Default | Grants |
|---|---|---|
| `gloss.editor` | op | Parent of the three below; `gloss.*` includes it |
| `gloss.editor.pair` | op | `/gloss editor pair`, `status`, `tokens` and `revoke` |
| `gloss.editor.commands` | op | Pairing `publish:commands` in game (the console always may) |
| `gloss.editor.notify` | op | Chat notices: pairings, publishes, revocations and failed pairings |

- A token a player made is revoked once that player no longer holds `gloss.editor.pair`, checked every 60
  seconds, online or offline. Offline players are checked against the operator list, the node's default,
  and Vault's permission provider when one is registered. When the server cannot tell (a permission plugin
  it cannot ask about offline players), the token keeps working for at most 7 days after the last
  confirmation, then it is revoked and the player pairs again.
- Losing `gloss.editor.commands` drops only `publish:commands`.
- Console-made tokens belong to no player and are never revoked by this check.

### 17. Config

`[editor]` in gloss.toml. Every key hotloads when the file is saved: `enabled` opens or closes the whole
editor, a bind key (`listenerEnabled`, `listenAddress`, `port`, `tlsCertificate`, `tlsPrivateKey`,
`openConnections`) restarts only the listener, a relay key restarts only the relay client, and everything
else applies to the next request. Paired browsers stay paired.

```toml
[editor]
enabled = true
serverName = ""
editorUrl = "https://gloss.volmitsoftware.com"
listenerEnabled = true
listenAddress = "127.0.0.1"
port = 9799
advertisedUrl = ""
trustedProxies = []
tlsCertificate = ""
tlsPrivateKey = ""
allowedOrigins = ["https://gloss.volmitsoftware.com", "https://gloss-editor.web.app", "https://gloss-editor.firebaseapp.com"]
tokenDays = 7
requestBodyBytes = 4194304
concurrentRequestsPerToken = 8
requestsPerSecond = 20
pairAttemptsPerMinute = 10
openConnections = 64
eventFramesPerSecond = 20
maxProjectMiB = 64
relayEnabled = true
relayUrl = "wss://gloss-relay-437930007071.us-central1.run.app"
relayReconnectMinSeconds = 1
relayReconnectMaxSeconds = 60
relayFrameBytes = 1048576
relayConcurrentRequests = 64
```

| Key | Meaning |
|---|---|
| `enabled` | The whole editor API, listener and relay |
| `serverName` | Name on the pairing card and in `hello`; empty falls back to the MOTD, then the server folder (section 3) |
| `editorUrl` | Where pairing links open |
| `listenerEnabled` | The plugin's own HTTP listener |
| `listenAddress` | Plain HTTP only on loopback; any other address needs TLS |
| `port` | Listener port; 0 picks a free one and links carry the bound port |
| `advertisedUrl` | The `https://` address a TLS proxy in front of the loopback listener answers on; links carry it as `directUrl` |
| `trustedProxies` | IP addresses of proxies in front of the listener; a request from one is rate-limited under the client address it names in `X-Forwarded-For`, and its connections count only toward `openConnections` |
| `tlsCertificate`, `tlsPrivateKey` | PEM files, relative to the Gloss folder, for HTTPS on a non-loopback `listenAddress` |
| `allowedOrigins` | Exact browser origins accepted on the direct path; `http://` only on loopback; at most 32 |
| `tokenDays` | Days a pairing lasts after its last use when `pair` has no `expires=`, 1 to 365 |
| `requestBodyBytes` | Largest authenticated body on the direct path |
| `concurrentRequestsPerToken` | Requests one token runs at once |
| `requestsPerSecond` | Requests per second per token |
| `pairAttemptsPerMinute` | Pair attempts per minute per address |
| `openConnections` | Listener connections in all |
| `eventFramesPerSecond` | Event frames per second per stream |
| `maxProjectMiB` | Image uploads staged in memory per server until a publish names them |
| `relayEnabled` | Dial out to the relay |
| `relayUrl` | `wss://host[:port][/path]`, `ws://` only on loopback |
| `relayReconnectMinSeconds`, `relayReconnectMaxSeconds` | Relay reconnect backoff bounds |
| `relayFrameBytes` | Largest relay frame; keep it equal to the relay's `MAX_FRAME_BYTES` |
| `relayConcurrentRequests` | Relayed requests the server runs at once |

`builderUrl` is now `editorUrl`. The HoloUi importer maps `builderUrl` to `editorUrl` and
`editorSyncMaxProjectMiB` to `maxProjectMiB`, and skips the other `editorSync*` keys.

### 18. Fixtures

Golden messages for this API live in `schema/editor/fixtures/` at the repository root.
