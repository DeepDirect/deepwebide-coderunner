package com.sandbox.sandbox_server.service;

import com.sandbox.sandbox_server.util.DockerfileUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class SandboxService {

    private static final int DOCKER_TIMEOUT_SECONDS = 300;
    // uuid → containerName
    private final ConcurrentHashMap<String, String> activeContainers = new ConcurrentHashMap<>();
    // uuid → 임시 작업 디렉토리 경로 (stop 시 자동 삭제용)
    private final ConcurrentHashMap<String, Path> tempDirs = new ConcurrentHashMap<>();

    /**
     * S3에서 프로젝트 zip을 받아 임시 디렉토리에서 빌드/실행 → 컨테이너 띄움
     * @param uuid     프로젝트 식별자
     * @param s3Url    프로젝트 zip 파일 S3 URL
     * @param framework spring/react/fastapi 등
     * @param port     외부 노출 포트
     */
    public String runProject(String uuid, String s3Url, String framework, int port) throws IOException {
        log.info("Starting project execution - uuid: {}, framework: {}, port: {}", uuid, framework, port);

        // 1. 기존 실행 중 컨테이너 및 임시 디렉토리 정리
        stopExistingContainer(uuid);

        // 2. 임시 작업 디렉토리 생성
        Path projectDir = Files.createTempDirectory("sandbox-" + uuid + "-");
        tempDirs.put(uuid, projectDir);

        try {
            // 3. S3에서 ZIP 다운로드
            Path zipPath = downloadFromS3(s3Url, projectDir);

            // 4. 압축 해제
            unzip(zipPath.toFile(), projectDir.toFile());

            // 5. 프로젝트 구조 정규화 (필요시)
            normalizeProjectStructure(projectDir.toFile());

            // 6. Dockerfile 생성
            DockerfileUtil.generateDockerfile(projectDir, framework);

            // 7. Docker 빌드 및 실행 (임시 디렉토리 경로 인자로 전달)
            String containerName = "sandbox-" + uuid;
            runDockerContainer(uuid, port, framework, projectDir.toString());

            // 8. 컨테이너/작업 디렉토리 등록
            activeContainers.put(uuid, containerName);

            log.info("Project execution completed - uuid: {}, port: {}", uuid, port);
            return uuid + ":" + port;

        } catch (Exception e) {
            log.error("Project execution failed - uuid: {}", uuid, e);
            cleanupResources(uuid); // uuid별로 임시 폴더까지 정리
            throw new IOException("Project execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * 기존 실행 중인 컨테이너 및 임시 디렉토리 삭제
     */
    private void stopExistingContainer(String uuid) {
        String existingContainer = activeContainers.get(uuid);
        if (existingContainer != null) {
            log.info("Stopping existing container for uuid {}: {}", uuid, existingContainer);
            try {
                ProcessBuilder stopBuilder = new ProcessBuilder("docker", "stop", existingContainer);
                Process stopProcess = stopBuilder.start();
                boolean stopFinished = stopProcess.waitFor(30, TimeUnit.SECONDS);
                if (!stopFinished) stopProcess.destroyForcibly();

                ProcessBuilder rmBuilder = new ProcessBuilder("docker", "rm", "-f", existingContainer);
                Process rmProcess = rmBuilder.start();
                boolean rmFinished = rmProcess.waitFor(10, TimeUnit.SECONDS);
                if (!rmFinished) rmProcess.destroyForcibly();

                ProcessBuilder rmiBuilder = new ProcessBuilder("docker", "rmi", "-f", existingContainer);
                Process rmiProcess = rmiBuilder.start();
                rmiProcess.waitFor(10, TimeUnit.SECONDS);

                log.info("Successfully stopped and removed container: {}", existingContainer);
            } catch (Exception e) {
                log.error("Failed to stop existing container {}: {}", existingContainer, e.getMessage());
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
                activeContainers.remove(uuid);
            }
        }
        // uuid에 해당하는 임시 작업 폴더 삭제
        cleanupResources(uuid);
    }

    /**
     * uuid별 임시 디렉토리 삭제
     */
    private void cleanupResources(String uuid) {
        Path dir = tempDirs.remove(uuid);
        if (dir != null && Files.exists(dir)) {
            try {
                Files.walk(dir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                log.info("Deleted temp dir for uuid {}: {}", uuid, dir);
            } catch (IOException e) {
                log.warn("Failed to cleanup project directory: {}", dir, e);
            }
        }
    }

    /**
     * 전체 임시 작업 디렉토리/컨테이너 종료 (서버 종료시)
     */
    @PreDestroy
    public void cleanupAllContainers() {
        log.info("Cleaning up all containers & temp dirs...");
        for (String uuid : activeContainers.keySet()) {
            stopExistingContainer(uuid);
        }
        for (String uuid : new HashSet<>(tempDirs.keySet())) {
            cleanupResources(uuid);
        }
        activeContainers.clear();
        tempDirs.clear();
        log.info("Container and temp dir cleanup completed");
    }

    /**
     * 현재 실행 중인 모든 컨테이너 목록 조회
     */
    public Map<String, String> getActiveContainers() {
        return new HashMap<>(activeContainers);
    }

    /**
     * 프로젝트 실행 중지 (컨테이너 및 임시 디렉토리 삭제)
     */
    public boolean stopProject(String uuid) {
        String containerName = activeContainers.get(uuid);
        if (containerName == null) {
            log.info("No running container found for uuid: {}", uuid);
            cleanupResources(uuid);
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

    /** S3에서 zip 파일 다운로드 (임시 작업 폴더 내) */
    private Path downloadFromS3(String s3Url, Path projectDir) throws IOException {
        log.debug("Downloading file from S3: {}", s3Url);
        Path zipPath = projectDir.resolve("project.zip");
        try (InputStream in = new URL(s3Url).openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("S3 download completed: {} ({} bytes)", zipPath, Files.size(zipPath));
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
        return entryName.contains("__MACOSX")
                || entryName.contains(".DS_Store")
                || entryName.contains("/._")
                || entryName.startsWith("._");
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
        // 프로젝트 구조 정규화 로직 (필요시 구현)
    }

    /**
     * Docker 빌드 및 실행
     * @param uuid
     * @param port
     * @param framework
     * @param projectDir 임시 작업 경로 (빌드 context)
     */
    private void runDockerContainer(String uuid, int port, String framework, String projectDir) throws IOException, InterruptedException {
        log.info("Running docker container - uuid: {}, port: {}, framework: {}, projectDir: {}", uuid, port, framework, projectDir);
        File scriptFile = new File("scripts/build_and_run.sh");
        if (!scriptFile.exists()) {
            throw new IOException("Build script not found: " + scriptFile.getAbsolutePath());
        }
        // build_and_run.sh 인자: uuid port framework projectDir
        ProcessBuilder pb = new ProcessBuilder(
                "bash", "-x", "scripts/build_and_run.sh", uuid, String.valueOf(port), framework, projectDir
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

    /**
     * 컨테이너 로그 조회
     */
    public Map<String, Object> getContainerLogs(String uuid, int lines, boolean follow, String since) {
        try {
            String containerName = "sandbox-" + uuid;
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
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("logs");
            if (lines > 0) {
                command.add("--tail");
                command.add(String.valueOf(lines));
            }
            if (follow) command.add("--follow");
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
            stdoutFuture.get(5, TimeUnit.SECONDS);
            stderrFuture.get(5, TimeUnit.SECONDS);

            String stdoutStr = stdout.toString();
            String stderrStr = stderr.toString();

            return Map.of(
                    "uuid", uuid,
                    "containerName", containerName,
                    "stdout", stdoutStr,
                    "stderr", stderrStr,
                    "logs", stdoutStr,
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
