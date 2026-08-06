package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("edit_step")
public class EditStepDO {
    private Long id;
    private Long sessionId;
    private Integer stepNumber;
    private String selectedLines;
    private String instruction;
    private String diffRemoved;
    private String diffAdded;
    private String contentAfter;
    private LocalDateTime createdAt;
}
