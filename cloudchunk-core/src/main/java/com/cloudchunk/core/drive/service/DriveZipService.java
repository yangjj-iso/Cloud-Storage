package com.cloudchunk.core.drive.service;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.drive.entity.UserFile;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipOutputStream;

/**
 * 网盘批量 / 文件夹打包下载：把选中的文件或整棵目录树流式打包成 ZIP。
 *
 * <p>边界处理：每段路径消毒（防 zip-slip 目录穿越）、重名自动加后缀去重、目录环保护
 * （visited 集合保证遍历终止）、条目数上限、坏/已删文件跳过、写入真实文件时间。</p>
 */
@Service
public class DriveZipService {

    private static final Logger log = LoggerFactory.getLogger(DriveZipService.class);

    /** 单次归档的文件条目上限，防止异常目录树产出失控的下载。 */
    private static final int MAX_ZIP_ENTRIES = 20_000;
    /** 单次归档遍历的目录树节点上限，防止纯目录大树消耗服务端资源。 */
    private static final int MAX_ZIP_NODES = 100_000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final UserFileService userFileService;
    private final FileMetaService fileMetaService;
    private final StorageStrategyFactory storageFactory;

    public DriveZipService(UserFileService userFileService,
                           FileMetaService fileMetaService,
                           StorageStrategyFactory storageFactory) {
        this.userFileService = userFileService;
        this.fileMetaService = fileMetaService;
        this.storageFactory = storageFactory;
    }

    public record Entry(String path, String bucket, String objectKey, long size, LocalDateTime modTime) {
    }

    public record ZipPlan(String name, List<Entry> entries) {
    }

    /**
     * 把给定的网盘条目 id（文件和/或目录，归属 userId）解析成扁平的归档条目清单，保留目录结构。
     * 所有权由 {@link UserFileService#findByIdActive} 强制。无可下载内容时抛 FILE_NOT_FOUND。
     */
    private record PendingFile(String path, String fileId) {
    }

