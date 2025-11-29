package org.seismograph.utils.outing;

import org.seismograph.utils.Fileable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NormalizedWaveformTXTWriter implements Fileable {

    public static Path fileSaving(double[] readyDataForFourier,
                                  String fileName) {
        Path outPath = Paths.get(System.getProperty("user.dir"))
                .resolve("src/main/java/mseeds/normalized")
                .resolve(getTodayDateString())
                .resolve(generateFilename(fileName));

        try {
            // Сначала директория!
            Files.createDirectories(outPath.getParent());

            try (BufferedWriter wr = Files.newBufferedWriter(outPath)) {

                byte onlySeven = 0;
                for (double piece : readyDataForFourier) {
                    wr.write(Double.toString(piece));
                    wr.write(' ');

                    onlySeven++;

                    if (onlySeven == 7) {
                        wr.newLine();
                        onlySeven = 0;
                    }
                }

                System.out.println("[✅] Saved to: " + outPath.getFileName());
                return outPath;
            }
        } catch (IOException ioe) {
            System.err.println("[❌] Failed saving... (" + ioe.getMessage() + ")");
            return null;
        }
    }

    private static String getTodayDateString() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE); // Формат: YYYY-MM-DD
    }

    private static String generateFilename(String fileName) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

        return changeExtension(fileName, "txt");
    }

    private static String changeExtension(String name,
                                          String newExtension) {
        int dot = name.lastIndexOf('.');
        String base = (dot == -1)
                ? name
                : name.substring(0, dot);

        return base + '.' + newExtension;
    }

    @Override
    public Path correctPath() {
        return Paths.get(System.getProperty("user.dir"))
                .resolve("src/main/java/mseeds/normalized")
                .resolve(getTodayDateString());
    }
}