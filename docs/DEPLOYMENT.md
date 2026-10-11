# Deploy robotOS and Hermes

robotOS is the workspace authority. Hermes is an independent execution service. They can run
on the same host, on different hosts, in Docker, or in Kubernetes/k3s. R1 devices and the browser
need only the robotOS HTTPS origin. **Docker Compose with both services is the primary community
installation.** Native services, split hosts and k3s are supported alternatives. The two server
connections are independent:

```text
R1 / browser ──HTTPS──> robotOS hub ──authenticated HTTPS──> Hermes API
                       robotOS hub <──scoped HTTPS── Hermes stdio MCP adapter
```

The MCP process belongs beside Hermes, including inside its container. It does not listen for
inbound network connections. Hermes receives a scoped MCP credential; owner credentials stay
with robotOS. The hub does not require Hermes's filesystem or a mounted Docker socket.

| Arrangement | Supported deployment path |
|---|---|
| Both in Docker on one host (primary) | Published images with `compose.yaml` + `compose.hermes.yaml` |
| Both native on macOS or Linux | `tools/hub-setup.sh --public-origin https://workspace.example.net` |
| Native Hermes, containerized robotOS on any host | Hub Compose plus `--mcp-only` on the Hermes host |
| Containerized Hermes, native robotOS | Hermes image/Compose overlay with the native hub's HTTPS URL |
| Both in Docker, together or on separate machines | Hub and Hermes images, persistent volumes, HTTPS routes in both directions |
| Hub in k3s, Hermes elsewhere | Hub kustomization plus remote Hermes API and MCP configuration |
| Both in k3s | Hub kustomization plus optional Hermes manifest |
| Docker Desktop on macOS or Windows | Linux containers and named volumes; Windows commands below run in WSL2 |

Every arrangement uses **one active hub** and **one active gateway per Hermes data directory**.
This is durable single-instance deployment, not active-active or automatic database failover.

## Primary setup: Docker Compose with robotOS and Hermes

Requirements: Docker Engine with Compose v2, or Docker Desktop in Linux-container mode, plus
Python 3.9+ for the credential initializer. Clone this repository for the Compose files. A new
Hermes gateway also needs a model/provider account or another configured model backend. Docker
Desktop on Windows uses WSL2 for these commands.

