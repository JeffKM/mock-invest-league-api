package com.jeffkm.mockleague.account;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 출석 기록(I7). 행은 (참가자, 날짜) 두 값뿐이고 고칠 일이 없어 엔티티 없이 SQL 로 다룬다.
 */
@Repository
public class AttendanceRepository {

  private final JdbcTemplate jdbc;

  public AttendanceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 오늘 출석을 남긴다. 이미 있으면 아무것도 하지 않는다.
   * "있는지 보고 → 없으면 넣기"를 두 문장으로 나누면 그 사이에 다른 요청이 끼어든다.
   * 기본키 (participant_id, attended_on) + ON CONFLICT DO NOTHING 으로 DB 가 한 문장 안에서 판정한다.
   *
   * @return 새로 남겼으면 true, 이미 출석했으면 false
   */
  public boolean insertIfAbsent(Long participantId, LocalDate day) {
    int inserted = jdbc.update("""
        insert into attendance (participant_id, attended_on) values (?, ?)
        on conflict do nothing
        """, participantId, Date.valueOf(day));
    return inserted == 1;
  }

  public int countByParticipant(Long participantId) {
    return jdbc.queryForObject("select count(*) from attendance where participant_id = ?", Integer.class,
        participantId);
  }
}
