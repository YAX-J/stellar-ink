package com.stellarink.sharedmodel.response;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一响应包装：{ code, msg, data, traceId, timestamp }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Response<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer code;

    private String msg;

    private T data;

    /** 链路追踪ID */
    private String traceId;

    private LocalDateTime timestamp;

    public static <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ErrorCode.SUCCESS.getCode())
                .msg(ErrorCode.SUCCESS.getMsg())
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /** 无数据成功响应 */
    public static Response<Void> success() {
        return success(null);
    }

    public static <T> Response<T> success(T data, String msg) {
        return Response.<T>builder()
                .code(ErrorCode.SUCCESS.getCode())
                .msg(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> Response<T> error(ErrorCode errorCode) {
        return Response.<T>builder()
                .code(errorCode.getCode())
                .msg(errorCode.getMsg())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> Response<T> error(ErrorCode errorCode, String msg) {
        return Response.<T>builder()
                .code(errorCode.getCode())
                .msg(msg)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> Response<T> error(Integer code, String msg) {
        return Response.<T>builder()
                .code(code)
                .msg(msg)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public Response<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }
}
