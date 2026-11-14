package com.tanjian.controller;

import com.tanjian.dto.BlogCommentRequest;
import com.tanjian.dto.Result;
import com.tanjian.service.IBlogCommentsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/blog-comments")
@RequiredArgsConstructor
public class BlogCommentsController {
    private final IBlogCommentsService commentsService;

    @GetMapping("/of/blog")
    public Result list(@RequestParam Long blogId,
                       @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return commentsService.queryComments(blogId, current);
    }

    @PostMapping
    public Result create(@RequestBody BlogCommentRequest request) {
        return commentsService.addComment(request);
    }
}
