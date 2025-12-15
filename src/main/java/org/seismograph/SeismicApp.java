package org.seismograph;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.seismograph.utils.EarthquakeMonitor;
import org.seismograph.utils.JacksonQuakeParser;
import org.seismograph.utils.SeismicApplicationException;
import org.seismograph.utils.dataonly.EarthquakeFeature;
import org.seismograph.utils.dataonly.ReducedComplex;
import org.seismograph.utils.download.IRISWaveformsDownloader;
import org.seismograph.utils.fouriersolver.*;
import org.seismograph.utils.outing.EarthquakeJSONFileWriter;
import org.seismograph.utils.outing.NormalizedWaveformTXTWriter;
import org.seismograph.utils.outing.WaveformMSEEDWriter;

import java.awt.*;
import java.io.File;
import java.io.IOException;

import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Класс, предоставляющий работу с сейсмическими данными, парсинг JSON и MSeed.
 * Взаимодействие происходит через несколько этапом, каждый из которых предоставляет по итогам открытый API:
 * <ul>
 *     <li> 1. <b>Фаза I: первое вхождение в программу.</b>
 *     <p>
 *         Подключаются наблюдатели к субъекту монитора для парсинга
 *         и записи в файл обработанных данных в формате JSON.
 *     </p>
 *     <li> 2. ...
 * </ul>
 * @apiNote Приложение логирует действия ИСКЛЮЧИТЕЛЬНО в консоль.
 * Внутренний логгер не предусмотрен из соображений избежания излишеств информации.
 * Весь смысл заключается в ряде Фурье, в который загоняются скаченные данные с двух видов станций,
 * необходимая информация вычисляется, но не каталогируется, потому что не происходит анализа данных
 */
public class SeismicApp {
/*
    ╭──────────────────────────────────────────────────────────────────────────────╮
    │ Блок с:                                                                      │
    │  * ГЛАВНЫМИ закрытыми полями программы (субъект и наблюдатели).              │
    │  * Конструктором.                                                            │
    ╰──────────────────────────────────────────────────────────────────────────────╯
*/
    // Монитор — основной субъект, к которому будут подписываться наблюдатели.
    private final EarthquakeMonitor monitor = new EarthquakeMonitor();

    /*
        Наблюдатель-парсер JSON-формата.
        Требуется исключительно для первоначальной выжимки данных
        (список землетрясений + фиксация пяти наиболее близких к Краснодару)
    */
    private final JacksonQuakeParser JSONparser = new JacksonQuakeParser();

    /*
        Наблюдатель-«записывальщик», фиксирующий абсолютно ВСЕ JSON-записи по земелтрясениям
        В день запуска программы в отдельный файл.
    */
    private final EarthquakeJSONFileWriter JSONfileWriter = new EarthquakeJSONFileWriter();

    /**
     * Конструктор по умолчанию: инициализация монитора
     * + подписка наблюдателей для парсинга и записи в {@code .json}.
     */
    public SeismicApp() {
        this.monitor.attach(JSONparser);
        this.monitor.attach(JSONfileWriter);
    }


/*
    ╭─────────────────────────────────────────────────────────────────────────────────────────────────╮
    │ Фаза I работы приложения                                                                        │
    │ * ВЗАИМОДЕЙСТВИЕ с ПРИЛОЖЕНИЕМ (получение топ-5 землетрясений), его методами, опциями и полями. │
    │                                                                                                 │
    │ Открытые методы:                                                                                │
    │       - constructJSON(boolean): построение директории с json внутри, а также                    │
    │                                 попытка получить waveforms по URL к iris.edu.                   │
    ╰─────────────────────────────────────────────────────────────────────────────────────────────────╯
*/
    /**
     * Список всех ПЯТИ землетрясений, наиболее ближайших к Краснодару (по долготе и широте)
     * @see JacksonQuakeParser#top5Earthquakes()
     */
    private List<EarthquakeFeature> eqs = null;

    /**
     * Созданные директории файлов
     */
    private final List<Path> createdFiles = new ArrayList<>();

