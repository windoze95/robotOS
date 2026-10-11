# Deploy robotOS and Hermes

robotOS is the workspace authority. Hermes is an independent execution service. They can run
on the same host, on different hosts, in Docker, or in Kubernetes/k3s. R1 devices and the browser
need only the robotOS HTTPS origin. The two server connections are independent:

```text
R1 / browser ──HTTPS──> robotOS hub ──authenticated HTTPS──> Hermes API
                       robotOS hub <──scoped HTTPS── Hermes stdio MCP adapter
```

The MCP process belongs beside Hermes, including inside its container. It does not listen for
inbound network connections. Hermes receives a scoped MCP credential; owner credentials stay
with robotOS. The hub does not require Hermes's filesystem or a mounted Docker socket.

| Arrangement | Supported deployment path |
|---|---|
| Both native on macOS or Linux | `tools/hub-setup.sh --register-hermes` |
| Native Hermes, containerized robotOS on any host | Hub Compose plus `--mcp-only` on the Hermes host |
| Containerized Hermes, native robotOS | Hermes image/Compose overlay with the native hub's HTTPS URL |
| Both in Docker, together or on separate machines | Hub and Hermes images, persistent volumes, HTTPS routes in both directions |
| Hub in k3s, Hermes elsewhere | Hub kustomization plus remote Hermes API and MCP configuration |
| Both in k3s | Hub kustomization plus optional Hermes manifest |
| Docker Desktop on macOS or Windows | Linux containers and named volumes; Windows commands below run in WSL2 |

Every arrangement uses **one active hub** and **one active gateway per Hermes data directory**.
This is durable single-instance deployment, not active-active or automatic database failover.

## Native hosts and split hosts

For the existing macOS launchd/Linux systemd installation, see [HUB.md](HUB.md). The installer
accepts a remote Hermes API URL and reads its key from a file:

```sh
tools/hub-setup.sh --public-origin https://workspace.example.net \
  --hermes-url https://hermes.example.net --hermes-token-file /private/hermes-api-key
```

On the separate Hermes host, register only the adapter and its skill:

```sh
tools/hub-setup.sh --mcp-only --hub-url https://workspace.example.net \
  --mcp-token-file /private/robotos-mcp-token
```

Copy only the hub's **MCP token** to that private file using your secret manager or a protected
transfer. Do not give Hermes `owner-token` or `owner-password`. `--no-start` prepares configuration
without restarting a gateway. Validate both HTTPS endpoints before reconnecting devices.

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
docker compose up -d --build
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
docker compose -f compose.yaml -f compose.tls.yaml up -d --build
```

This overlay explicitly exposes TLS on host port 443 and HTTP redirects/ACME on 80. Use the
Tailscale route for private tailnet names; Caddy's public ACME route is not a substitute for it.
The console still requires its owner password. Read `private/owner-password` locally to sign in.

Prebuilt multi-architecture images are published from successful `main` builds as
`ghcr.io/windoze95/robotos-hub:sha-<full-commit>` and
`ghcr.io/windoze95/robotos-hermes:sha-<full-commit>`. Set `ROBOTOS_IMAGE` and
`ROBOTOS_HERMES_IMAGE` to the chosen tags (or digests), then use `docker compose up -d --no-build`.
Build locally when testing an unmerged revision. `main` tags are moving development builds;
use immutable tags/digests for deployed systems.

## Docker: Hermes too

The derived Hermes image uses the [official upstream image](https://hermes-agent.nousresearch.com/docs/user-guide/docker)
pinned by its multi-architecture digest. It adds the robotOS stdio adapter, its skill, and a small
startup configurator. It runs the gateway in the foreground as UID 10000; robotOS runs as UID
10001. There is no root supervisor, host-network requirement, privileged container, or Docker
socket mount. Agent tools that need additional access must be configured deliberately.

For a new gateway, omit `--hermes-token-file` when creating secrets: the initializer generates a
new shared API credential. For an existing gateway, retain the current credential and migrate its
state deliberately. Both containers need HTTPS URLs reachable from inside their own network;
verify DNS and routing in both directions, including hairpin routing where appropriate.

First build and configure Hermes's model/provider. The setup command writes to its named volume:

```sh
cd deploy/hub
docker compose -f compose.yaml -f compose.hermes.yaml build
docker compose -f compose.yaml -f compose.hermes.yaml run --rm -it hermes setup
docker compose -f compose.yaml -f compose.hermes.yaml up -d
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
  -f compose.tls.yaml -f compose.tls-hermes.yaml up -d
```

For separate Docker machines, run the hub on one host and only the `hermes` service on the other
with the same overlay configuration. Transfer only `mcp-token` and `hermes-token` to the Hermes
host's private secret directory. That host does not need the hub's owner credentials; Compose
loads only the two secrets referenced by the selected service. Use `up -d --no-deps hermes`.
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
