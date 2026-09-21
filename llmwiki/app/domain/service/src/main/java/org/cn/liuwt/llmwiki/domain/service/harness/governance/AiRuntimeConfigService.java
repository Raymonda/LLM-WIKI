package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SystemConfigDO;
import org.cn.liuwt.llmwiki.common.util.ConfigCrypto;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiProviderInput;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiProviderView;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiRuntimeConfigSaveRequest;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiRuntimeConfigView;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiSlotInput;
import org.cn.liuwt.llmwiki.facade.model.AiRuntimeConfigDtos.AiSlotView;
import org.cn.liuwt.llmwiki.integration.ai.AiConfigChangedEvent;
import org.cn.liuwt.llmwiki.integration.ai.AiRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiRuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiRuntimeConfigService.class);

    public static final String CONFIG_KEY = "ai.runtime";
    public static final long GLOBAL_SCOPE_ID = 0L;

    private static final Set<String> KNOWN_SLOTS = Set.of(
        "main", "multimodal", "ocr", "deep-analysis", "diagram");
    private static final Pattern PROVIDER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private final SystemConfigService systemConfigService;
    private final ConfigCrypto crypto;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiRuntimeConfigService(SystemConfigService systemConfigService,
                                  @Value("${llmwiki.ai.config-secret:${llmwiki.jwt.secret}}") String secret,
                                  ApplicationEventPublisher eventPublisher) {
        this.systemConfigService = systemConfigService;
        this.crypto = new ConfigCrypto(secret);
        this.eventPublisher = eventPublisher;
    }

    public AiRuntimeConfigView getMaskedView() {
        AiRuntimeConfig cfg = readFromDb();
        List<AiProviderView> providers = new ArrayList<>();
        cfg.providers().forEach((name, p) -> {
            boolean configured = p.apiKey() != null && !p.apiKey().isBlank();
            providers.add(new AiProviderView(name, p.baseUrl(), p.enabled(), configured,
                configured ? mask(p.apiKey()) : ""));
        });
        List<AiSlotView> slots = new ArrayList<>();
        cfg.slots().forEach((slot, s) -> slots.add(new AiSlotView(slot, s.provider(), s.model(), s.multimodal())));
        return new AiRuntimeConfigView(providers, slots);
    }

    public void save(AiRuntimeConfigSaveRequest req, Long userId) {
        validate(req);

        AiRuntimeConfig existing = readFromDb();
        Map<String, String> oldKeyByProvider = new HashMap<>();
        existing.providers().forEach((name, p) -> oldKeyByProvider.put(name, p.apiKey()));

        Map<String, AiRuntimeConfig.ProviderEntry> providers = new LinkedHashMap<>();
        for (AiProviderInput in : req.providers()) {
            String key = in.apiKey();
            if (key == null || key.isBlank()) {
                key = oldKeyByProvider.get(in.name());
            }
            providers.put(in.name(), new AiRuntimeConfig.ProviderEntry(
                in.baseUrl(), key, in.enabled() == null || in.enabled()));
        }
        Map<String, AiRuntimeConfig.SlotEntry> slots = new LinkedHashMap<>();
        for (AiSlotInput in : req.slots()) {
            slots.put(in.slot(), new AiRuntimeConfig.SlotEntry(in.provider(), in.model(), Boolean.TRUE.equals(in.multimodal())));
        }
        AiRuntimeConfig config = new AiRuntimeConfig(providers, slots);

        systemConfigService.saveConfig(GLOBAL_SCOPE_ID, CONFIG_KEY, writeToJson(config), userId);
        eventPublisher.publishEvent(new AiConfigChangedEvent(config, "admin-save"));
        log.info("AI runtime config saved, providers={}, slots={}", providers.keySet(), slots.keySet());
    }

    public boolean isConfiguredInDb() {
        SystemConfigDO row = systemConfigService.getConfig(GLOBAL_SCOPE_ID, CONFIG_KEY);
        return row != null && row.getConfigValue() != null && !row.getConfigValue().isBlank();
    }

    public AiRuntimeConfig resolveEffective() {
        return readFromDb();
    }

    public String resolveApiKey(String providerName, String inlineKey) {
        if (inlineKey != null && !inlineKey.isBlank()) {
            return inlineKey;
        }
        if (providerName == null || providerName.isBlank()) {
            return null;
        }
        AiRuntimeConfig.ProviderEntry p = readFromDb().providers().get(providerName);
        return p != null ? p.apiKey() : null;
    }

    private AiRuntimeConfig readFromDb() {
        SystemConfigDO row = systemConfigService.getConfig(GLOBAL_SCOPE_ID, CONFIG_KEY);
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return AiRuntimeConfig.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(row.getConfigValue());
            Map<String, AiRuntimeConfig.ProviderEntry> providers = new LinkedHashMap<>();
            for (JsonNode p : root.path("providers")) {
                String key = p.path("apiKey").isTextual() ? crypto.decrypt(p.path("apiKey").asText()) : null;
                providers.put(p.path("name").asText(), new AiRuntimeConfig.ProviderEntry(
                    p.path("baseUrl").asText(""), key, !p.has("enabled") || p.path("enabled").asBoolean(true)));
            }
            Map<String, AiRuntimeConfig.SlotEntry> slots = new LinkedHashMap<>();
            for (JsonNode s : root.path("slots")) {
                String slotName = s.path("slot").asText();
                if (!KNOWN_SLOTS.contains(slotName)) {
                    continue;
                }
                slots.put(slotName, new AiRuntimeConfig.SlotEntry(
                    s.path("provider").asText(""), s.path("model").asText(""),
                    s.path("multimodal").asBoolean(false)));
            }
            return new AiRuntimeConfig(providers, slots);
        } catch (Exception e) {
            log.warn("Failed to parse stored AI runtime config, treating as empty", e);
            return AiRuntimeConfig.empty();
        }
    }

    private String writeToJson(AiRuntimeConfig cfg) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode providers = root.putArray("providers");
        cfg.providers().forEach((name, p) -> {
            ObjectNode node = providers.addObject();
            node.put("name", name);
            node.put("baseUrl", p.baseUrl());
            node.put("apiKey", p.apiKey() == null || p.apiKey().isEmpty() ? "" : crypto.encrypt(p.apiKey()));
            node.put("enabled", p.enabled());
        });
        ArrayNode slots = root.putArray("slots");
        cfg.slots().forEach((slot, s) -> {
            ObjectNode node = slots.addObject();
            node.put("slot", slot);
            node.put("provider", s.provider());
            node.put("model", s.model());
            node.put("multimodal", s.multimodal());
        });
        return root.toString();
    }

    private void validate(AiRuntimeConfigSaveRequest req) {
        if (req == null || req.providers() == null || req.providers().isEmpty()) {
            throw new BusinessException("至少配置一个模型提供商");
        }
        Set<String> names = new java.util.HashSet<>();
        for (AiProviderInput p : req.providers()) {
            if (p.name() == null || !PROVIDER_NAME.matcher(p.name()).matches()) {
                throw new BusinessException("提供商名称非法（小写字母/数字/中划线）: " + p.name());
            }
            if (!names.add(p.name())) {
                throw new BusinessException("提供商名称重复: " + p.name());
            }
            if (p.baseUrl() == null || !(p.baseUrl().startsWith("http://") || p.baseUrl().startsWith("https://"))) {
                throw new BusinessException("提供商 baseUrl 必须以 http(s):// 开头: " + p.name());
            }
        }
        Map<String, Boolean> enabledByName = new HashMap<>();
        for (AiProviderInput p : req.providers()) {
            enabledByName.put(p.name(), p.enabled() == null || p.enabled());
        }
        boolean hasMain = false;
        if (req.slots() != null) {
            for (AiSlotInput s : req.slots()) {
                if (!KNOWN_SLOTS.contains(s.slot())) {
                    throw new BusinessException("未知槽位: " + s.slot());
                }
                if (s.provider() == null || !names.contains(s.provider())) {
                    throw new BusinessException("槽位 " + s.slot() + " 引用了不存在的提供商: " + s.provider());
                }
                if (s.model() == null || s.model().isBlank()) {
                    throw new BusinessException("槽位 " + s.slot() + " 已选择提供商，必须填写模型名称");
                }
                if ("main".equals(s.slot()) && Boolean.TRUE.equals(enabledByName.get(s.provider()))) {
                    hasMain = true;
                }
            }
        }
        if (!hasMain) {
            throw new BusinessException("必须为 main 槽位指定一个已启用的提供商");
        }
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() < 8) {
            return "***";
        }
        return key.substring(0, 3) + "…" + key.substring(key.length() - 4);
    }
}
