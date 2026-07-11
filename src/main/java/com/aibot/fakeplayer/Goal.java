package com.aibot.fakeplayer;

/** One queued to-do list entry: gather this many units of this resource. */
public class Goal {
    public final GoalType type;
    public final int targetAmount;

    public Goal(GoalType type, int targetAmount) {
        this.type = type;
        this.targetAmount = targetAmount;
    }

    @Override
    public String toString() {
        return type.label + " (" + targetAmount + " " + type.unit + ")";
    }
}
