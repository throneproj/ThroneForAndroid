#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

# Resolve the latest release tag of a GitHub repository.
# Priority: parse the 302 redirect Location of releases/latest (no API rate
# limit) -> authenticated API via GITHUB_TOKEN -> plain API. Falls back to
# the releases/latest/download direct link when the tag cannot be resolved.
get_latest_release() {
  local repo=$1
  local tag=""

  # 1) 302 redirect Location: releases/latest redirects to .../tag/<tag>
  tag=`curl -fsSI -o /dev/null -w '%{redirect_url}' "https://github.com/$repo/releases/latest" 2>/dev/null \
    | sed -E 's#.*/tag/([^/]+)$#\1#'`
  if [ -z "$tag" ] || [ "$tag" = "https://github.com/$repo/releases/latest" ]; then
    tag=""
  fi

  # 2) Authenticated API (higher rate limit) when GITHUB_TOKEN is available
  if [ -z "$tag" ] && [ -n "$GITHUB_TOKEN" ]; then
    tag=`curl --silent --header "Authorization: Bearer $GITHUB_TOKEN" \
      "https://api.github.com/repos/$1/releases/latest" \
      | grep '"tag_name":' \
      | sed -E 's/.*"([^"]+)".*/\1/'`
  fi

  # 3) Plain API (subject to rate limiting)
  if [ -z "$tag" ]; then
    tag=`curl --silent "https://api.github.com/repos/$1/releases/latest" \
      | grep '"tag_name":' \
      | sed -E 's/.*"([^"]+)".*/\1/'`
  fi

  echo "$tag"
}

# Download a release asset, preferring the resolved tag; when the tag is
# empty, fall back to the releases/latest/download direct link.
download_release_asset() {
  local repo=$1
  local asset=$2
  local tag=$3
  if [ -n "$tag" ]; then
    curl -fLSsO "https://github.com/$repo/releases/download/$tag/$asset"
  else
    curl -fLSsO "https://github.com/$repo/releases/latest/download/$asset"
  fi
}

####
VERSION_GEOIP=`get_latest_release "SagerNet/sing-geoip"`
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
download_release_asset "SagerNet/sing-geoip" geoip.db "$VERSION_GEOIP"
xz -9 geoip.db

####
VERSION_GEOSITE=`get_latest_release "SagerNet/sing-geosite"`
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
download_release_asset "SagerNet/sing-geosite" geosite.db "$VERSION_GEOSITE"
xz -9 geosite.db
