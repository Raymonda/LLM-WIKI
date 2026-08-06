package org.cn.liuwt.llmwiki.web.security;

import org.cn.liuwt.llmwiki.domain.model.system.AuditLogModel;
import org.cn.liuwt.llmwiki.domain.service.system.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;

@Aspect
@Component
public class AuditLogAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogAspect.class);

    private static final Map<String, String> CLASS_ACTION_MAPPING = Map.ofEntries(
            Map.entry("IngestController", "INGEST_START"),
            Map.entry("ScopeController.createScope", "SCOPE_CREATE"),
            Map.entry("ScopeController.deleteScope", "SCOPE_DELETE"),
            Map.entry("ScopeController.addMember", "MEMBER_ADD"),
            Map.entry("ScopeController.removeMember", "MEMBER_REMOVE"),
            Map.entry("ScopeController.updateMemberRole", "MEMBER_ROLE_CHANGE"),
            Map.entry("SourceController.uploadSource", "SOURCE_UPLOAD"),
            Map.entry("SourceController.deleteSource", "SOURCE_DELETE"),
            Map.entry("SubscriptionController.createSubscription", "SUBSCRIPTION_CREATE"),
            Map.entry("SubscriptionController.cancelSubscription", "SUBSCRIPTION_CANCEL"),
            Map.entry("WikiController.deletePage", "PAGE_DELETE"),
            Map.entry("WikiController.updateVisibility", "PAGE_VISIBILITY"),
            Map.entry("WikiController.updateSensitivity", "PAGE_SENSITIVITY")
    );

    private static final Map<String, String> TARGET_TYPE_MAPPING = Map.ofEntries(
            Map.entry("INGEST_START", "source"),
            Map.entry("SCOPE_CREATE", "scope"),
            Map.entry("SCOPE_DELETE", "scope"),
            Map.entry("MEMBER_ADD", "member"),
            Map.entry("MEMBER_REMOVE", "member"),
            Map.entry("MEMBER_ROLE_CHANGE", "member"),
            Map.entry("SOURCE_UPLOAD", "source"),
            Map.entry("SOURCE_DELETE", "source"),
            Map.entry("SUBSCRIPTION_CREATE", "subscription"),
            Map.entry("SUBSCRIPTION_CANCEL", "subscription"),
            Map.entry("PAGE_DELETE", "wiki_page"),
            Map.entry("PAGE_VISIBILITY", "wiki_page"),
            Map.entry("PAGE_SENSITIVITY", "wiki_page")
    );

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @AfterReturning(pointcut = "execution(* org.cn.liuwt.llmwiki.web.controller..*.*(..))", returning = "result")
    public void audit(JoinPoint jp, Object result) {
        try {
            if (!isWriteMethod(jp)) {
                return;
            }

            String className = jp.getTarget().getClass().getSimpleName();
            String methodName = jp.getSignature().getName();
            String specificKey = className + "." + methodName;

            String action = CLASS_ACTION_MAPPING.get(specificKey);
            if (action == null) {
                return;
            }

            AuditLogModel model = new AuditLogModel();
            Long userId = jwtTokenProvider.getCurrentUserId();
            model.setActorUserId(userId);
            model.setAction(action);
            model.setTargetType(TARGET_TYPE_MAPPING.getOrDefault(action, "unknown"));
            model.setScopeId(resolveScopeIdFromArgs(jp.getArgs()));

            HttpServletRequest request = getRequest();
            if (request != null) {
                model.setIpAddress(getClientIp(request));
                model.setUserAgent(request.getHeader("User-Agent"));
            }

            auditLogService.log(model);
        } catch (Exception e) {
            logger.error("审计日志切面执行失败", e);
        }
    }

    private boolean isWriteMethod(JoinPoint jp) {
        if (!(jp.getSignature() instanceof MethodSignature ms)) {
            return false;
        }
        Method method = ms.getMethod();
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }

    private Long resolveScopeIdFromArgs(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Long l && l > 0 && l < 1_000_000_000L) {
                return l;
            }
        }
        return null;
    }

    private HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
