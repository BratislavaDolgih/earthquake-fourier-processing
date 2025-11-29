package org.seismograph.utils.fouriersolver;

import java.util.List;

public interface SignalSubjectLowLinked {
    void notifySubscriber(List<FourierSeriesComputer.SampledSignal> ssList);
}