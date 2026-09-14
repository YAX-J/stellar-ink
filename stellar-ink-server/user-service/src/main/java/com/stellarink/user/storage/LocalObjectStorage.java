package com.stellarink.user.storage;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.user.config.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储（{@code stellar.ink.storage.type=local}，默认）。
 *
 * <p>文件落在 {@code stellar.ink.upload.dir}（环境变量 {@code UPLOAD_DIR}，默认 {@code ./data/uploads}）。
 * 单机部署够用，也是对象存储的**回滚路径**：把配置改回 local 即可退回此实现。
 *
 * <p><b>已知限制</b>：文件与数据库必须同机可达。若服务连的是远程库、文件落在本机，
 * 别的机器读该 URL 必然 404（前端会静默降级成底字头像）。多实例或本地连远程库时请改用对象存储。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stellar.ink.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private final UploadProperties properties;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String store(AvatarValidator.ValidatedImage image) {
        Path target = avatarDir().resolve(image.objectName()).normalize();
        try {
            Files.createDirectories(avatarDir());
            try (InputStream in = image.openStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存头像失败 userId={} filename={}", image.userId(), image.objectName(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "头像保存失败，请稍后重试。");
        }
        log.info("头像已保存到本地 userId={} filename={} size={}B",
                image.userId(), image.objectName(), image.size());
        return properties.getPublicPrefix() + "/" + properties.getAvatarSubDir() + "/" + image.objectName();
    }

    @Override
    public void delete(String avatarUrl) {
        Path path = resolveInsideUploadRoot(avatarUrl);
        if (path == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(path)) {
                log.info("已删除旧头像文件 {}", path);
            }
        } catch (IOException e) {
            log.warn("删除旧头像文件失败 {}（不影响本次操作，属可清理的孤儿文件）", path, e);
        }
    }

    /**
     * 把公开路径还原成磁盘路径，并校验它确实落在上传根目录内。
     * <p>只认本存储产出的两种形态：站内相对路径、以及带本机 publicPrefix 的绝对地址；
     * 对象存储的 URL（如 COS 域名）一律返回 null 被忽略，避免跨存储误删。
     *
     * @return 合法路径；越界或不认识的路径返回 null
     */
    private Path resolveInsideUploadRoot(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return null;
        }
        /* 只取路径部分：忽略查询串（前端可能带 ?v=xxx 做缓存击穿）与域名前缀 */
        String path = avatarUrl.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String prefix = properties.getPublicPrefix() + "/" + properties.getAvatarSubDir() + "/";
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int idx = path.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            path = path.substring(idx);
        }
        if (!path.startsWith(prefix)) {
            return null;
        }
        String relative = path.substring(prefix.length());
        /* 只允许「单层文件名」：拒绝任何目录分隔符，杜绝 ../ 与子目录探测 */
        if (relative.isBlank() || relative.contains("/") || relative.contains("\\") || relative.contains("..")) {
            log.warn("拒绝清理可疑的头像路径：{}", avatarUrl);
            return null;
        }
        Path uploadRoot = uploadRoot();
        Path resolved = avatarDir().resolve(relative).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            log.warn("拒绝清理上传根目录之外的路径：{}", resolved);
            return null;
        }
        return resolved;
    }

    private Path uploadRoot() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    private Path avatarDir() {
        return uploadRoot().resolve(properties.getAvatarSubDir()).normalize();
    }
}
