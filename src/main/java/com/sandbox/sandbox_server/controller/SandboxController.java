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
            // uuid:port 형태로 결과를 반환받음
            String result = sandboxService.saveAndExtractProject(framework, projectZip);
            String[] arr = result.split(":");
            String uuid = arr[0];
            String port = arr[1];
            return ResponseEntity.ok(
                    Map.of(
                            "projectId", uuid,
                            "port", port,
                            "url", "http://localhost:" + port
                    )
            );
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
