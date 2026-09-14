package com.stellarink.user.component;

import com.stellarink.user.storage.AvatarValidator;
import com.stellarink.user.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 头像存储门面：校验 + 委派给当前生效的 {@link ObjectStorage} 实现。
 *
 * <p>业务层（{@code UserServiceImpl}）只依赖这个门面，不关心文件落在本地磁盘还是腾讯云 COS ——
 * 换存储由 {@code stellar.ink.storage.type} 决定，代码路径不变。
 *
 * <p>校验（大小 / 魔数认格式 / 服务端改名）统一在 {@link AvatarValidator} 中完成，
 * 与存储位置无关，因此新增对象存储实现时不可能漏掉安全检查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarStorage {

    private final AvatarValidator validator;
    private final ObjectStorage objectStorage;

    /**
     * 保存头像并返回可直接给前端 {@code <img src>} 的地址。
     *
     * @return 本地存储为站内相对路径（{@code /uploads/avatars/u1_ab12cd34.jpg}），
     *         COS 为绝对 URL（{@code https://cdn.xxx/avatars/u1_ab12cd34.jpg}）
     */
    public String store(Long userId, MultipartFile file) {
        AvatarValidator.ValidatedImage image = validator.validate(userId, file);
        String url = objectStorage.store(image);
        log.debug("头像已存储 storage={} userId={} url={}", objectStorage.type(), userId, url);
        return url;
    }

    /**
     * 删除历史头像（换头像 / 恢复底字时调用）。
     * <p>具体实现必须尽力而为、不抛异常；不属于当前存储的地址（例如从 local 切到 cos 后遗留的相对路径）
     * 会被安全忽略。
     */
    public void delete(String avatarUrl) {
        objectStorage.delete(avatarUrl);
    }

    /** 当前生效的存储类型，供健康检查或排障日志使用 */
    public String storageType() {
        return objectStorage.type();
    }
}
