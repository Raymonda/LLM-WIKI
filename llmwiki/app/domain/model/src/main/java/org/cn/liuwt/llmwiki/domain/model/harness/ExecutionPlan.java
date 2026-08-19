package org.cn.liuwt.llmwiki.domain.model.harness;

import java.util.List;

public class ExecutionPlan {

    private final List<TodoStep> steps;

    private ExecutionPlan(List<TodoStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public static ExecutionPlan of(List<TodoStep> steps) {
        return new ExecutionPlan(steps == null ? List.of() : steps);
    }

    public List<TodoStep> steps() {
        return steps;
    }

    public int doneCount() {
        return (int) steps.stream().filter(step -> TodoStep.DONE.equals(step.status())).count();
    }

    public String renderMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            TodoStep step = steps.get(i);
            String mark = TodoStep.DONE.equals(step.status()) ? "x"
                : TodoStep.IN_PROGRESS.equals(step.status()) ? "~" : " ";
            sb.append(i + 1).append(". [").append(mark).append("] ").append(step.content());
            if (!step.dependencies().isEmpty()) {
                sb.append("（依赖: ").append(String.join("、", step.dependencies())).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
