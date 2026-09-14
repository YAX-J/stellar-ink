package com.stellarink.user.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 头像静态资源映射：把 {@code /uploads/avatars/**} 指到磁盘目录。
 * <p>只映射 avatars 子目录而不是整个上传根目录 —— 上传根目录将来若放私密文件，
 * 不会因为一个宽松的 ResourceHandler 而意外公开。
 * <p>刻意不加缓存头：文件名里已带随机串（每次上传都是新文件名），
 * 浏览器无需缓存策略配合即可拿到最新头像，旧文件由存储层删除。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class UploadWebConfig implements WebMvcConfigurer {

    private final UploadProperties properties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path avatarDir = Paths.get(properties.getDir(), properties.getAvatarSubDir())
                .toAbsolutePath()
                .normalize();
        String pattern = properties.getPublicPrefix() + "/" + properties.getAvatarSubDir() + "/**";
        registry.addResourceHandler(pattern)
                .addResourceLocations(avatarDir.toUri().toString());
        log.info("头像静态资源映射：{} -> {}", pattern, avatarDir);
    }
}
