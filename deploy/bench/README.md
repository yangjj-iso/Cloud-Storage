# k6 Load Tests

Benchmarks for the chunked upload service covering proxy upload, direct (presigned) upload, and download paths.

## Install k6

```bash
# macOS
brew install k6

# Windows (scoop)
scoop install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Docker
docker run --rm -i grafana/k6 run - <script.js
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | Backend API base URL |
| `AUTH_TOKEN` | _(required)_ | Bearer token returned by `/api/v1/auth/login` |
| `FILE_SIZE_MB` | `10` | Simulated file size in MB |
| `CHUNK_SIZE_MB` | `10` | Chunk size in MB |
| `VUS` | `10` / `20` | Virtual users (concurrency) |
| `DURATION` | `30s` | Test duration |
| `FILE_ID` | _(required for download)_ | Existing file ID to download |

## Run

```bash
# Proxy upload (chunks through backend)
k6 run -e BASE_URL=http://localhost:8080 -e AUTH_TOKEN=$AUTH_TOKEN -e FILE_SIZE_MB=10 -e VUS=10 deploy/bench/proxy-upload.js

# Direct upload (presigned PUT to MinIO)
k6 run -e BASE_URL=http://localhost:8080 -e AUTH_TOKEN=$AUTH_TOKEN -e FILE_SIZE_MB=10 -e VUS=10 deploy/bench/direct-upload.js

# Download
k6 run -e BASE_URL=http://localhost:8080 -e AUTH_TOKEN=$AUTH_TOKEN -e FILE_ID=abc123 -e VUS=20 deploy/bench/download.js
```

## Interpreting Results

Each script tags requests by stage (init, chunk, presign, merge, etc.) so you can filter metrics per stage:

```bash
k6 run --out json=results.json deploy/bench/proxy-upload.js
```

Thresholds are pre-configured — k6 exits non-zero if they are breached.
