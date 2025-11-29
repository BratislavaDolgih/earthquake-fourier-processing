package org.seismograph.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.seismograph.utils.dataonly.EarthquakeObserver;
import org.seismograph.utils.dataonly.EarthquakeFeature;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Класс, предназначенный для парсинга строк {@code JSON} формата для загона
 * в монитор {@link EarthquakeMonitor} готовых данных для дальнейшего вычисления.
 */
public class JacksonQuakeParser implements EarthquakeObserver {
/*
    ╭──────────────────────────────────────────────────────────────────────────────╮
    │ Блок с:                                                                      │
    │  * КОНСТАНТНЫМИ и обычными ПОЛЯМИ, КОТОРЫЕ ИСПОЛЬЗУЮТСЯ В ПРОЦЕССЕ ПАРСИНГА. │
    │  * Двумя перегрузками конструкторов с настройкой пользовательского фильтра.  │
    ╰──────────────────────────────────────────────────────────────────────────────╯
*/
    // --- Координаты Краснодара для расчета близости ---
    private static final double KRASNODAR_LAT = 45.04;
    private static final double KRASNODAR_LON = 38.98;

    // Радиус Земли в километрах (используется для расчета расстояния по сфере)
    private static final double EARTH_RADIUS_KM = 6371;

    /**
     * Статическая константа-фильтр землетрясений по моментной магнитуде.
     * Все события с магнитудой меньше игнорируются.
     */
    public static final double DEFAULT_MAGNITUDE_THRESHOLD = 4.0;

    /**
     * Массив землетрясений (подготовленные данные — топ-5)
     * @see EarthquakeFeature
     */
    private final ArrayList<EarthquakeFeature> top5NearestEarthquakes = new ArrayList<>(5);

    /**
     * Пользовательский фильтр магнитуд, который настраивается для парсинга
     * по указанной моментной магнитуде.
     */
    private double customMagnitudeFilter;

    /**
     * Конструктор по умолчанию с дефолтным фильтром моментной магнитуды
     */
    public JacksonQuakeParser() {
        this.customMagnitudeFilter = DEFAULT_MAGNITUDE_THRESHOLD;
    }

    /**
     * Конструктор, настраивающий пользовательский фильтр
     * @param customThreshold значение моментной магнитуды
     */
    public JacksonQuakeParser(double customThreshold) {
        validateThreshold(customThreshold);
    }


/*
    ╭──────────────────────────────────────────────────────────────────────────────╮
    │ Блок с:                                                                      │
    │  * Основным методом обновления данных (update(String JSON)).                 │
    │  * Рекорд с краткими метаданными.                                            │
    ╰──────────────────────────────────────────────────────────────────────────────╯
*/
    /**
     * Временный рекорд для хранения метаданных
     * @param feature землетрясение конкретное
     * @param distance расстояние
     */
    private record QuakePair(EarthquakeFeature feature, double distance) {}

    /**
     * Переопределённый метод обновления данных парсинга, в случае,
     * когда с сервера поступили новые записи по землетрясениям.
     * <br>Повторные вызовы не очищаются, а добавляются к существующим данным</br>
     * @param inputJSONString поступившие данные {@code JSON} в строковом представлении
     */
    @Override public void update(String inputJSONString) {

        // Хелпер будет парсить JSON напрямую
        ObjectMapper mapHelper = new ObjectMapper();

        List<QuakePair> pairs = new ArrayList<>();

        try {
            // Загоняем в объект JSON дерево записей
            JsonNode root = mapHelper.readTree(inputJSONString);

            // Цикл по всем событиям, которые описаны в JSON
            for (JsonNode feature : root.path("features")) {
                JsonNode props = feature.path("properties");

                // Фильтруются ТОЛЬКО землетрясения.
                if (!"ke".equals(props.path("evtype").asText())) continue;

                // Редактируется «сырое» поле времени.
                LocalDateTime editedTime = getValidateTime(props.path("time").asText());
                double momentMag = convertToMw(props.path("magtype").asText(), props.path("mag").asDouble());

                // Фильтрация данных по магнитуде и континенту.
                if (notEurasia(props)) continue;

                // Хаверсинусом находить дистанцию
                double distanceKm = EarthquakeFeature.haversine(
                        KRASNODAR_LAT, KRASNODAR_LON,
                        props.path("lon").asDouble(),
                        props.path("lat").asDouble()
                );

                // Загоняем в массив землетрясений КОРРЕКТНО прочитанные
                EarthquakeFeature newFeature = new EarthquakeFeature(
                        props.path("flynn_region").asText(),     // Где произошло
                        editedTime,                                 // Время события (локализованное)
                        List.of(
                                props.path("lon").asDouble(),    // Долгота
                                props.path("lat").asDouble()     // Широта
                        ),
                        props.path("depth").asDouble(),          // Глубина землетрясения
                        Math.round((momentMag * 10000.0) / 1000.0) / 10.0
                );

                if (invalidMagnitude(momentMag)) continue;

                pairs.add(new QuakePair(newFeature, distanceKm));
            }

            // Сортировка по дистанции (от ближнего до дальнего)
            pairs.sort(Comparator.comparingDouble(QuakePair::distance));

            this.top5NearestEarthquakes.clear();

            // Добавляем <= 5 землетрясений
            byte hasNow = 1;
            for (var pair : pairs) {
                if (hasNow++ > 5) { break; }
                this.top5NearestEarthquakes.add(pair.feature());
            }

            System.out.printf("✅ Found %d nearest earthquakes (M >= 4.0 to Krasnodar) out of %d processed:%n",
                    this.top5NearestEarthquakes.size(), pairs.size());
        } catch (JsonMappingException jme) {

            /*
            В этой ветке catch следует ПРОМОЛЧАТЬ,
            потому что данные гарантированно поступают корректным образом.
             */
        } catch (JsonProcessingException jpe) {
            // Указание причины проблемы парсинга
            System.err.println(jpe.getMessage());
        }
    }


/*
    ╭──────────────────────────────────────────────────────────────────────────────╮
    │ Блок с ОТКРЫТЫМ API КЛАССА JacksonQuakeParser                                │
    ╰──────────────────────────────────────────────────────────────────────────────╯
*/
    /**
     * Геттер текущего порога сравнения магнитуды землетрясения.
     * @return моментная магнитуда, которая используется для парсинга
     */
    public double getCurrentThreshold() {
        return this.customMagnitudeFilter;
    }

