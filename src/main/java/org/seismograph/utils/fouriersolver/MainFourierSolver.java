package org.seismograph.utils.fouriersolver;

import org.seismograph.utils.dataonly.ReducedComplex;

public class MainFourierSolver {
    // Сиглетный класс, хранящий ссылку на экземпляр.
    private static volatile MainFourierSolver solver = new MainFourierSolver();

    private MainFourierSolver() {}

    public static synchronized MainFourierSolver getInstance() { return solver; }

    /* Само высчитывание, длина x ДОЛЖНА БЫТЬ ЧЁТНОЙ! */
    private static ReducedComplex[] fft(double[] x) {
        long N = x.length;

        if (N == 1) {
            return new ReducedComplex[]{ new ReducedComplex(x[0], 0) };
        }

        double[] even = new double[(int) N / 2];
        double[] odd = new double[(int) N / 2];
        for (int i = 0; i < N / 2; ++i) {
            even[i] = x[2 * i];
            odd[i] = x[2 * i + 1];
        }

        ReducedComplex[] FEven = fft(even);
        ReducedComplex[] FOdd = fft(odd);

        ReducedComplex[] X = new ReducedComplex[(int)N];
        for (int n = 0; n < N / 2; ++n) {
            double angle = -2 * Math.PI * n / N;
            ReducedComplex twiddle = new ReducedComplex(Math.cos(angle), Math.sin(angle));
            ReducedComplex t = twiddle.mul(FOdd[n]);

            X[n] = FEven[n].add(t);
            X[n + (int)N / 2] = FEven[n].sub(t);
        }
        return X;
    }

    public static ReducedComplex[] analyze(double[] signal) {
        int correctLength = 1;
        while (correctLength < signal.length) {
            correctLength *= 2;
        }

        double[] padded = new double[correctLength];

        System.arraycopy(signal, 0, padded, 0, signal.length);

        return fft(padded);
    }
}