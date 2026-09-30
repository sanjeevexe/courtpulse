package com.courtpulse.queuereplay;

import com.courtpulse.persistence.JdbcReplayRepository;
import com.courtpulse.providers.nba.NbaAction;
import com.courtpulse.providers.nba.NbaPlayByPlayCsv;
import com.courtpulse.providers.nba.NbaReplayGame;
import java.io.BufferedInputStream;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports completed real games for replay from the open nba_data project, which republishes
 * NBA.com live-data play-by-play by season (https://github.com/shufinskiy/nba_data). Only named
 * datasets from that repository are fetched, over HTTPS, with a size limit; a local .csv or
 * .tar.xz file can be imported instead. The data is unofficial and for personal, non-commercial
 * use; it is never bundled with CourtPulse.
 */
final class ReplayDatasetImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayDatasetImporter.class);
    static final Pattern DATASET = Pattern.compile("^cdnnba(_po)?_20[0-9]{2}$");
    static final String SOURCE = "https://github.com/shufinskiy/nba_data/raw/main/datasets/";
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

    /** {@code target} is a dataset name such as cdnnba_po_2025, or a path to a .csv or .tar.xz file. */
    Summary run(String target) throws IOException, InterruptedException {
        Summary summary;
        if (DATASET.matcher(target).matches()) {
            summary = download(target);
        } else {
            Path file = Path.of(target);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException(
                        "Expected a dataset name like cdnnba_po_2025 or an existing .csv/.tar.xz file");
            }
            String label = file.getFileName().toString().replaceAll("\\.(csv|tar\\.xz)$", "")
                    .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                summary = importStream(label.isEmpty() ? "local" : label.substring(0, Math.min(40, label.length())),
                        input, file.toString().endsWith(".tar.xz"));
            }
        }
        System.out.printf("Replay catalog import: dataset=%s games=%d skipped=%d actions=%d%s%n",
                summary.dataset(), summary.games(), summary.skipped(), summary.actions(),
                summary.skipReasons().isEmpty() ? "" : " skipReasons=" + summary.skipReasons());
        return summary;
    }

    private Summary download(String dataset) throws IOException, InterruptedException {
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
            return importStream(dataset, body, true);
        }
    }

    private Summary importStream(String dataset, InputStream input, boolean tarXz) throws IOException {
        if (!tarXz) {
            return importCsv(dataset, input);
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new XZCompressorInputStream(input))) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.isFile() && entry.getName().endsWith(".csv")) {
                    return importCsv(dataset, tar);
                }
            }
        }
        throw new IOException("Archive contains no CSV file");
    }

    private Summary importCsv(String dataset, InputStream csv) throws IOException {
        Map<String, List<NbaAction>> games = NbaPlayByPlayCsv.read(new InputStreamReader(
                new Limited(csv, MAXIMUM_CSV_BYTES), StandardCharsets.UTF_8));
        int imported = 0;
        int actions = 0;
        Map<String, Integer> skipped = new TreeMap<>();
        for (Map.Entry<String, List<NbaAction>> entry : games.entrySet()) {
            NbaReplayGame game;
            try {
                game = NbaReplayGame.from(entry.getValue());
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
