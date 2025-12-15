package org.seismograph.utils.fouriersolver;

import java.util.List;

public interface SignalSubjectLowLinked {
    SignalSubjectLowLinked notifySubscriber(List<FourierSeriesComputer.SampledSignal> ssList);
}