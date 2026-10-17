# Deploying to a small VM

Postgres, the app and Caddy (automatic HTTPS) via `compose.yml`. The VM only
runs the image; it is built elsewhere because a 1 GB machine can't build it.

## First time

On the VM: Docker, ports 80 and 443 open (cloud firewall and iptables), and
a DNS A record pointing at it.

```sh
scp deploy/compose.yml deploy/Caddyfile vm:duty-scheduler/
# on the VM: create duty-scheduler/.env from .env.example, chmod 600
```

## Each release

CI does this on every push to `main` after the tests pass (the `deploy` job,
using the `DEPLOY_SSH_KEY` and `DEPLOY_KNOWN_HOSTS` secrets and the
`DEPLOY_HOST` and `DEPLOY_USER` variables). By hand:

```sh
docker buildx build --platform linux/amd64 -t duty-scheduler:latest --load .
docker save duty-scheduler:latest | gzip -1 | ssh vm 'gunzip | docker load'
ssh vm 'cd duty-scheduler && docker compose up -d'
```

Use `linux/arm64` on an ARM VM.

## Backups

```sh
ssh vm 'cd duty-scheduler && docker compose exec -T db pg_dump -U duty duty' > duty-$(date +%F).sql
```
