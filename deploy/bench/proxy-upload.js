import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { md5 } from 'k6/crypto';
import { crypto } from 'k6/experimental/webcrypto';
import { SharedArray } from 'k6/data';

// ---------------------------------------------------------------------------
// Configuration via environment variables
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FILE_SIZE_MB = parseInt(__ENV.FILE_SIZE_MB || '10', 10);
const CHUNK_SIZE_MB = parseInt(__ENV.CHUNK_SIZE_MB || '10', 10);
const VUS = parseInt(__ENV.VUS || '10', 10);
const DURATION = __ENV.DURATION || '30s';
const AUTH_TOKEN = __ENV.AUTH_TOKEN;

if (!AUTH_TOKEN) {
  fail('AUTH_TOKEN environment variable is required. Pass -e AUTH_TOKEN=<token>');
}

const AUTH_HEADERS = { Authorization: `Bearer ${AUTH_TOKEN}` };

const CHUNK_SIZE = CHUNK_SIZE_MB * 1024 * 1024;
const FILE_SIZE = FILE_SIZE_MB * 1024 * 1024;
const CHUNK_TOTAL = Math.ceil(FILE_SIZE / CHUNK_SIZE);

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{stage:init}': ['p(95)<300'],
    'http_req_duration{stage:chunk}': ['p(95)<1000'],
    'http_req_duration{stage:merge}': ['p(95)<500'],
  },
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function randomHex(bytes) {
  const arr = new Uint8Array(bytes);
  crypto.getRandomValues(arr);
  return Array.from(arr)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

// ---------------------------------------------------------------------------
// setup — generate a reusable random chunk payload
// ---------------------------------------------------------------------------
export function setup() {
  const chunkData = new Uint8Array(CHUNK_SIZE);
  crypto.getRandomValues(chunkData);
  return { chunkData: Array.from(chunkData) };
}

// ---------------------------------------------------------------------------
// Main VU function
// ---------------------------------------------------------------------------
export default function (data) {
  const chunkBytes = new Uint8Array(data.chunkData);
  const fileMd5 = randomHex(16);
  const fileName = `bench-${fileMd5.slice(0, 8)}.bin`;

  // 1. Init upload
  const initPayload = JSON.stringify({
    fileMd5: fileMd5,
    fileName: fileName,
    fileSize: FILE_SIZE,
    chunkSize: CHUNK_SIZE,
    chunkTotal: CHUNK_TOTAL,
    mimeType: 'application/octet-stream',
  });

  const initRes = http.post(`${BASE_URL}/api/v1/upload/init`, initPayload, {
    headers: { ...AUTH_HEADERS, 'Content-Type': 'application/json' },
    tags: { stage: 'init' },
  });

  check(initRes, {
    'init status 200': (r) => r.status === 200,
    'init has fileId': (r) => {
      try {
        return JSON.parse(r.body).data.fileId !== undefined;
      } catch (_) {
        return false;
      }
    },
  });

  if (initRes.status !== 200) {
    return;
  }

  const fileId = JSON.parse(initRes.body).data.fileId;

  // 2. Upload each chunk via proxy
  for (let i = 0; i < CHUNK_TOTAL; i++) {
    const chunkMd5 = md5(chunkBytes.buffer, 'hex');

    const formData = {
      fileId: fileId,
      chunkIndex: String(i),
      chunkMd5: chunkMd5,
      chunkSize: String(CHUNK_SIZE),
      file: http.file(chunkBytes, `chunk-${i}.bin`, 'application/octet-stream'),
    };

    const chunkRes = http.post(`${BASE_URL}/api/v1/upload/chunk`, formData, {
      headers: AUTH_HEADERS,
      tags: { stage: 'chunk' },
    });

    check(chunkRes, {
      [`chunk ${i} status 200`]: (r) => r.status === 200,
    });
  }

  // 3. Merge
  const mergeRes = http.post(`${BASE_URL}/api/v1/upload/merge/${fileId}`, null, {
    headers: AUTH_HEADERS,
    tags: { stage: 'merge' },
  });

  check(mergeRes, {
    'merge status 200': (r) => r.status === 200,
  });

  sleep(0.5);
}
