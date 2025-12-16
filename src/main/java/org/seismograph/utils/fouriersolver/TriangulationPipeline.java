package org.seismograph.utils.fouriersolver;

import java.util.*;
import static org.seismograph.utils.fouriersolver.TriangulationPipeline.FFTUtils.*;

/**
 * Самодостаточный модуль:
 * signal utils, STA/LTA picker,
 * cross-correlation delay, solver (Gauss-Newton).
 */
public class TriangulationPipeline {

    /**
     * Минималистичная и математически удобная обёртка для сейсмического сигнала,
     * с которой работает вся сложная логика в пайплайне.
     */
    public static class Signal {
        public final double[] samples;   // амплитуды (кадры)
        public final double fs;          // частота дискретизации (Hz)
        public final double startSec;    // время начала в секундах UTC (epoch seconds)

        public Signal(double[] samples, double fs, double startSec) {
            this.samples = samples;
            this.fs = fs;
            this.startSec = startSec;
        }

        public int length() { return samples.length; }

        /**
         * Вычисляет интервал между двумя соседними точка (сэмплами)
         * <br>Например, если fs = 100 Гц, то dt = 0.01 сек</br>
         * @return время в секундах
         */
        public double dt() { return 1.0 / fs; }

        /**
         * Вычисляет точное (абсолютное) время i-го сэмпла.
         * <br>Время начала + i * dt</br>
         * @param i конкретный сэмпл
         * @return время в секундах UTC
         */
        public double timeOfSample(int i) { return startSec + i * dt(); }
    }

    /**
     * Функция вычисляет простое среднее арифметическое значение всех
     * точек в сейсмическом сигнале и вычитает это среднее из каждой точки.
     * <br>DC-offset — удаление постоянной составляющей</br>
     * <br><b>Если не удалить это смещение, то кросс-корреляция и STA/LTA
     * будут видеть это смещение как "ложную энергию", что потенциально
     * портит точность детектирования.</b></br>
     * @param x входные данные об амплитуде
     * @return данные, прогнанные через DC-offset.
     */
    public static double[] demean(double[] x) {
        double mean = 0;
        for (double v : x) mean += v;
        mean /= x.length; // среднее арифметическое

        double[] out = new double[x.length];
        // out[i] = in[i] - mean(in)
        for (int i = 0; i < x.length; i++) out[i] = x[i] - mean;
        return out;
    }

    /**
     * Функция применения к сигналу взвешивающую функцию (окно Хэмминга).
     * <br>В методе происходит генерация массива-окна, где значения
     * плавно растут от 0.08 (по краю) до 1.0 (центр)</br>
     * <br><i>А в конце применяется окно к каждой точке сигнала</i></br>
     * @param x сигнал (обязательно БЕЗ СМЕЩЕНИЯ {@link TriangulationPipeline#demean(double[])})
     * @return данные, прогнанные через DC-offset (вначале) + окном Хэмминга
     */
    public static double[] applyHamming(double[] x) {
        int n = x.length;
        double[] w = new double[n];
        int M = n - 1;

        // генерация массива-окна
        for (int i = 0; i < n; i++)
            w[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / M);

        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = x[i] * w[i]; // применение окна
        return out;
    }

