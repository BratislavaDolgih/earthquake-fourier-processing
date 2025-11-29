package org.seismograph.utils;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Интерфейс, маркирующий реализующие классы как "подлежат записи в файл",
 * или "могут читать и взаимодействовать с файлами"
 */
public interface Fileable {

    /**
     * Каждый реализующий класс должен отчитаться и предоставить корректную директорию,
     * куда будут писаться файлы!
     * @return готовый путь, по которому будет запись в файл
     */
    Path correctPath();

    /**
     * Метод, который записывает дату в корректном виде ({@code yyyy-mm-dd}).
     * @return строку с готовой датой сегодняшнего дня
     */
    static String formattedToday() {
        LocalDateTime today = LocalDateTime.now();
        return String.format("%04d-%02d-%02d",
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth());
    }
}
