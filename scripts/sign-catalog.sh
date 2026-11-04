#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
KEY=${ANCT_CATALOG_KEY:-"$ROOT/.runtime-cache/catalog-key/catalog-key.pem"}
CATALOG="$ROOT/app/src/main/assets/catalog.json"
PUBLIC="$ROOT/app/src/main/assets/catalog.pub.pem"
SIGNATURE="$ROOT/app/src/main/assets/catalog.sig"

if [ ! -f "$KEY" ]; then
    echo "Catalog signing key not found: $KEY" >&2
    echo "Set ANCT_CATALOG_KEY to the release signing key." >&2
    exit 1
fi

openssl ec -in "$KEY" -pubout -out "$PUBLIC" >/dev/null 2>&1
openssl dgst -sha256 -sign "$KEY" -out "$SIGNATURE" "$CATALOG"
openssl dgst -sha256 -verify "$PUBLIC" -signature "$SIGNATURE" "$CATALOG"
