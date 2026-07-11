package com.aibot.brain;

import com.aibot.web.ErrorLog;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Owns the single shared neural network and its training data, and drives
 * periodic backpropagation training on the server thread. Persisted to the
 * world save folder so the brain survives server restarts.
 */
public class BrainManager {

    private static final int[] TOPOLOGY = {StateEncoder.STATE_SIZE, 24, 16, ActionType.VALUES.length};
    private static final double LEARNING_RATE = 0.05;
    private static final int TRAIN_INTERVAL_TICKS = 20;
    private static final int BATCH_SIZE = 16;

    /** Previously brain.dat/samples.dat only got written on a clean server stop
     * (or a manual /brain save) - an ungraceful stop (crash, kill -9, power loss)
     * meant every training step since the last clean shutdown was silently lost.
     * Autosaving periodically means at most this many ticks of training can ever
     * be lost. 5 minutes at 20 ticks/sec. */
    private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60 * 5;

    public static final BrainManager instance = new BrainManager();

    private NeuralNetwork network = new NeuralNetwork(TOPOLOGY, LEARNING_RATE);
    private final BrainDataset dataset = new BrainDataset();

    private int tickCounter = 0;
    private int autosaveTickCounter = 0;
    private long totalTrainingSteps = 0;
    private double lastAverageLoss = 0.0;

    private BrainManager() {
    }

    public NeuralNetwork getNetwork() {
        return network;
    }

    public BrainDataset getDataset() {
        return dataset;
    }

    public long getTotalTrainingSteps() {
        return totalTrainingSteps;
    }

    public double getLastAverageLoss() {
        return lastAverageLoss;
    }

    public static double getLearningRate() {
        return LEARNING_RATE;
    }

    public void recordHumanSample(double[] state, ActionType action) {
        dataset.add(new TrainingSample(state, action.ordinal(), false));
    }

    public void recordSelfSample(double[] state, ActionType action) {
        dataset.add(new TrainingSample(state, action.ordinal(), true));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter >= TRAIN_INTERVAL_TICKS) {
            tickCounter = 0;
            trainOnce();
        }

        autosaveTickCounter++;
        if (autosaveTickCounter >= AUTOSAVE_INTERVAL_TICKS) {
            autosaveTickCounter = 0;
            save();
        }
    }

    private void trainOnce() {
        List<TrainingSample> batch = dataset.sampleBatch(BATCH_SIZE);
        if (batch.isEmpty()) return;

        // A training hiccup (bad sample, corrupted save data, future bug) must never be able
        // to take the whole server down - it runs on the same thread as the game tick loop.
        try {
            double totalLoss = 0.0;
            int trained = 0;
            for (TrainingSample sample : batch) {
                if (sample.state.length != StateEncoder.STATE_SIZE) {
                    continue;
                }
                totalLoss += network.loss(sample.state, sample.actionIndex);
                network.trainStep(sample.state, sample.actionIndex);
                trained++;
            }
            if (trained > 0) {
                lastAverageLoss = totalLoss / trained;
                totalTrainingSteps += trained;
            }
        } catch (Exception e) {
            ErrorLog.record("BrainManager.trainOnce", e);
        }
    }

    public void onServerStarting() {
        load();
    }

    private File getSaveDir() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        File dir = server != null ? server.getFile("aibot") : new File("aibot");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public synchronized void save() {
        File dir = getSaveDir();

        DataOutputStream netOut = null;
        try {
            netOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, "brain.dat"))));
            network.save(netOut);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeQuietly(netOut);
        }

        DataOutputStream dataOut = null;
        try {
            dataOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, "samples.dat"))));
            dataset.save(dataOut);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeQuietly(dataOut);
        }
    }

    public synchronized void load() {
        File dir = getSaveDir();

        File netFile = new File(dir, "brain.dat");
        if (netFile.exists()) {
            DataInputStream in = null;
            try {
                in = new DataInputStream(new BufferedInputStream(new FileInputStream(netFile)));
                NeuralNetwork loaded = NeuralNetwork.load(in, LEARNING_RATE);
                // NeuralNetwork.load() trusts whatever shape is embedded in the file, not this
                // build's TOPOLOGY constant - fine as long as they match, but if a code change
                // altered STATE_SIZE or the action count (e.g. adding an action), a saved network
                // built to the old shape would silently stay stuck at the old shape forever (any
                // new state/action index would just never be reachable), or worse, mismatch some
                // other assumption. Same category of bug as the old samples.dat FORMAT_MAGIC
                // incident - discard and start fresh on any shape mismatch instead of trusting it.
                if (java.util.Arrays.equals(loaded.getLayerSizes(), TOPOLOGY)) {
                    network = loaded;
                } else {
                    System.out.println("[aibot] Saved brain.dat topology " + java.util.Arrays.toString(loaded.getLayerSizes())
                            + " doesn't match current " + java.util.Arrays.toString(TOPOLOGY)
                            + " - starting a fresh network (existing training samples are unaffected and will retrain it).");
                    network = new NeuralNetwork(TOPOLOGY, LEARNING_RATE);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                closeQuietly(in);
            }
        }

        File sampleFile = new File(dir, "samples.dat");
        if (sampleFile.exists()) {
            DataInputStream in = null;
            try {
                in = new DataInputStream(new BufferedInputStream(new FileInputStream(sampleFile)));
                dataset.load(in);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                closeQuietly(in);
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
