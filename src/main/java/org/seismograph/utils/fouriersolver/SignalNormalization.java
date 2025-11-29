package org.seismograph.utils.fouriersolver;

import java.util.List;

public class SignalNormalization implements SignalSubjectLowLinked {
    private NormalizatorSamples obss = null;

    public SignalNormalization(SignalsObserver o) {
        if (o instanceof NormalizatorSamples) {
            this.obss = (NormalizatorSamples) o;
        } else {
            System.err.println("=-> ОШИБКА SignalNormalization(): объект не является экземпляром нормализатора!");
        }
    }

    @Override public void notifySubscriber(List<FourierSeriesComputer.SampledSignal> signalList) {
        if (obss != null) {
            obss.accepting(signalList);
        }
    }

    public double[] normalizeOpenly() {
        double[] normalized = new double[0];

        if (obss != null) {
            normalized = obss.normalize();
        }

        return normalized;
    }
}
