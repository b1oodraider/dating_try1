#!/usr/bin/env bash
# Installs / updates the Dating backend on an Ubuntu 24.04 VPS without Docker (for LXC hosts where runc
# cannot start containers): PostgreSQL 16 + Hunspell ru, Java 21, the backend as a systemd service, Caddy
# in front (HTTPS, web client, /api proxy). Idempotent: run again after uploading a new app.jar / web build.
#
# Before running, upload:  /opt/dating/app.jar   (backend/build/libs/*.jar)
#                          /opt/dating/web/      (npm run build -> dist/)
#                          /opt/dating/install.sh, dating-backend.service, Caddyfile (this folder)
# Usage: DOMAIN=example.com bash /opt/dating/install.sh
set -euo pipefail

DOMAIN="${DOMAIN:?DOMAIN must be set}"
APP_DIR=/opt/dating
ENV_FILE="$APP_DIR/.env"

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq postgresql postgresql-contrib openjdk-21-jre-headless openssl >/dev/null

# --- secrets: generated once on the server, never leave it
if [ ! -f "$ENV_FILE" ]; then
  umask 077
  cat > "$ENV_FILE" <<EOF
DB_NAME=dating
DB_USER=dating_user
DB_PASSWORD=$(openssl rand -hex 24)
APP_MEDIA_URL_SIGNING_SECRET=$(openssl rand -hex 32)
APP_AUTH_PHONE_HASH_SECRET=$(openssl rand -hex 32)
APP_CORS_ALLOWED_ORIGINS=https://$DOMAIN
APP_TESTING_ENABLED=true
APP_TESTING_TEST_PHONES="+7 900 000-00-01, +7 900 000-00-02, +7 900 000-00-03, +7 900 000-00-04, +7 900 000-00-05, +7 900 000-00-06, +7 900 000-00-07, +7 900 000-00-08, +7 900 000-00-09, +7 900 000-00-10, +7 900 000-00-11, +7 900 000-00-12, +7 900 000-00-13, +7 900 000-00-14, +7 900 000-00-15, +7 900 000-00-16, +7 900 000-00-17, +7 900 000-00-18, +7 900 000-00-19, +7 900 000-00-20, +7 900 000-00-21, +7 900 000-00-22, +7 900 000-00-23, +7 900 000-00-24, +7 900 000-00-25"
APP_TESTING_OTP_CODE=$(shuf -i 100000-999999 -n 1)
EOF
fi
set -a; . "$ENV_FILE"; set +a

# --- Russian Hunspell dictionary for full-text search (same pinned files as infra/postgres/Dockerfile)
TS_DIR="$(ls -d /usr/share/postgresql/*/tsearch_data | sort -V | tail -1)"
REF=918f8d093b1b6cd5f7e4c6b4874f559d7210b207
BASE="https://raw.githubusercontent.com/postgrespro/hunspell_dicts/$REF/hunspell_ru_ru"
if [ ! -f "$TS_DIR/ru_ru.dict" ]; then
  curl -fsSL -o "$TS_DIR/ru_ru.affix" "$BASE/ru_ru.affix"
  curl -fsSL -o "$TS_DIR/ru_ru.dict" "$BASE/ru_ru.dict"
  echo "9cf4c27307af21544ef461b3ecbceecf0eec6d93ee8494eb19cab0de63692441  $TS_DIR/ru_ru.affix" | sha256sum -c -
  echo "0963e4a7c06f922fe6e415c8303c20995a617e1d1c2acfae8b819c2f89c52101  $TS_DIR/ru_ru.dict" | sha256sum -c -
fi

# --- database (PostgreSQL listens on localhost only by default)
systemctl enable --now postgresql >/dev/null
sudo -u postgres psql -q -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$DB_USER') THEN
    CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASSWORD';
  END IF;
END \$\$;
SQL
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1 \
  || sudo -u postgres createdb -O "$DB_USER" "$DB_NAME"

# --- backend service
id dating >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin dating
mkdir -p /var/lib/dating/media
chown -R dating:dating /var/lib/dating
umask 077
cat > "$APP_DIR/backend.env" <<EOF
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/$DB_NAME
SPRING_DATASOURCE_USERNAME=$DB_USER
SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD
APP_MEDIA_STORAGE_PATH=/var/lib/dating/media
APP_MEDIA_URL_SIGNING_SECRET=$APP_MEDIA_URL_SIGNING_SECRET
APP_AUTH_PHONE_HASH_SECRET=$APP_AUTH_PHONE_HASH_SECRET
APP_CORS_ALLOWED_ORIGINS=$APP_CORS_ALLOWED_ORIGINS
APP_AUTH_MOCK_ENABLED=false
APP_TESTING_ENABLED=$APP_TESTING_ENABLED
APP_TESTING_TEST_PHONES="$APP_TESTING_TEST_PHONES"
APP_TESTING_OTP_CODE=$APP_TESTING_OTP_CODE
SERVER_ADDRESS=127.0.0.1
SERVER_FORWARD_HEADERS_STRATEGY=framework
EOF
# Provider settings (docs/integrations.md) pass through as they are.
grep -E '^APP_(SMS|PAYMENTS|PUSH)_' "$ENV_FILE" >> "$APP_DIR/backend.env" || true
chown root:dating "$APP_DIR/backend.env"
chmod 640 "$APP_DIR/backend.env"
umask 022
install -m 644 "$APP_DIR/dating-backend.service" /etc/systemd/system/dating-backend.service
install -m 755 "$APP_DIR/deploy.sh" /usr/local/sbin/dating-deploy
systemctl daemon-reload
systemctl enable dating-backend >/dev/null
systemctl restart dating-backend

# --- web client and HTTPS
mkdir -p /srv/dating-web
rm -rf /srv/dating-web/*
cp -r "$APP_DIR/web/." /srv/dating-web/
[ -f /etc/caddy/Caddyfile.before-dating ] || cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-dating
sed "s/{\$DOMAIN}/$DOMAIN/g" "$APP_DIR/Caddyfile" > /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
systemctl reload caddy

echo "Waiting for the backend..."
for _ in $(seq 1 60); do
  curl -fs http://127.0.0.1:8080/actuator/health >/dev/null && { echo "Backend is up"; exit 0; }
  sleep 3
done
echo "Backend did not start: journalctl -u dating-backend -n 100" >&2
exit 1
