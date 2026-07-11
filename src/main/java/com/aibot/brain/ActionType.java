package com.aibot.brain;

/** Discrete action space the network chooses from and that player behavior is labeled into. */
public enum ActionType {
    IDLE,
    MOVE_FORWARD,
    MOVE_BACKWARD,
    STRAFE_LEFT,
    STRAFE_RIGHT,
    JUMP,
    MINE_BLOCK,
    PLACE_BLOCK,
    ATTACK,
    SPRINT_FORWARD;

    public static final ActionType[] VALUES = values();
}
