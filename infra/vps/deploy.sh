#!/usr/bin/env bash
# Forced command of the CI deploy key (.github/workflows/ci.yml): reads a tar.gz with
# app.jar and web/ from stdin, swaps them in and restarts the backend. It never takes scripts or configs
# from the archive, so the key can ship a new build but cannot run anything as root.
set -euo pipefail

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
head -c 300000000 | tar -xz -C "$tmp" --no-same-owner --no-same-permissions app.jar web
[ -s "$tmp/app.jar" ] && [ -f "$tmp/web/index.html" ] || { echo "archive must contain app.jar and web/" >&2; exit 1; }

install -m 644 "$tmp/app.jar" /opt/dating/app.jar.new
mv /opt/dating/app.jar.new /opt/dating/app.jar
rm -rf /srv/dating-web.new
cp -r "$tmp/web" /srv/dating-web.new
chmod -R a+rX /srv/dating-web.new
rm -rf /srv/dating-web.old
mv /srv/dating-web /srv/dating-web.old
mv /srv/dating-web.new /srv/dating-web

systemctl restart dating-backend
for _ in $(seq 1 60); do
  if curl -fs http://127.0.0.1:8080/actuator/health >/dev/null; then
    echo "Deployed"
    exit 0
  fi
  sleep 3
done
echo "Backend did not come up" >&2
journalctl -u dating-backend -n 60 --no-pager >&2
exit 1
