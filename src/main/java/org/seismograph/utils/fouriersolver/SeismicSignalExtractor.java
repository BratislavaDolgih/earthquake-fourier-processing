package org.seismograph.utils.fouriersolver;

import edu.sc.seis.seisFile.mseed.Blockette1000;
import edu.sc.seis.seisFile.mseed.DataRecord;
import edu.sc.seis.seisFile.mseed.SeedFormatException;
import org.seismograph.utils.Fileable;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

public class SeismicSignalExtractor {

    public static MSeedParser parserOf(Path correctDirectory) {
        return new MSeedParser(correctDirectory);
    }

    /**
     * Группирует и склеивает все блоки данных ({@code DataRecord}) в длинные непрерывные сигналы
     * по коду канала (BHZ, BHN, BHE)
     * @param active парсер, который уже прочитал все блоки с {@code channelCode}
     * @return {Код канала -> склеенный {@code SampledSignal}}
     */
    public static Map<String, SampledSignal> mergeByChannel(SeismicSignalExtractor.MSeedParser active,
                                                            String staKey, // NET.STA
                                                            double lat,
                                                            double lon)
            throws Exception {
        // Разбиваем код-название на сеть и станцию.
        String netCode = staKey.split("\\.")[0];
        String stnCode = staKey.split("\\.")[1];

        // Получаем все блоки (вместе с кодом канала)
        List<SampledSignal> allBlocks = active.getDataRecords(active, netCode, stnCode, lat, lon);

        // Группировка по каналу:
        Map<String, List<SampledSignal>> grouped = new HashMap<>(); // Канал -> список блоков

        for (SampledSignal block : allBlocks) {
            String chnl = block.channelCode();

            if (!grouped.containsKey(chnl)) {
                grouped.put(chnl, new ArrayList<>());
            }

            grouped.get(chnl).add(block); // Добавляем в соответствующий список каналов
        }

        // MERGING:
        Map<String, SampledSignal> mergedSignals = new HashMap<>();

        for (Map.Entry<String, List<SampledSignal>> entry : grouped.entrySet()) {
            String channel = entry.getKey();
            List<SampledSignal> blocks = entry.getValue();

            // Сортируем по времени начала!
            blocks.sort(Comparator.comparing(SampledSignal::startTime));

            double rate = blocks.getFirst().samplingRate();
            LocalDateTime startTime = blocks.getFirst().startTime();

            ArrayList<Double> mergedAmpls = new ArrayList<>();
            for (SampledSignal block : blocks) {
                mergedAmpls.addAll(block.amplitudes());
            }

            // Сохраняем финальный склеенный сигнал
            mergedSignals.put(channel, new SampledSignal(
                    mergedAmpls,
                    rate,
                    startTime,
                    channel,
                    netCode,
                    stnCode,
                    lat,
                    lon
            ));
        }

        return mergedSignals;
    }

    /**
     * Класс, который позволяет
     */
    private static class MSeedParser implements Fileable {
        private Path toMSeedsDirectory;

        private MSeedParser(Path correctPathToMSeedFiles) {
            if (!Files.exists(correctPathToMSeedFiles)) {
                throw new IllegalArgumentException("Файл или директория не существует: "
                        + correctPathToMSeedFiles);
            }

            // Проверок на самом деле достаточно, так как все .mseed
            // мы получаем непосредственно программным путём, а не ручной записью!

            this.toMSeedsDirectory = Objects.requireNonNull(
                    correctPathToMSeedFiles,
                    "Путь к директории файлов с расширением mseed не может быть null!");
        }

        private List<SampledSignal> getDataRecords(MSeedParser activeParser,
                                                   String nc,
                                                   String sc,
                                                   double lat,
                                                   double lon)
                throws Exception {
            List<SampledSignal> signals = new ArrayList<>();

            try (DataInputStream dis =
                         new DataInputStream(
                                 new FileInputStream(activeParser.correctPath().toFile()))) {
                while (true) {
                    try {
                        DataRecord rec = (DataRecord) DataRecord.read(dis, 0);

                        // Локальная конвертация одного Record -> фиксированный блок сигнала
                        signals.add(convertToLocalSample(rec, nc, sc, lat, lon));

                    } catch (SeedFormatException e) {
                        System.err.println("[⚠] Corrupted record skipped");
                    } catch (EOFException e) {
                        // Вполне адекватное исключение -> выход из файла.
                        break;
                    }
                }
            }
            return signals;
        }

