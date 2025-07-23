package com.sandbox.sandbox_server.controller;

import com.sandbox.sandbox_server.dto.SandboxRunRequest;
import com.sandbox.sandbox_server.service.SandboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sandbox")
@RequiredArgsConstructor
public class SandboxRunnerController {

    private final SandboxService sandboxService;

    @PostMapping("/run")
    public ResponseEntity<?> runContainer(@RequestBody SandboxRunRequest request) {
        try {
            String result = sandboxService.runProject(
                    request.getUuid(),
                    request.getUrl(),
                    request.getFramework(),
                    request.getPort()
            );
            return ResponseEntity.ok(Map.of("message", "실행 완료", "result", result));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
