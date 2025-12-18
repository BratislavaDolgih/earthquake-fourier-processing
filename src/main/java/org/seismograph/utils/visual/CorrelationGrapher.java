package org.seismograph.utils.visual;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class CorrelationGrapher extends Application {

    private static double[] correlationData;
    private static double fs;
    private static String title;

    /**
     * Основной метод для вызова из SeismicApp.
     * Запускает JavaFX Application Thread и модальное окно.
     */
    public static void showCorrelation(double[] corrData, double samplingRate, String chartTitle) {
        // Сохраняем данные для использования в потоке JavaFX
        correlationData = corrData;
        fs = samplingRate;
        title = chartTitle;

        // Запускаем JavaFX Application Thread.
        launch(CorrelationGrapher.class);
    }

    @Override
    public void start(Stage primaryStage) {

        Stage graphStage = new Stage();

        // --- Создание осей ---
        // Ось X: Задержка (в секундах)
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Задержка (Сдвиг) в секундах");

        // Ось Y: Коэффициент корреляции (Нормализованный)
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Корреляция");

        // --- Создание графика ---
        final LineChart<Number, Number> lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setTitle(title);
        lineChart.setCreateSymbols(false); // Не показываем точки, только линии

        // --- 3. Подготовка серии данных ---
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("Кросс-корреляция");

        // Длина массива корреляции (N_corr = N_A + N_B - 1)
        int N_corr = correlationData.length;
        // Индекс нулевой задержки (нужен для правильного отображения оси X)
        // Если ты передавала окна A и B одинаковой длины N_win, то zeroIndex = N_win - 1
        int zeroIndex = (int)(N_corr / 2.0); // Простое приближение для центра

        ObservableList<XYChart.Data<Number, Number>> dataList = FXCollections.observableArrayList();

        for (int i = 0; i < N_corr; i++) {
            // Преобразуем индекс 'i' в секунды задержки:
            // lag_sec = (i - zeroIndex) / fs
            double lagInSec = (i - zeroIndex) / fs;

            dataList.add(new XYChart.Data<>(lagInSec, correlationData[i]));
        }

        series.setData(dataList);
        lineChart.getData().add(series);

        // --- 4. Отображение модального окна ---
        Scene scene = new Scene(lineChart, 1280, 720);
        graphStage.setScene(scene);
        graphStage.setTitle("Демонстрация графика кросс-корреляции на основе FFT");

        // ЭТО ДЕЛАЕТ ОКНО МОДАЛЬНЫМ И БЛОКИРУЕТ ВЫПОЛНЕНИЕ:
        graphStage.initModality(Modality.APPLICATION_MODAL);

        graphStage.showAndWait(); // <--- Блокировка!

        primaryStage.close();
    }
}