        private SampledSignal convertToLocalSample(DataRecord rec,
                                                   String nc,
                                                   String sc,
                                                   double lat,
                                                   double lon) throws Exception {
            // Извлекаем код канала
            String chlCode = rec.getHeader().getChannelIdentifier();

            // Декодинг времена старта:
            var seisTime = rec.getHeader().getStartTime();
            // ГГГГ,ДДД,ЧЧ:ММ:СС.ТТТТ
            LocalDateTime correctTime = transformedTime(seisTime);

            // Получение частоты дискретизации, причём
            // Срабатывает формула: samplingRate = multiplier ^ factor.
            double rate = rec.getSampleRate();

            // Извлечение и конвертация амплитуд
            ArrayList<Double> amplitudes = generateDecodingAmplitudes(rec);

            return new SampledSignal(amplitudes, rate, correctTime, chlCode, nc, sc, lat, lon);
        }

        private LocalDateTime transformedTime(String raw) {
            raw = raw.trim();

            int year = Integer.parseInt(raw.substring(0, 4)),
                    day = Integer.parseInt(raw.substring(5, 8)),
                    hours = Integer.parseInt(raw.substring(9, 11)),
                    minutes = Integer.parseInt(raw.substring(12, 14)),
                    seconds = Integer.parseInt(raw.substring(15, 17));

            // Распарсим наносекунды, потому что нет метода для миллисекунд!
            int nanos = extractNanos(raw);

            LocalDate date = LocalDate.ofYearDay(year, day);
            return LocalDateTime.of(date,
                    LocalTime.of(hours, minutes, seconds, nanos)
            );
        }

        private int extractNanos(String raw) {
            int dotIndex = raw.indexOf('.', 17);

            int nanos = 0;
            if (dotIndex != -1) {
                String frac = raw.substring(dotIndex + 1)
                        .replaceAll("[^0-9]", "");

                if (!frac.isEmpty()) {
                    // Нормализация в наносекунды.
                    if (frac.length() > 9) {
                        frac = frac.substring(0, 9);
                    } else {
                        frac = String.format("%-9s", frac)
                                .replace(' ', '0');
                    }

                    nanos = Integer.parseInt(frac);
                }
            }
            return nanos;
        }

        private ArrayList<Double> generateDecodingAmplitudes(DataRecord record) throws Exception {
            final int INT16 = 1,
                    INT32 = 3,
                    STEIM1 = 10,
                    STEIM2 = 11,
                    FLOAT32 = 4,
                    FLOAT64 = 5;

            Blockette1000 b1000 = (Blockette1000) record.getUniqueBlockette(1000);
            if (b1000 == null) {
                int[] ints = record.decompress().getAsInt();
                return castToDouble(ints);
            }

            return switch (b1000.getEncodingFormat()) {
                case INT16, INT32, STEIM1, STEIM2 -> {
                    int[] ints = record.decompress().getAsInt();
                    yield castToDouble(ints);
                }
                case FLOAT32, FLOAT64 -> translateToList(record.decompress().getAsDouble());
                default -> throw new UnsupportedOperationException(
                        "[\uD83D\uDCA5] Unknown encoding for blockette #1000"
                );
            };
        }

        private ArrayList<Double> castToDouble(int[] ints) {
            ArrayList<Double> d = new ArrayList<>();
            for (int anInt : ints) {
                d.add((double) anInt);
            }
            return d;
        }

        private ArrayList<Double> translateToList(double[] dls) {
            ArrayList<Double> d = new ArrayList<>();
            for (double dl : dls) {
                d.add(dl);
            }
            return d;
        }

        @Override
        public Path correctPath() {
            return this.toMSeedsDirectory;
        }
    }

    final static double RATE_EPSILON = 1e-15;

    /**
     * Рекорд хранит данные, причём знает, какой канал рассматривается
     */
    public record SampledSignal(
            ArrayList<Double> amplitudes,    // Амплитуды сигнала
            double samplingRate,             // Частота дискретизации
            LocalDateTime startTime,         // Момент начала записи сигнала
            String channelCode,              // Поле канала, фиксирует: BHZ, BHN или BHE

            // Добавление метаданных сигнала:
            String networkCode,
            String stationCode,
            double latitude,
            double longitude
    ) {
        public double[] amplitudesAsArray() {
            double[] arr = new double[this.amplitudes.size()];
            for (int pos = 0; pos < arr.length; ++pos) {
                arr[pos] = this.amplitudes.get(pos);
            }

            return arr;
        }
    }
}
