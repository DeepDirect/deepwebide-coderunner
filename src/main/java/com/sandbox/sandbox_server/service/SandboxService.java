package com.sandbox.sandbox_server.service;

import com.sandbox.sandbox_server.util.DockerfileUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class SandboxService {

    private static final int DOCKER_TIMEOUT_SECONDS = 300;

    // 실행 중인 컨테이너 추적을 위한 맵 (uuid -> 컨테이너명)
    // uuid를 프로젝트 식별자로 사용
    private final ConcurrentHashMap<String, String> activeContainers = new ConcurrentHashMap<>();

    /**
     * 프로젝트 실행 전체 프로세스
     * @param uuid 프로젝트 식별자 (동일한 uuid의 중복 실행 방지)
     */
    public String runProject(String uuid, String s3Url, String framework, int port) throws IOException {
        log.info("Starting project execution - uuid: {}, framework: {}, port: {}", uuid, framework, port);

        // 1. 기존 실행 중인 컨테이너 정리 (동일 uuid)
        stopExistingContainer(uuid);

        Path projectDir = Paths.get("uploads", uuid);
        Files.createDirectories(projectDir);

        try {
            // 2. S3에서 ZIP 다운로드
            Path zipPath = downloadFromS3(s3Url, projectDir);

            // 3. 압축 해제
            unzip(zipPath.toFile(), projectDir.toFile());

            // 4. 프로젝트 구조 정규화 (옵션)
            normalizeProjectStructure(projectDir.toFile());

            // 5. Dockerfile 생성
            DockerfileUtil.generateDockerfile(projectDir, framework);

            // 6. Docker 빌드 및 실행
            String containerName = "sandbox-" + uuid;
            runDockerContainer(uuid, port, framework);

            // 7. 활성 컨테이너 목록에 추가
            activeContainers.put(uuid, containerName);

            log.info("Project execution completed - uuid: {}, port: {}", uuid, port);
            return uuid + ":" + port;

        } catch (Exception e) {
            log.error("Project execution failed - uuid: {}", uuid, e);
            cleanupResources(uuid);
            throw new IOException("Project execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * 기존 실행 중인 컨테이너 중지 및 정리
     */
    private void stopExistingContainer(String uuid) {
        String existingContainer = activeContainers.get(uuid);
        if (existingContainer != null) {
            log.info("Stopping existing container for uuid {}: {}", uuid, existingContainer);

            try {
                // Docker 컨테이너 강제 중지
                ProcessBuilder stopBuilder = new ProcessBuilder("docker", "stop", existingContainer);
                Process stopProcess = stopBuilder.start();

                boolean stopFinished = stopProcess.waitFor(30, TimeUnit.SECONDS);
                if (!stopFinished) {
                    log.warn("Container stop timed out, forcing kill: {}", existingContainer);
                    stopProcess.destroyForcibly();
                }

                // Docker 컨테이너 삭제
                ProcessBuilder rmBuilder = new ProcessBuilder("docker", "rm", "-f", existingContainer);
                Process rmProcess = rmBuilder.start();

                boolean rmFinished = rmProcess.waitFor(10, TimeUnit.SECONDS);
                if (!rmFinished) {
                    log.warn("Container removal timed out: {}", existingContainer);
                    rmProcess.destroyForcibly();
                }

                // Docker 이미지도 삭제 (옵션)
                ProcessBuilder rmiBuilder = new ProcessBuilder("docker", "rmi", "-f", existingContainer);
                Process rmiProcess = rmiBuilder.start();
                rmiProcess.waitFor(10, TimeUnit.SECONDS);

                log.info("Successfully stopped and removed container: {}", existingContainer);

            } catch (Exception e) {
                log.error("Failed to stop existing container {}: {}", existingContainer, e.getMessage());

                // 강제로 모든 관련 컨테이너 정리 시도
                try {
                    ProcessBuilder forceCleanup = new ProcessBuilder(
                            "bash", "-c",
                            String.format("docker ps -a | grep %s | awk '{print $1}' | xargs -r docker rm -f", existingContainer)
                    );
                    forceCleanup.start().waitFor(10, TimeUnit.SECONDS);
                } catch (Exception cleanupEx) {
                    log.warn("Force cleanup also failed for {}: {}", existingContainer, cleanupEx.getMessage());
                }

            } finally {
                // 맵에서 제거
                activeContainers.remove(uuid);
            }
        }
    }

    /**
     * 특정 프로젝트의 실행 중인 컨테이너 수동 중지
     */
    public boolean stopProject(String uuid) {
        String containerName = activeContainers.get(uuid);
        if (containerName == null) {
            log.info("No running container found for uuid: {}", uuid);
            return false;
        }

        try {
            stopExistingContainer(uuid);
            log.info("Successfully stopped project: {}", uuid);
            return true;
        } catch (Exception e) {
            log.error("Failed to stop project {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /**
     * 현재 실행 중인 모든 컨테이너 목록 조회
     */
    public java.util.Map<String, String> getActiveContainers() {
        return new java.util.HashMap<>(activeContainers);
    }

    /**
     * 애플리케이션 종료 시 모든 컨테이너 정리
     */
    @PreDestroy
    public void cleanupAllContainers() {
        if (!activeContainers.isEmpty()) {
            log.info("Cleaning up {} active containers on shutdown...", activeContainers.size());

            for (java.util.Map.Entry<String, String> entry : activeContainers.entrySet()) {
                String uuid = entry.getKey();
                String containerName = entry.getValue();

                try {
                    log.info("Stopping container on shutdown - uuid: {}, container: {}", uuid, containerName);

                    ProcessBuilder stopBuilder = new ProcessBuilder("docker", "stop", containerName);
                    Process stopProcess = stopBuilder.start();
                    stopProcess.waitFor(10, TimeUnit.SECONDS);

                    ProcessBuilder rmBuilder = new ProcessBuilder("docker", "rm", "-f", containerName);
                    Process rmProcess = rmBuilder.start();
                    rmProcess.waitFor(5, TimeUnit.SECONDS);

                } catch (Exception e) {
                    log.error("Failed to cleanup container on shutdown - {}: {}", containerName, e.getMessage());
                }
            }

            activeContainers.clear();
            log.info("Container cleanup completed");
        }
    }

    // 나머지 기존 메서드들은 그대로...

    private Path downloadFromS3(String s3Url, Path projectDir) throws IOException {
        log.debug("Downloading file from S3: {}", s3Url);

        Path zipPath = projectDir.resolve("project.zip");
        try (InputStream in = new URL(s3Url).openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        long fileSize = Files.size(zipPath);
        log.debug("S3 download completed: {} ({} bytes)", zipPath, fileSize);
        return zipPath;
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        log.debug("Extracting zip file: {}", zipFile.getName());

        try (ZipFile zip = new ZipFile(zipFile)) {
            zip.stream()
                    .filter(entry -> !isMacOSMetadata(entry.getName()))
                    .forEach(entry -> {
                        try {
                            extractZipEntry(zip, entry, destDir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        Files.deleteIfExists(zipFile.toPath());
        log.debug("Zip extraction completed");
    }

    private boolean isMacOSMetadata(String entryName) {
        return entryName.contains("__MACOSX") ||
                entryName.contains(".DS_Store") ||
                entryName.contains("/._") ||
                entryName.startsWith("._");
    }

    private void extractZipEntry(ZipFile zip, ZipEntry entry, File destDir) throws IOException {
        File destFile = new File(destDir, entry.getName());

        if (!destFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
            throw new IOException("Entry is outside of the target dir: " + entry.getName());
        }

        if (entry.isDirectory()) {
            destFile.mkdirs();
        } else {
            destFile.getParentFile().mkdirs();
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                in.transferTo(out);
            }

            if (entry.getName().endsWith("gradlew") || entry.getName().endsWith(".sh")) {
                destFile.setExecutable(true);
                log.debug("Set executable permission for: {}", entry.getName());
            }
        }
    }

    private void normalizeProjectStructure(File destDir) throws IOException {
        // 프로젝트 구조 정규화 로직
    }

    private void runDockerContainer(String uuid, int port, String framework) throws IOException, InterruptedException {
        log.info("Running docker container - uuid: {}, port: {}, framework: {}", uuid, port, framework);

        File scriptFile = new File("scripts/build_and_run.sh");
        if (!scriptFile.exists()) {
            throw new IOException("Build script not found: " + scriptFile.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(
                "bash", "-x", "scripts/build_and_run.sh", uuid, String.valueOf(port), framework
        );

        pb.directory(new File("."));
        pb.redirectErrorStream(true);

        log.info("Executing command: {}", String.join(" ", pb.command()));

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> outputFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("DOCKER [{}]: {}", uuid, line);
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.warn("Failed to read process output", e);
            }
        });

        boolean finished = process.waitFor(DOCKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            log.error("Docker process timed out after {} seconds", DOCKER_TIMEOUT_SECONDS);
            process.destroyForcibly();
            throw new IOException("Docker execution timed out after " + DOCKER_TIMEOUT_SECONDS + " seconds");
        }

        try {
            outputFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to wait for output thread completion", e);
        }

        int exitCode = process.exitValue();
        log.info("Docker process completed with exit code: {}", exitCode);

        if (exitCode != 0) {
            throw new IOException("Docker execution failed:\n" + output);
        }
    }

    private void cleanupResources(String uuid) {
        Path projectDir = Paths.get("uploads", uuid);
        try {
            if (Files.exists(projectDir)) {
                Files.walk(projectDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup project directory: {}", projectDir, e);
        }
    }

    /**
     * 컨테이너 로그 조회
     */
    public Map<String, Object> getContainerLogs(String uuid, int lines, boolean follow, String since) {
        try {
            String containerName = "sandbox-" + uuid;

            // 먼저 컨테이너 존재 여부 확인
            ProcessBuilder checkBuilder = new ProcessBuilder("docker", "ps", "-a", "--filter", "name=" + containerName, "--format", "{{.Names}}");
            Process checkProcess = checkBuilder.start();

            StringBuilder containerCheck = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    containerCheck.append(line);
                }
            }

            checkProcess.waitFor(5, TimeUnit.SECONDS);

            if (containerCheck.toString().trim().isEmpty()) {
                return Map.of(
                        "uuid", uuid,
                        "containerName", containerName,
                        "status", "CONTAINER_NOT_FOUND",
                        "stdout", "",
                        "stderr", "Container not found",
                        "exitCode", -1,
                        "timestamp", System.currentTimeMillis(),
                        "message", "컨테이너를 찾을 수 없습니다."
                );
            }

            // 컨테이너가 존재하면 로그 조회
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("logs");

            if (lines > 0) {
                command.add("--tail");
                command.add(String.valueOf(lines));
            }

            if (follow) {
                command.add("--follow");
            }

            if (!"all".equals(since)) {
                command.add("--since");
                command.add(since);
            }

            command.add("--timestamps");
            command.add(containerName);

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            // stdout과 stderr 동시 읽기
            CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.error("Error reading stdout", e);
                }
            });

            CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.error("Error reading stderr", e);
                }
            });

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Docker logs command timed out");
            }

            // 출력 스레드 완료 대기
            stdoutFuture.get(5, TimeUnit.SECONDS);
            stderrFuture.get(5, TimeUnit.SECONDS);

            String stdoutStr = stdout.toString();
            String stderrStr = stderr.toString();

            return Map.of(
                    "uuid", uuid,
                    "containerName", containerName,
                    "stdout", stdoutStr,
                    "stderr", stderrStr,
                    "logs", stdoutStr, // logs 필드 추가 (호환성)
                    "exitCode", process.exitValue(),
                    "timestamp", System.currentTimeMillis(),
                    "status", "SUCCESS",
                    "hasLogs", !stdoutStr.trim().isEmpty(),
                    "linesReturned", stdoutStr.split("\n").length
            );

        } catch (Exception e) {
            log.error("Failed to get container logs: {}", uuid, e);
            return Map.of(
                    "uuid", uuid,
                    "status", "ERROR",
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis(),
                    "stdout", "",
                    "stderr", "",
                    "logs", ""
            );
        }
    }
}