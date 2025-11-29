package org.seismograph.utils.outing;

import org.seismograph.utils.dataonly.EarthquakeObserver;
import org.seismograph.utils.Fileable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Финальный класс-наблюдатель, который строку с {@code JSON} записывает в файл как есть.
 * Иными словами, мы фиксируем скаченные данные и в случае
 * номинального отсутствия Интернет-соединения, потенциально можно было бы обработать полученные файлы.
 * @see org.seismograph.utils.download.ESMCJSONDownloader
 */
public final class EarthquakeJSONFileWriter implements EarthquakeObserver, Fileable {

    /**
     * Метод обновления данных и перезапись их в отдельный файл
     * (либо дозапись в существующий)
     * @param JSONString исходные данные {@code JSON}, полученные из
     * {@link org.seismograph.utils.download.ESMCJSONDownloader}
     */
    @Override public void update(String JSONString) {

        // Путь к папке (рядом с org.*)
        Path p = correctPath();

        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(
                    p.toAbsolutePath(),
                    JSONString,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            System.out.println("[i] JSON data wrote to the directory:");
            System.out.println("> " + p.toAbsolutePath().toString());
        } catch (IOException ioe) {
            System.err.println("Writing file error as Observer!");
            System.err.println("-> Path: " + p.toAbsolutePath());
            System.err.println("-> Reason: " + ioe.getMessage());
        }
    }

    @Override public Path correctPath() {
        return Paths.get( System.getProperty("user.dir"))
                .resolve("src/main/java/eqsout")
                .resolve("earthquake_response_"
                        + Fileable.formattedToday() + ".json");
    }
}
