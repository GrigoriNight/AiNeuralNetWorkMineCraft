package com.aibot.brain;

public class TrainingSample {
    public final double[] state;
    public final int actionIndex;
    public final boolean selfGenerated;

    public TrainingSample(double[] state, int actionIndex, boolean selfGenerated) {
        this.state = state;
        this.actionIndex = actionIndex;
        this.selfGenerated = selfGenerated;
    }
}