Use named volumes for both services. The hub's live SQLite database must stay on the Docker VM's
native filesystem, not a macOS/Windows shared folder, NFS, SMB or replicated file share. Existing
Hermes users who want to keep their gateway should use the [hub-only Docker path](#docker-hub-with-an-existing-hermes-gateway).

### 1. Prepare credentials and addresses

From the repository root, for a **new** Hermes gateway:

```sh
python3 deploy/configure.py --directory deploy/hub/private
cp deploy/hub/.env.example deploy/hub/.env
```

The initializer creates separate owner, MCP and Hermes credentials in a private directory and
never replaces existing files. Copy `.env.example` only on first installation; preserve `.env`
on later runs. Do not put tokens in `.env`. Set the two exact HTTPS origins there:

```dotenv
ROBOTOS_PUBLIC_ORIGIN=https://workspace.example.net
HERMES_API_URL=https://hermes.example.net
```

Choose either a private Tailscale route or the public-DNS Caddy option below before starting.
The hub must reach `HERMES_API_URL`; Hermes's MCP must reach `ROBOTOS_PUBLIC_ORIGIN`. These must
resolve and route from inside their containers, including hairpin routing if needed. A different
machine's `localhost` is never a remote service address.

The Compose files use published `ghcr.io/windoze95/robotos-hub:main` and
`ghcr.io/windoze95/robotos-hermes:main` images. `main` is a moving development tag. For production,
set both `ROBOTOS_IMAGE` and `ROBOTOS_HERMES_IMAGE` to the same successful immutable
`sha-<full-commit>` tag, or their corresponding digests. Images support AMD64 and ARM64.

Host transcription is optional and starts disabled in Compose. To transcribe voice notes, add
`--speech-token-file /private/speech-key` when initializing credentials and set `SPEECH_API_URL`
to a compatible endpoint (for example `https://api.openai.com/v1`). Without it, recordings can be
saved but automatic host transcription is unavailable. Local live dictation and spoken replies
use the R1's separate speech configuration.

### 2. Pull images and configure Hermes

```sh
cd deploy/hub
docker compose -f compose.yaml -f compose.hermes.yaml pull
docker compose -f compose.yaml -f compose.hermes.yaml run --rm -it --no-deps hermes setup
```

Complete Hermes's model/provider setup. It saves configuration and provider credentials in the
Hermes named volume. The generated Hermes API credential authenticates robotOS to the gateway;
it does not authenticate to a model provider. The bootstrap manages only its API listener,
robotOS MCP entry and bundled skill, preserving other Hermes configuration.

### 3. Start services and expose HTTPS

For an existing HTTPS proxy or Tailscale Serve:

```sh
docker compose -f compose.yaml -f compose.hermes.yaml up -d --no-build
docker compose -f compose.yaml -f compose.hermes.yaml ps
docker compose -f compose.yaml -f compose.hermes.yaml exec hub python /opt/robotos/healthcheck.py ready
```

The ports are bound only to host loopback: hub `127.0.0.1:8787`, Hermes `127.0.0.1:8642`.
Configure separate HTTPS proxy routes preserving the original `Host`. For an already signed-in
Tailscale node, first inspect `tailscale serve status`. If ports 9443 and 443 are unused, these
examples expose the hub and Hermes respectively:

```sh
tailscale serve --bg --https=9443 http://127.0.0.1:8787
tailscale serve --bg --https=443 http://127.0.0.1:8642
```

Use the resulting exact tailnet URLs in `.env`, including `:9443` for the hub, and rerun the
same `up -d --no-build` command after editing it. Choose different unused ports if routes already
exist; do not replace unrelated routes or use Funnel. R1s need access to that tailnet. If the
host uses a private userspace Tailscale node, include its `--socket` option on every Serve command.

Alternatively, for public DNS with the supplied Caddy proxy, set `ROBOTOS_HOSTNAME` and
`HERMES_HOSTNAME` in `.env` to the two matching hostnames. Point DNS at the Docker host and
allow ACME to reach ports 80/443. Start all three services with:

```sh
docker compose -f compose.yaml -f compose.hermes.yaml \
  -f compose.tls.yaml -f compose.tls-hermes.yaml up -d --no-build
```

Caddy publishes HTTPS on host port 443 and HTTP redirects/ACME on 80. This public-DNS option is
separate from private tailnet HTTPS; do not run both proxies on the same host ports.

### 4. Verify and enroll

Verify `/api/v1/ready` on the hub's final HTTPS origin and `/health` on Hermes's final HTTPS
origin. Read `private/owner-password` locally, sign in to the hub console, and create a separate
enrollment code for each R1 under Devices. Enter the hub URL and code in **Workspace settings**.
No shared host credential belongs on an R1. Submit one harmless prompt and confirm its completed
reply and **Ran** status to verify authenticated execution, then open the saved result on the R1.

Keep the host available: paired R1s remain restricted after waking or reconnecting until the hub
verifies their policy. [HUB.md](HUB.md) covers permissions, recovery, saved prompt handoff and
acceptance limits. Back up data and credentials before upgrades; never remove the named volumes
as part of an ordinary update.

## Native hosts and split hosts

For an existing configured Hermes installation on the same macOS or Linux machine:

```sh
tools/hub-setup.sh --public-origin https://workspace.example.net
```

The native default installs the hub, configures Hermes's authenticated loopback API and scoped
MCP adapter, starts both services and checks them. `--register-hermes` remains a compatibility
alias. Existing credentials, data, model/provider settings and unrelated MCP entries are retained.
HTTPS ingress and enrollment remain explicit; see [HUB.md](HUB.md#native-installation-alternative).

macOS LaunchAgents run within the logged-in user session. Linux uses systemd user services;
configure user lingering if they must survive logout and start at boot. The host must remain
awake and reachable because paired R1s stay restricted while recovery status cannot be verified.

For a **separate Hermes machine**, explicitly install only the hub. The installer accepts a
remote authenticated Hermes API URL and reads its key from a private file:

```sh
tools/hub-setup.sh --hub-only --public-origin https://workspace.example.net \
  --hermes-url https://hermes.example.net --hermes-token-file /private/hermes-api-key
```

On the separate Hermes host, register only the adapter and its skill:

```sh
tools/hub-setup.sh --mcp-only --hub-url https://workspace.example.net \
  --mcp-token-file /private/robotos-mcp-token
```

Copy only the hub's **MCP token** to that private file using your secret manager or a protected
transfer. Do not give Hermes `owner-token` or `owner-password`. `--hub-only` leaves the remote
Hermes service/configuration alone; configure and validate that authenticated API on its own host.
`--no-start` prepares configuration without starting services. Validate both HTTPS endpoints before
enrolling devices or cutting over an existing deployment.

## Docker: hub with an existing Hermes gateway

Requirements: Docker Engine with Compose v2, or Docker Desktop in Linux-container mode. Use
named volumes so SQLite is stored on the Docker VM's native filesystem. Do not use a macOS/Windows
shared folder, NFS, SMB or a replicated file share for a live SQLite database.

From the repository root, initialize private secrets. This does not overwrite existing files:

```sh
python3 deploy/configure.py --directory deploy/hub/private \
  --hermes-token-file /private/existing-hermes-api-key
cp deploy/hub/.env.example deploy/hub/.env
```

Edit `.env` with the exact `ROBOTOS_PUBLIC_ORIGIN=https://workspace.example.net` and
`HERMES_API_URL=https://hermes.example.net`. Tokens stay in the private directory. Add
`--speech-token-file /private/speech-key` during initialization and set `SPEECH_API_URL` to enable
host transcription. An empty speech URL disables it. To change an existing credential, deliberately
replace its file while keeping the directory mode 0700 and the mount-readable file mode 0444;
restart the affected services to load it. Initialization never rotates existing identities.

```sh
cd deploy/hub
docker compose pull
docker compose up -d --no-build
docker compose ps
docker compose exec hub python /opt/robotos/healthcheck.py ready
```

The HTTP port is published **only on host loopback**, at `127.0.0.1:8787`. The container listener
explicitly enables non-loopback access behind that boundary; it still requires an exact HTTPS
public origin and enforces authentication, Host, Origin, and CSRF checks. Do not publish 8787 to
the network or disable TLS validation. Proxies must preserve the original `Host`; forwarded
identity and scheme headers do not bypass authorization.

Expose it through your existing HTTPS proxy. For Tailscale Serve on the Docker host:

```sh
tailscale serve --bg --https=9443 http://127.0.0.1:8787
```

Use the resulting exact tailnet origin, including `:9443`, in `.env`. A different machine's
`localhost` is never a remote service address. Do not overwrite an existing Serve port; choose
another port when needed. Register the adapter on the native Hermes host using the previous
section, then open the console and enroll each R1.

For a public DNS name, an optional pinned Caddy overlay provides certificates and HTTPS. Set
`ROBOTOS_HOSTNAME=workspace.example.net` in `.env`, point DNS at this host, and ensure ACME can
reach ports 80/443:

```sh
docker compose -f compose.yaml -f compose.tls.yaml up -d --no-build
```

This overlay explicitly exposes TLS on host port 443 and HTTP redirects/ACME on 80. Use the
Tailscale route for private tailnet names; Caddy's public ACME route is not a substitute for it.
The console still requires its owner password. Read `private/owner-password` locally to sign in.

For an unmerged source change, assign local tags in `.env` before running `docker compose build`
(add `-f compose.yaml -f compose.hermes.yaml` to build both). Do not build local edits over a
published immutable tag.

Prebuilt multi-architecture images are published from successful `main` builds as
`ghcr.io/windoze95/robotos-hub:sha-<full-commit>` and
`ghcr.io/windoze95/robotos-hermes:sha-<full-commit>`. Set `ROBOTOS_IMAGE` and
`ROBOTOS_HERMES_IMAGE` to the chosen tags (or digests), then use `docker compose up -d --no-build`.
Build locally when testing an unmerged revision. `main` tags are moving development builds;
use immutable tags/digests for deployed systems.

## Hermes container details and separate Docker hosts

The derived Hermes image uses the [official upstream image](https://hermes-agent.nousresearch.com/docs/user-guide/docker)
pinned by its multi-architecture digest. It adds the robotOS stdio adapter, its skill, and a small
startup configurator. It runs the gateway in the foreground as UID 10000; robotOS runs as UID
10001. There is no root supervisor, host-network requirement, privileged container, or Docker
socket mount. Agent tools that need additional access must be configured deliberately.

For a new gateway, omit `--hermes-token-file` when creating secrets: the initializer generates a
new shared API credential. For an existing gateway, retain the current credential and migrate its
state deliberately. Both containers need HTTPS URLs reachable from inside their own network;
verify DNS and routing in both directions, including hairpin routing where appropriate.

The primary setup above covers a new complete stack. To prepare **only Hermes** on a separate
Docker host, configure `.env` with the existing hub origin and transfer only `mcp-token` and
`hermes-token` into that host's private secret directory. Then pull and start only Hermes:

```sh
cd deploy/hub
docker compose -f compose.yaml -f compose.hermes.yaml pull hermes
docker compose -f compose.yaml -f compose.hermes.yaml run --rm -it --no-deps hermes setup
docker compose -f compose.yaml -f compose.hermes.yaml up -d --no-build --no-deps hermes
```

The bootstrap preserves existing model/provider and other MCP configuration, updates only
`mcp_servers.robotos` and its skill, and forwards ordinary CLI arguments. Provider credentials
belong in the Hermes volume or a provider-specific Secret/env configuration; the robotOS API
credential does not authenticate to a model provider. Hermes's dashboard is outside this gateway
image's purpose; use upstream deployment instructions if you also need that separate service.
The volume mounts at `/opt/data`; this image sets `HERMES_HOME` and `HOME` to the private 0700
subdirectory `/opt/data/hermes`. Restore an existing `~/.hermes` into that subdirectory when
migrating from a native installation or the upstream image's default volume layout.

Hermes's API is published on `127.0.0.1:8642`. Give it a second HTTPS route, for example Tailscale
Serve on another available port, then set `HERMES_API_URL` accordingly. For public DNS and both
services behind the supplied Caddy proxy, also set `HERMES_HOSTNAME=hermes.example.net`:

```sh
docker compose -f compose.yaml -f compose.hermes.yaml \
  -f compose.tls.yaml -f compose.tls-hermes.yaml up -d --no-build
```

For separate Docker machines, run the hub on one host and only the `hermes` service on the other
with the same overlay configuration. Transfer only `mcp-token` and `hermes-token` to the Hermes
host's private secret directory. That host does not need the hub's owner credentials; Compose
loads only the two secrets referenced by the selected service. Use `up -d --no-build --no-deps hermes`.
For native robotOS with containerized Hermes, the same Hermes-only command uses the native hub's
HTTPS URL and existing scoped token.

## Kubernetes / k3s

The manifests in `deploy/k3s` use the standard Kubernetes APIs and work with k3s's default
Traefik ingress and local-path storage. Make a deployment-specific copy; the committed example
uses placeholder DNS names and a local image tag intentionally.

1. Build and push the hub image to your registry, or select its published immutable GHCR tag.
   In `kustomization.yaml`, change the image name/tag or digest and the two HTTPS URLs.
2. In `ingress.yaml`, change the host to match the public origin exactly. Create a TLS Secret
   from a certificate trusted by your R1s and server clients, or configure your certificate
   controller. If using another ingress controller, update `ingressClassName` and the
   `network-policy.yaml` ingress namespace/pod selectors.
3. Choose a suitable block/local CSI storage class for `robotos-data`. The default class is used
   if omitted. Local-path storage is tied to one node; it does not provide node-loss failover.
4. Prepare the credentials with `deploy/configure.py`, then create the namespace and Secrets:

```sh
kubectl apply -f deploy/k3s/namespace.yaml
kubectl -n robotos create secret generic robotos-credentials --from-file=deploy/hub/private
kubectl -n robotos create secret tls robotos-tls --cert=/private/workspace-fullchain.pem --key=/private/workspace-key.pem
kubectl apply -k deploy/k3s
kubectl -n robotos rollout status deployment/robotos-hub
```

The namespace enforces restricted pod security. The hub has a read-only root filesystem, no
service-account token, dropped capabilities, resource limits and a private data subdirectory.
Kubernetes projected Secret symlinks are supported by explicit `*_FILE` settings; ordinary JSON
configuration still rejects symlinks and broad permissions. Secret values are loaded on startup,
so restart deployments after changing them. Protect cluster Secret access using RBAC and
at-rest encryption according to your cluster's operating policy.

The deployment uses `replicas: 1` with `Recreate`. A process lock independently refuses a second
writer using the same data directory. Do not add an HPA, use multiple replicas, or force-delete a
pod while its old node may still be writing. ReadWriteOnce alone does not prohibit two pods on
the same node. Recovery on another node requires the former writer to be fenced and the volume
to be safely detached before attachment.

The Service is internal. NetworkPolicy admits hub traffic only from the configured ingress
controller; HTTPS egress and DNS remain available. Add explicit egress ports for nonstandard
HTTPS upstream ports such as 9443. A private CA can be mounted read-only and selected using
`SSL_CERT_FILE` in the hub and Hermes containers; include public roots too if transcription uses
a public endpoint. The MCP child inherits that CA path. Never turn certificate verification off.

For containerized Hermes in k3s, add `hermes.yaml` to the kustomization resources, add the
`robotos-hermes` image override, set its ingress hostname, and create `hermes-tls`.
The Hermes pod mounts **only** its MCP and API credentials from the shared Secret. Configure its
model/provider before exposing the gateway for use. For interactive setup, create a temporary
pod using the same Hermes image, data PVC, and secret/env mounts, with `args: [setup]`, while the
gateway deployment is scaled to zero; attach a terminal, complete setup, delete that pod, and
restore replicas to one. For unattended setup, populate `/opt/data/hermes/config.yaml` and provider
credentials through your existing secret-manager workflow. Never run an initialization pod and
a live gateway against the Hermes PVC simultaneously.

Liveness `/api/v1/health` tests the hub listener. Readiness `/api/v1/ready` also verifies database
access and the run worker. Neither depends on Hermes or speech availability: notes and device
recovery remain available during an upstream outage. The probes execute locally and carry no
owner credential. Shutdown allows the worker to stop; uncertain upstream run outcomes remain
persisted for reconciliation after restart.

## Upgrade, migrate and recover

Keep the public hub origin stable to retain R1 enrollment settings. Save the original credentials
as well as database/media. Backups use the supplied SQLite online backup tool; never copy only a
live `.sqlite3` file and omit its WAL or media. Inside Compose, for example:

```sh
docker compose exec hub python /opt/robotos/hub-backup.py \
  --data /var/lib/robotos/data --output /var/lib/robotos/backups/before-upgrade
```

Copy the completed backup out of the named volume and protect it along with the secret files.
For migration between native, Docker and k3s: stop the old hub, restore the verified backup into
the target's private `data` directory, retain all existing credentials and the HTTPS origin, then
start exactly one new hub. Preserve numeric ownership (10001 for the container hub; 10000 for
Hermes) and directory mode 0700. Retain the old stopped data until validation succeeds. If an
upstream URL or credential changes while a run is unresolved, the hub fences that run's old
upstream identity; inspect the original Hermes instance before deciding what happened.

Pull/build the chosen image, take the backup, then recreate the hub. In k3s a normal Recreate
rollout stops the old pod first. Do not use `docker compose down -v` during upgrades: it deletes
workspace and Hermes volumes. Rollback requires compatible data; preserve the pre-upgrade
backup rather than assuming every schema supports an older image.

## Deployment checks

Run the dependency-free deployment tests:

```sh
python3 -m unittest discover -s deploy/tests -v
```

The real Docker smoke starts disposable containers and a synthetic authenticated TLS Hermes
endpoint. It tests persisted notes/results, restart, a second-writer rejection, Host/Origin/auth
boundaries, secure cookies, TLS validation and the real Hermes gateway/adapter without any model
credentials or requests:

```sh
python3 deploy/tests/container_smoke.py --image robotos-hub:local --hermes-image robotos-hermes:local
```

A separate smoke boots a disposable privileged k3s test node **inside Docker**, uses its own
private kubeconfig, applies the real manifests, and checks restricted security, projected Secret
files, probes, PVC persistence and a Recreate rollout. It never uses your current cluster context:

```sh
python3 deploy/tests/k3s_smoke.py --image robotos-hub:local --hermes-image robotos-hermes:local
```

These checks do not establish your production DNS/TLS/storage, model credentials, physical R1
handoff, or a lost-device wake test. Verify those on the actual target after deployment.
