package org.seismograph.utils.download;

// Импорт всех необходимых атрибутов.
import org.seismograph.utils.EarthquakeMonitor;
import org.seismograph.utils.Fileable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

/**
 * Вспомогательный класс, который отправляет GET-запрос к сейсмическому API (ESMC).
 * Возвращать данные будет в {@code JSON} формате с данными о землетрясениях за сегодня.
 * @see EarthquakeMonitor
 * @see org.seismograph.utils.JacksonQuakeParser
 */
public class ESMCJSONDownloader implements Fileable {

    /**
     * Статический и единственный метод в классе-хелпере по HTTP-запросу к сейсмическому API.
     * @return ответ от сервера, где {@code body()} — строка с «сырым» {@code JSON}.
     * @throws IOException возможные проблемы с сетью
     * @throws InterruptedException случай, когда поток был прерван во время ожидания ответа от сервера
     */
    public static HttpResponse<String> generateResponse()
            throws IOException, InterruptedException {

        // Формирование URL к сейсмическому порталу (ESMC — европейская, по всему миру)
        String toEMSC =
                "https://www.seismicportal.eu/fdsnws/event/1/query?format=json" + // Просим ответ в JSON
                        "&starttime=" + Fileable.formattedToday() +   // Временные диапазоны:
                        "&endtime=" + Fileable.formattedToday();      // Выставлен на каждый день

        // Создание HTTP клиента, который управляет подключением, TCP-сокетами.
        try (HttpClient client = HttpClient.newHttpClient()) {
            // Формируем запрос к серверу:
            HttpRequest request = HttpRequest.newBuilder() // Билдер экземпляра
                    .uri(URI.create(toEMSC))               // Адрес запроса (готовый URI строки ранее)
                    .build();                              // Построение объекта запроса

            // Полученный ответ от сервера станции.
            // Его отправляем на созданного клиента HTTP. Ответ читается как строка
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        /* Если сервер не отвечает, то на этом этапе вылетит IOException
           Номинально также и InterruptedException... */

            // Очевидная проверка: если сервер ничё не вернул, значит, данных пока нет за сегодня!
            if (response.body().isBlank()) {
                System.out.println("[!] No earthquake data for now!");
            }

            // Возвращаем объект ответа целиком.
            return response;
        }

        /*
            В дальнейшем из ответа можно будет получить .statusCode() и .body()

            .statusCode() может быть:
                200 — ok
                204 — данные не отданы сервером
                404 — не найдена ссылка
                500 — ошибка на стороне сервера
                429 — ограничение на сбор данных со стороны сервера
         */
    }

    /**
     * Метод, который в классе {@link ESMCJSONDownloader} не поддерживается.
     * @throws UnsupportedOperationException если совершена попытка получить путь
     */
    public Path correctPath() {
        throw new UnsupportedOperationException("[❗] ESMCJSONDownloader не предполагает файлового вывода");
    }
}