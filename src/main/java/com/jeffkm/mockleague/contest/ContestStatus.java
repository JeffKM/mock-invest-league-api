package com.jeffkm.mockleague.contest;

/** 대회 상태. SCHEDULED → RUNNING → FINISHED 한 방향으로만 바뀐다. */
public enum ContestStatus {
  SCHEDULED,
  RUNNING,
  FINISHED
}
