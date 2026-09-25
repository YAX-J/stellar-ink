package com.stellarink.aiclient.error;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;

/**
 * Python 侧**给出了明确答复**的错误：HTTP 4xx/5xx + 契约错误体 {@code {"code","message"}}。
 *
 * <p>与「连不上 Python」区分开：那种情况连响应都没有（连接被拒 / 超时），
 * 只能报「服务暂不可用」；而这一类是上游明确告诉我们「哪里不对」——
 * 例如「角色 embedding 尚未配置模型（请在 AI 实验室 → 模型配置里填写）」，
 * 这句话是**可操作的**，必须原样到用户面前。
 *
 * <p>为什么单独一个类型而不是直接抛 {@link BusinessException}：调用方与测试需要区分
 * 「上游答复了」与「我们自己判断失败」，也让全局异常处理器之外的地方（例如将来的降级工厂）
 * 有机会只对「上游答复」做特殊处理。
 */
public class PythonApiException extends BusinessException {

    /** 上游返回的 HTTP 状态码（保留下来用于日志与排查，不对外暴露）。 */
    private final int status;

    public PythonApiException(ErrorCode errorCode, int status, String message) {
        super(errorCode, message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