    /**
     * Реализация STA/LTA (Short-Term Average / Long-Term Average) пикера, самого распространённого
     * и надёжного алгоритма для поиска внезапных изменений в сеймическом шуме.
     * <br>Суть такова: <i>если энергия в коротком окне (STA) становится сильно больше,
     * чем энергия в длинном окне (LTA), то это, скорее всего, волна</i></br>
     * @param x готовый к обработке сигнал {@link TriangulationPipeline#demean(double[])} и
     *          {@link TriangulationPipeline#applyHamming(double[])}
     * @param fs входная частота дискретизации
     * @param staSec длина короткого окна: ловит резкий всплеск энергии (P-волна).
     * @param ltaSec длина длинного окна: определяет фоновый шум.
     * @param thrOn порог срабатывания (если STA в 3.5 раза больше LTA, то тревога).
     * @param thrOff порог отбоя (когда событие закончилось, досрочный выход)
     * @return Tp в сэмплах, который следует перевести в абсолютное время
     */
    public static int staLtaPick(double[] x, double fs, double staSec, double ltaSec,
                                 double thrOn, double thrOff) {
        int ns = x.length;

        // Удобные для человека секунды в сэмплы
        int staN = Math.max(1, (int)Math.round(staSec * fs)); // Длина STA в сэмплах
        int ltaN = Math.max(1, (int)Math.round(ltaSec * fs)); // Длина LTA в сэмплах

        if (ltaN <= staN) ltaN = staN * 2;
        /*
            Если fs = 100 Гц, staSec = 0.5 сек,
            то staN = 50 СЭМПЛОВ
         */

        // Приём для избежания пересчёта энергии окна на каждой точке
        double[] sq = new double[ns];

        // Энергия сигнала пропорциональна сумме квадратов амплитуд.
        // Вместо суммирования амплитуд, суммируются квадраты амплитуд, что лучше отражает энергию.
        for (int i = 0; i < ns; i++) sq[i] = x[i] * x[i];

        // pref хранит накопленную сумму квадратов с начала сигнала до текущей точки.
        // Позволяет МГНОВЕННО получить сумму квадратов в любом окне [A, B]
        double[] pref = new double[ns + 1];
        pref[0] = 0;
        for (int i = 0; i < ns; i++) pref[i + 1] = pref[i] + sq[i];

        boolean triggered = false; // возбудимое состояние
        int triggerSample = -1;

        for (int i = ltaN; i < ns - 1; i++) {
            // Подсчёт энергии LTA (Фоновый шум)
            double ltaEnergy = (pref[i] - pref[i - ltaN]) / ltaN;
            // Подсчёт энергии STA (Текущая)
            double staEnergy = (pref[i + 1] - pref[Math.max(0, i + 1 - staN)]) / staN;

            // Отношение (избегаем деление на ноль)
            double ratio = (ltaEnergy <= 0) ? Double.POSITIVE_INFINITY : (staEnergy / ltaEnergy);
            // Пока идёт шум, ratio примерно = 1
            // Когда волна подходит, то STA взлетает, а LTA ещё спит, ratio становится большим
            // (согласно порогу)

            if (!triggered && ratio >= thrOn) {
                triggered = true;
                // Мы уже внутри волны, алгоритм делает откат назад на длину STA-окна
                triggerSample = i - staN; // примерный индекс начала P-волны.

                if (triggerSample < 0) triggerSample = 0;
                break;
            } else if (triggered && ratio <= thrOff) {
                // release
                break;
            }
        }
        return triggerSample; // И есть тот самый Tp
    }

    // ---------------------------
    // Cross-correlation via FFT (uses recursive FFT)
    // Returns corr array (length n+m-1) and helper to get best lag
    // ---------------------------

    /**
     * Класс-реализация-адаптация <b>комплексного числа</b> {@code z = a + bi}.
     * В физике и инженерии, где имеют дело с волнами (например, синусоиды),
     * комплексные числа незаменимы.
     */
    public record ComplexNumber(double re, double im) {

        // Сложение комплексных чисел -> новое число
        public ComplexNumber add(ComplexNumber o){ return new ComplexNumber(re+o.re, im+o.im); }
        // Вычитание комплексных -> новое число
        public ComplexNumber sub(ComplexNumber o){ return new ComplexNumber(re-o.re, im-o.im); }
        // Умножение комплексных -> новое число
        public ComplexNumber mul(ComplexNumber o){ return new ComplexNumber(re*o.re - im*o.im, re*o.im + im*o.re); }
        // Комплескное сопряжение -> новое число
        public ComplexNumber conj(){ return new ComplexNumber(re, -im); }
        // Масштабирование комплексного числа -> новое число
        public ComplexNumber scale(double s){ return new ComplexNumber(re*s, im*s); }
    }

