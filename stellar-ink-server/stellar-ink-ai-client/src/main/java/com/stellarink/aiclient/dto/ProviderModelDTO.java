package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 供应商返回的一个模型条目（Python {@code ProviderModelEntry}）。
 *
 * <p>只有 {@code id} 与可选的 {@code created}：响应是**形状未知的外部输入**，
 * Java 侧不多取一个字段、也不加工 —— 多一层映射就多一处「厂商改了字段我们就崩」的风险。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderModelDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模型标识，原样透传（前端把它填进「模型名」一栏，或直接保存） */
    private String id;

    /** 供应商给出的创建时间戳；多数服务不返回，**为空是正常形态** */
    private Long created;
}
