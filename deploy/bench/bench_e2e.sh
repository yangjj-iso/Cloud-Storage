#!/bin/bash
# CloudChunk End-to-End Benchmark (serial, no dependencies beyond curl)
# Usage: bash deploy/bench/bench_e2e.sh [RUNS]

BASE=${BASE_URL:-http://localhost:8080/api/v1}
AUTH_TOKEN=${AUTH_TOKEN:-}
RUNS=${1:-50}
CHUNK_SIZE=$((1*1024*1024))
RESULTS_DIR=/tmp/bench_results
rm -rf "$RESULTS_DIR" && mkdir -p "$RESULTS_DIR"

echo "=== CloudChunk E2E Benchmark (serial) ==="
echo "Base URL: $BASE"
echo "Runs: $RUNS | Chunk: 1 MB"
echo ""

if [ -z "$AUTH_TOKEN" ]; then
    echo "AUTH_TOKEN is required. Login first and run: AUTH_TOKEN=<token> bash deploy/bench/bench_e2e.sh"
    exit 1
fi

AUTH_HEADER=(-H "Authorization: Bearer $AUTH_TOKEN")

dd if=/dev/urandom of=/tmp/chunk_data bs=$CHUNK_SIZE count=1 2>/dev/null
CHUNK_MD5=$(md5sum /tmp/chunk_data | awk '{print $1}')

compute_stats() {
    sort -n "$1" | awk '
    { vals[NR-1] = $1; sum += $1; count++ }
    END {
        if (count == 0) { print "NO DATA"; exit }
        printf "avg=%.1f p50=%.1f p95=%.1f p99=%.1f min=%.1f max=%.1f n=%d\n",
            sum/count, vals[int(count*0.50)], vals[int(count*0.95)],
            vals[int(count*0.99)], vals[0], vals[count-1], count
    }'
}

echo ">>> [1/4] Init Upload"
for i in $(seq 1 $RUNS); do
    md5=$(echo "bench-init-${i}-$$" | md5sum | awk '{print $1}')
    t=$(curl -s -o /dev/null -w "%{time_total}" -X POST "$BASE/upload/init" \
        -H "Content-Type: application/json" "${AUTH_HEADER[@]}" \
        -d "{\"fileMd5\":\"$md5\",\"fileName\":\"b-${i}.bin\",\"fileSize\":$CHUNK_SIZE,\"chunkSize\":$CHUNK_SIZE,\"chunkTotal\":1}")
    echo "$t" | awk '{printf "%.1f\n", $1*1000}' >> "$RESULTS_DIR/init.txt"
done
echo "  $(compute_stats $RESULTS_DIR/init.txt) ms"

echo ">>> [2/4] Upload Chunk (1MB)"
for i in $(seq 1 $RUNS); do
    md5=$(echo "bench-chunk-${i}-$$-$(date +%s%N)" | md5sum | awk '{print $1}')
    resp=$(curl -s -X POST "$BASE/upload/init" \
        -H "Content-Type: application/json" "${AUTH_HEADER[@]}" \
        -d "{\"fileMd5\":\"$md5\",\"fileName\":\"c-${i}.bin\",\"fileSize\":$CHUNK_SIZE,\"chunkSize\":$CHUNK_SIZE,\"chunkTotal\":1}")
    fid=$(echo "$resp" | grep -o '"fileId":"[^"]*"' | head -1 | cut -d'"' -f4)
    [ -z "$fid" ] && continue
    t=$(curl -s -o /dev/null -w "%{time_total}" -X POST \
        "$BASE/upload/chunk?fileId=${fid}&chunkIndex=0&chunkMd5=${CHUNK_MD5}&chunkSize=${CHUNK_SIZE}" \
        "${AUTH_HEADER[@]}" -H "Content-Type: application/octet-stream" --data-binary @/tmp/chunk_data)
    echo "$t" | awk '{printf "%.1f\n", $1*1000}' >> "$RESULTS_DIR/chunk.txt"
done
echo "  $(compute_stats $RESULTS_DIR/chunk.txt) ms"

echo ">>> [3/4] Merge"
for i in $(seq 1 $RUNS); do
    md5=$(echo "bench-merge-${i}-$$-$(date +%s%N)" | md5sum | awk '{print $1}')
    resp=$(curl -s -X POST "$BASE/upload/init" \
        -H "Content-Type: application/json" "${AUTH_HEADER[@]}" \
        -d "{\"fileMd5\":\"$md5\",\"fileName\":\"m-${i}.bin\",\"fileSize\":$CHUNK_SIZE,\"chunkSize\":$CHUNK_SIZE,\"chunkTotal\":1}")
    fid=$(echo "$resp" | grep -o '"fileId":"[^"]*"' | head -1 | cut -d'"' -f4)
    [ -z "$fid" ] && continue
    curl -s -o /dev/null -X POST \
        "$BASE/upload/chunk?fileId=${fid}&chunkIndex=0&chunkMd5=${CHUNK_MD5}&chunkSize=${CHUNK_SIZE}" \
        "${AUTH_HEADER[@]}" -H "Content-Type: application/octet-stream" --data-binary @/tmp/chunk_data
    t=$(curl -s -o /dev/null -w "%{time_total}" -X POST "$BASE/upload/merge/${fid}" "${AUTH_HEADER[@]}")
    echo "$t" | awk '{printf "%.1f\n", $1*1000}' >> "$RESULTS_DIR/merge.txt"
done
echo "  $(compute_stats $RESULTS_DIR/merge.txt) ms"

echo ">>> [4/4] Download Presign"
if [ -n "$fid" ]; then
    for i in $(seq 1 $RUNS); do
        t=$(curl -s -o /dev/null -w "%{time_total}" "$BASE/file/${fid}/url" "${AUTH_HEADER[@]}")
        echo "$t" | awk '{printf "%.1f\n", $1*1000}' >> "$RESULTS_DIR/download.txt"
    done
    echo "  $(compute_stats $RESULTS_DIR/download.txt) ms"
else
    echo "  SKIPPED (no file_id)"
fi

echo ""
echo "=== Done ==="