    /**
     * Спаршенные данные из JSON землетрясений
     * @return список землетрясений с их характеристиками
     */
    public final ArrayList<EarthquakeFeature> top5Earthquakes() {
        return new ArrayList<>(this.top5NearestEarthquakes);
    }

    /**
     * Отладочный метод получившихся (пропаршенных) данных о землетрясениях.
     */
    public void outputByConsole() {
        if (!this.top5NearestEarthquakes.isEmpty()) this.top5NearestEarthquakes.forEach(System.out::println);
    }


/*
    ╭──────────────────────────────────────────────────────────────────────────────╮
    │ Блок с УТИЛИТНЫМИ МЕТОДАМИ ПРОВЕРКИ УСЛОВИЙ                                  │
    ╰──────────────────────────────────────────────────────────────────────────────╯
*/
    /**
     * Метод эмпирической аппроксимации в универсальную шкалу (моментная магнитуда)
     * @param curType тип шкалы землетрясения (описано в JSON полем {@code magtype})
     * @param mag магнитуда в исходной шкале
     * @return значение в шкале моментной магнитуде
     */
    private double convertToMw(String curType, double mag) {
        return switch(curType) {
            case "mb" -> 0.67 * mag + 2.07;
            case "ml" -> 0.85 * mag + 0.15;
            default   -> mag;
        };
    }

    /**
     * Установка нового порога сравнения магнитуды.
     * @param newThreshold число, показывающее новый порог моментной магнитуды
     */
    private void validateThreshold(double newThreshold) {
        if (newThreshold < 0.00) {
            this.customMagnitudeFilter = DEFAULT_MAGNITUDE_THRESHOLD;
        } else if (newThreshold > 12.00) {
            this.customMagnitudeFilter = 12.0;
        } else {
            this.customMagnitudeFilter = newThreshold;
        }
    }

    /**
     * Приватный метод валидации по магнитуде
     * @param momentMagnitude моментная магнитуда
     */
    private boolean invalidMagnitude(double momentMagnitude) {
        final double MAGNITUDE_EPS = 1e-10;
        double rounded = Math.round(momentMagnitude * 10.0) / 10.0;
        return rounded + MAGNITUDE_EPS < this.customMagnitudeFilter;
    }

    /**
     * Проверка полей широты и долготы из JSON данных для проверки на континент.
     * <br>Пределы Евразийской плиты (грубо):
     * долгота — {@code [-10°...180°]}; широта — {@code [-10°...82°]}.</br>
     */
    private boolean notEurasia(JsonNode properties) {
        double minLat = -10.0, maxLat = 82.0;
        double minLon = -10.0, maxLon = 180.0;

        double lon = properties.path("lon").asDouble(),
                lat = properties.path("lat").asDouble();

        // Нормализация в (-180, 180)
        double normalizedLon = ((lon + 180.0) % 360.0);
        if (normalizedLon <= 0) {
            normalizedLon += 360.0;
        }
        normalizedLon -= 180.0;

        return !(lat >= minLat && lat <= maxLat && normalizedLon >= minLon && normalizedLon <= maxLon);
    }

    /**
     * Валидация времени: удаление суффикса 'Z' (UTC) и парсинг по ISO 8601.
     * @param raw сырое время из JSON
     */
    private LocalDateTime getValidateTime(String raw) {
        return LocalDateTime.parse(
                raw.replace("Z", ""),
                DateTimeFormatter.ISO_DATE_TIME
        );
    }
}
