package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 所有异常处理器统一返回的错误信封，前端拦截器只读取 {@code error} 字段。
 *
 * <p>用记录类而非 Map：Map.of("error", ...) 很容易在后续改动中悄悄改掉键名
 * （如 message、reason），直到用户看到空白提示才被发现；记录类把该契约变成
 * 编译期不变量，能出现在 OpenAPI 输出中，也便于以后扩展（如加 request-id）而不散改各处。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error) {
    /** 静态工厂：用错误信息构造响应。 */
    public static ErrorResponse of(String message) {
        return new ErrorResponse(message);
    }
}
