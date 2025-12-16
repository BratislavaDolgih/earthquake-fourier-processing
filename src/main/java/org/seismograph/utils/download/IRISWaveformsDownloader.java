package org.seismograph.utils.download;

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

/**
 * Главный класс, берущий на себя обязанность скачивать данные-waveforms с сервиса iris.edu
 */
public class IRISWaveformsDownloader implements Fileable {

    // Невозможно создать экземпляр класса.
    public IRISWaveformsDownloader() {
    }

    // Константный радиус поиска ближайшей станции по умолчанию
    private static final int DEFAULT_RADIUS = 20;

    // Константа, показывающая сколько станций нужно для локализации (используется трёхточечная).
    private static final int REQUIRED_STATIONS = 3;

    // Простой клиент HTTP.
    private static final HttpClient client = HttpClient.newHttpClient();

    /**
     * Глобальный форматтер для всех запросов IRIS
     * (формат ISO 8601 с явным указанием Z - Zulu/UTC)
     */
    private static final DateTimeFormatter IRIS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // Строим ссылку, по которой будем подключаться
    private static String constructIRISUrl(String stationNetwork,
                                           String stationCode,
                                           LocalDateTime start,
                                           LocalDateTime end) {
        // Обработка времени будет согласно формату в URL.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        return String.format(
                "http://service.iris.edu/fdsnws/dataselect/1/query?" +
                        "network=%s&station=%s&" +
                        "channel=BHZ,BHN,BHE&" +
                        "starttime=%s&endtime=%s&" +
                        "format=miniseed",
                stationNetwork,
                stationCode,
                start.format(formatter),
                end.format(formatter)
        );
    }

