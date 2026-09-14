package com.stellarink.user.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.user.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;

/**
 * 腾讯云 COS 存储（{@code stellar.ink.storage.type=cos}）。
 *
 * <p>解决的核心问题：图片不再与数据库绑定在同一台机器上 —— 服务只认
 * 「桶 + 对象键」，多实例部署、本地连远程库都能拿到同一张图。
 *
 * <p>几个必须做对的地方：
 * <ol>
 *   <li>{@code bucket} 必须是**带 APPID 后缀的全名**，否则一律 NoSuchBucket；</li>
 *   <li>返回给前端的 URL 用 {@code publicBase}（CDN 域名）拼装，**不能**用内网端点 ——
 *       写成浏览器不可达的地址，表现就是「上传成功但头像 404 降级成底字」；</li>
 *   <li>{@link #delete} 只解析本桶的 URL，遇到历史本地路径或别的域名安全忽略，
 *       且失败只记日志（旧对象清理不该让已成功的换头像操作回滚）。</li>
 * </ol>
 *
 * <p>性能取舍：不启用分块上传（Multipart Upload）。头像被前端压到 512px、上限 1MB，
 * 单次 PUT 足够；将来做文章配图等大文件时再引入 {@code TransferManager}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stellar.ink.storage.type", havingValue = "cos")
public class CosObjectStorage implements ObjectStorage {

    private final COSClient cosClient;
    private final StorageProperties properties;

    @Override
    public String type() {
        return "cos";
    }

    @Override
    public String store(AvatarValidator.ValidatedImage image) {
        StorageProperties.Cos cos = properties.getCos();
        String key = cos.getKeyPrefix() + image.objectName();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(image.contentType());
        /* 对象名带随机串，换头像即换 URL，可放心长缓存；不加这一条会导致每次打开页面都回源 */
        metadata.setCacheControl("public, max-age=31536000, immutable");
        metadata.setContentLength(image.size());

        try (InputStream in = image.openStream()) {
            cosClient.putObject(new PutObjectRequest(cos.getBucket(), key, in, metadata));
        } catch (CosServiceException e) {
            /* 服务端返回的错误：AccessDenied / NoSuchBucket / SignatureDoesNotMatch 都在这里 */
            log.error("COS 上传失败 userId={} key={} httpStatus={} errorCode={} requestId={}",
                    image.userId(), key, e.getStatusCode(), e.getErrorCode(), e.getRequestId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, uploadFailureMessage(e.getErrorCode()));
        } catch (CosClientException e) {
            /* 客户端侧：网络不通、DNS、超时 */
            log.error("COS 上传异常 userId={} key={} errorCode={}", image.userId(), key, e.getErrorCode(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "头像存储暂时不可用，请稍后重试。");
        } catch (Exception e) {
            log.error("COS 上传未预期异常 userId={} key={}", image.userId(), key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "头像保存失败，请稍后重试。");
        }

        String url = publicBase() + "/" + key;
        log.info("头像已上传 COS userId={} key={} size={}B url={}",
                image.userId(), key, image.size(), url);
        return url;
    }

    @Override
    public void delete(String avatarUrl) {
        String key = keyOf(avatarUrl);
        if (key == null) {
            return;
        }
        try {
            cosClient.deleteObject(properties.getCos().getBucket(), key);
            log.info("已删除 COS 旧头像对象 {}", key);
        } catch (Exception e) {
            /* 尽力而为：孤儿对象可由生命周期规则或人工清理，绝不能因此让换头像失败 */
            log.warn("删除 COS 旧头像对象失败 {}（不影响本次操作，属可清理的孤儿对象）", key, e);
        }
    }

    /**
     * 从存储的 URL 还原对象键，并做归属校验。
     *
     * @return 属于本桶且在本前缀下的对象键；不属于本存储、或形态不认识时返回 null
     */
    String keyOf(String avatarUrl) {
        if (!StringUtils.hasText(avatarUrl)) {
            return null;
        }
        String value = avatarUrl.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        /* 相对路径（历史本地存储的值）不是本存储的产物，直接忽略 */
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        String path;
        try {
            path = URI.create(value).getPath();
        } catch (IllegalArgumentException e) {
            log.warn("忽略无法解析的头像 URL：{}", avatarUrl);
            return null;
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        String key = path.startsWith("/") ? path.substring(1) : path;
        String prefix = properties.getCos().getKeyPrefix();
        /* 只允许删除自己前缀下的对象：即便 DB 里有脏数据，也不会误删桶里其它内容 */
        if (!key.startsWith(prefix) || key.contains("..")) {
            log.warn("忽略不属于头像前缀的对象键：{}", key);
            return null;
        }
        return key;
    }

    /** 对外基址：优先用配置的 CDN 域名，否则推导 COS 默认域名 */
    private String publicBase() {
        StorageProperties.Cos cos = properties.getCos();
        if (StringUtils.hasText(cos.getPublicBase())) {
            return trimTrailingSlash(cos.getPublicBase());
        }
        return "https://" + cos.getBucket() + ".cos." + cos.getRegion() + ".myqcloud.com";
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 把 COS 错误码翻译成用户能看懂、运维能对症的话 */
    private String uploadFailureMessage(String errorCode) {
        if (errorCode == null) {
            return "头像存储失败，请稍后重试。";
        }
        return switch (errorCode) {
            case "AccessDenied" -> "头像存储无权限，请联系站长检查存储配置。";
            case "NoSuchBucket" -> "头像存储桶不存在，请联系站长检查存储配置。";
            /* InvalidAccessKeyId：控制台粘错/粘了已删除的 SecretId（控制台列表里显示的是脱敏值，
               照抄一定失败）；SignatureDoesNotMatch：SecretId 对但 SecretKey 不对或已重置。
               两者都是「密钥本身的问题」，不是权限问题，提示要区分开，否则会去白查策略。 */
            case "InvalidAccessKeyId" -> "头像存储密钥无效（SecretId 不存在或已删除），请联系站长检查存储配置。";
            case "SignatureDoesNotMatch" -> "头像存储密钥不匹配（SecretKey 不正确或已重置），请联系站长检查存储配置。";
            default -> "头像存储失败，请稍后重试。";
        };
    }
}
