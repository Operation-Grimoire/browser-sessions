#!/usr/bin/env bash
set -euo pipefail
properties=gradle/wrapper/gradle-wrapper.properties
version=$(sed -n 's|^distributionUrl=.*gradle-\([0-9.]*\)-bin\.zip\r\?$|\1|p' "$properties")
checksum=$(sed -n 's/^distributionSha256Sum=//p' "$properties" | tr -d '\r')
[[ "$version" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]
[[ "$checksum" =~ ^[0-9a-f]{64}$ ]]
temporary="$RUNNER_TEMP"
if command -v cygpath >/dev/null; then temporary=$(cygpath -u "$temporary"); fi
destination="$temporary/browser-sessions-gradle"
mkdir -p "$destination"
gh release download "v$version" --repo gradle/gradle-distributions \
  --pattern "gradle-$version-bin.zip" --dir "$destination"
printf '%s  %s\n' "$checksum" "$destination/gradle-$version-bin.zip" | sha256sum --check
unzip -q "$destination/gradle-$version-bin.zip" -d "$destination"
binary="$destination/gradle-$version/bin"
if command -v cygpath >/dev/null; then binary=$(cygpath -w "$binary"); fi
echo "$binary" >> "$GITHUB_PATH"
