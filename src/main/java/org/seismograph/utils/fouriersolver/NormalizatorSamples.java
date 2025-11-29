package org.seismograph.utils.fouriersolver;

import java.util.*;
import static org.seismograph.utils.fouriersolver.FourierSeriesComputer.SampledSignal;

/**
 * Класс нормализатора сигнала — специальная утилита, позволяющая умерить скачки
 * в сигнале {@link SampledSignal}.
 * <p>(чтобы затолкнуть значения в ряд Фурье катастрофически необходимо, чтобы
 * данные были без резких скачков, потому что ряд будет работать с "непрерывной" волной)</p>
 */
public class NormalizatorSamples implements SignalsObserver {
    private List<SampledSignal> readyListForNormalization = null;
    private boolean isReady = false;

    /**
     * Принятие и заведение в класс сырых данных (сигналов в списке), которые будут нормализованы.
     * @param signals
     */
    @Override
    public void accepting(List<SampledSignal> signals) {
        if (!signals.isEmpty()) {
            this.readyListForNormalization = signals;
            isReady = true;
        }
    }

    /**
     * Метод, соблюдающий жёсткий pipeline нормализации:
     * <ul>
     *     <li> Сборка, затем её сортировка
     *     <li> Слияние через LowPass
     *     <li> Фильтрация через ресэмплинг
     *     <li> Удаление DC-смещения (Baseline Correction)
     *     <li> Финальная амплитудная нормализация
     * </ul>
     * @return нормализованный сигнал
     */
    double[] normalize() {
        if (!isReady) {
            throw new IllegalStateException("[❌] Ошибка нормализации: " +
                    "поступающие сигналы отсуствуют (проверьте обращение к accepting())");
        }

        // Сборка разрозненных частей в единый массив.
        double[] merged = InterpolationExtends.merge(this.readyListForNormalization);

        // Текущая частота дискретизации
        double sourceFs = this.readyListForNormalization.getFirst().samplingRate();

        /*
        Целевая частота дискретизации — единая скорость, с которой мы приводим
        все разношёрстные сигналы, чтобы потом их можно было анализировать по ряду Фурье!
        */
        double targetFs = 100.0;

        /*
        Почему 100 Гц?
        — по некоторой теореме Найквиста: «частота, которую можно надёжно разрешить,
        равна половине частоты дискретизации» (то есть, при 100 Гц можно анализировать все
        частоты до 50 Гц — такого диапазона хватит, чтобы захватить большинство волн)
         */


        // Борьба с высокочастотными шумом и артефактами.
        if (targetFs < sourceFs) {
            /*
                Фундаментальный параметр в цифровой обработке сигналов.

                В цифровой обработке сигналов самая высокая частота, которую можно увидеть,
                это половина частоты дискретизации F_{Nyquist} = F_s / 2 (поэтому делим каждое)

                Нормализация происходит по формуле: (новая частота) / исходная частота
             */
            double cutoff = (targetFs / 2.0) / (sourceFs / 2.0); // нормализованный

            /*
            Применяется низкочастотный FIR-фильтр.

            (Taps (коэффициенты) — количество элементов в массиве h[n], который
            генерируется в методе designFirLowPass.

            h[i] — ядро фильтра или импульсная характеристика ядра)
             */
            merged = InterpolationExtends.lowpass(merged, cutoff, 101);
        }

        // Resampling.
        double[] smoothed = InterpolationExtends.resample(merged, sourceFs, targetFs);

        // УДАЛЕНИЕ СМЕЩЕНИЯ (BASELINE CORRECTION)
        // Находим среднее значение (DC-смещение)
        double mean = 0;
        for (double d : smoothed) {
            mean += d;
        }
        mean /= smoothed.length;

        // Вычитаем среднее из каждого отсчёта
        for (int i = 0; i < smoothed.length; ++i) {
            smoothed[i] -= mean;
        }

        finalWindowing(smoothed,
                InterpolationExtends.generateSignalHammingWindow(smoothed.length));

        // Нормализация амплитуд ([-1..1])
        double max = 0;
        for (double d : smoothed) {
            max = Math.max(max, Math.abs(d));
        }

        if (max > 0) {
            for (int i = 0; i < smoothed.length; ++i) {
                smoothed[i] /= max;
            }
        }

        return smoothed;
    }

