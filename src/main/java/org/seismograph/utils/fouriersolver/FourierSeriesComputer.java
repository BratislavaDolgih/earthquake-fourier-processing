package org.seismograph.utils.fouriersolver;

import edu.iris.dmc.seedcodec.DecompressedData;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class FourierSeriesComputer {

    public static MSeedParser parserOf(Path correctDirectory) {
        return new MSeedParser(correctDirectory);
    }

    public static class MSeedParser implements Fileable {
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

        private List<SampledSignal> getDataRecords(MSeedParser activeParser)
                throws Exception {
            List<SampledSignal> signals = new ArrayList<>();

            try (DataInputStream dis =
                         new DataInputStream(
                                 new FileInputStream(activeParser.correctPath().toFile()))) {
                while (true) {
                    try {
                        DataRecord rec = (DataRecord) DataRecord.read(dis, 0);

                        // Локальная конвертация одного Record -> фиксированный блок сигнала
                        signals.add(convertToLocalSample(rec));

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

        private SampledSignal convertToLocalSample(DataRecord rec) throws Exception {
            // Декодинг времена старта:
            var seisTime = rec.getHeader().getStartTime();
            // ГГГГ,ДДД,ЧЧ:ММ:СС.ТТТТ
            LocalDateTime correctTime = transformedTime(seisTime);

            // Получение частоты дискретизации, причём
            // Срабатывает формула: samplingRate = multiplier ^ factor.
            double rate = rec.getSampleRate();

            // Извлечение и конвертация амплитуд
            ArrayList<Double> amplitudes = generateDecodingAmplitudes(rec);

            return new SampledSignal(amplitudes, rate, correctTime);
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

    public static List<SampledSignal> convertClosely(MSeedParser activeParser) throws Exception {
        return activeParser.getDataRecords(activeParser);
    }

    public record SampledSignal(
            ArrayList<Double> amplitudes,    // Амплитуды сигнала
            double samplingRate,             // Частота дискретизации
            LocalDateTime startTime          // Момент начала записи сигнала
    ) {
        public double[] asArray() {
            double[] arr = new double[this.amplitudes.size()];
            for (int pos = 0; pos < arr.length; ++pos) {
                arr[pos] = this.amplitudes.get(pos);
            }

            return arr;
        }
    }
}
