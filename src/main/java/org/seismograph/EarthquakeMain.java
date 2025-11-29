package org.seismograph;

import edu.iris.dmc.seedcodec.*;
import org.seismograph.utils.dataonly.ReducedComplex;

import java.nio.file.Path;
import java.util.Arrays;

public class EarthquakeMain {

    public static void main(String[] args) {

        // Построение консольного приложения по обработке сейсмических процессов.
        SeismicApp app = new SeismicApp();

        try {
//            app.constructJSON(true);
//            app.readFolder(app.getMSeedDirectory());
            ReducedComplex[] complexCoordinates = app.fourierCalculate(Path.of(
                    "C:/Users/User333/(IntellijIDEA) Java Projects/" +
                            "EarthquakeTimelapse/src/main/java/mseeds/normalized/2025-11-22"));
            System.out.println(Arrays.toString(complexCoordinates));

            SeismicApp.setVisualizingData(complexCoordinates);
            SeismicApp.visualize(complexCoordinates);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}