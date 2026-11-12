package com.jeffkm.mockleague.user.dto;

import com.jeffkm.mockleague.user.User;

public record UserResponse(Long id, String nickname) {

  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getNickname());
  }
}
