package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.drive.dto.UserFileItem;
import com.cloudchunk.core.drive.service.UserFileService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回收站：列出、还原、彻底删除（均按整棵子树处理）。
 */
@RestController
@RequestMapping("/api/v1/recycle")
public class RecycleBinController {

    private final UserFileService userFileService;

    public RecycleBinController(UserFileService userFileService) {
        this.userFileService = userFileService;
    }

    @GetMapping("/list")
    public R<List<UserFileItem>> list() {
        long userId = UserContext.requireUserId();
        return R.ok(userFileService.listRecycleBin(userId));
    }

    @PostMapping("/{id}/restore")
    public R<Void> restore(@PathVariable long id) {
        long userId = UserContext.requireUserId();
        userFileService.restore(id, userId);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> hardDelete(@PathVariable long id) {
        long userId = UserContext.requireUserId();
        userFileService.hardDelete(id, userId);
        return R.ok();
    }
}
