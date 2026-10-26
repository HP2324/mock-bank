#!/bin/bash
# EC2 user data for Ubuntu Server 24.04 LTS: installs Docker Engine + the Compose plugin, then
# builds and starts the whole stack. Set REPO_URL (and optionally REPO_REF) before launching.
set -euxo pipefail

REPO_URL="${REPO_URL:-https://github.com/HP2324/mock-bank.git}"
REPO_REF="${REPO_REF:-main}"
APP_DIR=/opt/mock-bank

apt-get update
apt-get install -y ca-certificates curl git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
usermod -aG docker ubuntu

git clone --branch "$REPO_REF" "$REPO_URL" "$APP_DIR"
cd "$APP_DIR"
# A real password instead of the local-dev default; kept on the instance only.
if [ ! -f .env ]; then
  echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)" > .env
  chmod 600 .env
fi
docker compose up -d --build --wait --wait-timeout 600
