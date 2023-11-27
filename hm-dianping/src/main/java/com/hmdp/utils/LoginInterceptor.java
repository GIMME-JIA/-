package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.aopalliance.intercept.Interceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_TOKEN_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_TOKEN_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 登录前拦截
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*// 1、获取session
        HttpSession session = request.getSession();
        // 2、获取session中的用户
        Object user = session.getAttribute("user");
        // 3、判断用户是否存在
        if (user == null) {
            // 4、不存在，拦截，返回401
            response.setStatus(401);
            return false;
        }


        // 5、存在，保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);*/

        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // 6、放行
        return true;

        // return HandlerInterceptor.super.preHandle(request, response, handler);
    }

}
