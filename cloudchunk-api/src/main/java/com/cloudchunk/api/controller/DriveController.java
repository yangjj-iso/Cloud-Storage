package com.cloudchunk.api.controller;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.drive.dto.DriveRequests.MkdirRequest;
import com.cloudchunk.core.drive.dto.DriveRequests.MoveRequest;
import com.cloudchunk.core.drive.dto.DriveRequests.RenameRequest;
import com.cloudchunk.core.drive.dto.DriveRequests.ZipRequest;
import com.cloudchunk.core.drive.dto.UserFileItem;
import com.cloudchunk.core.drive.service.DriveZipService;
import com.cloudchunk.core.drive.service.UserFileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用户网盘目录树：新建目录、列目录、重命名、移动、删除（进回收站）、批量/文件夹打包下载。
 */
@RestController
@RequestMapping("/api/v1/drive")
public class DriveController {

    private final UserFileService userFileService;
    private final DriveZipService driveZipService;

    public DriveController(UserFileService userFileService, DriveZipService driveZipService) {
        this.userFileService = userFileService;
        this.driveZipService = driveZipService;
    }

    @PostMapping("/mkdir")
    public R<UserFileItem> mkdir(@Valid @RequestBody MkdirRequest req) {
        long userId = UserContext.requireUserId();
        return R.ok(userFileService.mkdir(userId, req.parentIdOrRoot(), req.name()));
    }

    @GetMapping("/list")
    public R<List<UserFileItem>> list(@RequestParam(value = "parentId", defaultValue = "0") long parentId) {
        long userId = UserContext.requireUserId();
        return R.ok(userFileService.list(userId, parentId));
    }

    @PostMapping("/rename")
    public R<Void> rename(@Valid @RequestBody RenameRequest req) {
        long userId = UserContext.requireUserId();
        userFileService.rename(req.id(), userId, req.newName());
        return R.ok();
    }

    @PostMapping("/move")
    public R<Void> move(@Valid @RequestBody MoveRequest req) {
        long userId = UserContext.requireUserId();
        userFileService.move(req.id(), userId, req.newParentIdOrRoot());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        long userId = UserContext.requireUserId();
        userFileService.moveToRecycleBin(id, userId);
        return R.ok();
    }

    /**
     * 批量 / 文件夹打包下载：请求体 {"ids":[...]}，可混合文件与目录，单个目录 id 即整目录打包。
     * 归档为流式生成，长度未知（chunked）。清单在写响应体前解析完成，因此参数/权限类错误仍能
     * 走全局异常处理返回 JSON。
     */
    @PostMapping("/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(@Valid @RequestBody ZipRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "ids is required");
        }
        long userId = UserContext.requireUserId();
        DriveZipService.ZipPlan plan = driveZipService.prepareZip(userId, req.ids());

        String disposition = "attachment; filename*=UTF-8''"
                + URLEncoder.encode(plan.name(), StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/zip");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");

        StreamingResponseBody body = out -> driveZipService.streamZip(plan.entries(), out);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
