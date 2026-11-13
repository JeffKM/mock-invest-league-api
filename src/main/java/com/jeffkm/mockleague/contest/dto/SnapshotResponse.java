package com.jeffkm.mockleague.contest.dto;

/** created=false 면 같은 틱 스냅샷이 이미 있거나 아직 열린 틱이 없다. */
public record SnapshotResponse(boolean created) {}
