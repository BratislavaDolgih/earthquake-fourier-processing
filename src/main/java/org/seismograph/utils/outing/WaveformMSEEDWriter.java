package org.seismograph.utils.outing;

import org.seismograph.utils.Fileable;
import org.seismograph.utils.dataonly.EarthquakeFeature;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public class WaveformMSEEDWriter implements Fileable {

    private static String getTodayDateString() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE); // Формат: YYYY-MM-DD
    }

    public static Path saveToFile(EarthquakeFeature quake,
                                   String station,
                                   HttpResponse<byte[]> response) {
        Path outputPath = Paths.get( System.getProperty("user.dir"))
                .resolve("src/main/java/mseeds")
                .resolve(getTodayDateString())
                .resolve(generateFilename(quake, station));

        try {
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, response.body());

            System.out.println("[✅] Saved to: " + outputPath.getFileName());
            return outputPath;
        } catch (IOException ioe) {
            System.err.println("[❌] Failed saving... (" + ioe.getMessage() + ")");
            return null;
        }
    }

    private static String generateFilename(EarthquakeFeature quake, String st) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

        return String.format(
                "quake_M%.1f_%s_%s.mseed",
                quake.momentMagnitude(),
                quake.absoluteTime().format(formatter),
                st.replace(".", "_")
        );
    }

    @Override
    public Path correctPath() {
        return Paths.get( System.getProperty("user.dir"))
                .resolve("src/main/java/mseeds")
                .resolve(getTodayDateString());
    }
}
