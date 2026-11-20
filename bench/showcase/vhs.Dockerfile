# vhs (terminal -> GIF) plus curl and jq, so the API demo runs real HTTP calls inside the recording.
FROM ghcr.io/charmbracelet/vhs:v0.10.0
# --allow-releaseinfo-change: the image's apt sources still say "bookworm" after Debian's stable moved on.
RUN apt-get update --allow-releaseinfo-change && apt-get install -y --no-install-recommends curl jq && rm -rf /var/lib/apt/lists/*