    /**
     * Алгоритм Быстрого Преобразования Фурье работает только в том случае,
     * если длина входного сигнала N является степенью двойки.
     * @param v длина сигнала
     * @return ближайшее БОЛЬШЕЕ число или равное, которое — степень двойки
     */
    private static int nextPow2(int v) {
        int n = 1; while(n < v) n <<= 1; return n;
    }

    /**
     * Сборник методов, которые используют быстрое преобразование Фурье.
     * Объединено исключительно в целях определённости.
     */
    public static class FFTUtils {
        // Блокируем возможность создать экземпляр класса
        private FFTUtils() {}

        /**
         * Метод быстрого преобразования Фурье (алгоритм Кули-Тьюки), который сокращает
         * Фурье-преобразования с O(n^2) до O(NlogN)
         * @param x входной сигнал в комплексном представлении
         * @return преобразованный массив с комплексным представлением
         */
        static ComplexNumber[] fft(ComplexNumber[] x) {
            int N = x.length;
            if (N == 1) return new ComplexNumber[]{ x[0] };

            if ((N & (N - 1)) != 0) throw new IllegalArgumentException("N must be power of two");

            // Массив делится на две части: чётную и нечётную.
            ComplexNumber[] even = new ComplexNumber[N / 2];
            ComplexNumber[] odd = new ComplexNumber[N / 2];

            for (int i = 0; i < N / 2; i++) { even[i] = x[2 * i]; odd[i] = x[2 * i + 1]; }

            ComplexNumber[] Fe = fft(even); // Фурье-преобразование для ЧЁТНОЙ части
            ComplexNumber[] Fo = fft(odd);  // Фурье-преобразование для НЕЧЁТНОЙ части

            ComplexNumber[] X = new ComplexNumber[N]; // Финальный ответ, инкубирующийся из половинок

            for (int k = 0; k < N / 2; k++) {
                // Вычисление поворотного множителя в fft — комплексное число на единичной окружности
                double ang = -2.0 * Math.PI * k / N;
                ComplexNumber wk = new ComplexNumber(Math.cos(ang), Math.sin(ang));

                ComplexNumber t = wk.mul(Fo[k]);
                X[k] = Fe[k].add(t);
                X[k + N / 2] = Fe[k].sub(t);
            }
            return X;
        }

        /**
         * Обратное преобразование Фурье, которое возвращает преобразованный сигнал
         * обратно во временной домен.
         * @param X преобразованный сигнал
         * @return исходный
         */
        static ComplexNumber[] ifft(ComplexNumber[] X) {
            int N = X.length;

            // На старте — комплексное сопряжение
            ComplexNumber[] conjX = new ComplexNumber[N];
            for (int i = 0; i < N; i++) conjX[i] = X[i].conj();

            ComplexNumber[] fy = fft(conjX); // fft к сопряженному массиву

            ComplexNumber[] out = new ComplexNumber[N];
            for (int i = 0; i < N; i++) out[i] = fy[i].conj().scale(1.0 / N);
            return out;
        }

        /**
         * Метод комплексном прокладки выполняет две функции:
         * <p>
         *     1) Преобразование в комплексные числа, потому что FFT работает с комплексными.
         * <p>
         *     2) Дополнение нулями ({@link TriangulationPipeline#nextPow2(int)})
         * @param x входной сигнал
         * @param N количество сигналов
         * @return комплесное представление магнитуд
         */
        static ComplexNumber[] toComplexPadded(double[] x, int N) {
            ComplexNumber[] out = new ComplexNumber[N];
            for (int i = 0; i < N; i++) out[i] = new ComplexNumber(i < x.length ? x[i] : 0.0, 0.0);
            return out;
        }
    }

