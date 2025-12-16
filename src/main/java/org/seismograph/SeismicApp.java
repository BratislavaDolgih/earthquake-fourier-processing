package org.seismograph;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.stage.Stage;
import org.seismograph.utils.EarthquakeMonitor;
import org.seismograph.utils.JacksonQuakeParser;
import org.seismograph.utils.SeismicApplicationException;
import org.seismograph.utils.dataonly.EarthquakeFeature;
import org.seismograph.utils.removal.ReducedComplex;
import org.seismograph.utils.download.IRISWaveformsDownloader;
import org.seismograph.utils.fouriersolver.*;
import org.seismograph.utils.outing.EarthquakeJSONFileWriter;
import org.seismograph.utils.outing.NormalizedWaveformTXTWriter;
import org.seismograph.utils.outing.WaveformMSEEDWriter;

import static org.seismograph.utils.fouriersolver.SeismicSignalExtractor.SampledSignal;

import java.io.File;
import java.io.IOException;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

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
    │                 tryToCreateWaveforms(): попытка получить waveforms по URL к iris.edu.           │
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
        System.out.println("⚡️ ЗАПУСК приложения (instance of SeismicApp.java) ⚡️");
        System.out.println("————————————————————————————————————————————————————————");
        try {
            System.out.println("\n==---( [Step 1/4] СТАРТОВАЛ ПАРСИНГ JSON с seismicportal.eu )---==");
            // Монитор делает запрос, сравнивает (впервые, значит, новое), уведомляет:
            monitor.launchingMonitor();

            // Проверка Результатов Парсера
            System.out.println("\n==---( АНАЛИЗ ПОЛУЧЕННЫХ РЕЗУЛЬТАТОВ )---==");
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
            return;
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

    // Костыль, помогающий в дальнейшем протолкнуть данные о локации станции в локализацию.
    private record WaveformsCoordinatesPair(double latitude, double longitude) {}
    // Также костыль, помогающий потом правильно НАЙТИ координаты по коду станции NET.STA
    private final Map<String, WaveformsCoordinatesPair> wfCoords = new HashMap<>();


    /**
     * Попытка создать Waveforms по скаченным данным с SeismicPortal.eu.
     * @return директория записанного файла.
     */
    private Path tryToCreateWaveforms() {
        if (this.eqs == null) {
            System.err.println("[⚠] Earthquakes are missing.");
            return null;
        }

        System.out.println("\n-----( [Step 2/4] ПОПЫТКА СКАЧАТЬ WAVEFORMS (ГЕНЕРАЦИЯ ФАЙЛОВ .mseed) )-----");
        System.out.println("=========================================================");

        Path pathToMSeedFile = null;

        for (EarthquakeFeature eq : this.eqs) {

            // UPDATE (15.12): теперь возвращаем СПИСОК РЕЗУЛЬТАТОВ!
            List<IRISWaveformsDownloader.WaveformResult> results
                    = IRISWaveformsDownloader.downloadBestWaveforms(eq);

            if (results == null || results.isEmpty()) {
                System.err.println("[⚠️] Не удалось найти и скачать данные ни с одной станции для землетрясения "
                        + eq.flynnRegion());
                continue; // Идём к следующему землетрясению
            }

            for (IRISWaveformsDownloader.WaveformResult result : results) {
                Path currentPathToMSeedFile = WaveformMSEEDWriter.saveToFile(
                        eq,
                        result.station(),
                        result.response()
                );

                if (currentPathToMSeedFile != null) {
                    createdFiles.add(currentPathToMSeedFile);

                    System.out.println("[✅] Waveform saved for " + result.station() + " to: " +
                            currentPathToMSeedFile.toAbsolutePath());

                    // Сохраняем путь для возврата
                    lastSavedFile = currentPathToMSeedFile;

                    // КОСТЫЛЬ (тупанул, но ладно) ! ! !
                    String reservedStationKey = result.throwOverNetwork() + "." + result.throwOverStation();

                    this.wfCoords.put(
                            reservedStationKey,
                            new WaveformsCoordinatesPair(
                                    result.throwOverLatitude(),
                                    result.throwOverLongitude()
                            )
                    );
                } else {
                    System.out.println("[⚠️] Failed to save waveform data for station: " + result.station());
                }
            }
        }

        if (createdFiles.isEmpty()) {
            System.err.println("[⚠] No .mseed files were created!");
            return null;
        }

        // Сохраняем последний записанный файл.
        return lastSavedFile;
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
    ╭────────────────────────────────────────────────────────────────╮
    │ Фаза II работы приложения (также для «Вычислительнх методов»): │
    │  * ВЗАИМОДЕЙСТВИЕ с ПРИЛОЖЕНИЕМ (parsing & merging signal).    │
    ╰────────────────────────────────────────────────────────────────╯
*/

    /**
     * Закрытый метод парсинга + перегонка и мёрджинг сигнала в один непрерывный.
     */
    private Map<String, SampledSignal> parseAsMSeed(
            Path correctlyPath,
            String stationKey,
            double lat,
            double lon
    ) throws Exception {
        if (correctlyPath == null) throw new IllegalArgumentException("Incorrect path to .mseed-files");

        var mseedParser = SeismicSignalExtractor.parserOf(correctlyPath);

        // Проброс: передаём все данные в mergeBy.
        // А позже уже в изменённый SampledSignal'е будет лежать информация о широте и долготе.
        Map<String, SampledSignal> mergedSignals = SeismicSignalExtractor.mergeByChannel(
                mseedParser,
                stationKey,
                lat,
                lon
        );

        // Ничего не теряем!
        return mergedSignals;
    }

    // Малейший костылёк, который будет получать проброшенные 100 раз метаданные
    private List<Map<String, SampledSignal>> signalMaps = null;

    /**
     * Просмотр и запись обработанных, отфильтрованных данных ({@code .mseed} -> {@code .txt}).
     * @param folderDirectory директория папки
     */
    public boolean readFolder(String folderDirectory) {
        SeismicApp.validateFolderAsException(Path.of(folderDirectory));
        SeismicApp.validateForExisting(Path.of(folderDirectory));

        File[] files = new File(folderDirectory).listFiles();

        this.signalMaps = new ArrayList<>();

        boolean saved = false;

        if (files != null) {
            for (File f : files) {

                if (!f.getName().endsWith(".mseed")) continue;

                System.out.println("➡ Проверка: " + f.getName());

                try {
                    // --------------------------------------------
                    String filename = f.getName();
                    String stationKey = extractStationKeyFromFilename(filename);

                    WaveformsCoordinatesPair coords = this.wfCoords.get(stationKey);
                    if (coords == null) {
                        System.err.println("❌ Координаты станции " + stationKey +
                                " не найдены. Пропускаем файл: " + filename);
                        continue;
                    }

                    // --------------------------------------------

                    // Данные с карты записываются позже в список карт, поэтому
                    // можно сделать костыль в виде списка карт и проходится по индексу.
                    Map<String, SampledSignal> mergedSignals = parseAsMSeed(
                            f.toPath(),
                            stationKey,
                            coords.latitude(),
                            coords.longitude()
                    );

                    // Сохраняем при TXT-файла, по одному на каждый канал
                    this.savingFile(mergedSignals, filename);

                    // Гарантировано сохранено в файл и расхождений не будет!
                    this.signalMaps.add(mergedSignals);
                    saved = true;
                } catch (Exception e) {
                    System.out.println("❌ Забракованный: " + e.getMessage());
                }
            }
        }

        return saved;
    }

    /**
     * Просмотр и запись обработанных, отфильтрованных данных ({@code .mseed} -> {@code .txt}).
     * @param correctlyPath директория папки
     */
    public boolean readFolder(Path correctlyPath) {
        SeismicApp.validateFolderAsException(correctlyPath);
        SeismicApp.validateForExisting(correctlyPath);

        return readFolder(correctlyPath.toString());
    }

    private void savingFile(Map<String, SampledSignal> signals,
                            String baseFileName) {

        // Перебор BHZ, BHN, BHE:
        for (Map.Entry<String, SampledSignal> entry : signals.entrySet()) {

            String channel = entry.getKey();
            SampledSignal s = entry.getValue();

            // Сохраняем сырой сигнал, нормализация/фильтрация не нужна.

            double[] rawData = s.amplitudesAsArray();

            // Имя файла: quake_..,1_..._NET_STA_CHL.txt
            String newName = baseFileName.replace(".mseed", "_" + channel + ".txt");

            NormalizedWaveformTXTWriter.fileSaving(rawData, newName);
        }
    }

    /**
     * Извлекает ключ станции (NET.STA) из имени файла,
     * используя фиксированный формат: quake_M*,*_NET_STA.mseed
     */
    private String extractStationKeyFromFilename(String filename) {
        // Убираем расширение .mseed
        if (!filename.endsWith(".mseed")) {
            // На всякий случай, если передали что-то другое
            return "UNKNOWN.UNKNOWN";
        }
        String baseName = filename.substring(0, filename.length() - ".mseed".length());

        // Разделяем строку по символу подчеркивания '_'
        String[] parts = baseName.split("_");

        // Ожидаемый формат: [quake, M*, YMDHms, NET, STA]
        // Нам нужны последние два элемента перед расширением (.mseed).
        // parts.length должно быть 5.

        if (parts.length < 5) {
            System.err.println("Некорректный формат имени файла (ожидалось 5 частей): " + filename);
            return "UNKNOWN.UNKNOWN";
        }

        // Извлекаем предпоследний (NET) и последний (STA) элемент.
        // NET находится по индексу parts.length - 2
        String network = parts[parts.length - 2].trim();
        // STA находится по индексу parts.length - 1
        String station = parts[parts.length - 1].trim();

        // 4. Формируем ключ в формате NET.STA
        return network + "." + station;
    }

    public Path getLastMSeedPath() {
        return this.createdFiles.getLast();
    }

    public Path getMSeedDirectory() { return getLastMSeedPath().getParent(); }


    /* Валидаторы для файлов */
    /**
     * Закрытый метод быстрой проверки на существование каталога.
     */
    private static void validateFolderAsException(Path folder) {
        if (folder == null) {
            throw new SeismicApplicationException("Directory of normalized files isn't exist.");
        }
        if (!Files.exists(folder)) {
            throw new SeismicApplicationException("Directory does not exist: " + folder);
        }
        if (!Files.isDirectory(folder)) {
            throw new SeismicApplicationException("Path is not a directory: " + folder);
        }
    }

    /**
     * Закрытый метод быстрой проверки на существование файлов внутри проверенного каталога
     */
    private static void validateForExisting(Path checkingFolder) {
        File[] files = checkingFolder.toFile().listFiles();
        if (files == null || files.length == 0) {
            throw new IllegalStateException("Папка пустая: " + checkingFolder);
        }
    }


/*
    ╭────────────────────────────────────────────────────────────────╮
    │ Фаза III работы приложения:                                    │
    │  * Применение ряда Фурье по «сокращённой комплексной формуле». │
    │  * Отрисовка графиков разложения (внутри пайплайна)            │
    │  * Локализация землетрясения.                                  │
    ╰────────────────────────────────────────────────────────────────╯
*/

    /**
     * Временный метод для проверки, правильно ли проброшены метаданные станции.
     */
    public void printSignalMetadata() {
        if (this.signalMaps == null || this.signalMaps.isEmpty()) {
            System.out.println("--- [ℹ️] Список сигналов (signalMaps) пуст. ---");
            return;
        }

        System.out.println("\n--- [✅] ПРОВЕРКА ЗАГРУЖЕННЫХ МЕТАДАННЫХ СИГНАЛОВ ---");
        int stationIndex = 0;

        // Перебор списка файлов/станций
        for (Map<String, SampledSignal> signalMap : this.signalMaps) {
            System.out.println("=========================================================");

            // Берем BHZ, так как он должен содержать полные метаданные станции
            SampledSignal ss = signalMap.get("BHZ");

            if (ss == null) {
                System.err.printf("[⚠️] Станция %d: Не найден BHZ-канал.\n", stationIndex);
                continue;
            }

            // Вывод метаданных
            System.out.printf("   [СТАНЦИЯ %d] KEY: %s.%s\n",
                    stationIndex,
                    ss.networkCode(),
                    ss.stationCode());

            System.out.printf("   - Координаты (Lat/Lon): %.4f / %.4f\n",
                    ss.latitude(),
                    ss.longitude());

            System.out.printf("   - Начало записи: %s\n", ss.startTime());
            System.out.printf("   - Частота дискретизации: %.2f Hz\n", ss.samplingRate());

            System.out.println("   - Каналы в наборе:");

            // Вывод информации о каналах в этом наборе
            for (String channel : signalMap.keySet()) {
                System.out.printf("     -> %s (%d сэмплов)\n",
                        channel,
                        signalMap.get(channel).amplitudes().size());
            }

            stationIndex++;
        }
        System.out.println("=========================================================\n");
    }

    /**
     * Главный метод для запуска полной локализации.
     * Запускается после того, как все .mseed файлы загружены и прочитаны в signalMaps.
     * * @throws SeismicApplicationException при ошибке, связанной с данными.
     */
    public void runLocalizationPipeline() throws SeismicApplicationException {

        // Проверка наличия данных
        if (this.signalMaps == null || this.signalMaps.isEmpty()) {
            System.err.println("[❌] Невозможно запустить локализацию: seismicSignals пуст.");
            return;
        }

        EarthquakeLocalizer localizer = new EarthquakeLocalizer();

        try {
            // ----------------------------------------------------------------------
            // 1. ПОДГОТОВКА ДАННЫХ (Конверсия в X, Y относительно опорной точки)
            // ----------------------------------------------------------------------

            System.out.println("\n\n#####################################################");
            System.out.println("### [СЕЙСМИЧЕСКАЯ ЛОКАЛИЗАЦИЯ] СТАРТ ПАЙПЛАЙНА ###");
            System.out.println("#####################################################");

            List<EarthquakeLocalizer.StationData> preparedData =
                    localizer.prepareData(this.signalMaps);

            if (preparedData.isEmpty()) {
                throw new SeismicApplicationException("Подготовка данных для локализации не удалась.");
            }

            // ----------------------------------------------------------------------
            // 2. ОТБОР P-ВОЛНЫ (STA/LTA + Кросс-корреляция)
            // ----------------------------------------------------------------------

            // ВАЖНО: pickPWaveArrivals использует STA/LTA для якорной станции
            // и кросс-корреляцию для получения относительных задержек (TDOA)
            // для остальных. Временные метки являются относительными (от начала записи).
            EarthquakeLocalizer.PWaveSpawning.pickPWaveArrivals(preparedData);

            // ----------------------------------------------------------------------
            // 3. ЗАПУСК РЕШАТЕЛЯ (TDOA на основе Гаусса-Ньютона)
            // ----------------------------------------------------------------------

            EarthquakeLocalizer.PWaveSpawning.localizeAllEvents(preparedData);

        } catch (IllegalStateException e) {
            System.err.println("[❌] Критическая ошибка локализации: " + e.getMessage());
            throw new SeismicApplicationException("Локализация прервана из-за ошибки в данных.");
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
}