    /**
     * Последняя сохранённая директория.
     */
    private Path lastSavedFile = null;

    /**
     * Метод запуска приложжения: запуск основного монитора-приёмника JSON,
     * получение 5-ти наиболее <i>впечатляющих</i> землетрясений,
     * построения директории к MSeed-файлам со скаченными землетрясениями.
     */
    public void constructJSON(boolean needToConsoleLog) throws SeismicApplicationException {
        System.out.println("————————————————————————————————————————————————————————");
        System.out.println("⚡️ Run the application (instance of SeismicApp.java) ⚡️");
        System.out.println("————————————————————————————————————————————————————————");
        try {
            System.out.println("\n-----( [Step 1/3] STARTING PARSING API FETCH )-----");
            // Монитор делает запрос, сравнивает (впервые, значит, новое), уведомляет:
            monitor.launchingMonitor();

            // Проверка Результатов Парсера
            System.out.println("\n-----( ANALYSIS RESULTS )-----");
            if (!JSONparser.top5Earthquakes().isEmpty()) {
                this.eqs = JSONparser.top5Earthquakes();
                System.out.println("✅ Found " + JSONparser.top5Earthquakes().size() +
                        " significant earthquakes (M >= " + JSONparser.getCurrentThreshold() + " in Eurasia):");
            } else {
                System.out.println("🤷‍♂️ No significant earthquakes (M >= " + JSONparser.getCurrentThreshold() +
                        ") found in Eurasia today.");
                throw new SeismicApplicationException("There haven't been any earthquakes in the world yet. " +
                        "Try again later.");
            }

        } catch (IOException e) {
            System.err.println("\n[FATAL ERROR] Failed to fetch data from API: " + e.getMessage());
        } catch (InterruptedException e) {
            // Если запрос HTTP был прерван
            System.err.println("\n[FATAL ERROR] API connection interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }

        if (needToConsoleLog) JSONparser.outputByConsole();

        // Отписка от наблюдателя
        monitor.detach(JSONfileWriter);

        System.out.println("——————————————————————————————————————————————————");
        System.out.println(">> Check your directory for the saved JSON file.");
        System.out.println("——————————————————————————————————————————————————");

        this.lastSavedFile = tryToCreateWaveforms();

        // Логируем в консольку некоторые результаты:
        this.printingPathInfo();
    }

    /**
     * Попытка создать Waveforms по скаченным данным с SeismicPortal.eu.
     * @return директория записанного файла.
     */
    private Path tryToCreateWaveforms() {
        if (this.eqs == null) {
            System.err.println("[⚠] Earthquakes are missing.");
            return null;
        }

        System.out.println("\n-----( [Step 2/3] CREATING WAVEFORM MINISEEDS )-----");
        System.out.println("=========================================================");

        Path pathToMSeedFile = null;

        for (EarthquakeFeature eq : this.eqs) {
            IRISWaveformsDownloader.WaveformResult res
                    = IRISWaveformsDownloader.generateWaveformResponse(eq);

            if (res != null && res.response() != null) {
                pathToMSeedFile = WaveformMSEEDWriter.saveToFile(
                        eq,
                        res.station(), res.response()
                );

                if (pathToMSeedFile != null) {
                    createdFiles.add(pathToMSeedFile);
                    System.out.println("[✅] Waveform saved for " + " to: " +
                            pathToMSeedFile.toAbsolutePath());
                } else {
                    System.out.println("[⚠️] Failed to save waveform data.");
                }
            }
        }

        if (createdFiles.isEmpty()) {
            System.err.println("[⚠] No .mseed files were created!");
            return null;
        }

        // Сохраняем последний записанный файл.
        return pathToMSeedFile;
    }

    /**
     * Простой вывод директории, куда сохранены все mseed-файлы.
     * @see SeismicApp#constructJSON(boolean)
     */
    private void printingPathInfo() {
        if (this.getPathList().isEmpty() || this.getPathList().getLast() == null) {
            throw new SeismicApplicationException("Paths to .mseed-files is null.");
        }
        System.out.println("[>] Directory to the .mseed files: " +
                this.getPathList().getLast());
    }

    /**
     * Открытый метод получения списка директорий файлов с расширением .mseed.
     * @return список зафиксированных директорий ИЛИ {@code null} в ином случае.
     */
    public List<Path> getPathList() {
        return List.copyOf(Objects.requireNonNull(this.createdFiles));
    }

    /**
     * Открытый метод API приложения на получение пяти землетрясений.
     * @return список топ-5 землетрясений
     */
    public List<EarthquakeFeature> getFiveNearestEarthquakes() {
        return List.copyOf(Objects.requireNonNull(this.eqs));
    }


/*
    ╭───────────────────────────────────────────────────────────────╮
    │ Фаза II работы приложения (only for «Вычислительные методы»): │
    │  * ВЗАИМОДЕЙСТВИЕ с ПРИЛОЖЕНИЕМ (parsing & merging signal).   │
    ╰───────────────────────────────────────────────────────────────╯
*/
    /**
     * Ядро с парсингом и склеиванием непосредственно сигнала.
     */
    private SignalNormalization mseedKernel = new SignalNormalization(new NormalizatorSamples());


    /**
     * Метод парсинга файлов с расширением {@code .mseed}.
     * @param correctlyPath корректный путь до correctlyPath
     */
    public double[] parseAsMSeed(Path correctlyPath) throws Exception {
        if (correctlyPath == null) {
            throw new IllegalArgumentException("Incorrect path to .mseed-files");
        }

        var mseedParser = FourierSeriesComputer.parserOf(correctlyPath);

        List<FourierSeriesComputer.SampledSignal> sampledBlocks = FourierSeriesComputer.convertClosely(mseedParser);

        // Обязательная строчка! Без этого не получится сделать merging!
        // Выполнение всего pipeline и ВОЗВРАТ результата, о боже...
        return mseedKernel.notifySubscriber(sampledBlocks)
                .normalizeOpenly(); // Данные заведены в наблюдатель за числами.

    }

    /**
     * Просмотр и запись обработанных, отфильтрованных данных ({@code .mseed} -> {@code .txt}).
     * @param folderDirectory
     */
    public void readFolder(String folderDirectory) {
        File dir = new File(folderDirectory);

        File[] files = dir.listFiles();

        if (files != null) {
            for (File f : files) {

                if (!f.getName().endsWith(".mseed")) continue;

                System.out.println("➡ Проверка: " + f.getName());

                try {
                    this.savingFile(parseAsMSeed(f.toPath()), f.getName());
                } catch (Exception e) {
                    System.out.println("❌ Забракованный: " + e.getMessage());
                    this.createdFiles.remove(f.toPath());
                }
            }
        }

        System.out.println("✔️ OK: " + createdFiles.size() + " files \uD83D\uDE04");
    }

    public void readFolder(Path correctlyPath) {

        File dir = correctlyPath.toFile();

        if (!dir.exists()) {
            throw new IllegalArgumentException("Папка не существует: " + correctlyPath);
        }

        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Указан не каталог: " + correctlyPath);
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            throw new IllegalStateException("Папка пустая: " + correctlyPath);
        }

        readFolder(correctlyPath.toString());
    }

    private void savingFile(double[] data, String name) {
        NormalizedWaveformTXTWriter.fileSaving(data, name);
    }

    public Path getLastMSeedPath() {
        return this.createdFiles.getLast();
    }

    public Path getMSeedDirectory() { return getLastMSeedPath().getParent(); }

/*
    ╭────────────────────────────────────────────────────────────────╮
    │ Фаза III работы приложения:                                    │
    │  * Вычисление ряда Фурье по «сокращённой комплексной формуле». │
    ╰────────────────────────────────────────────────────────────────╯
*/
    public static ReducedComplex[] fourierCalculate(Path directory) {
        if (directory == null) {
            throw new SeismicApplicationException("Directory of normalized files isn't exist.");
        }
        if (!Files.exists(directory)) {
            throw new SeismicApplicationException("Directory does not exist: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new SeismicApplicationException("Path is not a directory: " + directory);
        }

        double[] rawInput;

        try {
            Path signalFile = null;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.txt")) {
                for (Path entry : stream) {
                    signalFile = entry;
                    break;
                }
            } catch (IOException ioe) {
                throw new SeismicApplicationException("File of " + directory + " has problems!");
            }

            if (signalFile == null) {
                throw new SeismicApplicationException("No .txt files found in " + directory);
            }


            try {
                String content = Files.readString(signalFile);
                String[] tokens = content.split("\\s+|,");

                List<Double> tempList = new ArrayList<>(tokens.length);

                for (String token : tokens) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) {
                        tempList.add(Double.parseDouble(trimmed));
                    }
                }

                double[] rawSignal = new double[tempList.size()];
                for (int i = 0; i < tempList.size(); i++) {
                    rawSignal[i] = tempList.get(i);
                }

                rawInput = rawSignal;
            } catch (IOException ioe) {
                throw new SeismicApplicationException("Problem with reading lines " + directory);
            }
        } catch (Exception e) {
            throw new SeismicApplicationException("Error in fourierCalculate().");
        }