    public ZipPlan prepareZip(long userId, List<Long> ids) {
        Set<Long> seenNode = new HashSet<>();
        // visitedDir 在所有顶层选择间共享：既防止（异常数据导致的）父子环无限递归，
        // 又避免“同时选中父目录和其子目录”时重复打包。
        Set<Long> visitedDir = new HashSet<>();
        List<PendingFile> pending = new ArrayList<>();
        String firstName = null;
        boolean firstIsDir = false;
        int[] visitedNodes = new int[]{0};

        for (Long id : ids) {
            if (id == null || id <= 0 || !seenNode.add(id)) {
                continue;
            }
            Optional<UserFile> nodeOpt = userFileService.findByIdActive(id, userId);
            if (nodeOpt.isEmpty()) {
                continue; // 跳过不属于该用户 / 不存在的条目
            }
            UserFile node = nodeOpt.get();
            if (firstName == null) {
                firstName = node.getFileName();
                firstIsDir = node.dir();
            }
            collect(userId, node, "", pending, visitedDir, visitedNodes);
            if (pending.size() > MAX_ZIP_ENTRIES) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "too many files to archive in one request");
            }
        }

        if (pending.isEmpty()) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        }

        // 批量解析 FileMeta（一次查询消除逐文件 N+1），保持遍历顺序，过滤已删/损坏。
        Set<String> fileIds = new HashSet<>();
        for (PendingFile pf : pending) {
            fileIds.add(pf.fileId());
        }
        Map<String, FileMeta> metas = fileMetaService.findByFileIds(fileIds);

        List<Entry> entries = new ArrayList<>(pending.size());
        for (PendingFile pf : pending) {
            FileMeta fm = metas.get(pf.fileId());
            if (fm == null || fm.getStatus() != FileStatus.AVAILABLE) {
                continue;
            }
            entries.add(new Entry(pf.path(), fm.getBucket(), fm.getObjectKey(),
                    fm.getFileSize() == null ? 0 : fm.getFileSize(), fm.getCreatedAt()));
        }

        if (entries.isEmpty()) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        }

        dedupe(entries);

        String name = "cloudchunk-" + LocalDateTime.now().format(TS) + ".zip";
        if (ids.size() == 1 && firstName != null && !firstName.isBlank()) {
            String base = sanitize(firstName);
            name = firstIsDir ? base + ".zip" : stripExt(base) + ".zip";
        }
        return new ZipPlan(name, entries);
    }

    private void collect(long userId, UserFile node, String prefix, List<PendingFile> out,
                         Set<Long> visited, int[] visitedNodes) {
        if (++visitedNodes[0] > MAX_ZIP_NODES) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "too many entries to archive in one request");
        }
        if (out.size() >= MAX_ZIP_ENTRIES) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "too many files to archive in one request");
        }
        String full = sanitize(node.getFileName());
        if (!prefix.isEmpty()) {
            full = prefix + "/" + full;
        }

        if (node.dir()) {
            // 环 / DoS 守护：同一目录只遍历一次（只有文件会增长 out，条目上限拦不住纯目录环）。
            if (!visited.add(node.getId())) {
                return;
            }
            for (UserFile child : userFileService.findByParentActive(userId, node.getId())) {
                collect(userId, child, full, out, visited, visitedNodes);
            }
            return;
        }

        if (node.getFileId() == null || node.getFileId().isEmpty()) {
            return;
        }
        out.add(new PendingFile(full, node.getFileId()));
    }

    /** 懒打开对象流（供分享单文件直连下载复用存储层）。 */
    public InputStream openObject(String bucket, String objectKey) {
        return storageFactory.current().get(new GetRequest(bucket, objectKey));
    }

    /**
     * 把解析后的条目流式写入 {@code out}。恒定内存（每次只持有小缓冲），单个不可读对象跳过，
     * 不因一个坏文件中断整个下载。
     */
    public void streamZip(List<Entry> entries, OutputStream out) throws IOException {
        StorageStrategy storage = storageFactory.current();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            for (Entry e : entries) {
                InputStream in;
                try {
                    in = storage.get(new GetRequest(e.bucket(), e.objectKey()));
                } catch (Exception ex) {
                    log.warn("[Zip] skip unreadable object {}/{}: {}", e.bucket(), e.objectKey(), ex.getMessage());
                    continue;
                }
                try (InputStream stream = in) {
                    java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(e.path());
                    if (e.modTime() != null) {
                        ze.setTime(e.modTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                    }
                    zos.putNextEntry(ze);
                    int n;
                    while ((n = stream.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    /* ============================ 纯函数工具 ============================ */

    /**
     * 把单个网盘节点名转成安全的归档路径段：中和路径分隔符与控制字符、替换纯点名，
     * 防止目录穿越（zip-slip）。
     */
    static String sanitize(String name) {
        if (name == null) {
            name = "";
        }
        name = name.strip().replace("\\", "_").replace("/", "_");
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((c < 0x20 || c == 0x7f) ? '_' : c);
        }
        name = sb.toString();
        int start = 0, end = name.length();
        while (start < end && (name.charAt(start) == '.' || name.charAt(start) == ' ')) start++;
        while (end > start && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) end--;
        name = name.substring(start, end);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "unnamed";
        }
        return name;
    }

    /** 对重复归档路径就地改写：在扩展名前插入数字后缀（a.txt -> a (2).txt）。 */
    static void dedupe(List<Entry> entries) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (seen.add(e.path())) {
                continue;
            }
            String ext = extOf(e.path());
            String stem = e.path().substring(0, e.path().length() - ext.length());
            for (int n = 2; ; n++) {
                String cand = stem + " (" + n + ")" + ext;
                if (seen.add(cand)) {
                    entries.set(i, new Entry(cand, e.bucket(), e.objectKey(), e.size(), e.modTime()));
                    break;
                }
            }
        }
    }

    static String extOf(String p) {
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        int dot = p.lastIndexOf('.');
        return (dot > slash && dot >= 0) ? p.substring(dot) : "";
    }

    static String stripExt(String s) {
        String ext = extOf(s);
        return s.substring(0, s.length() - ext.length());
    }
}
