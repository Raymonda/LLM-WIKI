package org.cn.liuwt.llmwiki.domain.service.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PythonProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(PythonProcessRunner.class);
    private static final String SCRIPT_RESOURCE = "doc_parser.py";

    private static final List<String> ENV_ALLOWLIST = List.of(
            "PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "USERNAME", "USER", "HOME",
            "LANG", "LC_ALL", "LC_CTYPE", "TZ",
            "PYTHONIOENCODING", "PYTHONUTF8", "PYTHONHOME", "PYTHONPATH", "VIRTUAL_ENV",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "PROGRAMDATA", "APPDATA", "LOCALAPPDATA"
    );
    private static final List<String> ENV_SENSITIVE_PATTERNS = List.of("KEY", "SECRET", "TOKEN", "PASSWORD", "CREDENTIAL");
    private static final ObjectMapper CREDENTIALS_OM = new ObjectMapper();

    private static final Object EXTRACT_LOCK = new Object();
    private static volatile Path extractedScriptPath;

    private static final Object PYTHON_CMD_LOCK = new Object();
    private static volatile String cachedPythonCommand;

    private final Path scriptPath;
    private final long timeoutSeconds;
    private String ocrBaseUrl;
    private String diagramBaseUrl;

    public PythonProcessRunner(long timeoutSeconds) {
        this.scriptPath = resolveScriptPath();
        this.timeoutSeconds = timeoutSeconds;
    }

    public PythonProcessRunner(Path scriptPath, long timeoutSeconds) {
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public void setOcrBaseUrl(String ocrBaseUrl) {
        this.ocrBaseUrl = ocrBaseUrl;
    }

    public void setDiagramBaseUrl(String diagramBaseUrl) {
        this.diagramBaseUrl = diagramBaseUrl;
    }

    private static Path resolveScriptPath() {
        if (extractedScriptPath != null && Files.exists(extractedScriptPath)) {
            return extractedScriptPath;
        }
        synchronized (EXTRACT_LOCK) {
            if (extractedScriptPath != null && Files.exists(extractedScriptPath)) {
                return extractedScriptPath;
            }
            try {
                Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "llmwiki");
                Files.createDirectories(tmpDir);
                Path tmpScript = tmpDir.resolve("doc_parser.py");

                try (InputStream in = PythonProcessRunner.class.getClassLoader().getResourceAsStream(SCRIPT_RESOURCE)) {
                    if (in == null) {
                        throw new RuntimeException("doc_parser.py 不在 classpath 中，jar 打包可能缺失该文件");
                    }
                    byte[] content = in.readAllBytes();
                    Files.write(tmpScript, content);
                }

                log.info("doc_parser.py 已从 classpath 提取到: {}", tmpScript.toAbsolutePath());
                extractedScriptPath = tmpScript;
                return tmpScript;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("从 classpath 提取 doc_parser.py 失败: " + e.getMessage(), e);
            }
        }
    }

    public String run(String filePath, String format) {
        return run(filePath, format, false, null, null, 20, 0.3);
    }

    public String run(String filePath, String format, boolean ocrEnable, String ocrApiKey, String ocrModel, int ocrMaxPages, double ocrThreshold) {
        return run(filePath, format, ocrEnable, ocrApiKey, ocrModel, ocrMaxPages, ocrThreshold, false, null);
    }

    public String run(String filePath, String format, boolean ocrEnable, String ocrApiKey, String ocrModel, int ocrMaxPages, double ocrThreshold, boolean multimodalMain, String assetsDir) {
        return run(filePath, format, ocrEnable, ocrApiKey, ocrModel, ocrMaxPages, ocrThreshold, multimodalMain, assetsDir, false, null, null, 15, 150, 85, 4, 5.0, 0.05, 0.05, 2.0, null);
    }

    public String run(String filePath, String format, boolean ocrEnable, String ocrApiKey, String ocrModel, int ocrMaxPages, double ocrThreshold, boolean multimodalMain, String assetsDir, boolean diagramEnable, String diagramApiKey, String diagramModel, int diagramMaxImages) {
        return run(filePath, format, ocrEnable, ocrApiKey, ocrModel, ocrMaxPages, ocrThreshold, multimodalMain, assetsDir, diagramEnable, diagramApiKey, diagramModel, diagramMaxImages, 150, 85, 4, 5.0, 0.05, 0.05, 2.0, null);
    }

    public String run(String filePath, String format, boolean ocrEnable, String ocrApiKey, String ocrModel, int ocrMaxPages, double ocrThreshold, boolean multimodalMain, String assetsDir, boolean diagramEnable, String diagramApiKey, String diagramModel, int diagramMaxImages, int diagramDpi, int diagramJpegQuality, int diagramConcurrency, double diagramScoreThreshold, double diagramLargeDrawingRatio, double diagramSignificantImageRatio, double diagramPayloadGateMb, String imageDescModel) {
        try {
            List<String> command = new ArrayList<>();
            boolean passOcrKey = ocrEnable && ocrApiKey != null && !ocrApiKey.isBlank();
            boolean passDiagramKey = diagramEnable && diagramApiKey != null && !diagramApiKey.isBlank();
            command.add(resolvePythonCommand());
            command.add(scriptPath.toString());
            command.add("--input");
            command.add(filePath);
            command.add("--format");
            command.add(format);
            if (ocrEnable) {
                command.add("--ocr-enable");
                if (ocrBaseUrl != null && !ocrBaseUrl.isBlank()) {
                    command.add("--ocr-base-url");
                    command.add(ocrBaseUrl);
                }
                if (ocrModel != null && !ocrModel.isBlank()) {
                    command.add("--ocr-model");
                    command.add(ocrModel);
                }
                command.add("--ocr-max-pages");
                command.add(String.valueOf(ocrMaxPages));
                command.add("--ocr-threshold");
                command.add(String.valueOf(ocrThreshold));
            }
            if (multimodalMain) {
                command.add("--multimodal-main");
            }
            if (assetsDir != null && !assetsDir.isBlank()) {
                command.add("--assets-dir");
                command.add(assetsDir);
            }
            if (diagramEnable) {
                command.add("--diagram-enable");
                if (diagramBaseUrl != null && !diagramBaseUrl.isBlank()) {
                    command.add("--diagram-base-url");
                    command.add(diagramBaseUrl);
                }
                if (diagramModel != null && !diagramModel.isBlank()) {
                    command.add("--diagram-model");
                    command.add(diagramModel);
                }
                command.add("--diagram-max-images");
                command.add(String.valueOf(diagramMaxImages));
                command.add("--diagram-dpi");
                command.add(String.valueOf(diagramDpi));
                command.add("--diagram-jpeg-quality");
                command.add(String.valueOf(diagramJpegQuality));
                command.add("--diagram-concurrency");
                command.add(String.valueOf(diagramConcurrency));
                command.add("--diagram-score-threshold");
                command.add(String.valueOf(diagramScoreThreshold));
                command.add("--diagram-large-drawing-ratio");
                command.add(String.valueOf(diagramLargeDrawingRatio));
                command.add("--diagram-image-area-ratio");
                command.add(String.valueOf(diagramSignificantImageRatio));
                command.add("--diagram-payload-gate-mb");
                command.add(String.valueOf(diagramPayloadGateMb));
            }
            if (imageDescModel != null && !imageDescModel.isBlank()) {
                command.add("--image-desc-model");
                command.add(imageDescModel);
            }
            if (passOcrKey || passDiagramKey) {
                command.add("--credentials-stdin");
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            applyScrubbedEnvironment(pb);

            Process process = pb.start();
            writeCredentialsViaStdin(process, passOcrKey ? ocrApiKey : null, passDiagramKey ? diagramApiKey : null);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            Thread outThread = transferAsync(process.getInputStream(), stdout);
            Thread errThread = transferAsync(process.getErrorStream(), stderr);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                waitForQuiescence(process, outThread, errThread);
                throw new RuntimeException("文档解析超时（" + timeoutSeconds + "秒），文件可能过大");
            }

            outThread.join(5000);
            errThread.join(5000);

            int exitCode = process.exitValue();
            String stdoutStr = stdout.toString(StandardCharsets.UTF_8);
            if (exitCode != 0) {
                String errOutput = stderr.toString(StandardCharsets.UTF_8);
                log.error("Python parser exited with code {}\n--- stderr ---\n{}\n--- stdout ---\n{}", exitCode, errOutput, stdoutStr);
                if (exitCode == 9009) {
                    throw new RuntimeException("Python 未安装或不在系统 PATH 中，请安装 Python 3.8+ 后重启服务");
                }
                String detail = extractErrorDetail(stdoutStr, errOutput);
                throw new RuntimeException("文档解析失败 (exit=" + exitCode + "): " + detail);
            }

            return extractJsonOutput(stdoutStr);
        } catch (IOException e) {
            log.error("Failed to start Python process", e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("CreateProcess error=2") || msg.contains("Cannot run program")) {
                throw new RuntimeException("Python 未安装或不在系统 PATH 中，请安装 Python 3.8+ 后重启服务", e);
            }
            throw new RuntimeException("文档解析进程异常: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to run Python parser", e);
            throw new RuntimeException("文档解析进程异常: " + e.getMessage(), e);
        }
    }

    public static String resolvePythonCommand() {
        if (cachedPythonCommand != null) {
            return cachedPythonCommand;
        }
        synchronized (PYTHON_CMD_LOCK) {
            if (cachedPythonCommand != null) {
                return cachedPythonCommand;
            }
            List<String> candidates = new ArrayList<>();
            // Check local venv first (created by setup-python.bat/sh)
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                candidates.add(".venv-python\\Scripts\\python");
                candidates.add(".venv\\Scripts\\python");
            } else {
                candidates.add(".venv-python/bin/python");
                candidates.add(".venv/bin/python");
            }
            // System Python
            candidates.addAll(Arrays.asList("python", "python3", "py"));
            for (String cmd : candidates) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                    pb.redirectErrorStream(true);
                    applyScrubbedEnvironment(pb);
                    Process p = pb.start();
                    boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                    if (finished && p.exitValue() == 0) {
                        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                        if (output.contains("Python")) {
                            log.info("Detected Python command: {} ({})", cmd, output);
                            cachedPythonCommand = cmd;
                            return cmd;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            log.warn("No Python command found, defaulting to 'python'");
            cachedPythonCommand = "python";
            return "python";
        }
    }

    public static PythonEnvironmentCheckResult checkEnvironment() {
        String pythonCmd = resolvePythonCommand();
        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();
        String pythonVersion = "unknown";

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, "--version");
            pb.redirectErrorStream(true);
            applyScrubbedEnvironment(pb);
            Process p = pb.start();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                pythonVersion = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            return new PythonEnvironmentCheckResult(false, pythonCmd, pythonVersion,
                    List.of("Python \u672a\u5b89\u88c5\u6216\u4e0d\u53ef\u7528"), List.of());
        }

        String checkScript = String.join(";",
                "import sys",
                "required = {'fitz':'pymupdf','docx':'python-docx','openpyxl':'openpyxl','pptx':'python-pptx'}",
                "optional = {'pymupdf4llm':'pymupdf4llm','pypdf':'pypdf','pandas':'pandas'}",
                "missing_req = [pkg for mod,pkg in required.items() if not __import__('importlib',fromlist=['util']).util.find_spec(mod)]",
                "missing_opt = [pkg for mod,pkg in optional.items() if not __import__('importlib',fromlist=['util']).util.find_spec(mod)]",
                "print('|'.join(missing_req) + '||' + '|'.join(missing_opt))"
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, "-c", checkScript);
            pb.redirectErrorStream(true);
            applyScrubbedEnvironment(pb);
            Process p = pb.start();
            if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String[] parts = output.split("\\|\\|", -1);
                if (parts.length >= 1 && !parts[0].isBlank()) {
                    for (String pkg : parts[0].split("\\|")) {
                        if (!pkg.isBlank()) missingRequired.add(pkg.trim());
                    }
                }
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    for (String pkg : parts[1].split("\\|")) {
                        if (!pkg.isBlank()) missingOptional.add(pkg.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Python dependency check failed: {}", e.getMessage());
        }

        boolean ok = missingRequired.isEmpty();
        return new PythonEnvironmentCheckResult(ok, pythonCmd, pythonVersion, missingRequired, missingOptional);
    }

    public record PythonEnvironmentCheckResult(
            boolean ok,
            String pythonCommand,
            String pythonVersion,
            List<String> missingRequired,
            List<String> missingOptional
    ) {}


    private Thread transferAsync(InputStream in, OutputStream out) {
        Thread t = new Thread(() -> {
            try (in; out) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        });
        t.start();
        return t;
    }

    private String extractJsonOutput(String stdoutStr) {
        String trimmed = stdoutStr.trim();
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
        return trimmed;
    }

    private String extractErrorDetail(String stdoutStr, String errOutput) {
        String jsonError = null;
        if (stdoutStr != null && !stdoutStr.isBlank()) {
            String trimmed = stdoutStr.trim();
            int errIdx = trimmed.indexOf("\"error\"");
            if (trimmed.startsWith("{") && errIdx >= 0) {
                int colon = trimmed.indexOf(':', errIdx);
                if (colon > 0) {
                    int qStart = trimmed.indexOf('"', colon + 1);
                    int qEnd = qStart >= 0 ? trimmed.indexOf('"', qStart + 1) : -1;
                    if (qStart >= 0 && qEnd > qStart) {
                        jsonError = trimmed.substring(qStart + 1, qEnd);
                    }
                }
            }
        }
        if (jsonError != null && !jsonError.isBlank() && !jsonError.equals("文档解析失败: ")) {
            return jsonError;
        }
        if (errOutput != null && !errOutput.isBlank()) {
            String[] lines = errOutput.trim().split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (!line.isBlank() && !line.startsWith("Traceback") && !line.startsWith("  File ") && !line.startsWith("    ")) {
                    if (line.length() > 300) line = line.substring(0, 300) + "...";
                    return line;
                }
            }
            String last = lines[lines.length - 1].trim();
            if (last.length() > 300) last = last.substring(0, 300) + "...";
            return last;
        }
        return "Python 进程异常退出，详情见服务端日志（搜索 'Python parser exited with code' 查看完整 stderr）";
    }

    private static void writeCredentialsViaStdin(Process process, String ocrApiKey, String diagramApiKey) {
        ObjectNode payload = CREDENTIALS_OM.createObjectNode();
        payload.put("ocrApiKey", ocrApiKey != null ? ocrApiKey : "");
        payload.put("diagramApiKey", diagramApiKey != null ? diagramApiKey : "");
        try (OutputStream os = process.getOutputStream()) {
            os.write((payload.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            log.warn("Failed to write credentials via stdin: {}", e.getMessage());
        }
    }

    static Map<String, String> buildScrubbedEnvironment(Map<String, String> source) {
        Map<String, String> scrubbed = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return scrubbed;
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            String upper = name.toUpperCase(Locale.ROOT);
            if (!ENV_ALLOWLIST.contains(upper)) {
                continue;
            }
            boolean sensitive = false;
            for (String pattern : ENV_SENSITIVE_PATTERNS) {
                if (upper.contains(pattern)) {
                    sensitive = true;
                    break;
                }
            }
            if (sensitive) {
                continue;
            }
            scrubbed.put(name, entry.getValue());
        }
        return scrubbed;
    }

    private static void applyScrubbedEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(buildScrubbedEnvironment(System.getenv()));
        env.put("PYTHONIOENCODING", "utf-8");
    }

    private static void waitForQuiescence(Process process, Thread... drainThreads) {
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Thread thread : drainThreads) {
            joinQuietly(thread);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
