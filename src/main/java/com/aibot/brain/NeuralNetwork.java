package com.aibot.brain;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Plain-Java feedforward neural network (multilayer perceptron) trained with
 * backpropagation. ReLU hidden layers, softmax output, cross-entropy loss.
 * No external dependencies so it can be bundled straight into the mod jar.
 *
 * Momentum SGD, not plain SGD - velocity terms damp out the noisy zig-zagging
 * that comes from small (16-sample) batches, without needing a bigger batch
 * size (which would mean fewer, coarser updates per second of gameplay).
 * Velocity is intentionally NOT persisted across save/load - it's just an
 * in-session smoothing aid, not something that needs to survive a restart,
 * and starting fresh is always safe (worst case, momentum "ramps up" again
 * over the first few dozen steps).
 */
public class NeuralNetwork {

    private static final Random RANDOM = new Random();
    private static final double MOMENTUM = 0.9;

    private final int[] layerSizes;
    private final double[][][] weights; // [layer][toNeuron][fromNeuron]
    private final double[][] biases;    // [layer][toNeuron]
    private final double[][][] velocityWeights;
    private final double[][] velocityBiases;
    private double learningRate;

    public NeuralNetwork(int[] layerSizes, double learningRate) {
        this.layerSizes = layerSizes;
        this.learningRate = learningRate;

        int numLayers = layerSizes.length - 1;
        weights = new double[numLayers][][];
        biases = new double[numLayers][];
        velocityWeights = new double[numLayers][][];
        velocityBiases = new double[numLayers][];

        for (int l = 0; l < numLayers; l++) {
            int in = layerSizes[l];
            int out = layerSizes[l + 1];
            weights[l] = new double[out][in];
            biases[l] = new double[out];
            velocityWeights[l] = new double[out][in];
            velocityBiases[l] = new double[out];
            double scale = Math.sqrt(2.0 / in);
            for (int o = 0; o < out; o++) {
                for (int i = 0; i < in; i++) {
                    weights[l][o][i] = (RANDOM.nextDouble() * 2.0 - 1.0) * scale;
                }
                biases[l][o] = 0.0;
            }
        }
    }

    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }

    public double getLearningRate() {
        return learningRate;
    }

    private double[][] forwardAll(double[] input) {
        double[][] activations = new double[layerSizes.length][];
        activations[0] = input;
        double[] current = input;

        for (int l = 0; l < weights.length; l++) {
            boolean isOutput = (l == weights.length - 1);
            double[] z = new double[layerSizes[l + 1]];
            for (int o = 0; o < z.length; o++) {
                double sum = biases[l][o];
                for (int i = 0; i < current.length; i++) {
                    sum += weights[l][o][i] * current[i];
                }
                z[o] = sum;
            }

            double[] activated;
            if (isOutput) {
                activated = softmax(z);
            } else {
                activated = new double[z.length];
                for (int o = 0; o < z.length; o++) {
                    activated[o] = relu(z[o]);
                }
            }
            activations[l + 1] = activated;
            current = activated;
        }
        return activations;
    }

    public int[] getLayerSizes() {
        return layerSizes;
    }

    public double[] predict(double[] input) {
        double[][] all = forwardAll(input);
        return all[all.length - 1];
    }

    public int predictAction(double[] input) {
        double[] out = predict(input);
        int best = 0;
        for (int i = 1; i < out.length; i++) {
            if (out[i] > out[best]) {
                best = i;
            }
        }
        return best;
    }

    /** One backpropagation step (momentum SGD) toward the target class. */
    public void trainStep(double[] input, int targetIndex) {
        double[][] activations = forwardAll(input);
        int numLayers = weights.length;
        double[][] deltas = new double[numLayers][];

        double[] output = activations[numLayers];
        double[] outDelta = new double[output.length];
        for (int i = 0; i < output.length; i++) {
            outDelta[i] = output[i] - (i == targetIndex ? 1.0 : 0.0);
        }
        deltas[numLayers - 1] = outDelta;

        for (int l = numLayers - 2; l >= 0; l--) {
            double[] nextDelta = deltas[l + 1];
            double[][] nextWeights = weights[l + 1];
            double[] activated = activations[l + 1];
            double[] delta = new double[layerSizes[l + 1]];
            for (int i = 0; i < delta.length; i++) {
                double sum = 0.0;
                for (int o = 0; o < nextDelta.length; o++) {
                    sum += nextWeights[o][i] * nextDelta[o];
                }
                delta[i] = sum * reluDerivative(activated[i]);
            }
            deltas[l] = delta;
        }

        for (int l = 0; l < numLayers; l++) {
            double[] prevActivation = activations[l];
            double[] delta = deltas[l];
            for (int o = 0; o < delta.length; o++) {
                for (int i = 0; i < prevActivation.length; i++) {
                    double gradient = delta[o] * prevActivation[i];
                    double v = MOMENTUM * velocityWeights[l][o][i] - learningRate * gradient;
                    velocityWeights[l][o][i] = v;
                    weights[l][o][i] += v;
                }
                double biasV = MOMENTUM * velocityBiases[l][o] - learningRate * delta[o];
                velocityBiases[l][o] = biasV;
                biases[l][o] += biasV;
            }
        }
    }

    public double loss(double[] input, int targetIndex) {
        double[] out = predict(input);
        double p = Math.max(out[targetIndex], 1e-9);
        return -Math.log(p);
    }

    private static double relu(double x) {
        return x > 0 ? x : 0.0;
    }

    private static double reluDerivative(double activatedValue) {
        return activatedValue > 0 ? 1.0 : 0.0;
    }

    private static double[] softmax(double[] z) {
        double max = z[0];
        for (double v : z) {
            if (v > max) max = v;
        }
        double sum = 0.0;
        double[] out = new double[z.length];
        for (int i = 0; i < z.length; i++) {
            out[i] = Math.exp(z[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    public void save(DataOutputStream out) throws IOException {
        out.writeInt(layerSizes.length);
        for (int size : layerSizes) {
            out.writeInt(size);
        }
        for (double[][] layer : weights) {
            for (double[] row : layer) {
                for (double w : row) {
                    out.writeDouble(w);
                }
            }
        }
        for (double[] layerBias : biases) {
            for (double b : layerBias) {
                out.writeDouble(b);
            }
        }
    }

    public static NeuralNetwork load(DataInputStream in, double learningRate) throws IOException {
        int numLayerSizes = in.readInt();
        int[] sizes = new int[numLayerSizes];
        for (int i = 0; i < numLayerSizes; i++) {
            sizes[i] = in.readInt();
        }
        NeuralNetwork net = new NeuralNetwork(sizes, learningRate);
        for (double[][] layer : net.weights) {
            for (int o = 0; o < layer.length; o++) {
                for (int i = 0; i < layer[o].length; i++) {
                    layer[o][i] = in.readDouble();
                }
            }
        }
        for (double[] layerBias : net.biases) {
            for (int o = 0; o < layerBias.length; o++) {
                layerBias[o] = in.readDouble();
            }
        }
        return net;
    }
}
