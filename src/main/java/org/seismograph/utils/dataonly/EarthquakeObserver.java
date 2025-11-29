package org.seismograph.utils.dataonly;

/**
 * Интерфейс наблюдателя с одним методом, но логически НЕ ЯВЛЯЕТСЯ функциональным!
 * Типичный «скелет» для идеального наблюдателя.
 * @see EarthquakeSubject
 */
public interface EarthquakeObserver {

    /**
     * Единственный метод обновления данных. В параметре сбрасываются данные,
     * которые будут переданы в наблюдатель из субъекта.
     * @param eventDetails сообщение-посылка
     */
    void update(String eventDetails);
}