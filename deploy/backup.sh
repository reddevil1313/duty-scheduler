#!/bin/sh
# Nightly database dump, kept for 7 days. Run from cron on the VM:
#   0 19 * * * $HOME/duty-scheduler/backup.sh   (19:00 UTC)
set -eu
cd "$(dirname "$0")"
mkdir -p backups
file="backups/duty-$(date +%F).sql.gz"
docker compose exec -T db pg_dump -U duty --clean --if-exists duty | gzip > "$file.tmp"
mv "$file.tmp" "$file"
find backups -name 'duty-*.sql.gz' -mtime +6 -delete
