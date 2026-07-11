package com.aibot.fakeplayer;

/** A task type a player can queue onto the bot's to-do list via /brain goal. */
public enum GoalType {
    WOOD("gather wood", "logs"),
    STONE("gather stone", "stone mined"),
    ORE("mine ore", "ore mined"),
    WOOL("get wool", "sheep sheared/killed"),
    FOOD("hunt food", "animals killed");

    public final String label;
    public final String unit;

    GoalType(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public static GoalType fromCommand(String s) {
        String lower = s.toLowerCase();
        if (lower.equals("wood")) return WOOD;
        if (lower.equals("stone")) return STONE;
        if (lower.equals("ore")) return ORE;
        if (lower.equals("wool")) return WOOL;
        if (lower.equals("food")) return FOOD;
        return null;
    }
}
