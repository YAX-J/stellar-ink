package com.stellarink.common.advice;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.exception.SystemException;
import com.stellarink.sharedmodel.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 全局异常处理类（Servlet 环境）
 * 统一处理应用中的各种异常，返回标准格式的响应，并附 traceId 便于排查
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Response<?> handleBusinessException(BusinessException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.warn("业务异常[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI());
        return Response.error(e.getCode(), e.getMessage()).withTraceId(traceId);
    }

    @ExceptionHandler(SystemException.class)
    public Response<?> handleSystemException(SystemException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.error("系统异常[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI(), e);
        return Response.error(e.getCode(), e.getMessage()).withTraceId(traceId);
    }

    /** 未登录（Sa-Token） */
    @ExceptionHandler(NotLoginException.class)
    public Response<?> handleNotLoginException(NotLoginException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.warn("未登录[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI());
        return Response.error(ErrorCode.UNAUTHORIZED).withTraceId(traceId);
    }

    /** 缺少权限 / 角色（Sa-Token） */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public Response<?> handlePermissionException(Exception e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.warn("无权限[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI());
        return Response.error(ErrorCode.FORBIDDEN).withTraceId(traceId);
    }

    /** 参数校验异常（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<?> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse(ErrorCode.PARAM_ERROR.getMsg());
        log.warn("参数校验失败[{}]: {}, 请求路径: {}", traceId, msg, request.getRequestURI());
        return Response.error(ErrorCode.PARAM_ERROR.getCode(), msg).withTraceId(traceId);
    }

    /** 缺少请求参数 / 类型不匹配 / JSON 解析失败 */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public Response<?> handleBadRequest(Exception e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.warn("请求参数错误[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI());
        return Response.error(ErrorCode.PARAM_ERROR).withTraceId(traceId);
    }

    /** 资源不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Response<?> handleNotFound(NoResourceFoundException e) {
        return Response.error(ErrorCode.NOT_FOUND).withTraceId(generateTraceId());
    }

    /** 数据完整性冲突 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Response<?> handleDataIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.error("数据完整性冲突[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI());
        return Response.error(ErrorCode.DATABASE_ERROR).withTraceId(traceId);
    }

    /** 数据库异常 */
    @ExceptionHandler(SQLException.class)
    public Response<?> handleSql(SQLException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.error("数据库异常[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI(), e);
        return Response.error(ErrorCode.DATABASE_ERROR).withTraceId(traceId);
    }

    /** 兜底：未预期异常 */
    @ExceptionHandler(Exception.class)
    public Response<?> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = generateTraceId();
        log.error("未预期异常[{}]: {}, 请求路径: {}", traceId, e.getMessage(), request.getRequestURI(), e);
        return Response.error(ErrorCode.SYSTEM_ERROR).withTraceId(traceId);
    }

    private String generateTraceId() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        org.slf4j.MDC.put("traceId", traceId);
        return traceId;
    }
}
