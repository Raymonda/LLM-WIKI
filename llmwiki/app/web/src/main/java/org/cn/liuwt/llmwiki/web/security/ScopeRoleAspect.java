package org.cn.liuwt.llmwiki.web.security;

import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;

@Aspect
@Component
public class ScopeRoleAspect {

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Around("@annotation(org.cn.liuwt.llmwiki.web.security.RequireScopeRole)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        RequireScopeRole anno = method.getAnnotation(RequireScopeRole.class);
        Set<String> allowed = Set.of(anno.value());
        String scopeIdParam = anno.scopeIdParam();

        Long scopeId = resolveScopeId(method, pjp.getArgs(), scopeIdParam);
        if (scopeId == null) {
            throw new BusinessException(ErrorCode.SCOPE_ID_MISSING);
        }
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_NOT_LOGGED_IN);
        }
        String role = scopeService.getMemberRole(scopeId, userId);
        if (role == null || !allowed.contains(role)) {
            throw new BusinessException(ErrorCode.AUTH_SCOPE_FORBIDDEN);
        }
        return pjp.proceed();
    }

    private Long resolveScopeId(Method method, Object[] args, String paramName) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter p = parameters[i];
            String name = resolveParamName(p);
            if (paramName.equals(name)) {
                return coerceLong(args[i]);
            }
        }
        for (int i = 0; i < parameters.length; i++) {
            if (paramName.equals(parameters[i].getName())) {
                return coerceLong(args[i]);
            }
        }
        return null;
    }

    private String resolveParamName(Parameter p) {
        for (Annotation a : p.getAnnotations()) {
            if (a instanceof PathVariable pv) {
                return firstNonBlank(pv.value(), pv.name(), p.getName());
            }
            if (a instanceof RequestParam rp) {
                return firstNonBlank(rp.value(), rp.name(), p.getName());
            }
        }
        return p.getName();
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse("");
    }

    private Long coerceLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
