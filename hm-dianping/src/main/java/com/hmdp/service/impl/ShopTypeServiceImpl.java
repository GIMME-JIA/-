package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.ErrorMessageConstants.QUERY_ERROR;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopTypeMapper shopTypeMapper;

    /**
     * @return
     */
    public Result listShop() {
        String key = CACHE_SHOP_TYPE_KEY;
        //debug发现取出的10条数据全在一起，也就是说List里面只有一个元素，内容是所有的数据
        List<String> shopTypeList = stringRedisTemplate.opsForList().
                range(key, 0, 9);
        //如果有，直接返回
        if (shopTypeList != null && !shopTypeList.isEmpty()){
            return Result.ok(JSONUtil.toList(shopTypeList.get(0),ShopType.class));
        }
        //没有先去数据库查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //如果数据库没有则报错
        if (shopTypes == null || shopTypes.isEmpty()){
            return Result.fail(QUERY_ERROR);
        }
        //有则写入redis
        String shopTypesToString = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForList().leftPushAll(key,shopTypesToString);
        //返回
        return Result.ok(shopTypes);

    }
}
