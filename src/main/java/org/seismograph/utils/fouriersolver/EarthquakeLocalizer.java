package org.seismograph.utils.fouriersolver;

import static org.seismograph.utils.fouriersolver.SeismicSignalExtractor.SampledSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EarthquakeLocalizer {

    // P-волна, среднее значение в коре
    private static final double waveSpeed = 6.0;

    public static class PWaveSpawning {

        /**
         * Определяет время прибытия P-волны для каждой станции, используя
         * STA/LTA для первичной станции и кросс-корреляцию для остальных.
         * @throws IllegalStateException Если не найдена P-волна на опорной станции.
         */
        public static TriangulationPipeline.CCTuple pickPWaveArrivals(List<StationData> allPreparedData) throws IllegalStateException {

            System.out.println("\n-----( [Step 3/4] ОПРЕДЕЛЕНИЕ ВРЕМЕНИ ПРИБЫТИЯ P-ВОЛНЫ (STA/LTA + CC) )-----");

            // Параметры для STA/LTA (на первом пике)
            final double STA_SEC = 0.5;
            final double LTA_SEC = 5.0;
            final double THR_ON = 3.5;
            final double THR_OFF = 1.4;

            // Максимально допустимое время прибытия (например, 10 минут = 600 сек)
            // Если волна пришла позже, это, вероятно, S-волна или шум.
            // Если tP < 0, то CC ошиблась.
            final double MAX_ARRIVAL_TIME_SEC = 600.0;
            // ИСПРАВЛЕНИЕ # Установка 0.0, Tp не может быть раньше начала записи.
            final double MIN_ARRIVAL_TIME_SEC = 0.0;

            // Удачную захватим, чтобы график получился правильный и красивый.
            TriangulationPipeline.CCTuple lastSuccessfulTuple = null;

            // Итерация по тройкам (поскольку данные в allPreparedData сгруппированы по 3)
            for (int i = 0; i < allPreparedData.size(); i += 3) {

                List<StationData> currentEventData = allPreparedData.subList(i, Math.min(i + 3, allPreparedData.size()));
                int eventIndex = i / 3;

                if (currentEventData.size() < 3) {
                    System.err.printf("[⚠️] Событие №%d: Недостаточно станций (%d) для локализации. Пропускаем.\n",
                            eventIndex, currentEventData.size());
                    continue;
                }

                System.out.printf("\n--- Событие №%d: Обработка 3 станций ---\n", eventIndex);

                // 1. Выбираем якорную станцию (Station 0 в тройке)
                StationData anchor = currentEventData.getFirst();
                double fs = anchor.pipelineSignal.fs;
                double[] pAnchor = anchor.pipelineSignal.samples;

                // 2. STA/LTA для первичного пика на якорной станции
                int anchorPickSample = TriangulationPipeline.staLtaPick(pAnchor, fs, STA_SEC, LTA_SEC, THR_ON, THR_OFF);

                if (anchorPickSample == -1) {
                    System.err.printf("[❌] Событие №%d: STA/LTA не сработал на якорной станции (%s). Пропускаем локализацию.\n",
                            eventIndex, anchor.channel);
                    // Важно: если Tp не найдено, мы не должны продолжать
                    continue;
                }

                double anchorArrivalTimeSec = (double) anchorPickSample / fs;
                anchor.setArrivalTimeSec(anchorArrivalTimeSec);

                System.out.printf("[✅] Якорная станция (%s) | Tp: %.3f сек (от начала записи)\n",
                        anchor.channel, anchorArrivalTimeSec);

                // --- Параметры окна для кросс-корреляции (CC) ------------------------------------
                final int fsInt = (int) fs;
                // Окно для ЯКОРЯ: 2 сек до и 4 сек после пика (узкое, для эталона)
                final int WINDOW_A_PRE_SEC = 2;
                final int WINDOW_A_POST_SEC = 4;

                // Окно для ПОИСКА: Начинается раньше (для захвата возможных отрицательных задержек)
                // и длится дольше (для захвата максимальной положительной задержки).
                final int WINDOW_B_SEARCH_DURATION_SEC = 40; // Ширина окна поиска (например, 40 секунд)

                // Определяем начало поиска (за 10 секунд до пика)
                int searchWindowStartSample = Math.max(0, anchorPickSample - 10 * fsInt);
                int searchWindowEndSample = searchWindowStartSample + WINDOW_B_SEARCH_DURATION_SEC * fsInt;

                // Обрезанный якорь (для эталона)
                int startA = Math.max(0, anchorPickSample - WINDOW_A_PRE_SEC * fsInt);
                int lenA = (WINDOW_A_PRE_SEC + WINDOW_A_POST_SEC) * fsInt;

                double[] windowedAnchor = TriangulationPipeline.slice(pAnchor, startA, lenA);
                // --------------------------------------------------------------------------------

                for (int j = 1; j < currentEventData.size(); j++) {
                    StationData current = currentEventData.get(j);
                    double[] pCurrent = current.pipelineSignal.samples;

                    // Обрезаем текущий сигнал на то же временное окно, что и окно поиска
                    double[] windowedCurrent = TriangulationPipeline.slice(
                            pCurrent,
                            searchWindowStartSample,
                            searchWindowEndSample - searchWindowStartSample
                    );

                    // Вычисляем задержку CC между двумя ОКНАМИ
                    TriangulationPipeline.CCTuple tupleForFX = TriangulationPipeline.estimateDelaySeconds(
                            windowedCurrent,
                            windowedAnchor,
                            fs
                    );

                    double windowDelay = tupleForFX.delaySec();

                    // Корректировка:
                    // 1. Сначала находим время Tp якоря относительно начала *ОКНА ПОИСКА*
                    double anchorTimeInWindow = anchorArrivalTimeSec - ((double)searchWindowStartSample / fs);

                    // 2. Теперь вычисляем фактическое время прихода:
                    // refinedArrivalTimeSec = (Время начала окна) + (Время пика якоря в окне) + (Задержка между пиками)
                    double refinedArrivalTimeSec =
                            ((double)searchWindowStartSample / fs) +
                                    anchorTimeInWindow +
                                    windowDelay;

                    // Если CC не сработала, то windowDelay будет экстремальным (близко к краю окна CC)
                    // и refinedArrivalTimeSec все равно будет сильно отрицательным или огромным.

                    // 💡 ВАЛИДАЦИЯ: Защита от ошибок кросс-корреляции (CC)
                    if (refinedArrivalTimeSec < MIN_ARRIVAL_TIME_SEC || refinedArrivalTimeSec > MAX_ARRIVAL_TIME_SEC) {

                        System.err.printf("[❌] Станция %s: CC выдала неправдоподобный Tp (%.3f сек)! Устанавливаем 0.0.\n",
                                current.channel, refinedArrivalTimeSec);

                        // Установка Tp = 0.0 сигнализирует localizeAllEvents, что эта станция не годится.
                        current.setArrivalTimeSec(0.0);

                    } else {

                        current.setArrivalTimeSec(refinedArrivalTimeSec);
                        double delaySec = refinedArrivalTimeSec - anchorArrivalTimeSec; // Фактическая задержка относительно якоря

                        // Лог для проверки
                        System.out.printf("[✅] Станция %s | Сдвиг (CC): %.3f сек | Общий Tp: %.3f сек\n",
                                current.channel, delaySec, refinedArrivalTimeSec);

                        lastSuccessfulTuple = tupleForFX;
                    }
                }
            }

            return lastSuccessfulTuple;
        }

        /**
         * Локализует все события, используя подготовленные данные и вычисленные Tp.
         * Использует решатель TDOA (Time Difference of Arrival).
         */
        public static void localizeAllEvents(List<StationData> allPreparedData) {

            System.out.println("\n-----( [Step 4/4] ЗАПУСК РЕШАТЕЛЯ ЛОКАЛИЗАЦИИ (TDOA) )-----");
            final double V = EarthquakeLocalizer.waveSpeed; // Скорость P-волны

            for (int i = 0; i < allPreparedData.size(); i += 3) {

                List<StationData> currentEventData = allPreparedData.subList(i, Math.min(i + 3, allPreparedData.size()));
                int eventIndex = i / 3;

                // Проверяем на наличие 3 станций и на отсутствие "плохих" Tp (равных 0.0)
                if (currentEventData.size() < 3 ||
                        currentEventData.stream().anyMatch(sd -> sd.getArrivalTimeSec() == 0.0)) {
                    System.err.print("========================================================\n");
                    System.err.printf("[❌] Локализация события %d пропущена: недостаточно данных или сбой Tp.\n", eventIndex);
                    continue;
                }

                // Преобразование в список Station для решателя TDOA
                List<TriangulationPipeline.Station> stations = currentEventData.stream()
                        .map(sd -> new TriangulationPipeline.Station(
                                sd.station.x(),
                                sd.station.y(),
                                sd.getArrivalTimeSec() // t = t_arrival (в секундах)
                        ))
                        .toList(); // Используем toList() для Java 16+ или .collect(Collectors.toList())

                TriangulationPipeline.TDOALocalizer.Point solution = null;

                System.out.print("========================================================\n");
                try {
                    // Решатель TDOA (возвращает Point(x, y))
                    solution = TriangulationPipeline.TDOALocalizer.localize(stations, V);
                } catch (RuntimeException e) {
                    // Вырожденная геометрия (станции в линию)
                    System.err.printf("[❌] Локализация события %d не удалась: %s\n", eventIndex, e.getMessage());
                }


                if (solution != null) {
                    double x = solution.x();
                    double y = solution.y();

                    // Время t0 не вычисляется в TDOA, но можно его оценить.
                    // Для лога пока t0 не отображаем.

                    // Получаем опорную точку
                    StationData refData = currentEventData.getFirst();
                    double refLat = refData.getRefLatitude();
                    double refLon = refData.getRefLongitude();

                    // 💡 Обратная конвертация!
                    double[] globalCoords = CoordinateConverter.toGlobalLatLon(x, y, refLat, refLon);
                    double lat = globalCoords[0];
                    double lon = globalCoords[1];

                    System.out.printf("✨ ЛОКАЛИЗАЦИЯ СОБЫТИЯ %d УСПЕШНА (TDOA) ✨\n", eventIndex);
                    System.out.printf("-> Эпицентр (X, Y) относительно центра: (%.3f км, %.3f км)\n", x, y);
                    System.out.printf("-> Эпицентр (Lat, Lon): (%.4f, %.4f)\n", lat, lon);
                    System.out.printf("-> Опорный центр: (%.4f, %.4f)\n", refLat, refLon);
                    // Примечание: t0 теперь отсутствует, так как TDOA его не дает
                }
                // Сообщение об ошибке уже выведено в блоке catch
            }
        }
    }

    /**
     * Очередной класс с данными по станции, который в себе содержит:
     * <p>* Сигнал в формате пайплайна
     * <p>* Станция (локализатор)
     * <p>* Канал (BHZ, BHE, BHN)
     */
    public static class StationData {
        private final TriangulationPipeline.Signal pipelineSignal;
        private final TriangulationPipeline.Station station;
        private final String channel;

        // Новые поля для опорной точки
        private final double refLatitude;
        private final double refLongitude;

        public StationData(TriangulationPipeline.Signal ps,
                           TriangulationPipeline.Station st,
                           String ch, double refLat, double refLon) {
            this.pipelineSignal = ps;
            this.channel = ch;
            this.station = st;
            this.refLatitude = refLat;
            this.refLongitude = refLon;
        }

        // T_p - время прихода будет заполнено позже.
        private double arrivalTimeSec = 0.0;

        public double getArrivalTimeSec() {
            return arrivalTimeSec;
        }

        public void setArrivalTimeSec(double arrivalTimeSec) {
            this.arrivalTimeSec = arrivalTimeSec;
        }

        public double getRefLatitude() {
            return this.refLatitude;
        }

        public double getRefLongitude() {
            return this.refLongitude;
        }
    }

    /**
     * Конвертер данных о сигнале из {@code SampledSignal} в {@code TriangulationPipeline.Signal}.
     * Метод также применяет необходимую предобработку ({@link TriangulationPipeline#demean(double[])}
     * и {@link TriangulationPipeline#applyHamming(double[])})
     * @see org.seismograph.utils.fouriersolver.SeismicSignalExtractor.SampledSignal
     * @see org.seismograph.utils.fouriersolver.TriangulationPipeline.Signal
     */
    private TriangulationPipeline.Signal preprocess(SampledSignal ss) {
        // Копируем массив, не меняя исходные данные
        double[] samples = ss.amplitudesAsArray();

        // Предобработка:
        double[] processed = TriangulationPipeline.demean(samples);
        processed = TriangulationPipeline.applyHamming(processed);

        // Возвращаем новый объект
        return new TriangulationPipeline.Signal(processed, ss.samplingRate(), ss.startTime().getSecond());
    }

    /**
     * Сборка данных по станциям, используя только вертикальный канал (BHZ),
     * который чаще всего используется для обнаружения P-волн.
     */
    public List<StationData> prepareData(List<Map<String, SampledSignal>> allSignalsList) {

        List<StationData> data = new ArrayList<>();

        for (int i = 0; i < allSignalsList.size(); i += 3) {

            // Сбор текущей тройки для ОДНОГО события
            List<Map<String, SampledSignal>> currentEventSignals = new ArrayList<>();

            for (int j = 0; j < 3 && (i + j) < allSignalsList.size(); ++j) {
                currentEventSignals.add(allSignalsList.get(i + j));
            }

            if (currentEventSignals.size() < 3) {
                System.err.printf("[⚠️] Событие, начинающееся с индекса %d, содержит только %d станции. Пропускаем.\n",
                        i, currentEventSignals.size());
                continue;
            }

            // Собираем все координаты для вычисления ОПОРНОЙ ТОЧКИ этого события.
            List<double[]> currentCoords = new ArrayList<>();

            for (Map<String, SampledSignal> signalMap : currentEventSignals) {
                // Поиск вертикального канала:
                SampledSignal ss = signalMap.get("BHZ");

                if (ss != null && ss.latitude() != 0.0 && ss.longitude() != 0.0) {
                    currentCoords.add(new double[] { ss.latitude(), ss.longitude() });
                }
            }

            if (currentCoords.size() < 3) {
                System.err.printf("[❌] Для события %d найдено недостаточно координат. Пропускаем.\n", i);
                continue;
            }


            // Динамически вычисляем опорную точку (средние координаты всех станций)
            double[] referencePoint =
                    CoordinateConverter.calculateReferencePointForThreeStations(
                            currentCoords
                    );

            double REFERENCE_LATITUDE_DEG = referencePoint[0];
            double REFERENCE_LONGITUDE_DEG = referencePoint[1];

            for (Map<String, SampledSignal> signalMap : currentEventSignals) {
                SampledSignal ss = signalMap.get("BHZ");

                TriangulationPipeline.Signal pipelineSignal = preprocess(ss);

                double[] localXY = CoordinateConverter.toLocalXY(
                        ss.latitude(),
                        ss.longitude(),
                        REFERENCE_LATITUDE_DEG,
                        REFERENCE_LONGITUDE_DEG
                );

                // Подготовка координат станций:
                TriangulationPipeline.Station station =
                        new TriangulationPipeline.Station(
                                localXY[0],
                                localXY[1],
                                0.0
                        );

                data.add(new StationData(pipelineSignal, station, "BHZ",
                        REFERENCE_LATITUDE_DEG, REFERENCE_LONGITUDE_DEG));
            }
        }

        return data;
    }

    /**
     * Утилитный класс для преобразования координат (Latitude/Longitude)
     * в локальные плоские координаты (X, Y) в километрах.
     * Использует простое приближение для малых областей (Local Flat Projection).
     */
    public final class CoordinateConverter {
        // Средний радиус Земли в километрах (константа)
        private static final double EARTH_RADIUS_KM = 6371.0;

        // Приватный конструктор, чтобы предотвратить создание экземпляров
        private CoordinateConverter() {}

        /**
         * Преобразует широту/долготу в локальные координаты X, Y в километрах.
         * Использует предоставленную центральную точку как начало координат (0, 0).
         * * @param latitudeDeg Широта станции (в градусах).
         * @param longitudeDeg Долгота станции (в градусах).
         * @param refLatitudeDeg Широта центральной точки (в градусах).
         * @param refLongitudeDeg Долгота центральной точки (в градусах).
         * @return Массив {X (Восток, в км), Y (Север, в км)}.
         */
        public static double[] toLocalXY(
                double latitudeDeg,
                double longitudeDeg,
                double refLatitudeDeg,
                double refLongitudeDeg
        ) {
            // Переводим координаты в радианы
            double latRad = Math.toRadians(latitudeDeg);
            double lonRad = Math.toRadians(longitudeDeg);
            double refLatRad = Math.toRadians(refLatitudeDeg);
            double refLonRad = Math.toRadians(refLongitudeDeg);

            // Коэффициент сжатия для долготы (зависит от центральной широты)
            double cosRefLat = Math.cos(refLatRad);

            // 1. Расчет Y (Север-Юг) в км
            // Y = R * (lat_rad - refLat_rad)
            double Y = EARTH_RADIUS_KM * (latRad - refLatRad);

            // 2. Расчет X (Восток-Запад) в км
            // X = R * (lon_rad - refLon_rad) * cos(refLat_rad)
            double X = EARTH_RADIUS_KM * (lonRad - refLonRad) * cosRefLat;

            return new double[] {X, Y};
        }

        /**
         * Вычисляет среднюю широту и долготу для списка координат.
         * Эта точка будет использоваться как опорный центр (REF_LATITUDE_DEG, REF_LONGITUDE_DEG)
         * для преобразования в плоские координаты (X, Y).
         *
         * @param coords Список массивов Double[], где [0] = широта, [1] = долгота.
         * @return Массив [Средняя широта, Средняя долгота].
         */
        public static double[] calculateReferencePointForThreeStations(List<double[]> coords) {
            if (coords == null || coords.isEmpty()) {
                throw new IllegalArgumentException("Список координат не может быть пустым.");
            }

            double sumLat = 0.0, sumLon = 0.0;

            for (double[] coord : coords) {
                sumLat += coord[0]; // Latitude
                sumLon += coord[1]; // Longitude
            }

            double avgLat = sumLat / coords.size();
            double avgLon = sumLon / coords.size();

            return new double[] { avgLat, avgLon };
        }

        /**
         * Обратное преобразование локальных координат (X, Y) в широту/долготу.
         * @param X_km Координата X (Восток) в км.
         * @param Y_km Координата Y (Север) в км.
         * @param refLatitudeDeg Широта центральной точки (в градусах).
         * @param refLongitudeDeg Долгота центральной точки (в градусах).
         * @return Массив {Широта (deg), Долгота (deg)}.
         */
        public static double[] toGlobalLatLon(
                double X_km,
                double Y_km,
                double refLatitudeDeg,
                double refLongitudeDeg
        ) {
            // Переводим опорную широту в радианы
            double refLatRad = Math.toRadians(refLatitudeDeg);

            // 1. Расчет разницы долготы (dLon)
            // dLon_rad = X_km / (R * cos(refLat_rad))
            double cosRefLat = Math.cos(refLatRad);
            double dLonRad = X_km / (EARTH_RADIUS_KM * cosRefLat);

            // 2. Расчет разницы широты (dLat)
            // dLat_rad = Y_km / R
            double dLatRad = Y_km / EARTH_RADIUS_KM;

            // 3. Абсолютные координаты
            double finalLatRad = refLatRad + dLatRad;
            double finalLonRad = Math.toRadians(refLongitudeDeg) + dLonRad;

            double finalLatDeg = Math.toDegrees(finalLatRad);
            double finalLonDeg = Math.toDegrees(finalLonRad);

            return new double[] {finalLatDeg, finalLonDeg};
        }
    }
}
