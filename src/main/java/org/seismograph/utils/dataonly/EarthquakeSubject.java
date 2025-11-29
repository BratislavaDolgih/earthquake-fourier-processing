package org.seismograph.utils.dataonly;

import org.seismograph.utils.EarthquakeMonitor;
import org.seismograph.utils.JacksonQuakeParser;
import org.seismograph.utils.outing.EarthquakeJSONFileWriter;

/**
 * Интерфейс для субъекта, за которым будут наблюдать.
 * <br>Подразумевается, что на {@link EarthquakeMonitor} будут подписаны
 * {@link JacksonQuakeParser} и {@link EarthquakeJSONFileWriter}.</br>
 * @see EarthquakeObserver
 */
public interface EarthquakeSubject {

    /**
     * Метод позволяющий подписать наблюдателя на текущий субъект.
     * @param newObserver новый наблюдатель
     */
    void attach(EarthquakeObserver newObserver);

    /**
     * Метод отписки от обновлений субъекта.
     * @param existingObserver текущий наблюдатель
     */
    void detach(EarthquakeObserver existingObserver);

    /**
     * Метод оповещения всех наблюдателей о получении новых данных для отправки.
     */
    void notifyObservers();
}
