import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { setImmediate } from 'node:timers/promises';
import vm from 'node:vm';
import ts from 'typescript';

// Exercise the real upload scheduler with deterministic storage/API boundaries.
const compiled = ts.transpileModule(
  readFileSync(new URL('../src/lib/upload.ts', import.meta.url), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
).outputText;

function uploadWith(response) {
  const calls = { confirm: 0, proxy: 0, merge: 0, done: 0, errors: [], statuses: [] };
  const controller = new AbortController();
  const api = {
    initUpload: async () => ({ fileId: 'file', mode: 'UPLOAD', chunkSize: 4, chunkTotal: 1 }),
    presignChunks: async () => ({ 0: 'https://storage.test/chunk' }),
    confirmChunk: async () => { calls.confirm++; },
    uploadChunk: async () => { calls.proxy++; },
    mergeUpload: async () => { calls.merge++; },
  };
  const modules = {
    './api': { api },
    './md5': { hashFileWorker: async () => ({ fileMd5: 'file-md5', chunkHashes: ['chunk-md5'] }) },
    './utils': { guessChunkSize: () => 4 },
    './idb': {
      hashCacheKey: () => 'key', getHashCache: async () => null,
      setHashCache: async () => {}, saveUploadState: async () => {}, delUploadState: async () => {},
    },
    './ws': { connectUploadWs: () => ({ close() {} }) },
  };
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, require: (name) => {
      assert.ok(Object.hasOwn(modules, name), `unexpected dependency ${name}`);
      return modules[name];
    },
    fetch: async () => response, DOMException,
    console: { warn() {}, error() {} }, window: { setTimeout, clearTimeout },
  });
  const completed = exports.runUpload({
    file: new File(['data'], 'upload.txt'), abortController: controller,
  }, {
    onStatus: (status) => calls.statuses.push(status),
    onHashProgress() {}, onChunkDone() {},
    onDone: () => { calls.done++; }, onError: (error) => calls.errors.push(error),
  });
  return { calls, controller, completed };
}

test('PUT response headers alone cannot confirm or merge a chunk', async () => {
  let body;
  const response = new Response(new ReadableStream({ start(controller) { body = controller; } }));
  const upload = uploadWith(response);
  await setImmediate();
  assert.equal(upload.calls.confirm, 0);
  assert.equal(upload.calls.merge, 0);
  body.close();
  await upload.completed;
  assert.equal(response.bodyUsed, true);
  assert.equal(upload.calls.confirm, 1);
  assert.equal(upload.calls.proxy, 0);
  assert.equal(upload.calls.merge, 1);
  assert.equal(upload.calls.done, 1);
  assert.deepEqual(upload.calls.errors, []);
});

test('a truncated successful PUT response falls back without confirming direct upload', async () => {
  let body;
  const response = new Response(new ReadableStream({ start(controller) { body = controller; } }));
  const upload = uploadWith(response);
  await setImmediate();
  body.error(new TypeError('connection closed before response completion'));
  await upload.completed;
  assert.equal(upload.calls.confirm, 0);
  assert.equal(upload.calls.proxy, 1);
  assert.equal(upload.calls.done, 1);
});

test('pausing while the PUT response is pending neither confirms nor falls back', async () => {
  let body;
  const response = new Response(new ReadableStream({ start(controller) { body = controller; } }));
  const upload = uploadWith(response);
  await setImmediate();
  upload.controller.abort('pause');
  body.error(new DOMException('paused', 'AbortError'));
  await upload.completed;
  assert.equal(upload.calls.confirm, 0);
  assert.equal(upload.calls.proxy, 0);
  assert.equal(upload.calls.merge, 0);
  assert.equal(upload.calls.done, 0);
  assert.equal(upload.calls.statuses.at(-1), 'PAUSED');
});
