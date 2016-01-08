/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.layers.recurrent;

import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.GravesBidirectionalLSTMParamInitializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.util.Map;

/**
 * LSTM layer implementation.
 * Based on Graves: Supervised Sequence Labelling with Recurrent Neural Networks
 * http://www.cs.toronto.edu/~graves/phd.pdf
 * See also for full/vectorized equations (and a comparison to other LSTM variants):
 * Greff et al. 2015, "LSTM: A Search Space Odyssey", pg11. This is the "vanilla" variant in said paper
 * http://arxiv.org/pdf/1503.04069.pdf
 *
 * @author Alex Black
 * @author Benjamin Joseph
 */
public class GravesBidirectionalLSTM extends BaseRecurrentLayer<org.deeplearning4j.nn.conf.layers.GravesLSTM> {
    public static final String STATE_KEY_PREV_ACTIVATION_FORWARDS = "prevActForwards";
    public static final String STATE_KEY_PREV_MEMCELL_FORWARDS = "prevMemForwards";
    public static final String STATE_KEY_PREV_ACTIVATION_BACKWARDS = "prevActBackwards";
    public static final String STATE_KEY_PREV_MEMCELL_BACKWARDS = "prevMemBackwards";

    public GravesBidirectionalLSTM(NeuralNetConfiguration conf) {
        super(conf);
    }

    public GravesBidirectionalLSTM(NeuralNetConfiguration conf, INDArray input) {
        super(conf, input);
    }

