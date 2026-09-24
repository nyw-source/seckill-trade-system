package com.nyw.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nyw.common.exception.BadRequestException;
import com.nyw.common.exception.BizIllegalException;
import com.nyw.common.exception.ForbiddenException;
import com.nyw.user.config.JwtProperties;
import com.nyw.user.domain.dto.LoginFormDTO;
import com.nyw.user.domain.po.User;
import com.nyw.user.domain.vo.UserLoginVO;
import com.nyw.user.enums.UserStatus;
import com.nyw.user.mapper.UserMapper;
import com.nyw.user.service.IUserService;
import com.nyw.user.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtTool jwtTool;
    private final JwtProperties jwtProperties;

    @Override
    public UserLoginVO login(LoginFormDTO loginDTO) {
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        User user = lambdaQuery().eq(User::getUsername, username).one();
        Assert.notNull(user, "用户名错误");
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException("用户被冻结");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BadRequestException("用户名或密码错误");
        }
        String token = jwtTool.createToken(user.getId(), jwtProperties.getTokenTTL());
        UserLoginVO vo = new UserLoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setBalance(user.getBalance());
        vo.setToken(token);
        return vo;
    }

    @Override
    public void deductMoney(String pw, Integer totalFee) {
        log.info("开始扣款");
        // 注意：此方法需要从请求上下文获取 userId，通过 Gateway 透传的 X-User-Id 头
        throw new UnsupportedOperationException("请使用 deductMoneyByUser 方法");
    }

    @Override
    public void deductMoneyByUser(Long userId, Integer totalFee) {
        log.info("开始扣款 userId={} amount={}", userId, totalFee);
        try {
            baseMapper.updateMoney(userId, totalFee);
        } catch (Exception e) {
            throw new RuntimeException("扣款失败，可能是余额不足！", e);
        }
        log.info("扣款成功");
    }
}