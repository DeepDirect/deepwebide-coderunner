package com.sandbox.sandbox_server.controller;

import com.sandbox.sandbox_server.dto.SandboxRunRequest;
import com.sandbox.sandbox_server.dto.SandboxRunResponse;
import com.sandbox.sandbox_server.service.SandboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sandbox")
@RequiredArgsConstructor
public class SandboxRunnerController {

    private final SandboxService sandboxService;

    @PostMapping("/run")
    public ResponseEntity<SandboxRunResponse> runContainer(@RequestBody SandboxRunRequest request) {
        try {
            log.info("Received sandbox run request - uuid: {}, framework: {}, port: {}",
                    request.getUuid(), request.getFramework(), request.getPort());

            String result = sandboxService.runProject(
                    request.getUuid(),
                    request.getUrl(),
                    request.getFramework(),
                    request.getPort()
            );

            SandboxRunResponse response = SandboxRunResponse.builder()
                    .message("실행 완료")
                    .result(result)
                    .status("SUCCESS")
                    .executionId(request.getUuid())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Sandbox execution failed", e);

            SandboxRunResponse response = SandboxRunResponse.builder()
                    .message("실행 실패")
                    .error(e.getMessage())
                    .status("FAILED")
                    .executionId(request.getUuid())
                    .build();

            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/status/{uuid}")
    public ResponseEntity<?> getContainerStatus(@PathVariable String uuid) {
        try {
            String containerName = "sandbox-" + uuid;

            ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter", "name=" + containerName, "--format", "{{.Status}}");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String status = reader.readLine();

                if (status != null && !status.trim().isEmpty()) {
                    return ResponseEntity.ok(Map.of(
                            "uuid", uuid,
                            "status", "RUNNING",
                            "details", status
                    ));
                } else {
                    return ResponseEntity.ok(Map.of(
                            "uuid", uuid,
                            "status", "STOPPED",
                            "details", "Container not found or stopped"
                    ));
                }
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "uuid", uuid,
                    "status", "ERROR",
                    "error", e.getMessage()
            ));
        }
    }
}
