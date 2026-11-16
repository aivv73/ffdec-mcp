#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
archive=${FFDEC_LIB_ARCHIVE:-"$temporary/ffdec_lib.zip"}
if [[ -z ${FFDEC_LIB_ARCHIVE:-} ]]; then
    curl --fail --location --retry 2 \
        https://github.com/jindrapetrik/jpexs-decompiler/releases/download/version25.1.3/ffdec_lib_25.1.3.zip \
        --output "$archive"
fi
expected=b99690c58823c64065b4a21b4d35d490cf9bf8827ffad3d6faa2141831b20629
if command -v sha256sum >/dev/null; then
    actual=$(sha256sum "$archive")
else
    actual=$(shasum -a 256 "$archive")
fi
if [[ ${actual%% *} != "$expected" ]]; then
    printf '%s\n' 'FFDec library checksum mismatch.' >&2
    exit 1
fi
unzip -q "$archive" -d "$temporary/library"
mvn "$@" install:install-file \
    -Dfile="$temporary/library/ffdec_lib.jar" \
    -DgroupId=com.jpexs -DartifactId=ffdec_lib -Dversion=1.0.0 -Dpackaging=jar
mvn "$@" -Dtest=SwfAs2ToolsTest clean package