    @Override
    public Gradient gradient() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Gradient calcGradient(Gradient layerError, INDArray activation) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon) {
        return backpropGradientHelper(epsilon, false, -1);
    }

    @Override
    public Pair<Gradient, INDArray> tbpttBackpropGradient(INDArray epsilon, int tbpttBackwardLength) {
        return backpropGradientHelper(epsilon, true, tbpttBackwardLength);
    }


    private Pair<Gradient, INDArray> backpropGradientHelper(final INDArray epsilon,final boolean truncatedBPTT,final int tbpttBackwardLength) {



        //First: Do forward pass to get gate activations, zs etc.
        FwdPassReturn fwdPass;
        if (truncatedBPTT) {
            fwdPass = activateHelperDirectional(true, stateMap.get(STATE_KEY_PREV_ACTIVATION_FORWARDS), stateMap.get(STATE_KEY_PREV_MEMCELL_FORWARDS), true,true);
            //Store last time step of output activations and memory cell state in tBpttStateMap
            tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION_FORWARDS, fwdPass.lastAct);
            tBpttStateMap.put(STATE_KEY_PREV_MEMCELL_FORWARDS, fwdPass.lastMemCell);
        } else {
            fwdPass = activateHelperDirectional(true, null, null, true,true);
        }

        final Pair<Gradient, INDArray> forwardsGradient = LSTMHelpers.backpropGradientHelper(
                this.conf,
                this.input,
                getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_FORWARDS),
                getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS),
                epsilon,
                truncatedBPTT,
                tbpttBackwardLength,
                fwdPass,
                true,
                GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS,
                GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_FORWARDS,
                GravesBidirectionalLSTMParamInitializer.BIAS_KEY_FORWARDS);


        FwdPassReturn backPass;
        if (truncatedBPTT) {
            backPass = activateHelperDirectional(true, stateMap.get(STATE_KEY_PREV_ACTIVATION_BACKWARDS), stateMap.get(STATE_KEY_PREV_MEMCELL_BACKWARDS), true,false);
            //Store last time step of output activations and memory cell state in tBpttStateMap
            tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION_BACKWARDS, fwdPass.lastAct);
            tBpttStateMap.put(STATE_KEY_PREV_MEMCELL_BACKWARDS, fwdPass.lastMemCell);
        } else {
            backPass = activateHelperDirectional(true, null, null, true,false);
        }

        final Pair<Gradient, INDArray> backwardsGradient = LSTMHelpers.backpropGradientHelper(
                this.conf,
                this.input,
                getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_BACKWARDS),
                getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS),
                epsilon,
                truncatedBPTT,
                tbpttBackwardLength,
                backPass,
                false,
                GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS,
                GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_BACKWARDS,
                GravesBidirectionalLSTMParamInitializer.BIAS_KEY_BACKWARDS);


        //merge the gradient, which is key value pair of String,INDArray
        //the keys for forwards and backwards should be different

        final Gradient combinedGradient = new DefaultGradient();

        for (Map.Entry<String,INDArray> entry : forwardsGradient.getFirst().gradientForVariable().entrySet()) {
            combinedGradient.setGradientFor(entry.getKey(),entry.getValue());
        }

        for (Map.Entry<String,INDArray> entry : backwardsGradient.getFirst().gradientForVariable().entrySet()) {
            combinedGradient.setGradientFor(entry.getKey(),entry.getValue());
        }

        final INDArray forwardEpsilon = forwardsGradient.getSecond();
        final INDArray backwardsEpsilon = backwardsGradient.getSecond();
        final INDArray combinedEpsilon = forwardEpsilon.add(backwardsEpsilon);

        //sum the errors that were back-propagated
        return  new Pair<>(combinedGradient,combinedEpsilon );

    }



    @Override
    public INDArray preOutput(INDArray x) {
        return activate(x, true);
    }

    @Override
    public INDArray preOutput(INDArray x, boolean training) {
        return activate(x, training);
    }

    @Override
    public INDArray activate(INDArray input, boolean training) {
        setInput(input);
        return activateOutput(training, false);
    }

    @Override
    public INDArray activate(INDArray input) {
        setInput(input);
        return activateOutput(true, false);
    }

    @Override
    public INDArray activate(boolean training) {
        return activateOutput(training, false);
    }

    @Override
    public INDArray activate() {

        return activateOutput(false, false);
    }

    private INDArray activateOutput(final boolean training, boolean forBackprop) {


        final FwdPassReturn forwardsEval = LSTMHelpers.activateHelper(
                this,
                this.conf,
                this.input,
                getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_FORWARDS),
                getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS),
                getParam(GravesBidirectionalLSTMParamInitializer.BIAS_KEY_FORWARDS),
                training,null,null,forBackprop,true,
                GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS);

        final FwdPassReturn backwardsEval = LSTMHelpers.activateHelper(
                this,
                this.conf,
                this.input,
                getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_BACKWARDS),
                getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS),
                getParam(GravesBidirectionalLSTMParamInitializer.BIAS_KEY_BACKWARDS),
                training,null,null,forBackprop,false,
                GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS);


        //sum outputs
        final INDArray fwdOutput = forwardsEval.fwdPassOutput;
        final INDArray backOutput = backwardsEval.fwdPassOutput;
        final INDArray totalOutput = fwdOutput.add(backOutput);

        return totalOutput;
    }

    private FwdPassReturn activateHelperDirectional(final boolean training,
                                         final INDArray prevOutputActivations,
                                         final INDArray prevMemCellState,
                                         boolean forBackprop,
                                         boolean forwards) {

        String recurrentKey = GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_FORWARDS;
        String inputKey = GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS;
        String biasKey = GravesBidirectionalLSTMParamInitializer.BIAS_KEY_FORWARDS;

        if (!forwards) {
            recurrentKey = GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_BACKWARDS;
            inputKey = GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS;
            biasKey = GravesBidirectionalLSTMParamInitializer.BIAS_KEY_BACKWARDS;
        }

        return LSTMHelpers.activateHelper(
                this,
                this.conf,
                this.input,
                getParam(recurrentKey),
                getParam(inputKey),
                getParam(biasKey),
                training,
                prevOutputActivations,
                prevMemCellState,
                forBackprop,
                forwards,
                inputKey);

    }

    @Override
    public INDArray activationMean() {
        return activate();
    }

    @Override
    public Type type() {
        return Type.RECURRENT;
    }

    @Override
    public Layer transpose() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public double calcL2() {
        if (!conf.isUseRegularization() || conf.getLayer().getL2() <= 0.0) return 0.0;
        double l2 = Transforms.pow(getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_FORWARDS), 2).sum(Integer.MAX_VALUE).getDouble(0)
                + Transforms.pow(getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS), 2).sum(Integer.MAX_VALUE).getDouble(0)
                + Transforms.pow(getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_BACKWARDS), 2).sum(Integer.MAX_VALUE).getDouble(0)
                + Transforms.pow(getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS), 2).sum(Integer.MAX_VALUE).getDouble(0);

        return 0.5 * conf.getLayer().getL2() * l2;
    }



    @Override
    public double calcL1() {
        if (!conf.isUseRegularization() || conf.getLayer().getL1() <= 0.0) return 0.0;
        double l1 = Transforms.abs(getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_FORWARDS)).sum(Integer.MAX_VALUE).getDouble(0)
                + Transforms.abs(getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_FORWARDS)).sum(Integer.MAX_VALUE).getDouble(0)
        + Transforms.abs(getParam(GravesBidirectionalLSTMParamInitializer.RECURRENT_WEIGHT_KEY_BACKWARDS)).sum(Integer.MAX_VALUE).getDouble(0)
        + Transforms.abs(getParam(GravesBidirectionalLSTMParamInitializer.INPUT_WEIGHT_KEY_BACKWARDS)).sum(Integer.MAX_VALUE).getDouble(0);

        return conf.getLayer().getL1() * l1;
    }

    @Override
    public INDArray rnnTimeStep(INDArray input) {
        throw new UnsupportedOperationException("you can not time step a bidirectional RNN, it has to run on a batch of data all at once");
    }



    @Override
    public INDArray rnnActivateUsingStoredState(INDArray input, boolean training, boolean storeLastForTBPTT) {
        setInput(input);
        final FwdPassReturn fwdPass = activateHelperDirectional(training, stateMap.get(STATE_KEY_PREV_ACTIVATION_FORWARDS), stateMap.get(STATE_KEY_PREV_MEMCELL_FORWARDS), false,true);
        final INDArray outActForwards = fwdPass.fwdPassOutput;
        if (storeLastForTBPTT) {
            //Store last time step of output activations and memory cell state in tBpttStateMap
            tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION_FORWARDS, fwdPass.lastAct);
            tBpttStateMap.put(STATE_KEY_PREV_MEMCELL_FORWARDS, fwdPass.lastMemCell);
        }

        final FwdPassReturn backPass = activateHelperDirectional(training, stateMap.get(STATE_KEY_PREV_ACTIVATION_BACKWARDS), stateMap.get(STATE_KEY_PREV_MEMCELL_BACKWARDS), false,false);
        final INDArray outActBackwards = backPass.fwdPassOutput;
        if (storeLastForTBPTT) {
            //Store last time step of output activations and memory cell state in tBpttStateMap
            tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION_BACKWARDS, backPass.lastAct);
            tBpttStateMap.put(STATE_KEY_PREV_MEMCELL_BACKWARDS, backPass.lastMemCell);
        }

        return outActForwards.add(outActBackwards);
    }
}
