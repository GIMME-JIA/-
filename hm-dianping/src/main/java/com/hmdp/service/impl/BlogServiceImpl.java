package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 查询热度最高的博客
     *
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isLikedBlog(blog);
        });

        return Result.ok(records);
    }

    /**
     * 根据id查询博客
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在!");
        }
        queryBlogUser(blog);

        isLikedBlog(blog);
        return Result.ok(blog);
    }

    /**
     * 用户查询博客的同时判断该用户是否有给此博客点赞
     * @param blog
     */
    private void isLikedBlog(Blog blog) {
        // 1、获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){      // 用户未登录的情况下无需判断是否点赞
            return;
        }
        Long userId = user.getId();
        // 2、判断是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞博客
     * @param id
     */
    @Override
    public void likeBlog(Long id) {
        // 1、获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){      // 用户未登录的情况下无需判断是否点赞
            return;
        }
        Long userId = user.getId();
        // 2、判断是否已经点赞
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            // 2.1、未点赞
            // 2.1.1 数据库点赞数 +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 2.1.2 保存该用户id到redis的set集合
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            // 2.2、 已点赞
            // 2.2.1 数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 2.2.2 把用户id从redis的set集合中移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }


    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 1、根据博客id在redis中查询top5的点赞用户
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok();
        }
        // 2、解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3、根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids)
                .last("order by field(id,"+idStr+")" ).list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4、返回
        return Result.ok(userDTOS);
    }

    /**
     * 手动添加blog中的额外字段
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
