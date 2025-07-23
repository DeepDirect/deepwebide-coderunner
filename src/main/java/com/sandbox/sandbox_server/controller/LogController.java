package com.sandbox.sandbox_server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;


@RestController
@RequestMapping("/api/sandbox")
public class LogController {

    @GetMapping("/logs/{containerId}")
    public ResponseEntity<String> getLogs(@PathVariable String containerId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "logs", containerId);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.status(500).body("도커 로그 조회 실패");
            }

            return ResponseEntity.ok(output.toString());

        } catch (Exception e) {
            return ResponseEntity.status(500).body("오류 발생: " + e.getMessage());
        }
    }
}
