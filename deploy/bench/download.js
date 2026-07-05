import http from 'k6/http';
import { check, sleep, fail } from 'k6';

// ---------------------------------------------------------------------------
// Configuration via environment variables
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FILE_ID = __ENV.FILE_ID;
const VUS = parseInt(__ENV.VUS || '20', 10);
const DURATION = __ENV.DURATION || '30s';
const AUTH_TOKEN = __ENV.AUTH_TOKEN;

if (!FILE_ID) {
  fail('FILE_ID environment variable is required. Pass -e FILE_ID=<id>');
}
if (!AUTH_TOKEN) {
  fail('AUTH_TOKEN environment variable is required. Pass -e AUTH_TOKEN=<token>');
}

const AUTH_HEADERS = { Authorization: `Bearer ${AUTH_TOKEN}` };

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{stage:presign-download}': ['p(95)<300'],
    'http_req_duration{stage:download-presigned}': ['p(95)<2000'],
    'http_req_duration{stage:range-download}': ['p(95)<1000'],
  },
};

// ---------------------------------------------------------------------------
// Main VU function
// ---------------------------------------------------------------------------
export default function () {
  // --- Presigned URL download path ---

  // 1. Get presigned download URL
  const presignRes = http.get(
    `${BASE_URL}/api/v1/file/${FILE_ID}/url`,
    { headers: AUTH_HEADERS, tags: { stage: 'presign-download' } }
  );

  check(presignRes, {
    'presign-download status 200': (r) => r.status === 200,
  });

  if (presignRes.status === 200) {
    let presignedUrl;
    try {
      presignedUrl = JSON.parse(presignRes.body).data.url;
    } catch (_) {
      // skip if response format unexpected
    }

    if (presignedUrl) {
      // 2. Download from presigned URL
      const dlRes = http.get(presignedUrl, {
        tags: { stage: 'download-presigned' },
      });

      check(dlRes, {
        'download-presigned status 200': (r) => r.status === 200,
      });
    }
  }

  sleep(0.3);

  // --- Range download path (through backend) ---

  // First request: get first 1MB with Range header
  const rangeRes = http.get(`${BASE_URL}/api/v1/file/${FILE_ID}/download`, {
    headers: { ...AUTH_HEADERS, Range: 'bytes=0-1048575' },
    tags: { stage: 'range-download' },
  });

  check(rangeRes, {
    'range-download status 206 or 200': (r) =>
      r.status === 206 || r.status === 200,
  });

  sleep(0.3);
}
