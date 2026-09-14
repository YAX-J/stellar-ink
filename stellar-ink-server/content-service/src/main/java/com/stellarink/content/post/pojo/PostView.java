package com.stellarink.content.post.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 浏览计数闸门：每个登录用户一天只留一行。
 * 目的是给浏览量做去重 —— 同一人同一天刷新多少次都只计一次，
 * 同时不记录「谁看了哪篇」，避免额外的行为数据。
 */
@Data
@TableName("post_view")
public class PostView {

    @TableId(type = IdType.AUTO)
    private Long userId;

    /** 最近一次计数日期，用于当天去重 */
    private LocalDate viewedAt;
}
