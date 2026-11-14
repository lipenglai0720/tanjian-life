package com.tanjian.service;

import com.tanjian.entity.BlogComments;
import com.tanjian.dto.BlogCommentRequest;
import com.tanjian.dto.Result;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogCommentsService extends IService<BlogComments> {
    Result queryComments(Long blogId, Integer current);
    Result addComment(BlogCommentRequest request);
}
