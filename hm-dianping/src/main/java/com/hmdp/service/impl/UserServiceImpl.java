package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.ErrorMessageConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.ErrorMessageConstants.LOGIN_ERROR;
import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送短信验证码校验用户登录
     *
     * @param phone
     * @param session
     */
    @Override
    public Result sendcode(String phone, HttpSession session) {
        // 1、校验提交的手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(ErrorMessageConstants.PHONENUMBER_ERROR);
        }

        // 2、生成验证码
        String code = RandomUtil.randomNumbers(6);

        /*// 3、存到session
        session.setAttribute(phone, code);
        session.setAttribute(code,phone);*/

        /*
         3、由于session无法进行数据共享，现改为存到redis中
         */
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4、发送验证码,这里简化为打印日志
        log.info("登录验证码:{}", code);

        // 返回成功信息
        return Result.ok();

    }

    /**
     * 用户登录
     *
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result  login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();

        String code = loginForm.getCode();  // 用户输入的验证码
//        校验手机号是否合法

        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail(LOGIN_ERROR);
        }

        /*if (RegexUtils.isPhoneInvalid(phone) || !session.getAttribute(code).equals(phone) || !session.getAttribute(phone).equals(code)) {
            return Result.fail(ErrorMessageConstants.LOGIN_ERROR);
        }*/

        /*
        从redis中取出验证码
         */
        String value = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if(value == null || !code.equals(value)){
            return Result.fail(LOGIN_ERROR);
        }

        // 先通过手机号到数据库查询用户相关信息  select * from tb_user where phone = ?，mp实现
        User user = query().eq("phone", phone).one();

        if (user == null) {
            // 用户不存在，生成用户
            user = createUserWithPhone(phone);
        }

        // 此时存用户信息不必存取过多信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // 将创建的用户保存到session,用于后续用户访问页面的信息校验，现改为保存到redis
        // 1、以uuid生成token，作为key存到redis
        UUID token = UUID.randomUUID();
        String tokenKey = LOGIN_TOKEN_KEY + token;

        // 2、将用户数据封装成map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        // 3、存储到redis
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,30,TimeUnit.MINUTES);   // 给这个token设置三十分钟有效期

        /*session.setAttribute("user", userDTO);*/
        log.info(String.valueOf(token));
        return Result.ok(String.valueOf(token));
    }

    /**
     * 用户签到
     * @return
     */
    @Override
    public Result sign() {
        // 1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2、获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3、拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        // 4、存redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2、获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3、拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4、获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5、获取本月截至今天为止的所有的签到记录，返回一个十进制数字 bitfield key get u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }

        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }

        // 循环遍历
        int count = 0;
        while (true){
            if((num & 1) == 0){
                break;
            }else{
                count++;
            }

            num >>>= 1;     // 无符号右移移位
        }


        return Result.ok(count);
    }

    /**
     * 通过手机号创建用户信息
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        save(user);     // mp将数据保存到数据库

        return user;
    }
}

















