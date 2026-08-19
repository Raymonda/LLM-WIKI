package org.cn.liuwt.llmwiki.common.util.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    INTERNAL_ERROR("SYS_001", "Internal server error"),
    INVALID_PARAM("SYS_002", "Invalid parameter: {0}"),

    AUTH_INVALID_CREDENTIALS("AUTH_001", "Invalid username or password"),
    AUTH_TOKEN_EXPIRED("AUTH_002", "Token expired"),
    AUTH_ACCESS_DENIED("AUTH_003", "Access denied"),

    SCOPE_NOT_FOUND("SCOPE_001", "Scope not found"),
    SCOPE_SCHEMA_NOT_READY("SCOPE_002", "Schema not initialized"),
    SCOPE_LANGUAGE_INVALID("SCOPE_003", "Unsupported language: {0}"),

    INGEST_PARSE_FAILED("INGEST_001", "Failed to parse document"),
    INGEST_FILE_TOO_LARGE("INGEST_002", "File exceeds size limit: {0}"),
    INGEST_ALREADY_RUNNING("INGEST_003", "An ingest task is already running"),

    WIKI_PAGE_NOT_FOUND("WIKI_001", "Page not found"),
    WIKI_RAW_DIR_IMMUTABLE("WIKI_002", "Raw directory is immutable"),

    LINT_ALREADY_RUNNING("LINT_001", "A health check is already running"),

    QUERY_FAILED("QUERY_001", "Query execution failed"),

    // Auth additional
    AUTH_NOT_LOGGED_IN("AUTH_101", "Not logged in or session expired"),
    AUTH_SCOPE_FORBIDDEN("AUTH_102", "You do not have permission for this action in this scope"),
    AUTH_SELF_REGISTER_DISABLED("AUTH_103", "Registration is disabled, please contact the administrator"),
    AUTH_USER_DISABLED("AUTH_105", "Account has been disabled"),

    // Scope permissions and management
    SCOPE_PERMISSION_DELETE("SCOPE_101", "Only Owner or Admin can delete the scope"),
    SCOPE_PERMISSION_DELETE_OWNER("SCOPE_102", "Only the Owner can delete the scope"),
    SCOPE_PERMISSION_ADD_MEMBER("SCOPE_103", "Only Owner or Admin can add members"),
    SCOPE_PERMISSION_REMOVE_MEMBER("SCOPE_104", "Only Owner or Admin can remove members"),
    SCOPE_PERMISSION_CHANGE_ROLE("SCOPE_105", "Only Owner or Admin can change member roles"),
    SCOPE_CANNOT_REMOVE_OWNER("SCOPE_106", "Cannot remove the scope Owner"),
    SCOPE_CANNOT_CHANGE_OWNER_ROLE("SCOPE_107", "Cannot modify the Owner role"),
    SCOPE_INVALID_ROLE("SCOPE_108", "Invalid role: {0}"),
    SCOPE_MEMBER_EXISTS("SCOPE_109", "User is already a scope member"),
    SCOPE_MEMBER_NOT_FOUND("SCOPE_110", "Member not found"),
    SCOPE_ALREADY_MEMBER("SCOPE_111", "You are already a member of this scope"),
    SCOPE_REQUEST_EXISTS("SCOPE_112", "A pending request already exists"),
    SCOPE_PERMISSION_UPDATE_CONFIG("SCOPE_113", "Only Owner or Admin can update scope configuration"),
    SCOPE_PAGE_PATH_REQUIRED("SCOPE_114", "pagePath is required"),
    SCOPE_PERMISSION_VIEW_REQUESTS("SCOPE_115", "Only admins can view join requests"),
    SCOPE_PERMISSION_APPROVE_REQUESTS("SCOPE_116", "Only admins can approve join requests"),
    SCOPE_ID_MISSING("SCOPE_117", "Missing scopeId parameter"),

    // User management
    USER_NOT_FOUND("USER_001", "User not found"),
    USER_INVALID_USERNAME("USER_002", "Username cannot be empty"),
    USER_INVALID_ROLE("USER_003", "System role must be admin or user"),
    USERNAME_EXISTS("USER_004", "Username already exists"),

    // AI / Bootstrap
    AI_UNAVAILABLE("AI_001", "AI service not configured"),
    BOOTSTRAP_SYNTH_EMPTY("BOOT_001", "AI failed to generate a draft, please retry"),
    BOOTSTRAP_SYNTH_PARSE_FAILED("BOOT_002", "AI-generated content could not be parsed as structured Schema, please retry"),
    BOOTSTRAP_REFINE_EMPTY("BOOT_003", "AI returned no refinement result"),
    BOOTSTRAP_SCOPE_NULL("BOOT_004", "scopeId is required"),
    BOOTSTRAP_ALREADY_DONE("BOOT_005", "Schema already exists for this scope, bootstrap is not needed"),
    BOOTSTRAP_EMPTY_CAPABILITIES("BOOT_006", "Please select at least one capability domain"),

    // Schema config
    SCHEMA_CONFIG_NOT_FOUND("SCHEMA_001", "Schema configuration not found"),
    SCHEMA_VERSION_NOT_FOUND("SCHEMA_002", "Schema version not found"),
    SCHEMA_VERSION_ACCESS_DENIED("SCHEMA_003", "Access denied to this Schema version"),
    SCHEMA_ACTION_TYPE_REQUIRED("SCHEMA_004", "Action type is required"),
    SCHEMA_TARGET_VERSION_NOT_FOUND("SCHEMA_005", "Target version not found"),
    SCHEMA_ROLLBACK_ACCESS_DENIED("SCHEMA_006", "Access denied to rollback to this version"),
    SCHEMA_CONFIG_VALUE_REQUIRED("SCHEMA_007", "Configuration value is required"),
    SCHEMA_SKELETON_INVALID("SCHEMA_008", "Skeleton validation failed: {0}"),

    // Conflict ruling
    CONFLICT_REVIEW_NOT_FOUND("CONFLICT_001", "Ruling record not found"),
    CONFLICT_ALREADY_PROCESSED("CONFLICT_002", "This ruling has already been processed"),
    CONFLICT_UNSUPPORTED_ACTION("CONFLICT_003", "Unsupported ruling action: {0}"),
    CONFLICT_PAGE_NOT_FOUND("CONFLICT_004", "Conflict page not found"),
    CONFLICT_AI_UNAVAILABLE("CONFLICT_005", "AI service unavailable, cannot execute merge"),
    CONFLICT_AI_CONCURRENCY("CONFLICT_006", "AI concurrency limit reached, please retry later"),

    // Wiki additional
    WIKI_MERGE_MIN_PAGES("WIKI_003", "At least two pages are required for merging"),
    WIKI_INVALID_VISIBILITY("WIKI_004", "visibility must be open or private"),

    // Ingest additional
    INGEST_SOURCE_NOT_FOUND("INGEST_004", "Source not found or does not belong to this scope"),
    INGEST_INVALID_STATUS_REVIEW("INGEST_005", "Execution is not in pending review state, please use /start to initiate a new ingest"),
    INGEST_ALREADY_FINISHED_CANCEL("INGEST_006", "Execution has already finished, cannot cancel"),
    INGEST_ALREADY_FINISHED_PAUSE("INGEST_007", "Execution has already finished or paused, cannot pause"),
    INGEST_CANCELLED_CANNOT_RESUME("INGEST_008", "Cancelled execution cannot be resumed, please start a new ingest"),
    INGEST_INVALID_STATUS_RESUME("INGEST_009", "Only failed or paused executions can be resumed, current status: {0}"),
    INGEST_EXECUTION_NOT_FOUND("INGEST_010", "Execution record not found"),

    // Scope additional
    SCOPE_REQUEST_NOT_FOUND("SCOPE_120", "Join request not found"),
    SCOPE_REQUEST_ALREADY_REVIEWED("SCOPE_121", "This request has already been processed"),
    SCOPE_PERMISSION_VIEW_AUDIT_LOG("SCOPE_122", "Only Owner or Admin can view audit logs"),
    SCOPE_PERMISSION_VIEW_AUDIT_STATS("SCOPE_123", "Only Owner or Admin can view audit statistics"),

    // Query additional
    QUERY_SAVE_FAILED("QUERY_002", "Failed to save to knowledge base: {0}"),

    // Lint additional
    LINT_SUPPLEMENT_REQUIRED("LINT_002", "Supplement content is required"),
    LINT_APPROVE_LINK_FAILED("LINT_003", "Failed to approve link: {0}"),
    LINT_MISSING_PARAMS("LINT_004", "Missing required parameters: sourceTitle and targetTitle"),

    // Bootstrap additional
    BOOTSTRAP_INVALID_CAPABILITIES("BOOT_007", "No valid capability domains found"),
    BOOTSTRAP_NO_CAPABILITIES("BOOT_008", "Please select capability domains first"),
    BOOTSTRAP_INVALID_BLUEPRINT("BOOT_009", "Page blueprint format error: {0}"),
    BOOTSTRAP_NO_DRAFT("BOOT_010", "Please complete all steps before saving"),
    BOOTSTRAP_INVALID_TREE("BOOT_011", "Category tree format error: {0}"),
    BOOTSTRAP_V1_SESSION("BOOT_012", "This session uses legacy API, please use V2 endpoints"),
    BOOTSTRAP_SESSION_INVALID("BOOT_013", "sessionId is required"),
    BOOTSTRAP_SESSION_EXPIRED("BOOT_014", "Bootstrap session does not exist or has expired, please restart"),

    // Subscription additional
    SUBSCRIPTION_SELF_SUBSCRIBE("SUB_001", "Cannot subscribe to your own scope"),
    SUBSCRIPTION_DUPLICATE("SUB_002", "Subscription already exists"),
    SUBSCRIPTION_NOT_FOUND("SUB_003", "Subscription not found"),

    // Wiki additional (extended)
    WIKI_PROMOTED_VISIBILITY_LOCKED("WIKI_005", "Cannot change visibility of a promoted page"),
    WIKI_INVALID_SAVE_MODE("WIKI_006", "Unsupported save mode: {0}"),
    WIKI_TITLE_REQUIRED("WIKI_007", "Title is required"),
    WIKI_CONTENT_REQUIRED("WIKI_008", "Content is required"),
    WIKI_EDIT_SESSION_NOT_FOUND("WIKI_009", "Edit session not found"),
    WIKI_EDIT_STEP_NOT_FOUND("WIKI_010", "Edit step not found"),
    WIKI_DRAFT_NOT_FOUND("WIKI_011", "Draft not found"),

    // User additional
    USER_INVALID_STATUS("USER_005", "Status must be active or disabled"),
    USER_LAST_ADMIN_PROTECTED("USER_006", "Cannot modify the last admin account"),

    // Schema patch
    PATCH_ID_NULL("PATCH_001", "patchId is required"),
    PATCH_NOT_FOUND("PATCH_002", "Patch not found: {0}"),
    PATCH_SCHEMA_NOT_READY("PATCH_003", "Schema bootstrap not completed, cannot accept patches"),
    PATCH_NOT_OBSERVING("PATCH_004", "Only observing patches can be promoted, current status: {0}"),
    PATCH_NOT_PENDING("PATCH_005", "Patch already processed, current status: {0}"),
    PATCH_SECTION_NOT_FOUND("PATCH_006", "Target section not found: {0}"),
    PATCH_DIFF_EMPTY_ADD("PATCH_007", "ADD operation requires diffAfter"),
    PATCH_DIFF_EMPTY_MODIFY("PATCH_008", "MODIFY operation requires diffBefore and diffAfter"),
    PATCH_DIFF_EMPTY_DELETE("PATCH_009", "DELETE operation requires diffBefore"),
    PATCH_CONFLICT("PATCH_010", "Patch conflict: {0}"),
    PATCH_OP_UNKNOWN("PATCH_011", "Unknown operation: {0}"),
    PATCH_SECTION_UNSUPPORTED("PATCH_012", "Structured path does not support section {0}: {1}"),

    // Lint additional (extended)
    LINT_INVALID_RESOLVE_TYPE("LINT_005", "Only schema_compliance findings in open status can be marked as resolved");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
