package com.stellarink.user.storage;

/**
 * 头像对象存储的抽象。
 *
 * <p>存在的理由：本地磁盘与对象存储（COS 等）在「文件放哪」这件事上完全不同，
 * 但「存一张头像 / 删一张头像」的语义完全一致。抽出接口后：
 * <ul>
 *   <li>业务层（{@code UserServiceImpl}）不再关心存储位置；</li>
 *   <li>换存储 = 新增一个实现类 + 改 {@code stellar.ink.storage.type}，可随时回滚；</li>
 *   <li>安全校验（服务端改名、魔数认格式、大小限制）收在 {@link AvatarValidator} 里，
 *       两个实现共用 —— 否则对象存储版很容易漏掉其中一条。</li>
 * </ul>
 *
 * <p><b>实现约定</b>：
 * <ol>
 *   <li>{@link #store} 接收的 {@code image} 已通过校验，实现类不必重复校验；</li>
 *   <li>{@link #delete} 必须「尽力而为」：失败只记日志、绝不抛异常。
 *       换头像时旧文件清理失败不应让已经成功的操作回滚，最多留一个孤儿对象；</li>
 *   <li>{@link #delete} 只能删除本存储自己产出的 URL，遇到其它形态
 *       （本地相对路径 / 别的桶的地址 / 历史数据）必须安全忽略，不能误删。</li>
 * </ol>
 */
public interface ObjectStorage {

    /** 存储类型标识，用于日志与排障（local / cos） */
    String type();

    /**
     * 保存头像并返回可直接给前端 {@code <img src>} 使用的 URL。
     *
     * <p>本地实现返回站内相对路径（{@code /uploads/avatars/u1_ab12cd34.jpg}），
     * 对象存储实现返回绝对 URL（{@code https://cdn.xxx/avatars/u1_ab12cd34.jpg}）。
     * 前端组件对两者一视同仁。
     */
    String store(AvatarValidator.ValidatedImage image);

    /**
     * 删除头像对象。尽力而为，不抛异常。
     *
     * @param url 之前由 {@link #store} 返回的地址；为空、非本存储地址、或对象已不存在时静默返回
     */
    void delete(String url);
}