    /**
     * Финальное окноирование (минимизация спектральной утечки при
     * заведении в ряд Фурье)
     */
    private void finalWindowing(double[] smoothedSignal,
                                    double[] hammingWindow) {
        for (int i = 0; i < smoothedSignal.length; ++i) {
            smoothedSignal[i] *= hammingWindow[i];
        }
    }

    /**
     * Утилитный приватный класс-помощник с интерполированием скачков.
     * Соблюдает чёткий pipeline!
     */
    private static class InterpolationExtends {

        /**
         * Метод слияния всех исходных разрозненных сигналов
         * (фактически обработанных {@code DataRecord}) в единый, упорядоченный временной ряд.
         * @param signals список всех переписанных сигналов
         * @return массив неровных чисел (будет далее применен в интерполяции)
         */
        private static double[] merge(List<SampledSignal> signals) {
            // Сортировка в порядке корректного времени
            signals.sort(Comparator.comparing(SampledSignal::startTime));

            // Гарантированно: самый ранний сигнал не будет после более позднего!

            // Считывание количества отсчётов во всех входных блоках
            int total = 0;
            for (SampledSignal s : signals) {
                total += s.amplitudes().size();
            }
            double[] out = new double[total];

            // Конкатенация: последовательно берутся данные из отсортированных сигналов
            // и записываются в общий массив out (pos — итерируемая переменная).
            int pos = 0;
            for (SampledSignal s : signals) {
                for (double d : s.asArray()) {
                    out[pos++] = d;
                }
            }
            return out;  // Возвращается непрерывный массив данных об амплитудах

            // Однако данный массив ещё не является сглаженным, на это существуют
            // методы, описанные и объявленные статическими далее в классе.
        }

        /**
         * Генерирует окно Хэмминга для конечного сигнала (перед FFT),
         * чтобы избежать спектральной утечки.
         * @param length длина ресемплированного сигнала
         * @return массив коэффициентов окна (от 0 до 1)
         */
        private static double[] generateSignalHammingWindow(int length) {
            double[] w = new double[length];
            // N-1 — делитель, чтобы окно начиналось с 0 и заканчивалось на length-1
            int M = length - 1;

            for (int i = 0; i < length; ++i) {
                // Формула окна Хэмминга: w[i] = 0.54 - 0.46 * cos(2πi / M)
                w[i] = (0.54 - 0.46 * Math.cos(2 * Math.PI * i / M));
            }
            return w;
        }

        /**
         * Связующее звено в применении низкочастотного фильтра.
         * Выполняет две вещи: <i>проектирование ядра</i> и
         * <i>применение его к входному сигналу</i>
         */
        private static double[] lowpass(double[] x, double cutoff, int taps) {
            double[] h = designFirLowPass(cutoff, taps);
            return convolve(x, h);
        }

        /**
         * Реализация метода окон для создания ядра низкочастотного фильтра (FIR kernel)
         * с конечной импульсной характеристикой.
         * <p>Происходит вычисление коэффициентов фильтра, который будет
         * пропускать частоты ниже определённого порога и подавлять те, что выше.</p>
         * @param cutoff частота среза
         * @param taps коэффициенты фильтра (количество)
         */
        private static double[] designFirLowPass(double cutoff, int taps) {
            // Импульсная характеристика фильтра, состоющая из taps коэффициентов
            double[] ker = new double[taps];
            // Делитель в формуле окна Хэмминга (Длина окна).
            int M = taps - 1;

            // Вычисление идеальной импульсной характеристики низкочастотного фильтра.
            for (int i = 0; i < taps; ++i) {

                // M / 2 — центр массива, особая точка.
                // Центризация вычисления ведется относительно этой точки.

                if (i == M / 2) {
                    ker[i] = 2 * cutoff;
                } else {

                    // Функция sinc, которая описывает идеальный и бесконечно длинный фильтр
                    double k = Math.PI * (i - M / 2);
                    ker[i] = Math.sin(2 * cutoff * k) / k;
                }

                // Коэффициенты окна Хэмминга определяются формулой:
                // window[i] = 0.54 - 0.46cos(2пi / M)
                ker[i] *= (0.54 - 0.46 * Math.cos(2 * Math.PI * i / M));
            }

            return ker;
            /*
            Массив ker содержит коэффициенты фильтра, которые определяют,
            как будет работать фильтр.
             */
        }

