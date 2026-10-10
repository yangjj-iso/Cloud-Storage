package com.cloudchunk.transcode.knowledge;

import com.cloudchunk.core.knowledge.KnowledgePolicy;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** An explicit parser allowlist: no automatic archive, script, remote fetch or OCR dispatch. */
@Component
public class KnowledgeExtractor {
    public record Extraction(String text, String status, String reason) {}

    public Extraction extract(InputStream source, String mimeType) throws Exception {
        String mime = KnowledgePolicy.mime(mimeType);
        Parser parser = switch (mime) {
            case "text/plain", "text/markdown", "text/x-markdown" -> new TXTParser();
            case "application/pdf" -> new PDFParser();
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> new OOXMLParser();
            default -> throw new IllegalArgumentException("unsupported document format");
        };
        BodyContentHandler handler = new BodyContentHandler(KnowledgePolicy.MAX_CHARS);
        ParseContext context = new ParseContext();
        boolean[] omittedEmbedded = {false};
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata metadata) {
                omittedEmbedded[0] = true;
                return false;
            }
            @Override public void parseEmbedded(InputStream stream, ContentHandler output,
                                                Metadata metadata, boolean html) { /* explicitly excluded */ }
        });
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        boolean truncated = false;
        try (InputStream bounded = new SizeLimitedStream(source, KnowledgePolicy.MAX_BYTES)) {
            parser.parse(bounded, handler, new Metadata(), context);
        } catch (Exception error) {
            if (!WriteLimitReachedException.isWriteLimitReached(error)) throw error;
            truncated = true;
        }
        String text = handler.toString().replace("\u0000", "");
        if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            text = text.substring(0, text.length() - 1);
            truncated = true;
        }
        if (text.isBlank()) return new Extraction("", "EMPTY", "未提取到正文；扫描件 OCR 尚未启用");
        if (truncated) return new Extraction(text, "PARTIAL", "已达到 262144 字符上限，仅索引已提取部分");
        if (omittedEmbedded[0]) return new Extraction(text, "PARTIAL", "已提取正文，内嵌附件未解析");
        return new Extraction(text, "READY", null);
    }

    /** Protect against metadata understating source size. This is not a CPU/memory sandbox. */
    private static final class SizeLimitedStream extends FilterInputStream {
        private final long max;
        private long read;
        SizeLimitedStream(InputStream in, long max) { super(in); this.max = max; }
        @Override public int read() throws IOException {
            int value = in.read(); if (value != -1) count(1); return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int count = in.read(b, off, (int) Math.min(len, Math.max(1, max - read + 1)));
            if (count > 0) count(count);
            return count;
        }
        @Override public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(Math.max(0, n), Math.max(1, max - read + 1)));
            count(skipped); return skipped;
        }
        private void count(long n) throws IOException {
            read += n;
            if (read > max) throw new IOException("document exceeds extraction byte budget");
        }
    }
}
