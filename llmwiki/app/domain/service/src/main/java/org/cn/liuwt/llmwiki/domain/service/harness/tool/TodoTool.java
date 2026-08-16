package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionPlan;
import org.cn.liuwt.llmwiki.domain.model.harness.TodoStep;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TodoTool {

    private static final int MAX_SESSIONS = 500;

    private static final Set<String> VALID_STATUSES = Set.of(TodoStep.PENDING, TodoStep.IN_PROGRESS, TodoStep.DONE);

    private final Map<String, ExecutionPlan> plans = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "维护当前会话的任务清单（todo）。面对多跳或复杂问题时，先用本工具把问题分解为步骤清单，之后每完成一步就更新状态，帮助追踪进度、避免遗漏。stepsJson 为 JSON 数组，每项形如 {\"content\":\"步骤描述\",\"status\":\"pending|in_progress|done\",\"dependencies\":[\"可选，前置步骤描述\"]}。每次调用整体替换当前清单")
    public String todoWrite(
        @ToolParam(description = "任务清单 JSON 数组字符串") String stepsJson,
        ToolContext toolContext
    ) {
        String sessionId = sessionIdOf(toolContext);
        if (sessionId == null) {
            return "错误：缺少会话上下文，无法保存任务清单";
        }
        List<TodoStep> steps;
        try {
            steps = objectMapper.readValue(stepsJson, new TypeReference<List<TodoStep>>() {
            });
        } catch (Exception e) {
            return "错误：stepsJson 解析失败，请提供合法的 JSON 数组: " + e.getMessage();
        }
        for (TodoStep step : steps) {
            if (!VALID_STATUSES.contains(step.status())) {
                return "错误：非法 status '" + step.status() + "'，允许值 pending/in_progress/done";
            }
        }
        if (plans.size() > MAX_SESSIONS) {
            plans.clear();
        }
        ExecutionPlan plan = ExecutionPlan.of(steps);
        plans.put(sessionId, plan);
        return "任务清单已更新（" + steps.size() + " 项，已完成 " + plan.doneCount() + " 项）：\n" + plan.renderMarkdown();
    }

    @Tool(description = "读取当前会话的任务清单（todo）。若之前用 todoWrite 建立过清单则返回其最新内容与进度，否则提示无清单")
    public String todoRead(ToolContext toolContext) {
        String sessionId = sessionIdOf(toolContext);
        if (sessionId == null) {
            return "错误：缺少会话上下文，无法读取任务清单";
        }
        ExecutionPlan plan = plans.get(sessionId);
        if (plan == null || plan.steps().isEmpty()) {
            return "当前会话尚无任务清单。可用 todoWrite 建立。";
        }
        return "任务清单（已完成 " + plan.doneCount() + "/" + plan.steps().size() + " 项）：\n" + plan.renderMarkdown();
    }

    public record Progress(int done, int total) {
    }

    public Progress progressOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        ExecutionPlan plan = plans.get(sessionId);
        if (plan == null || plan.steps().isEmpty()) {
            return new Progress(0, 0);
        }
        return new Progress(plan.doneCount(), plan.steps().size());
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            plans.remove(sessionId);
        }
    }

    private String sessionIdOf(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get("sessionId");
        return value != null ? String.valueOf(value) : null;
    }
}
