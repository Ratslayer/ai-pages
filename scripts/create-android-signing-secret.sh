#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: bash $0 /path/to/ai-pages-android-signing.secret" >&2
  exit 1
fi
if [[ -e "$1" ]]; then
  echo "The output already exists. Reuse it to keep signing updates with the same key." >&2
  exit 1
fi

umask 077
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
export AI_PAGES_KEY_PASSWORD
AI_PAGES_KEY_PASSWORD="$(openssl rand -hex 24)"
openssl req -x509 -newkey rsa:3072 -nodes -days 10000 -subj "/CN=AI Pages Android" \
  -keyout "$temp/key.pem" -out "$temp/cert.pem" 2>"$temp/openssl.log" || {
    cat "$temp/openssl.log" >&2
    exit 1
  }
openssl pkcs12 -export -inkey "$temp/key.pem" -in "$temp/cert.pem" \
  -name ai-pages -passout env:AI_PAGES_KEY_PASSWORD -out "$temp/key.p12"
{
  printf '%s\n' "$AI_PAGES_KEY_PASSWORD"
  openssl base64 -A -in "$temp/key.p12"
  printf '\n'
} > "$temp/signing.secret"
(set -o noclobber; cat "$temp/signing.secret" > "$1")
printf 'Signing secret saved to %s\n' "$1"