        return MainFourierSolver.analyze(rawInput);
    }

/*
    ╭──────────────────────────╮
    │ VISUALISING, JAVA FX API │
    ╰──────────────────────────╯
*/

    public static void setVisualizingData(ReducedComplex[] fftResult){
        FFTVisualizer.setData(fftResult);
    }

    public static void visualize(ReducedComplex[] fftResult) {
        Application.launch(FFTVisualizer.class);
    }

    public static class FFTVisualizer extends Application {
        private static ReducedComplex[] fftResultStatic;
        public static final double NORMALIZED_SAMPLING_RATE = 100.0;

        public FFTVisualizer() {}

        public static void setData(ReducedComplex[] fftResult) {
            fftResultStatic = fftResult;
        }

        @Override public void start(Stage stage) {
            int N = fftResultStatic.length;
            final NumberAxis xAxis = new NumberAxis();
            final NumberAxis yAxis = new NumberAxis();
            xAxis.setLabel("Частота (Hz)");
            yAxis.setLabel("Амплитуда");

            final LineChart<Number, Number> lineChart = new LineChart<>(xAxis, yAxis);
            lineChart.setTitle("Спектр FFT");

            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName("Амплитуда");

            final int step = 100;

            for (int k = 0; k < N / 2; k += step) {
                double real = fftResultStatic[k].reality();
                double imag = fftResultStatic[k].imaginary();
                double magnitude = Math.sqrt(real * real + imag * imag) / N;  // Нормализация
                double frequency = k * NORMALIZED_SAMPLING_RATE / N;
                series.getData().add(new XYChart.Data<>(frequency, magnitude));
            }

            lineChart.getData().add(series);

            Scene scene = new Scene(lineChart, 1280, 720);
            stage.setScene(scene);
            stage.show();
        }
    }


/*
    ╭────────────────────────────────────╮
    │ Специальные поля / блоки / методы. │
    ╰────────────────────────────────────╯
*/
    /*
        Настройка корректной кодировки ВСЕГО заранее, перед запуском программы
        (потому что статический блок).
     */
    static {
        System.setProperty("file.encoding", "UTF-8");
        System.setOut(new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
    }

/*
    ╭─────────────────────────────╮
    │ DEPRECATED METHODS, FIELDS. │
    ╰─────────────────────────────╯
*/

    @Deprecated
    private double[] mergeSignalsAsArray(List<FourierSeriesComputer.SampledSignal> list) {
        int totalSize = 0;
        for (FourierSeriesComputer.SampledSignal s : list) {
            totalSize += s.amplitudes().size();
        }

        double[] result = new double[totalSize];

        int idx = 0;
        for (FourierSeriesComputer.SampledSignal s : list) {
            for (Double a : s.amplitudes()) {
                result[idx++] = a;
            }
        }

        return result;
    }
}
