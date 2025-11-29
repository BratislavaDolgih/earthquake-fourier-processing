package org.seismograph.utils.download;

import org.seismograph.SeismicApp;
import org.seismograph.utils.Fileable;
import org.seismograph.utils.dataonly.EarthquakeFeature;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class IRISWaveformsDownloader implements Fileable {

    // Невозможно создать экземпляр класса.
    public IRISWaveformsDownloader() {
    }

    // Константный радиус поиска ближайшей станции по умолчанию
    private static final int DEFAULT_RADIUS = 20;

    private static final HttpClient client = HttpClient.newHttpClient();

    /**
     * Глобальный форматтер для всех запросов IRIS
     * (формат ISO 8601 с явным указанием Z - Zulu/UTC)
     */
    private static final DateTimeFormatter IRIS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");


/*
########################################################################################
    ---===== ВЗАИМОДЕЙСТВИЕ с DOWNLOADER'ом через 1 открытый метод в API =====---
########################################################################################
*/
    /**
     * Генерация запроса к IRIS станциям.
     * Возможно перезапись и(или) переадресация на другую станцию.
     * @param quake текущее землетрясение.
     * @return данные в мета-формате
     */
    public static WaveformResult generateWaveformResponse(EarthquakeFeature quake) {

        // Множество посещенных (зафиксированных станций) — для удаления дубликатов.
        Set<String> visitedStations = new HashSet<>();

        try {
            // Строчка с пойманной станцией, обрабатывающей событие.
            String currentStation = IRISWaveformsDownloader
                    .strokeWithNearestStation(quake, DEFAULT_RADIUS, visitedStations);

            // Временное окно [-2 мин от начала события; +10 мин после события]
            LocalDateTime START = quake.absoluteTime().minusMinutes(2);
            LocalDateTime END = quake.absoluteTime().plusMinutes(10);

            // Формирование URL для запроса в IRIS:
            String url = constructIRISUrl(currentStation, START, END);
            System.out.println("[->] Downloading waveform for M" +
                    quake.momentMagnitude() + " at " +
                    quake.flynnRegion());
            System.out.println("     Station: " + currentStation);

            // Формирование запроса
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("User-Agent", "JavaSeismoClient (mailto:ksa8552855@gmail.com)")
                    .build();

            // Ответ в массиве байт.
            HttpResponse<byte[]> response = client.send(
                    req,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            if (response.statusCode() != 200) {
                System.err.println("[ERROR] Failed to download: HTTP " +
                       response.statusCode());
            }

            // Если выскочила проблема HTTP 204 и мы хотим всё-таки получить данные...
            byte attempt = 1;
            int radius = DEFAULT_RADIUS;

            // Даём три попытки получения данных с другой станции.
            while (response.statusCode() == 204 && attempt <= 3) {
                System.out.println("- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
                String curStationKey = extractStationKey(currentStation);
                visitedStations.add(curStationKey);

                radius += 10;
                System.out.printf("[⚠️] Attempt %d: no data (HTTP 204). " +
                        "Expanding search radius to %d°...%n", attempt, radius);

                // Запуск с новейшим радиусом (+10 град. от прошлого)
                currentStation = IRISWaveformsDownloader
                        .strokeWithNearestStation(quake, radius, visitedStations);

                if (currentStation == null) {
                    System.err.println("[❌] No alternative stations found");
                    break;
                }

                // Формирование URL для запроса в IRIS:
                url = constructIRISUrl(currentStation, START, END);
                System.out.printf("[->] Downloading waveform for M%.1f at %s%n",
                        quake.momentMagnitude(), quake.flynnRegion());
                System.out.println("     Station: " + currentStation);


                // Формирование запроса
                req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("User-Agent", "JavaSeismoClient (mailto:ksa8552855@gmail.com)")
                        .build();

                // Ответ в массиве байт.
                response = client.send(
                        req,
                        HttpResponse.BodyHandlers.ofByteArray()
                );

                attempt++; // Следующая попытка.
                System.out.println("- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
            }

            if (response.statusCode() == 200) {
                return new WaveformResult(currentStation, response);
            } else {
                // Тут уже неважно, какой статус кода: 204, 404, 505!
                // Данных либо нет, либо они невалдины.
                System.err.printf("[❌] Финальная попытка не сработала. " +
                                "Статус ответа: %d. Будет возвращено null!%n",
                        response.statusCode());

                return null;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[\uD83D\uDCA5] Download error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Вспомогательный метод, помогающий доставать ключ станции (формат: {@code NETWORK.STATION})
     * @param stationQuery записка с указанием станции
     * @return готовый ключ станции
     */
    private static String extractStationKey(String stationQuery) {
        String network = null;
        String station = null;
        for (String param : stationQuery.split("&")) {
            if (param.startsWith("network=")) {
                network = param.substring(8);
            } else if (param.startsWith("station=")) {
                station = param.substring(8);
            }
        }

        if (network == null || station == null) {
            throw new IllegalArgumentException("Malformed station query: " + stationQuery);
        }

        return network + "." + station;
    }

    /**
     * Метод поиска ближайшей станции по географическим характеристикам станции.
     * <br>Поиск подразумевает в радиусе, выраженному в географических градусах</br>
     * <br>(1 радиус примерно около 110 км)</br>
     * @param quake входящее землетрясение
     * @return подстрока запроса, содержащая кодовое название ближайшей к землетрясению станции.
     */
    private static String strokeWithNearestStation(EarthquakeFeature quake,
                                                   int maxRadius,
                                                   Set<String> excludedStations) {

        // Забираем поля землетрясения:
        final double latitude = quake.latitude();             // Широта
        final double longitude = quake.longitude();           // Долгота
        final LocalDateTime quakeTime = quake.absoluteTime(); // Абсолютное время случившегося

        // Найденные станции (представление в метаданных)
        List<StationDistance> founded = new ArrayList<>();

        /*
        * Создаём временное окно землетрясения (чтобы был полностью покрыт «континуум»)
        * Окно рассматривается: [за 1 час до события; по прошествию 1 часа после события]
        * */
        final String startStationTime = quakeTime.minusHours(1)
                .format(IRIS_TIME_FORMATTER);
        final String endStationTime = quakeTime.plusHours(1)
                .format(IRIS_TIME_FORMATTER);

        /*
            Задаём радиусы поиска.
            Это нужно потому, что землетрясение может быть далеко от станции
            Например, где-то в океане, далеко от суши.
        */
        System.out.printf("[ℹ️] Searching stations within %d° radius...%n", maxRadius);

        // Создаём ссылку к IRIS Service (через него получим waveforms)
        String stationURL = String.format(
                java.util.Locale.ROOT,
                "https://service.iris.edu/fdsnws/station/1/query?" +
                        "latitude=%.4f&longitude=%.4f&" +
                        "maxradius=%d&" +  // радиус в градусах (~1110 км на экваторе)
                        "level=channel&" +
                        "format=text&" +
                        "channel=BHZ&" +  // канал вертикальной компоненты
                        "starttime=%s&" +
                        "endtime=%s",
                latitude, longitude, maxRadius,
                startStationTime, endStationTime
        );

        // ЛОГ в консоль URL для ручной проверки, если поиск снова не сработает
        System.out.printf("[ℹ️] Station URL Check: %s%n", stationURL);

        // Посылаем вежливый запрос на сервак IRIS'а.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stationURL))
                .timeout(java.time.Duration.ofSeconds(20)) // максимальное время ожидания 20 сек
                .header("User-Agent", "JavaSeismoClient (mailto:ksa8552855@gmail.com)")
                .build();

        try {
            // ответ в строковом виде
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200
                    && response.body() != null
                    && !response.body().isBlank()) { // запрос успешно принят и обработан

                /*
                    Строки ответа будут иметь структуру, примерно такую:
                    GE|BFO||BHZ|48.33|8.33|640|1990-01-01T00:00:00|--
                */

                String[] lines = response.body().split("\\r?\\n");

                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] parts = line.split("\\|");

                    // Структура: NET|STA|LOC|CHA|LAT|LON|ELE|DEP|START|END
                    if (parts.length >= 6) {
                        String net = parts[0].trim();
                        String st = parts[1].trim();
                        double statLat = Double.parseDouble(parts[4].trim());
                        double statLon = Double.parseDouble(parts[5].trim());

                        String stationKey = net + "." + st;


                        // Повторения игнорируются
                        if (excludedStations.contains(stationKey)) { continue; }

                        StationDistance sd = new StationDistance(net, st, statLat, statLon);

                        // Точное расстояние по дуге большого круга от ЭПИЦЕНТРА до КОНКРЕТНОЙ СТАНЦИИ
                        sd.distanceKm = EarthquakeFeature.haversine(latitude, longitude, statLat, statLon);
                        founded.add(sd);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[\uD83D\uDCA5] Station search error: " + e.getMessage());
        }

        // Если что-то нашли, то...
        if (!founded.isEmpty()) {
            // Посортируем по дистанции
            founded.sort(Comparator.comparingDouble(StationDistance::getDistanceKm));

            StationDistance nearest = founded.getFirst();

            System.out.printf("[✅] Nearest station found: %s.%s at %.2f km%n",
                    nearest.network, nearest.station, nearest.distanceKm);

            return String.format("network=%s&station=%s&", nearest.network, nearest.station);
        }

        // Если не удалось найти станцию, то берем крайние.
        String fallback = fallbackStation(latitude, longitude);
        String fallbackKey = extractStationKey(fallback);

        if (excludedStations.contains(fallbackKey)) {
            System.err.println("[❌] Fallback station already tried: " + fallbackKey);
            return null;
        }

        System.out.println("[ℹ️] Using fallback station: " + fallbackKey);
        return fallback;
    }

    /**
     * Генерирует строку со станцией по умолчанию,
     * если станция не нашлась в {@link IRISWaveformsDownloader#strokeWithNearestStation(EarthquakeFeature, int, Set)}.
     * <p>Работает в самом крайнем случае, потому что станции придётся пройти процесс попыток (3)</p>
     * @param latitude широта
     * @param longitude долгота
     * @return готовая подстрочка со станцией, обрабатывающая землетрясение
     */
    private static String fallbackStation(double latitude, double longitude) {
        String defaultStation = null;

        // Северо-Запад (Европейская часть)                   Обнинск, Россия
        if (latitude >= 55 && longitude < 60)      { defaultStation = "II OBN"; }

        // Северо-Восток (Сибирь)                              Якутск, Россия
        else if (latitude >= 55)                   { defaultStation = "IU YAK"; }

        // Кавказ / Восточная Европа               Фюрстенфельдбрук, Германия
        else if (latitude >= 40 && longitude < 40) { defaultStation = "GE FUR"; }

        // Средняя Азия / Ближний Восток                        Анкара, Турция
        else if (latitude < 40 && longitude < 60)  { defaultStation = "IU ANTO"; }

        // Восточная Азия / Китай / Монголия                     Пекин, Китай
        else if (longitude > 100)                  { defaultStation = "IC BJT"; }

        // Центральная Евразия                                  Россия, Алтай
        else                                       { defaultStation = "IU TLY"; }

        System.err.println("[ℹ️] Using fallback station: " + defaultStation);

        String[] ds = defaultStation.split(" ");

        return String.format("network=%s&station=%s&", ds[0].trim(), ds[1].trim());
    }

    /**
     * Метод построения ссылки к станции.
     * @param station подстрока
     *                (из {@link IRISWaveformsDownloader#strokeWithNearestStation(EarthquakeFeature, int, Set)}
     *                или {@link IRISWaveformsDownloader#fallbackStation(double, double)})
     * @param start момент старта отслеживания
     * @param end момент конца отслеживания
     * @return строковое представление URL.
     */
    private static String constructIRISUrl (String station,
                                            LocalDateTime start,
                                            LocalDateTime end) {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        return String.format(
                "http://service.iris.edu/fdsnws/dataselect/1/query?" +
                        "%s" +
                        "starttime=%s&endtime=%s&" +
                        "format=miniseed",
                station,
                start.format(formatter),
                end.format(formatter)
        );
    }

    /**
     * Класс метаданных и полезных методов для станций,
     * в число которых входит: <i>сеть, станцция, широта, долгота, дистанция в километрах</i>.
     */
    private static class StationDistance {
        final String network;
        final String station;
        final double latitude;
        final double longitude;
        double distanceKm = Double.MAX_VALUE;

        public StationDistance(String net, String st, double lat, double lon) {
            this.network = net;
            this.station = st;
            this.latitude = lat;
            this.longitude = lon;
        }

        // Геттер для сравнения
        public double getDistanceKm() {
            return distanceKm;
        }
    }

    /**
     * Метаданные результата {@code Waveform}
     * @param station станция
     * @param response что получили от сервера
     */
    public record WaveformResult(String station, HttpResponse<byte[]> response) {}

    /**
     * Метод, который в классе {@link IRISWaveformsDownloader} не поддерживается.
     * @throws UnsupportedOperationException если совершена попытка получить путь
     */
    public Path correctPath() {
        throw new UnsupportedOperationException("[❗] IRISWaveformsDownloader не предполагает файлового вывода");
    }
}
