package org.seismograph;

import edu.iris.dmc.seedcodec.*;

public class EarthquakeMain {

    public static void main(String[] args) {

        // Построение консольного приложения по обработке сейсмических процессов.
        SeismicApp app = new SeismicApp();

        try {
            app.constructJSON(true);
            if (app.readFolder(app.getMSeedDirectory())) {
                app.runLocalizationPipeline();
            }

            // SeismicApp.setVisualizingData(complexCoordinates);
            // SeismicApp.visualize(complexCoordinates);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}