    /**
     * Реализация метода самой быстрой + эффективной кросс-корреляции* с использованием БПФ.
     * <p>
     *     <b>*Кросс-корреляция</b> — мера сходства двух сигналов как функции смещения
     *     одного относительно другого. Она показывает <i>насколько сэмплов нужно сдвинуть</i>
     *     сигнал В, чтобы он максимально совпал с сигналом А.
     * <br> (используется теорема о свёртке: CC(A, B) = IFFT(FFT(A) * conj(FFT(B))))
     * @param a первый сигнал
     * @param b второй сигнал
     * @return показательный массив амплитуд, говорящий о том «насколько один сигнал отстал от другого»
     */
    public static double[] crossCorrelation(double[] a, double[] b) {
        int n = a.length, m = b.length;
        int convLen = n + m - 1;
        int N = nextPow2(convLen);

        ComplexNumber[] A = fft(toComplexPadded(a, N));
        ComplexNumber[] B = fft(toComplexPadded(b, N));

        ComplexNumber[] C = new ComplexNumber[N];
        // Умножение в частотной области (A * conj(B))
        for (int i = 0; i < N; i++) C[i] = A[i].mul(B[i].conj());
        ComplexNumber[] corrC = ifft(C);

        // Итоговый массив корреляции...
        double[] corr = new double[convLen];
        for (int i = 0; i < convLen; i++) corr[i] = corrC[i].re; // пишем только действительную часть
        return corr;
    }


    /**
     * Метод параболического уточнения. Когда вычисляется массив кросс-корреляции,
     * то максимум этого массива даёт <b>целочисленный</b> сдвиг в сэмплах.
     * Но реальный сдвиг может быть МЕЖДУ сэмплами.
     * <p>
     *     Математическая функция берёт три точки вокруг максимума и аппроксимирует их
     *     параболой. Она вычисляет, где именно у этой параболы находится вершина.
     * </p>
     * @param ym1 точка перед предполагаемым максимумом
     * @param y0 предполагаемый максимум
     * @param yp1 точка после предполагаемого максимума
     * @return аппроксимированный максимум
     */
    public static double parabolicRefine(double ym1, double y0, double yp1) {
        double denominator = (ym1 - 2*y0 + yp1);
        if (Math.abs(denominator) < 1e-12) return 0.0;
        return 0.5 * (ym1 - yp1) / denominator;

        /*
            Это позволяет найти задержку с суб-сэмпловой точностью.
            Она становится выше, чем интервал дискретизации, что собственно делает
            локализацию точнее!
         */
    }

    /**
     * Метод финального расчёта задержки. Берёт два сигнала,
     * подчищает их ({@link TriangulationPipeline#demean(double[])}),
     * вычисляет кросс-корреляцию их ({@link TriangulationPipeline#crossCorrelation(double[], double[])}),
     * находит пик с суб-сэмпловой точностью ({@link TriangulationPipeline#parabolicRefine(double, double, double)})
     * и переводит в человеческие секунды.
     * @param a первый сигнал
     * @param b второй сигнал
     * @param fs интервал дискретизации (нужен для обратной конверсии)
     * @return чистые секунды
     */
    public static double estimateDelaySeconds(double[] a, double[] b, double fs) {
        // Чистка:
        double[] demeanedA = demean(a);
        double[] demeanedB = demean(b);

        // Вычисление корреляции:
        double[] corr = crossCorrelation(demeanedA, demeanedB);

        int zeroIndex = b.length - 1; // Индекс нулевой задержки
        int kMax = 0; double valMax = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < corr.length; k++) {
            if (corr[k] > valMax) {
                valMax = corr[k];
                kMax = k;
            }
        }

        // Параболическое уточнение:
        double sub = 0.0;
        if (kMax > 0 && kMax < corr.length - 1) sub = parabolicRefine(corr[kMax-1], corr[kMax], corr[kMax+1]);

        // Расчёт сдвига:
        double lagSamples = (kMax + sub) - zeroIndex;

