package com.sandbox.sandbox_server.controller;

import com.sandbox.sandbox_server.service.SandboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeProject(
            @RequestParam("framework") String framework,
            @RequestParam("projectZip") MultipartFile projectZip
    ) {
        try {
            String uuid = sandboxService.saveAndExtractProject(framework, projectZip);
            return ResponseEntity.ok(Map.of("projectId", uuid));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
