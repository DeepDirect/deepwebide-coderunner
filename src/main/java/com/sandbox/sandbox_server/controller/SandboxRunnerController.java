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
        long startTime = System.currentTimeMillis();

        try {
            log.info("Received sandbox run request - uuid: {}, framework: {}, port: {}",
                    request.getUuid(), request.getFramework(), request.getPort());

            // 기존과 동일하게 호출하지만, 내부적으로 중복 실행 방지 로직이 작동
            String result = sandboxService.runProject(
                    request.getUuid(),      // uuid를 프로젝트 식별자로 사용
                    request.getUrl(),
                    request.getFramework(),
                    request.getPort()
            );

            long executionTime = System.currentTimeMillis() - startTime;

            SandboxRunResponse response = SandboxRunResponse.builder()
                    .message("실행 완료")
                    .result(result)
                    .status("SUCCESS")
                    .executionId(request.getUuid())
                    .executionTime(executionTime)
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Sandbox execution failed", e);

            long executionTime = System.currentTimeMillis() - startTime;

            SandboxRunResponse response = SandboxRunResponse.builder()
                    .message("실행 실패")
                    .error(e.getMessage())
                    .status("FAILED")
                    .executionId(request.getUuid())
                    .executionTime(executionTime)
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

    /**
     * 특정 프로젝트 중지 (추가)
     */
    @DeleteMapping("/stop/{uuid}")
    public ResponseEntity<?> stopProject(@PathVariable String uuid) {
        try {
            log.info("Received stop request for uuid: {}", uuid);

            boolean success = sandboxService.stopProject(uuid);

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "uuid", uuid,
                        "status", "STOPPED",
                        "message", "프로젝트가 성공적으로 중지되었습니다."
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "uuid", uuid,
                        "status", "NOT_FOUND",
                        "message", "실행 중인 컨테이너가 없습니다."
                ));
            }

        } catch (Exception e) {
            log.error("Failed to stop project: {}", uuid, e);
            return ResponseEntity.status(500).body(Map.of(
                    "uuid", uuid,
                    "status", "ERROR",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 현재 실행 중인 모든 프로젝트 조회 (추가)
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActiveContainers() {
        try {
            Map<String, String> activeContainers = sandboxService.getActiveContainers();

            return ResponseEntity.ok(Map.of(
                    "active_containers", activeContainers,
                    "count", activeContainers.size(),
                    "status", "SUCCESS"
            ));

        } catch (Exception e) {
            log.error("Failed to get active containers", e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/logs/{uuid}")
    public ResponseEntity<?> getContainerLogs(
            @PathVariable String uuid,
            @RequestParam(defaultValue = "50") int lines,
            @RequestParam(defaultValue = "false") boolean follow,
            @RequestParam(defaultValue = "all") String since) {

        try {
            log.info("Getting container logs - uuid: {}, lines: {}", uuid, lines);

            Map<String, Object> logs = sandboxService.getContainerLogs(uuid, lines, follow, since);

            return ResponseEntity.ok(logs);

        } catch (Exception e) {
            log.error("Failed to get container logs: {}", uuid, e);
            return ResponseEntity.status(500).body(Map.of(
                    "uuid", uuid,
                    "status", "ERROR",
                    "error", e.getMessage(),
                    "stdout", "",
                    "stderr", "",
                    "logs", ""
            ));
        }
    }
}