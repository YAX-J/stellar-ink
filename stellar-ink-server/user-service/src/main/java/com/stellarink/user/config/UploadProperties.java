package com.stellarink.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘上传配置（仅 {@code stellar.ink.storage.type=local} 时生效，见 LocalObjectStorage）。
 * <p>目录走环境变量 {@code UPLOAD_DIR}，缺省为相对路径 {@code ./data/uploads}（相对服务进程工作目录）。
 * 生产用 Docker 卷挂载持久化，否则容器重建后头像文件会连同旧镜像一起消失。
 * <p>改用对象存储（COS）后本配置不再参与上传，但 {@code publicPrefix} 仍用于识别历史遗留的
 * 站内相对路径，便于切换后优雅忽略旧值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stellar.ink.upload")
public class UploadProperties {

    /** 头像文件的磁盘根目录 */
    private String dir = "./data/uploads";

    /** 单个头像文件大小上限（字节），默认 1MB */
    private long avatarMaxBytes = 1024 * 1024L;

    /** 对外公开的访问前缀（与网关路由、前端 <img src> 一致，不含域名） */
    private String publicPrefix = "/uploads";

    /** 头像在磁盘上的子目录名 */
    private String avatarSubDir = "avatars";
}
