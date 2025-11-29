package org.seismograph.utils.dataonly;

import org.seismograph.utils.EarthquakeMonitor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Рекорд, который является объектом готовых данных для дальнейшей обработки
 * с необходимыми полями. {@link EarthquakeMonitor} качает {@code JSON}
 * и через парсер {@link org.seismograph.utils.JacksonQuakeParser} загоняет данные в рекорд.
 * @param flynnRegion регион по Флинну (содержится в JSON)
 * @param absoluteTime время, которое локализуется в часовой пояс МСК
 * @param coordinates долгота и широта
 * @param depth глубина землетрясения
 * @param momentMagnitude универсальная магнитуда для комплексной оценки
 */
public record EarthquakeFeature(String flynnRegion,
                                LocalDateTime absoluteTime,
                                List<Double> coordinates,
                                double depth,
                                double momentMagnitude) {

    public double latitude() {
        return this.coordinates.getLast();
    }

    public double longitude() {
        return this.coordinates.getFirst();
    }


    /**
     * <b>Хаверсинус (Haversine)</b> — математическая формула,
     * которая считает кратчайшее расстояние между двумя тчоками на сфере.
     * <p>Короче говоря, расстояние по поверхности Земли, как если бы мы летели по дуге большого круга.</p>
     *
     * @return Расстояние между точками (в км).
     */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        // Перевод градусов в радианы (основная единица измерения для тригонометрии)
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);

        // Формула Хаверсинуса (Haversine), расписанная через синусы.
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c; // d = R * c
    }

    // Радиус Земли в километрах
    private static final double EARTH_RADIUS_KM = 6371.0;
}
