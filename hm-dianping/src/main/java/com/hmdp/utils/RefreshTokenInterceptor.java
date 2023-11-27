package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_TOKEN_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_TOKEN_TTL;

/**
 * token刷新拦截器
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、从请求头中获取携带的token
        String headerToken = request.getHeader("authorization");
        if (headerToken != null){
            System.out.println("请求头的token1：" + headerToken.toString());
        }
        // 没有就放行
        if (StrUtil.isBlank(headerToken)) {
            return true;
        }
        // 有的话刷新再放行
        String key = LOGIN_TOKEN_KEY + headerToken;
        System.out.println("key:" + key);
        // 2、从redis获取token
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        if (userMap.isEmpty()) {
            return true;
        }
        // 3、验证请求携带的token
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,  new UserDTO(),false);

        // 4、将信息保存到threadlocal
        UserHolder.saveUser(userDTO);

        // 5、刷新token有效期
        stringRedisTemplate.expire(key,LOGIN_TOKEN_TTL, TimeUnit.MINUTES);
        // 6、放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
