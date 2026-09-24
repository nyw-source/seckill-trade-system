package com.nyw.user.controller;

import com.nyw.user.domain.dto.LoginFormDTO;
import com.nyw.user.domain.vo.UserLoginVO;
import com.nyw.user.service.IUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户相关接口")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    @Operation(summary = "用户登录接口")
    @PostMapping("login")
    public UserLoginVO login(@RequestBody @Validated LoginFormDTO loginFormDTO) {
        return userService.login(loginFormDTO);
    }

    @Operation(summary = "扣减余额")
    @Parameters({
            @Parameter(name = "pw", description = "支付密码"),
            @Parameter(name = "amount", description = "支付金额")
    })
    @PutMapping("/money/deduct")
    public void deductMoney(@RequestParam("pw") String pw, @RequestParam("amount") Integer amount) {
        userService.deductMoney(pw, amount);
    }

    @Operation(summary = "扣减余额（内部调用，通过 userId）")
    @PutMapping("/money/deduct/{userId}")
    public void deductMoneyByUser(@PathVariable("userId") Long userId, @RequestParam("amount") Integer amount) {
        userService.deductMoneyByUser(userId, amount);
    }
}