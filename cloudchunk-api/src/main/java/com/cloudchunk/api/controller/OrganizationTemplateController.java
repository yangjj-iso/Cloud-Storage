package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.OrganizationTemplateService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Authenticated user-scoped organizing strategy APIs. No autonomous execution. */
@RestController
@RequestMapping("/api/v1/knowledge/organize")
public class OrganizationTemplateController {
    private final OrganizationTemplateService service;
    public OrganizationTemplateController(OrganizationTemplateService service) {
        this.service = service;
    }

    private static <T> ResponseEntity<R<T>> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(value));
    }

    @PostMapping("/templates")
    public ResponseEntity<R<OrganizationTemplateService.Template>> create(
            @RequestBody OrganizationTemplateService.CreateTemplate request) {
        return noStore(service.create(UserContext.requireUserId(),request));
    }

    @GetMapping("/templates")
    public ResponseEntity<R<List<OrganizationTemplateService.Template>>> templates() {
        return noStore(service.templates(UserContext.requireUserId()));
    }

    @PostMapping("/templates/{id}/archive")
    public ResponseEntity<R<Void>> archive(@PathVariable String id,
            @RequestBody OrganizationTemplateService.Confirm request) {
        service.archive(UserContext.requireUserId(),id,request);
        return noStore(null);
    }

    @PostMapping("/simulate")
    public ResponseEntity<R<OrganizationTemplateService.Run>> simulate(
            @RequestBody OrganizationTemplateService.SimulateRequest request) {
        return noStore(service.simulate(UserContext.requireUserId(),request));
    }

    @GetMapping("/runs")
    public ResponseEntity<R<List<OrganizationTemplateService.Run>>> recentRuns() {
        return noStore(service.recent(UserContext.requireUserId()));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<R<OrganizationTemplateService.Run>> run(@PathVariable String id) {
        return noStore(service.detail(UserContext.requireUserId(),id));
    }

    @PostMapping("/runs/{id}/steps/{stepNo}/approve")
    public ResponseEntity<R<OrganizationTemplateService.Run>> approve(@PathVariable String id,
            @PathVariable int stepNo,@RequestBody OrganizationTemplateService.Confirm request) {
        return noStore(service.approve(UserContext.requireUserId(),id,stepNo,request));
    }

    @PostMapping("/runs/{id}/steps/{stepNo}/execute")
    public ResponseEntity<R<OrganizationTemplateService.Run>> execute(@PathVariable String id,
            @PathVariable int stepNo,@RequestBody OrganizationTemplateService.Confirm request) {
        return noStore(service.execute(UserContext.requireUserId(),id,stepNo,request));
    }

    @PostMapping("/runs/{id}/steps/{stepNo}/skip")
    public ResponseEntity<R<OrganizationTemplateService.Run>> skip(@PathVariable String id,
            @PathVariable int stepNo,@RequestBody OrganizationTemplateService.Confirm request) {
        return noStore(service.skip(UserContext.requireUserId(),id,stepNo,request));
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<R<OrganizationTemplateService.Run>> cancel(@PathVariable String id,
            @RequestBody OrganizationTemplateService.Confirm request) {
        return noStore(service.cancel(UserContext.requireUserId(),id,request));
    }
}
