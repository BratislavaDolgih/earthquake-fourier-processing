package org.seismograph.utils;

import org.seismograph.utils.dataonly.EarthquakeObserver;
import org.seismograph.utils.dataonly.EarthquakeSubject;
import org.seismograph.utils.download.ESMCJSONDownloader;
import org.seismograph.utils.outing.EarthquakeJSONFileWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Главный класс-субъект, который выполняет одну главную функцию: оповещение наблюдателей для
 * парсинга землетрясений с Единого Европейского портала данных о сеймических явлениях и угрозах.
 * <br>Сам по себе выполнять парсинг не будет, подключение наблюдателей <u>ОБЯЗАТЕЛЬНО</u>.</br>
 * @see JacksonQuakeParser
 * @see EarthquakeJSONFileWriter
 */
public class EarthquakeMonitor implements EarthquakeSubject {

    /**
     * Список всех наблюдателей, подписанных на обновление данных монитора.
     * Существует для глобального (может быть и локального) оповещения наблюдателей.
     */
    private final List<EarthquakeObserver> observers = new ArrayList<>();

    /**
     * Последние полученные JSON данные в строковом представлении.
     * Хранятся для возможной отладки.
     */
    private String latestJSONData = null;

    /* ================ ПЕРЕОПРЕДЕЛЕНИЕ МЕТОДОВ ИНТЕРФЕЙСА SUBJECT ================ */

    @Override public void attach(EarthquakeObserver o) {
        observers.add(o);
        System.out.println("[✅] New observer (" +
                o.getClass().getSimpleName() + ") subscribed!");
    }

    @Override public void detach(EarthquakeObserver o) {
        observers.remove(o);
        System.out.println("[❌] Observer " +
                o.getClass().getSimpleName() + " became unsubscribed for latest!");
    }

    @Override public void notifyObservers() {
        if (this.latestJSONData != null)  {
            for (EarthquakeObserver obs : observers) {
                obs.update(this.latestJSONData);
            }
        }
    }

    /* ================ САМОСТОЯТЕЛЬНАЯ ЛОГИКА ================ */

    /**
     * Метод запуска монитора, всей его логики: <b>генерация запроса</b> на сервер сеймического портала,
     * <b>парсинг</b> полученных данных. <br>На выходе запущенный монитор ничего не возвращает,
     * всю логику выполняют исключительно наблюдатели</br>
     * @throws InterruptedException
     * @throws IOException
     */
    public void launchingMonitor()
            throws InterruptedException, IOException {
        String newJSONData = ESMCJSONDownloader.generateResponse().body();

        if (!newJSONData.equals(this.latestJSONData)) {
            this.latestJSONData = newJSONData;
            System.out.println("[i] New data accepted!");
            notifyObservers();
        } else {
            System.out.println("The data remained the same.");
        }
    }

    // В разработке (должен будет вызываться, когда программа перейдёт к вычислению ряда Фурье)
    @Deprecated // пока флажок установлен
    public void shutdownMonitor() {

    }
}