        /**
         * Выполняет операцию свёртки — ключевая математическая операция, которая
         * применяет <b>ядро</b> фильтра к <b>входному</b> сигналу.
         * @param x входной сигнал в массиве с неровными числами
         * @param h ядро, вычисленное методом окон Хэмминга.
         * @return отфильтрованный сигнал
         */
        private static double[] convolve(double[] x, double[] h) {
            int n = x.length, m = h.length;

            // Получающийся фильтрач
            double[] y = new double[n + m - 1];

            for (int i = 0; i < n; ++i) {      // Индекс входящего сигнала
                for (int j = 0; j < m; ++j) {  // Индекс ядра фильтра
                    y[i + j] += x[i] * h[j];   // Накопление произведения.
                }
            }

            return y;
        }

        /**
         * Решает проблему неравномерности временного шага между отсчётами,
         * которая возникает при слиянии сигналов с разными частотами (@code samplingRate).
         * Ресемплинг происходит на основе интерполяции кубическим сплайном.
         * @param filtered отфильтрованный (FIR) непрерывный сигнал
         * @param oldFs старая герцовка
         * @param newFs новая герцовка
         * @return ресемплированный массив с единой частотой дискретизации.
         */
        private static double[] resample(double[] filtered, double oldFs, double newFs) {
            /*
                Коэффициент, показывающий, во сколько раз новый временной шаг отличается от легаси.

                * Если old = 200, new = 100 --> ratio = 2
                            => новые отсчёты нужно брать каждые два старых
                * Если old = 50, new = 100 --> ratio = 0.5
                            => новые отсчёты нужно брать вдвое чаще
             */
            double ratio = oldFs / newFs;
            int newLen = (int) (filtered.length * (newFs / oldFs)); // Вычисление новой длины по коэффициенту
            double[] out = new double[newLen];               // Выходной массив (ресемплированный)

            for (int i = 0; i < newLen; i++) {
                /*
                    pos вычисляет, какому РЕАЛЬНОМУ, дробному индексу в исходном
                    массиве x соответствует новый отсчёт out[i].

                    * Ratio = 2 & i = 0 --> pos = 0.0
                    * Ratio = 2 & i = 1 --> pos = 2.0
                    ...

                    * Ratio = 0.5 & i = 0 --> pos = 0.0
                    * Ratio = 0.5 & i = 1 --> pos = 0.5
                    * Ratio = 0.5 & i = 2 --> pos = 1.0
                    ...
                 */
                double pos = i * ratio;
                out[i] = interpolateCubic(filtered, pos);
            }
            return out;
        }

        /**
         * Кубическая интерполяция по четырём опорным точкам, окружающие конкретную позицию.
         * @param y исходные точки
         * @param x позиция по ratio
         * @return неровное состояние точки
         */
        private static double interpolateCubic(double[] y, double x) {
            int i = (int) x;  // целая часть x
            double t = x - i; // дробная часть х

            // Проверка на границы массива (чтобы не вылететь за пределы)
            // И не схватить ArrayOutOfBounds.
            double y0 = (i > 0) ? y[i - 1] : y[i];
            double y1 = y[i];
            double y2 = (i < y.length - 1) ? y[i + 1] : y[i];
            double y3 = (i < y.length - 2) ? y[i + 2] : y[i];

            // передача в кубический сплайн по Эрмиту
            return cubicHermite(y0, y1, y2, y3, t);
        }

        /**
         * Получение гораздо более гладкой и предсказуемой кривой по Эрмиту
         * (значение функции + значение её первой производной).
         * <p>Проблема Лагранжа в том, что кривая часто вынуждена резко менять
         * направление между точками, следовательно, получается очень волнистая
         * и негладкая кривая. Для сигнала это плоховато...</p>
         */
        private static double cubicHermite(double y0,
                                           double y1,
                                           double y2,
                                           double y3,
                                           double t) {

            // Оценка наклона
            /*
            Коэффициенты выводятся из формулы, использующую y_0 и y_3 для оценки наклона
            в точках y_1 и y_2 (Эквивалентно использованию центральной разности для
            аппроксимации производной)
             */
            double a0 = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3;
            double a1 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3;
            double a2 = -0.5 * y0 + 0.5 * y2;
            double a3 = y1;

            // Возврат полинома кубического сплайна:
            // P(t) = a_0 * t^3 + a_1 * t^2 + a_2 * t + a_3
            return ((a0*t + a1)*t + a2)*t + a3;
        }
    }
}
