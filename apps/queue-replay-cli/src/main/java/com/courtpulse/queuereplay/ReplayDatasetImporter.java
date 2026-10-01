package com.courtpulse.queuereplay;

import com.courtpulse.persistence.JdbcReplayRepository;
import com.courtpulse.providers.nba.NbaAction;
import com.courtpulse.providers.nba.NbaPlayByPlayCsv;
import com.courtpulse.providers.nba.NbaReplayGame;
import com.courtpulse.providers.nba.NbaStatsPlayByPlayCsv;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports completed real games for replay from the open nba_data project, which republishes NBA
 * play-by-play by season (https://github.com/shufinskiy/nba_data). Two layouts are supported:
 * NBA.com live data ({@code cdnnba_*}, with real timestamps) and stats.nba.com play-by-play
 * ({@code nbastatsv3_*}, dated from the matching {@code shotdetail_*} dataset and paced from the
 * game clock). Only named datasets from that repository are fetched, over HTTPS, with a size
 * limit; a local .csv or .tar.xz file can be imported instead. The data is unofficial and for
 * personal, non-commercial use; it is never bundled with CourtPulse.
 */
final class ReplayDatasetImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayDatasetImporter.class);
    static final Pattern DATASET = Pattern.compile("^(cdnnba|nbastatsv3)(_po)?_20[0-9]{2}$");
    static final String SOURCE = "https://github.com/shufinskiy/nba_data/raw/main/datasets/";
    static final String KEPT = "kept the timestamped import from another dataset";
    private static final long MAXIMUM_ARCHIVE_BYTES = 200L * 1024 * 1024;
    private static final long MAXIMUM_CSV_BYTES = 1024L * 1024 * 1024;

    private final JdbcReplayRepository replays;
    private final TransactionTemplate transactions;
    private final HttpClient http;
    private final Clock clock;

    ReplayDatasetImporter(JdbcReplayRepository replays, TransactionTemplate transactions, HttpClient http, Clock clock) {
        this.replays = replays;
        this.transactions = transactions;
        this.http = http;
        this.clock = clock;
    }

    record Summary(String dataset, int games, int skipped, int actions, Map<String, Integer> skipReasons) {}

    @FunctionalInterface
    private interface CsvHandler<T> {
        T handle(InputStream csv) throws IOException;
    }

    /**
     * {@code target} is a dataset name such as cdnnba_po_2025 or nbastatsv3_po_2025, or a path to a
     * .csv or .tar.xz file (a local stats-layout file has no dates, so its games are skipped).
     */
    Summary run(String target) throws IOException, InterruptedException {
        Summary summary;
        if (DATASET.matcher(target).matches()) {
            summary = download(target);
        } else {
            Path file = Path.of(target);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Expected a dataset name like cdnnba_po_2025 or "
                        + "nbastatsv3_po_2025, or an existing .csv/.tar.xz file");
            }
            String label = file.getFileName().toString().replaceAll("\\.(csv|tar\\.xz)$", "")
                    .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            String dataset = label.isEmpty() ? "local" : label.substring(0, Math.min(40, label.length()));
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                summary = file.toString().endsWith(".tar.xz")
                        ? fromArchive(input, csv -> importCsv(dataset, csv, Map.of()))
                        : importCsv(dataset, input, Map.of());
            }
        }
        System.out.printf("Replay catalog import: dataset=%s games=%d skipped=%d actions=%d%s%n",
                summary.dataset(), summary.games(), summary.skipped(), summary.actions(),
                summary.skipReasons().isEmpty() ? "" : " skipReasons=" + summary.skipReasons());
        return summary;
    }

    private Summary download(String dataset) throws IOException, InterruptedException {
        Map<String, LocalDate> dates = Map.of();
        if (dataset.startsWith("nbastatsv3")) {
            dates = fetch(dataset.replaceFirst("^nbastatsv3", "shotdetail"), csv -> NbaStatsPlayByPlayCsv.readGameDates(
                    new InputStreamReader(new Limited(csv, MAXIMUM_CSV_BYTES), StandardCharsets.UTF_8)));
        }
        Map<String, LocalDate> gameDates = dates;
        return fetch(dataset, csv -> importCsv(dataset, csv, gameDates));
    }

    /** Downloads one nba_data dataset archive and hands its CSV to {@code handler}. */
    private <T> T fetch(String dataset, CsvHandler<T> handler) throws IOException, InterruptedException {
        URI uri = URI.create(SOURCE + dataset + ".tar.xz");
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(5)).header("User-Agent", "CourtPulse replay importer").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        URI finalUri = response.uri();
        if (!"https".equals(finalUri.getScheme()) || finalUri.getHost() == null
                || !(finalUri.getHost().equals("github.com") || finalUri.getHost().endsWith(".githubusercontent.com"))) {
            response.body().close();
            throw new IOException("Dataset download was redirected to an unexpected host");
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Dataset download failed with HTTP " + response.statusCode()
                    + " (is " + dataset + " published by the nba_data project?)");
        }
        try (InputStream body = new BufferedInputStream(new Limited(response.body(), MAXIMUM_ARCHIVE_BYTES))) {
            return fromArchive(body, handler);
        }
    }

    private static <T> T fromArchive(InputStream input, CsvHandler<T> handler) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new XZCompressorInputStream(input))) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.isFile() && entry.getName().endsWith(".csv")) {
                    return handler.handle(tar);
                }
            }
        }
        throw new IOException("Archive contains no CSV file");
    }

    private Summary importCsv(String dataset, InputStream csv, Map<String, LocalDate> dates) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new Limited(csv, MAXIMUM_CSV_BYTES), StandardCharsets.UTF_8));
        boolean stats = NbaStatsPlayByPlayCsv.detect(reader);
        Map<String, Supplier<List<NbaAction>>> games = new LinkedHashMap<>();
        if (stats) {
            NbaStatsPlayByPlayCsv.read(reader).forEach((id, game) -> games.put(id, () -> game.actions(dates.get(id))));
        } else {
            NbaPlayByPlayCsv.read(reader).forEach((id, actions) -> games.put(id, () -> actions));
        }
        // Clock-paced games never replace a game already imported with real timestamps.
        Set<String> kept = stats ? replays.gamesFromOtherDatasets(dataset) : Set.of();
        int imported = 0;
        int actions = 0;
        Map<String, Integer> skipped = new TreeMap<>();
        for (Map.Entry<String, Supplier<List<NbaAction>>> entry : games.entrySet()) {
            if (kept.contains(entry.getKey())) {
                skipped.merge(KEPT, 1, Integer::sum);
                continue;
            }
            NbaReplayGame game;
            try {
                game = NbaReplayGame.from(entry.getValue().get());
            } catch (IllegalArgumentException exception) {
                String reason = exception.getMessage().replaceAll("[0-9]+", "N");
                skipped.merge(reason, 1, Integer::sum);
                LOGGER.atDebug().addKeyValue("nbaGameId", entry.getKey()).addKeyValue("reason", reason)
                        .log("Skipped a game that cannot be replayed");
                continue;
            }
            transactions.executeWithoutResult(status -> replays.saveGame(dataset, game, clock.instant()));
            imported++;
            actions += game.actions().size();
        }
        return new Summary(dataset, imported, skipped.values().stream().mapToInt(Integer::intValue).sum(), actions,
                skipped);
    }

    /** Fails instead of reading past a fixed number of bytes. */
    private static final class Limited extends FilterInputStream {
        private long remaining;

        Limited(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                consume(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                consume(read);
            }
            return read;
        }

        private void consume(long count) throws IOException {
            remaining -= count;
            if (remaining < 0) {
                throw new IOException("Input exceeds the size limit");
            }
        }
    }
}
