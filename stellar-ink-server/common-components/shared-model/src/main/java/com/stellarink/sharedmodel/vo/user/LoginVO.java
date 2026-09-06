package com.stellarink.sharedmodel.vo.user;

import com.stellarink.sharedmodel.vo.user.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginVO {

    /** token 名称（前端以该请求头携带） */
    private String tokenName;

    /** token 值 */
    private String tokenValue;

    private UserVO user;
}