        // Конверсия: сэмплы -> секунды
        return lagSamples / fs;
    }

    /*
        ╭──────────────────────────╮
        │ JAVAFX VISUALIZING       │
        ╰──────────────────────────╯
     */

    /**
     * Результат кросс-корреляции, включающий вычисленную задержку и сам массив CC.
     */
    public record CCTuple(double delaySec, double[] correlation) {}



    /*
        ╭──────────────────────────╮
        │ Localizer by slices      │
        ╰──────────────────────────╯
     */

    /**
     * Утилитный метод для безопасной обрезки массива (окна).
     * @param x Исходный массив
     * @param startIndex Индекс начала
     * @param length Желаемая длина окна
     * @return Обрезанный массив
     */
    public static double[] slice(double[] x, int startIndex, int length) {
        if (startIndex < 0) {
            length += startIndex; // Компенсируем отрицательный старт
            startIndex = 0;
        }
        if (startIndex + length > x.length) {
            length = x.length - startIndex;
        }
        if (length <= 0) {
            return new double[0];
        }

        double[] out = new double[length];
        System.arraycopy(x, startIndex, out, 0, length);
        return out;
    }

    public static class TDOALocalizer {

        public record Point(double x, double y) {}

        public static Point localize(
                List<Station> stations,
                double waveSpeed
        ) {
            if (stations.size() < 3) {
                throw new IllegalArgumentException("Нужно минимум 3 станции");
            }

            Station ref = stations.get(0);

            // Начальное приближение — центр масс станций
            double x = 0, y = 0;
            for (Station s : stations) {
                x += s.x();
                y += s.y();
            }
            x /= stations.size();
            y /= stations.size();

            final int MAX_ITER = 50;
            final double EPS = 1e-6;

            for (int iter = 0; iter < MAX_ITER; iter++) {

                int m = stations.size() - 1;

                double[][] J = new double[m][2];
                double[] r = new double[m];

                double dx0 = x - ref.x();
                double dy0 = y - ref.y();
                double d0 = Math.hypot(dx0, dy0);

                for (int i = 1; i < stations.size(); i++) {
                    Station s = stations.get(i);

                    double dx = x - s.x();
                    double dy = y - s.y();
                    double d = Math.hypot(dx, dy);

                    double dtObs = s.arrivalTime() - ref.arrivalTime();
                    double dtModel = (d - d0) / waveSpeed;

                    r[i - 1] = dtModel - dtObs;

                    // Якобиан
                    J[i - 1][0] =
                            (dx / d - dx0 / d0) / waveSpeed;
                    J[i - 1][1] =
                            (dy / d - dy0 / d0) / waveSpeed;
                }

                // Решаем (JᵀJ)Δ = -Jᵀr
                double[][] A = new double[2][2];
                double[] b = new double[2];

                for (int i = 0; i < m; i++) {
                    A[0][0] += J[i][0] * J[i][0];
                    A[0][1] += J[i][0] * J[i][1];
                    A[1][0] += J[i][1] * J[i][0];
                    A[1][1] += J[i][1] * J[i][1];

                    b[0] -= J[i][0] * r[i];
                    b[1] -= J[i][1] * r[i];
                }

                double det = A[0][0] * A[1][1] - A[0][1] * A[1][0];
                if (Math.abs(det) < 1e-12) {
                    throw new RuntimeException("Вырождение геометрии (det≈0)");
                }

                double dxStep =
                        ( b[0] * A[1][1] - b[1] * A[0][1] ) / det;
                double dyStep =
                        ( A[0][0] * b[1] - A[1][0] * b[0] ) / det;

                x += dxStep;
                y += dyStep;

                if (Math.hypot(dxStep, dyStep) < EPS) {
                    break;
                }
            }

            return new Point(x, y);
        }
    }

    /**
     * Контейнер данных, который собирает всё необходимое для решателя проблемы локализации.
     * @param x абсцисса
     * @param y ордината
     * @param arrivalTime время прихода сигнала
     */
    public record Station(double x, double y, double arrivalTime) {}

    // Запретка на создание экземпляра класс, потому что утилиты:
    private TriangulationPipeline() {}
}
