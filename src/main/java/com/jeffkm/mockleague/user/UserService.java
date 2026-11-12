package com.jeffkm.mockleague.user;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 1주차 임시 가입: 닉네임만 받는다. 인증은 X-User-Id 헤더로 대신한다(3주차 이후 JWT 검토). */
@Service
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Transactional
  public UserResponse create(String nickname) {
    // 먼저 확인해 친절한 오류를 주고, 동시에 같은 닉네임이 들어오면 DB 유니크 제약이 막는다
    if (userRepository.existsByNickname(nickname)) {
      throw new BusinessException(ErrorCode.NICKNAME_TAKEN);
    }
    return UserResponse.from(userRepository.save(new User(nickname)));
  }
}