/*
########################################################################################
    ---===== ВЗАИМОДЕЙСТВИЕ с DOWNLOADER'ом через 1 открытый метод в API =====---
########################################################################################
*/

    /**
     * Главный API метод поиска трёх ближайших станций, активные во время землетрясения,
     * и загружает MSeed-данные со всех трёх компонент (BHZ, BHN, BHE) для каждой из них.
     * @param quake текущее землетрясение.
     * @return список результатов загрузки (по одному WaveformResult на станцию)
     */
    public static List<WaveformResult> downloadBestWaveforms(EarthquakeFeature quake) {
    // Список результатов, которые нам будут попадаться (интересные), согласно пропаршенным JSON-нам.
        List<WaveformResult> allResults = new ArrayList<>(REQUIRED_STATIONS);
        // Множество посещенных (зафиксированных станций) — для удаления дубликатов.
        Set<String> visitedStations = new HashSet<>();

        // Временное окно [-2 мин от начала события; +10 мин после события]
        LocalDateTime START = quake.absoluteTime().minusMinutes(5);
        LocalDateTime END = quake.absoluteTime().plusMinutes(5);

        int currentRadius = DEFAULT_RADIUS;  // Итерационная переменная, которая будет увеличивать радиус поиска.
        final int MAX_RADIUS = 60;           // Максимальный радиус поиска.

        System.out.printf("[ℹ️] Начинаем поиск и загрузку %d станций для локализации...%n", REQUIRED_STATIONS);

        while (allResults.size() < REQUIRED_STATIONS && currentRadius <= MAX_RADIUS) {
            // Запрос количества с запасом в 10 станций (заложено вовнутрь метода)!
            List<StationDistance> candidates = findNearestCandidates(
                    quake, currentRadius, visitedStations
            );

            if (candidates.isEmpty()) {
                System.out.printf("[⚠️] Нет активных станций в радиусе %d°. Расширяем поиск...%n", currentRadius);
                currentRadius += 10;
                continue;
            }

            // Проходимся по полученным кандидатам...
            for (StationDistance cand : candidates) {
                String stationKey = cand.getStationKey();

                if (allResults.size() >= REQUIRED_STATIONS) break;  // Достигли максимума
                if (visitedStations.contains(stationKey)) continue; // Пропуск проверенных

                System.out.printf("  [? -> ...] Пробуем станцию %s (%.2f km)...%n", stationKey, cand.distanceKm);

                // ПОПЫТКА ЗАГРУЗКИ (ответ) MSEED (по трём каналам сразу же)
                HttpResponse<byte[]> response = attemptToDownload(
                        cand.network,
                        cand.station,
                        START, END
                );

                visitedStations.add(stationKey); // Зап

                if (response != null) {
                    WaveformResult result = new WaveformResult(
                            stationKey, response,
                            cand.latitude, cand.longitude, // Пробрасываемые долгота и широта у конкретного Waveform
                            cand.station, cand.network
                    );
                    allResults.add(result);
                    System.out.printf("[✅] Успешно загружена станция %s! Собрано %d из %d.%n",
                            stationKey, allResults.size(), REQUIRED_STATIONS);
                } else {
                    // response == null, значит, данные не получены (HTTP 204 или ошибка)
                    System.err.printf("[❌] Станция %s не предоставила полных данных (204 / ошибка).%n",
                            stationKey);
                }
            }

            currentRadius += 10; // Расширение радиуса.
        }

        if (allResults.size() < REQUIRED_STATIONS) {
            System.err.printf("[❌] Не удалось собрать минимально необходимые %d станции. " +
                            "Найдено только %d.%n",
                    REQUIRED_STATIONS, allResults.size());
        }

        return allResults;
    }

    /**
     * Вспомогательный метод для выполнения HTTP-запроса и тихой обработки 204.
     * @param network "геолокация" станции
     * @param station строчка со станцией
     * @param start временное окно: начало
     * @param end временное окно: конец
     * @return {@code HttpResponse<byte[]>} с MSeed данными или null, если загрузка не удалась
     * @see IRISWaveformsDownloader#downloadBestWaveforms
     */
    private static HttpResponse<byte[]> attemptToDownload(String network,
                                                          String station,
                                                          LocalDateTime start,
                                                          LocalDateTime end) {
        String url = constructIRISUrl(network, station, start, end);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", "JavaSeismoClient (mailto:ksa8552855@gmail.com)")
                .build();

        try {
            HttpResponse<byte[]> resp = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            // HTTP 200: OK — данные получены, возвращаем
            if (resp.statusCode() == 200) {
                return resp;
            }

            // HTTP 204: No Content — нет данных, но запрос валиден.
            if (resp.statusCode() == 204) {
                // Вывод того, что станция послала с ответом 204 уже есть выше.
                return null;
            }

            // Другие ошибки (404, 500 и т.д.)
            System.err.printf("    [ERROR] Загрузка %s.%s: HTTP %d%n", network, station, resp.statusCode());
            return null;
        } catch (IOException | InterruptedException exc) {
            System.err.printf("[FATAL ERROR] Download exception for %s.%s: %s%n", network, station, exc.getMessage());
            return null;
        }
    }

    /**
     * Запрашивание у IRIS FDSN Station Service только список координат, засчёт {@code level=station},
     * которые вообще работали во время землетрясения. Каждая найденная станция — просто точка на карте
     * с неизвестным расстояниям до эпицентра.
     * <p>
     * Метод возвращает список потенциальных станций-кандидатов, отсортированных по возрастанию
     * расстояния до эпицентра.
     * <p>
     * IMPORTANT: Этот метод только находит координаты и не гарантирует,
     * что станция имеет данные (MSeed) для всех трёх каналов (BHZ, BHN, BHE).
     *
     * @param quake            входящее землетрясение, у которого ищутся станции
     * @param maxRadius        максимальный радиус поиска от эпицентра в географических градусах
     * @param excludedStations множество станций {@code NET.STA}, которые уже были проверены
     * @return отсортированный список объектов с метаданными ближайших станций.
     */
    private static List<StationDistance> findNearestCandidates(EarthquakeFeature quake,
                                                   int maxRadius,
                                                   Set<String> excludedStations) {

        // Забираем поля входного землетрясения:
        final double latitude = quake.latitude();              // Широта
        final double longitude = quake.longitude();            // Долгота
        final LocalDateTime quakeTime = quake.absoluteTime();  // Абсолютное время случившегося события

        // Найденные станции (представление в метаданных)
        List<StationDistance> founded = new ArrayList<>();

        /*
        * Создаём временное окно землетрясения (берём НЕМНОГО, потому что нам важно разложение, а не всё время)
        * Окно рассматривается: [за 5 минут до события; по прошествию 5 минут после события]
        * */
        final String startStationTime = quakeTime.minusMinutes(5)
                .format(IRIS_TIME_FORMATTER);
        final String endStationTime = quakeTime.plusMinutes(5)
                .format(IRIS_TIME_FORMATTER);

        /*
            Задаём радиусы поиска.
            Это нужно потому, что землетрясение может быть далеко от станции
            Например, где-то в океане, далеко от суши.
        */
        System.out.printf("[ℹ️] Searching stations within %d° radius...%n", maxRadius);

        // Создаём ссылку к IRIS Service
        String stationURL = String.format(
                java.util.Locale.ROOT,
                "https://service.iris.edu/fdsnws/station/1/query?" +
                        "latitude=%.4f&longitude=%.4f&" +
                        "maxradius=%d&" +  // радиус в градусах (~1110 км на экваторе)
                        "level=station&" + // Ключевое изменение: ищем станции, которые обрабатывали ТЕКУЩЕЕ
                        "format=text&" +
                        // Канал конкретный удаляется, ведь нам обязательно нужны волны с BHZ, BHE, BHN.
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
                .header("User-Agent",
                        "JavaSeismoClient (mailto:ksa8552855@gmail.com)")
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

                String[] lines = response.body().split("\\r?\\n");

                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] parts = line.split("\\|");

                    // Структура для level=station: NET|STA|LAT|LON|ELE|START|END
                    // Здесь нужно 4 поля, чтобы получить координаты
                    if (parts.length >= 4) {
                        String net = parts[0].trim();
                        String st = parts[1].trim();

                        try {
                            // Update: LAT теперь parts[2], LON = parts[3]
                            double statLat = Double.parseDouble(parts[2].trim());
                            double statLon = Double.parseDouble(parts[3].trim());

                            String stationKey = net + "." + st;

                            // Повторения игнорируются
                            if (excludedStations.contains(stationKey)) { continue; }

                            StationDistance sd = new StationDistance(net, st, statLat, statLon);

                            // Точное расстояние по дуге большого круга от ЭПИЦЕНТРА до КОНКРЕТНОЙ СТАНЦИИ
                            sd.distanceKm = EarthquakeFeature.haversine(latitude, longitude, statLat, statLon);
                            founded.add(sd); // Отправляем подготовленные данные
                        } catch (NumberFormatException nfe) {
                            // Если даже после смены индексов парсинг не удался (пришло имя города)
                            System.err.println("[💥] WARNING: Corrupted line in station response: " + line);
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[\uD83D\uDCA5] Station search error: " + e.getMessage());
        }

        // Если что-то нашли, то...
        if (!founded.isEmpty()) {
            // Сортируем по дистанции
            founded.sort(Comparator.comparingDouble(StationDistance::getDistanceKm));

            // Ограничиваем щедро до 10, чтобы без перегрузов
            int limit = Math.min(10, founded.size());

            return founded.subList(0, limit);
        }

        // Если не удалось найти станцию, то возвращаем пустой список.
        return Collections.emptyList();
    }


    /**
     * Класс метаданных и полезных методов НАЙДЕННЫХ СТАНЦИЙ,
     * в число которых входит: <i>сеть, станция, широта, долгота, дистанция в километрах</i>.
     * @see IRISWaveformsDownloader#findNearestCandidates(EarthquakeFeature, int, Set)
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

        public String getStationKey() {
            return this.network + "." + this.station;
        }

        // Геттер для сравнения
        public double getDistanceKm() {
            return distanceKm;
        }
    }

    /**
     * Метаданные результата {@code Waveform}.
     * @param station станция, которая рассматривала этот waveform
     * @param response что получили от сервера (ответ в байтовом представлении)
     * @param throwOverLatitude широта, которая по факту будет далее пробрасываться до момента локализации
     * @param throwOverLongitude долгота, которая по факту будет далее пробрасываться до момента локализации
     * @param throwOverStation код станции, который по факту будет далее пробрасываться до момента локализации
     * @param throwOverNetwork код сети станции, который по факту будет далее пробрасываться до момента локализации
     */
    public record WaveformResult(String station, HttpResponse<byte[]> response,
                                 double throwOverLatitude, double throwOverLongitude,
                                 String throwOverStation,
                                 String throwOverNetwork) {}

    /**
     * Метод, который в классе {@link IRISWaveformsDownloader} не поддерживается.
     * @throws UnsupportedOperationException если совершена попытка получить путь
     */
    public Path correctPath() {
        throw new UnsupportedOperationException("[❗] IRISWaveformsDownloader не предполагает файлового вывода");
    }
}
