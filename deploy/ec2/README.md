# Running on Linux / AWS EC2 (Ubuntu)

The whole stack is Docker Compose, so any Linux host with Docker runs it the same way as
Docker Desktop. CI already proves this on every push: the `e2e` job in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) starts the stack on an Ubuntu runner
and replays the saved automation artifacts against it.

## Launch an instance

1. **AMI:** Ubuntu Server 24.04 LTS (x86_64 or arm64 both work).
2. **Type:** `t3.medium` (2 vCPU, 4 GiB) or larger. Kafka + PostgreSQL + two JVMs + the image
   builds do not fit comfortably in 1–2 GiB.
3. **Storage:** 20 GiB gp3.
4. **Security group:** allow inbound **SSH (22) from your IP only**. Nothing else is needed —
   every service binds to `127.0.0.1` on the instance (see `docker-compose.yml`).
5. **User data:** paste [`user-data.sh`](user-data.sh). It installs Docker Engine and the Compose
   plugin, clones this repo to `/opt/mock-bank`, writes a random `POSTGRES_PASSWORD` to `.env`, and
   runs `docker compose up -d --build --wait`. First boot takes ~5–8 minutes (image builds).
   To use a fork or branch, edit `REPO_URL` / `REPO_REF` at the top of the script.

## Use it

Open an SSH tunnel and browse locally — the demo login (`operator` / `password`) is never exposed
to the internet:

```bash
ssh -i your-key.pem -L 8080:localhost:8080 -L 8081:localhost:8081 ubuntu@<instance-public-dns>
```

Then visit <http://localhost:8080> (staff console) and <http://localhost:8081/api/events/summary>
(audit events consumed from Kafka).

Check progress or troubleshoot on the instance:

```bash
sudo tail -f /var/log/cloud-init-output.log   # user-data progress
cd /opt/mock-bank && docker compose ps         # all four services should be "healthy"
docker compose logs -f mock-bank audit-consumer
```

## Run the automation replay against the instance

With the tunnel open, the Python automation works unchanged from your machine:

```bash
cd automation && source .venv/bin/activate
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers python scripts/replay_smoke.py --base-url http://localhost:8080
```

## Update / reset / tear down

```bash
cd /opt/mock-bank && git pull && docker compose up -d --build --wait   # deploy latest
docker compose down -v && docker compose up -d --wait                  # reset to seed data
```

Terminate the instance when finished — it is billed while running.
