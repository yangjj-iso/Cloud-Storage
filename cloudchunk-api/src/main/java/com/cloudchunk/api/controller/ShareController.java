package com.cloudchunk.api.controller;

import com.cloudchunk.api.security.PublicShareAccessGuard;
import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.drive.dto.UserFileItem;
import com.cloudchunk.core.drive.service.DriveZipService;
import com.cloudchunk.core.share.dto.ShareDtos.CreateShareRequest;
import com.cloudchunk.core.share.dto.ShareDtos.SaveToDriveRequest;
import com.cloudchunk.core.share.dto.ShareDtos.ShareDetail;
import com.cloudchunk.core.share.dto.ShareDtos.ShareItem;
import com.cloudchunk.core.share.dto.ShareDtos.ShareResult;
import com.cloudchunk.core.share.service.ShareService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件 / 文件夹分享。
 *
 * <p>拥有者操作（create/list/cancel/save）需登录；公开访问（view/children/download-zip）
 * 无需登录但需提取码，由 Spring Security 放行显式的公开 GET 路由。</p>
 */
@RestController
@RequestMapping("/api/v1/share")
public class ShareController {

    private final ShareService shareService;
    private final PublicShareAccessGuard publicAccessGuard;

    public ShareController(ShareService shareService, PublicShareAccessGuard publicAccessGuard) {
        this.shareService = shareService;
        this.publicAccessGuard = publicAccessGuard;
    }

    /* -------- 拥有者（需登录） -------- */

    @PostMapping
    public R<ShareResult> create(@Valid @RequestBody CreateShareRequest req) {
        long userId = UserContext.requireUserId();
        return R.ok(shareService.createShare(userId, req.userFileId(), req.expireDaysOrZero()));
    }

    @GetMapping("/list")
    public R<PageResult<ShareItem>> list(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        long userId = UserContext.requireUserId();
        return R.ok(shareService.listShares(userId, page, size));
    }

    @DeleteMapping("/{shareId}")
    public R<Void> cancel(@PathVariable String shareId) {
        long userId = UserContext.requireUserId();
        shareService.cancelShare(shareId, userId);
        return R.ok();
    }

    @PostMapping("/save")
    public R<Void> save(@Valid @RequestBody SaveToDriveRequest req) {
        long userId = UserContext.requireUserId();
        shareService.saveToMyDrive(req.shareId(), req.shareCode(), userId, req.parentIdOrRoot());
        return R.ok();
    }

    /* -------- 公开（需提取码，无需登录） -------- */

    @GetMapping("/{shareId}")
    public R<ShareDetail> view(@PathVariable String shareId,
                               @RequestParam(required = false, defaultValue = "") String code,
                               HttpServletRequest request) {
        publicAccessGuard.check(shareId, request);
        return R.ok(shareService.getShare(shareId, code));
    }

    @GetMapping("/{shareId}/children")
    public R<List<UserFileItem>> children(@PathVariable String shareId,
                                          @RequestParam(required = false, defaultValue = "") String code,
                                          @RequestParam(defaultValue = "0") long parentId,
                                          HttpServletRequest request) {
        publicAccessGuard.check(shareId, request);
        return R.ok(shareService.listShareChildren(shareId, code, parentId));
    }

    @GetMapping("/{shareId}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable String shareId,
            @RequestParam(required = false, defaultValue = "") String code,
            HttpServletRequest request) {
        publicAccessGuard.check(shareId, request);
        ShareService.ShareFile sf = shareService.prepareShareFile(shareId, code);
        String contentType = sf.meta().getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : sf.meta().getMimeType();
        String disposition = "attachment; filename*=UTF-8''"
                + URLEncoder.encode(sf.fileName(), StandardCharsets.UTF_8);
        String bucket = sf.meta().getBucket();
        String objectKey = sf.meta().getObjectKey();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=600");
        StreamingResponseBody body = out -> {
            try (InputStream in = shareService.driveZip().openObject(bucket, objectKey)) {
                in.transferTo(out);
                out.flush();
            }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @GetMapping("/{shareId}/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(
            @PathVariable String shareId,
            @RequestParam(required = false, defaultValue = "") String code,
            HttpServletRequest request) {
        publicAccessGuard.check(shareId, request);
        DriveZipService.ZipPlan plan = shareService.prepareShareArchive(shareId, code);
        String disposition = "attachment; filename*=UTF-8''"
                + URLEncoder.encode(plan.name(), StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/zip");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        StreamingResponseBody body = out -> shareService.driveZip().streamZip(plan.entries(), out);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

}
