package com.aibot.brain;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Capped ring buffer of (state, action) samples. Most come from real players;
 * a bounded slice can come from the bot's own actions when nobody's online to
 * observe, so there's always something to train on. Self-generated samples are
 * capped as a fraction of the dataset - without a real human to imitate there's
 * no ground truth, so letting them dominate would just make the network more
 * confident in whatever it already does instead of actually improving it.
 */
public class BrainDataset {

    private static final int MAX_SAMPLES = 20000;
    private static final double MAX_SELF_GENERATED_FRACTION = 0.15;

    // Bumped whenever the on-disk sample format changes. A file written by an
    // older/newer format gets discarded instead of being misread byte-by-byte,
    // which otherwise silently desyncs the stream and produces corrupt samples.
    private static final int FORMAT_MAGIC = 0x41494231; // "AIB1"

    private final LinkedList<TrainingSample> samples = new LinkedList<TrainingSample>();
    private final Random random = new Random();
    private int selfGeneratedCount = 0;

    public synchronized boolean add(TrainingSample sample) {
        if (sample.state.length != StateEncoder.STATE_SIZE) {
            return false;
        }

        if (sample.selfGenerated) {
            double fractionIfAdded = (selfGeneratedCount + 1.0) / (samples.size() + 1.0);
            if (fractionIfAdded > MAX_SELF_GENERATED_FRACTION) {
                return false;
            }
        }

        samples.addLast(sample);
        if (sample.selfGenerated) {
            selfGeneratedCount++;
        }

        while (samples.size() > MAX_SAMPLES) {
            TrainingSample removed = samples.removeFirst();
            if (removed.selfGenerated) {
                selfGeneratedCount--;
            }
        }
        return true;
    }

    public synchronized List<TrainingSample> sampleBatch(int batchSize) {
        List<TrainingSample> pool = new ArrayList<TrainingSample>(samples);
        List<TrainingSample> batch = new ArrayList<TrainingSample>(Math.min(batchSize, pool.size()));
        for (int i = 0; i < batchSize && !pool.isEmpty(); i++) {
            int idx = random.nextInt(pool.size());
            batch.add(pool.remove(idx));
        }
        return batch;
    }

    /** Read-only page over the stored samples, for exporting via the API. Doesn't consume/remove anything. */
    public synchronized List<TrainingSample> getSamples(int offset, int limit) {
        List<TrainingSample> result = new ArrayList<TrainingSample>();
        int i = 0;
        for (TrainingSample s : samples) {
            if (result.size() >= limit) break;
            if (i >= offset) {
                result.add(s);
            }
            i++;
        }
        return result;
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized int selfGeneratedSize() {
        return selfGeneratedCount;
    }

    public synchronized void save(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT_MAGIC);
        out.writeInt(samples.size());
        for (TrainingSample s : samples) {
            out.writeInt(s.state.length);
            for (double v : s.state) {
                out.writeDouble(v);
            }
            out.writeInt(s.actionIndex);
            out.writeBoolean(s.selfGenerated);
        }
    }

    public synchronized void load(DataInputStream in) throws IOException {
        samples.clear();
        selfGeneratedCount = 0;

        int magic = in.readInt();
        if (magic != FORMAT_MAGIC) {
            System.out.println("[aibot] Saved training data is in an old/incompatible format - starting with an empty dataset instead of risking corrupt reads.");
            return;
        }

        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int len = in.readInt();
            double[] state = new double[len];
            for (int j = 0; j < len; j++) {
                state[j] = in.readDouble();
            }
            int action = in.readInt();
            boolean selfGenerated = in.readBoolean();

            if (len != StateEncoder.STATE_SIZE) {
                continue;
            }
            samples.addLast(new TrainingSample(state, action, selfGenerated));
            if (selfGenerated) {
                selfGeneratedCount++;
            }
        }
    }
}
