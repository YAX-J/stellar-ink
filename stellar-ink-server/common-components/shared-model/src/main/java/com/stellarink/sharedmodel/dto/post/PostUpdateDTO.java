package com.stellarink.sharedmodel.dto.post;

import lombok.Data;

import java.util.List;

@Data
public class PostUpdateDTO {

    private String title;

    private String content;

    private List<String> tags;

    private Integer status;
}
