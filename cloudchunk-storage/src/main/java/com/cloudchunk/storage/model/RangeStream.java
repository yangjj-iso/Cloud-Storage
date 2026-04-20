package com.cloudchunk.storage.model;

import java.io.IOException;
import java.io.InputStream;

public record RangeStream(InputStream stream, long start, long end, long total) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        if (stream != null) {
            stream.close();
        }
    }
}
