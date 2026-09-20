package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AiRuntimeConfigService;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiConnectionTestRequest;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiConnectionTestResult;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiRuntimeConfigSaveRequest;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiRuntimeConfigView;
import org.cn.liuwt.llmwiki.integration.ai.AiConnectionTester;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/ai-config")
@PreAuthorize("hasRole('ADMIN')")
public class AiConfigController {

    private final AiRuntimeConfigService aiRuntimeConfigService;
    private final AiConnectionTester aiConnectionTester;
    private final JwtTokenProvider jwtTokenProvider;

    public AiConfigController(AiRuntimeConfigService aiRuntimeConfigService,
                              AiConnectionTester aiConnectionTester,
                              JwtTokenProvider jwtTokenProvider) {
        this.aiRuntimeConfigService = aiRuntimeConfigService;
        this.aiConnectionTester = aiConnectionTester;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping
    public Result<AiRuntimeConfigView> get() {
        return Result.success(aiRuntimeConfigService.getMaskedView());
    }

    @PutMapping
    public Result<Void> save(@RequestBody AiRuntimeConfigSaveRequest request) {
        aiRuntimeConfigService.save(request, jwtTokenProvider.getCurrentUserId());
        return Result.success(null);
    }

    @PostMapping("/test")
    public Result<AiConnectionTestResult> test(@RequestBody AiConnectionTestRequest request) {
        String key = aiRuntimeConfigService.resolveApiKey(request.providerName(), request.apiKey());
        if (key == null || key.isBlank()) {
            return Result.success(new AiConnectionTestResult(false, 0, "API key not configured"));
        }
        return Result.success(aiConnectionTester.test(request.baseUrl(), key, request.model()));
    }
}
