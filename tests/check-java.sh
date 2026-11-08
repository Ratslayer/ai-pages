#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
javac -d "$build_dir" app/src/main/java/app/aipages/{OAuthCallback,CatalogKey}.java tests/{OAuthCallbackTest,CatalogKeyTest}.java
java -cp "$build_dir" app.aipages.OAuthCallbackTest
java -cp "$build_dir" app.aipages.CatalogKeyTest
