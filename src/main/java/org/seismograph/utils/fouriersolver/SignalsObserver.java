package org.seismograph.utils.fouriersolver;

import java.util.List;

public interface SignalsObserver {
    void accepting(List<FourierSeriesComputer.SampledSignal> signals);
}
