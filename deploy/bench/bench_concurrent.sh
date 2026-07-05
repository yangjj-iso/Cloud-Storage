#!/bin/bash
# CloudChunk Concurrent Benchmark (init→chunk→merge full flow)
# Usage: bash deploy/bench/bench_concurrent.sh [CONCURRENCY] [TOTAL]

BASE=${BASE_URL:-http://localhost:8080/api/v1}
AUTH_TOKEN=${AUTH_TOKEN:-}
CONCURRENCY=${1:-10}
TOTAL=${2:-100}
CHUNK_SIZE=$((1*1024*1024))
RESULTS_DIR=/tmp/bench_concurrent
rm -rf "$RESULTS_DIR" && mkdir -p "$RESULTS_DIR"

echo "=== CloudChunk Concurrent Benchmark ==="
echo "Base URL: $BASE"
echo "Concurrency: $CONCURRENCY | Total: $TOTAL | Chunk: 1 MB"
echo ""

if [ -z "$AUTH_TOKEN" ]; then
    echo "AUTH_TOKEN is required. Login first and run: AUTH_TOKEN=<token> bash deploy/bench/bench_concurrent.sh"
    exit 1
fi
AUTH_HEADER="Authorization: Bearer $AUTH_TOKEN"

dd if=/dev/urandom of=/tmp/chunk_data bs=$CHUNK_SIZE count=1 2>/dev/null
CHUNK_MD5=$(md5sum /tmp/chunk_data | awk '{print $1}')

compute_stats() {
    sort -n "$1" | awk '
    { vals[NR-1] = $1; sum += $1; count++ }
    END {
        if (count == 0) { print "NO DATA"; exit }
        printf "avg=%.1f p50=%.1f p95=%.1f p99=%.1f n=%d\n",
            sum/count, vals[int(count*0.50)], vals[int(count*0.95)],
            vals[int(count*0.99)], count
    }'
}

do_one_upload() {
    local i=$1
    local md5=$(echo "conc-${i}-$(date +%s%N)-$$" | md5sum | awk '{print $1}')
    local t0=$(date +%s%N)

    local resp=$(curl -s -X POST "$BASE/upload/init" \
        -H "Content-Type: application/json" -H "$AUTH_HEADER" \
        -d "{\"fileMd5\":\"$md5\",\"fileName\":\"conc-${i}.bin\",\"fileSize\":$CHUNK_SIZE,\"chunkSize\":$CHUNK_SIZE,\"chunkTotal\":1}")
    local t1=$(date +%s%N)

    local fid=$(echo "$resp" | grep -o '"fileId":"[^"]*"' | head -1 | cut -d'"' -f4)
    [ -z "$fid" ] && return

    curl -s -o /dev/null -X POST \
        "$BASE/upload/chunk?fileId=${fid}&chunkIndex=0&chunkMd5=${CHUNK_MD5}&chunkSize=${CHUNK_SIZE}" \
        -H "$AUTH_HEADER" -H "Content-Type: application/octet-stream" --data-binary @/tmp/chunk_data
    local t2=$(date +%s%N)

    curl -s -o /dev/null -X POST "$BASE/upload/merge/${fid}" -H "$AUTH_HEADER"
    local t3=$(date +%s%N)

    echo "$(( (t1-t0)/1000000 ))" >> "$RESULTS_DIR/init.txt"
    echo "$(( (t2-t1)/1000000 ))" >> "$RESULTS_DIR/chunk.txt"
    echo "$(( (t3-t2)/1000000 ))" >> "$RESULTS_DIR/merge.txt"
    echo "$(( (t3-t0)/1000000 ))" >> "$RESULTS_DIR/total.txt"
}

export -f do_one_upload
export BASE AUTH_HEADER CHUNK_SIZE CHUNK_MD5 RESULTS_DIR

START=$(date +%s%N)
active=0
for i in $(seq 1 $TOTAL); do
    do_one_upload $i &
    active=$((active + 1))
    if [ $active -ge $CONCURRENCY ]; then
        wait -n 2>/dev/null || wait
        active=$((active - 1))
    fi
done
wait
END=$(date +%s%N)
WALL_MS=$(( (END-START)/1000000 ))
THROUGHPUT=$(awk "BEGIN { printf \"%.1f\", $TOTAL / ($WALL_MS / 1000.0) }")

echo "Results:"
echo "  Init:        $(compute_stats $RESULTS_DIR/init.txt) ms"
echo "  Chunk (1MB): $(compute_stats $RESULTS_DIR/chunk.txt) ms"
echo "  Merge:       $(compute_stats $RESULTS_DIR/merge.txt) ms"
echo "  Total E2E:   $(compute_stats $RESULTS_DIR/total.txt) ms"
echo "  Wall time:   ${WALL_MS} ms"
echo "  Throughput:  ${THROUGHPUT} uploads/s"
