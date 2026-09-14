package com.stellarink.user.storage;

import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.user.config.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 头像上传的公共校验：大小、真实格式、对象名。
 *
 * <p>这些校验与「文件存到哪里」无关，所以从存储实现里抽出来，由 {@link AvatarStorage} 门面统一调用。
 * 这样新增对象存储实现时不可能漏掉安全检查（曾经的写法是把校验写在本地存储类内部）。
 *
 * <p>三条硬性校验：
 * <ol>
 *   <li><b>大小</b>：Servlet multipart 层拦一遍（application.yml），这里按字节再拦一遍，
 *       保证脱离 web 容器调用时也安全；</li>
 *   <li><b>真实格式</b>：用 {@link ImageIO} 读文件头（魔数）判断，不信任 Content-Type 与扩展名；</li>
 *   <li><b>对象名</b>：服务端生成 {@code u{userId}_{uuid8}.{ext}}，绝不采用客户端文件名 ——
 *       从根上消除 {@code ../} 穿越与「上传可执行后缀到静态目录」这类问题。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarValidator {

    /** 允许的图片格式（ImageIO 返回的格式名，小写） */
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png", "webp");

    private final UploadProperties properties;

    /** 校验通过后的结果：可直接交给任意 {@link ObjectStorage} 落地 */
    public record ValidatedImage(Long userId, String objectName, String contentType,
                                 long size, InputStreamSupplier streamSupplier) {

        /** 每次调用返回一个新的输入流（MultipartFile 的流只能读一次，重试/多次使用时必须重新取） */
        public InputStream openStream() throws IOException {
            return streamSupplier.open();
        }

        @FunctionalInterface
        public interface InputStreamSupplier {
            InputStream open() throws IOException;
        }
    }

    /**
     * 校验并规范化上传的头像。
     *
     * @throws BusinessException 文件为空、超限、或不是允许的图片格式
     */
    public ValidatedImage validate(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请选择一张图片。");
        }
        long max = properties.getAvatarMaxBytes();
        if (file.getSize() > max) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "图片太大了，请压到 " + (max / 1024) + "KB 以内。");
        }
        String format = detectFormat(file);
        String objectName = "u" + userId + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + format;
        return new ValidatedImage(userId, objectName, "image/" + format, file.getSize(), file::getInputStream);
    }

    /** 读文件头判断真实图片格式，顺带拦截伪装成图片的其它文件 */
    private String detectFormat(MultipartFile file) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.getInputStream())) {
            if (iis == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "这个文件不是有效的图片。");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "只支持 JPG / PNG / WebP 图片。");
            }
            String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
            /* ImageIO 对 jpg 返回 "JPEG"，统一成扩展名友好的写法 */
            if (!ALLOWED_FORMATS.contains(format)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "只支持 JPG / PNG / WebP 图片。");
            }
            return "jpeg".equals(format) ? "jpg" : format;
        } catch (IOException e) {
            log.warn("读取上传图片失败", e);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片读取失败，请换一张试试。");
        }
    }
}
