import SparkMD5 from 'spark-md5';

const SUBCHUNK = 4 * 1024 * 1024; // 4MB 子块，单次 append 的上限

/**
 * Streaming MD5 for a Blob. Reads the source in 4MB sub-chunks to avoid
 * allocating a huge ArrayBuffer and yields to the event loop so the UI stays
 * responsive. Used for both whole-file hashing and per-chunk hashing.
 */
export async function md5OfBlob(
  blob: Blob,
  onProgress?: (processed: number, total: number) => void
): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer();
  const total = blob.size;
  let offset = 0;
  try {
    while (offset < total) {
      const end = Math.min(offset + SUBCHUNK, total);
      const slice = blob.slice(offset, end);
      const buf = await slice.arrayBuffer();
      spark.append(buf);
      offset = end;
      onProgress?.(offset, total);
      if (offset < total) {
        // Yield to event loop
        await new Promise((r) => setTimeout(r, 0));
      }
    }
    return spark.end();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new Error(`MD5 计算失败（${offset}/${total} bytes）: ${msg}`);
  } finally {
    spark.destroy();
  }
}

/** Single-chunk MD5 — reuses streaming implementation for robustness. */
export function md5OfChunk(blob: Blob): Promise<string> {
  return md5OfBlob(blob);
}
