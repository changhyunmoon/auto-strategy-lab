package com.mch.strategy.collect.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.mch.strategy.instrument.Exchange;

import lombok.extern.slf4j.Slf4j;

/**
 * 바이낸스 현물 1분봉.
 * 1) data.binance.vision 월별 zip (지난달까지 한 달 전체가 필요할 때)
 * 2) 일별 zip
 * 3) 일별 파일이 아직 없으면(최근 1~2일) REST /api/v3/klines 로 보완
 */
@Slf4j
@Component
public class BinanceCandleSource implements CandleSource {

    private static final String FILES = "https://data.binance.vision/data/spot";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final RestClient files = RestClient.create();
    private final RestClient api = RestClient.builder().baseUrl("https://data-api.binance.vision").build();

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public void fetch(String symbol, Instant from, Instant to, Consumer<List<Candle1m>> batchConsumer) {
        LocalDate day = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate lastDay = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC);
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);

        while (!day.isAfter(lastDay)) {
            YearMonth month = YearMonth.from(day);
            boolean wholeMonthNeeded = day.getDayOfMonth() == 1
                    && !month.atEndOfMonth().isAfter(lastDay)
                    && month.isBefore(thisMonth);
            if (wholeMonthNeeded) {
                List<Candle1m> candles = downloadZip(FILES + "/monthly/klines/" + symbol + "/1m/"
                        + symbol + "-1m-" + month.format(MONTH) + ".zip");
                if (candles != null) {
                    batchConsumer.accept(filter(candles, from, to));
                    day = month.plusMonths(1).atDay(1);
                    continue;
                }
            }
            List<Candle1m> candles = downloadZip(FILES + "/daily/klines/" + symbol + "/1m/"
                    + symbol + "-1m-" + day.format(DAY) + ".zip");
            if (candles == null) {
                candles = fetchDayFromRest(symbol, day);
            }
            batchConsumer.accept(filter(candles, from, to));
            day = day.plusDays(1);
        }
    }

    /** zip 을 받아 체크섬을 확인하고 CSV 를 읽는다. 파일이 없으면 null. */
    private List<Candle1m> downloadZip(String url) {
        byte[] zip;
        try {
            zip = files.get().uri(url).retrieve().body(byte[].class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
        if (zip == null) {
            return null;
        }
        verifyChecksum(url, zip);
        return BinanceKlines.parseCsv(unzipFirstEntry(zip));
    }

    private void verifyChecksum(String url, byte[] zip) {
        String checksumFile;
        try {
            checksumFile = files.get().uri(url + ".CHECKSUM").retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("체크섬 파일 없음: {}", url);
            return;
        }
        if (checksumFile == null || checksumFile.isBlank()) {
            return;
        }
        String expected = checksumFile.trim().split("\\s+")[0];
        String actual = sha256(zip);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("바이낸스 파일 체크섬 불일치: " + url);
        }
    }

    private List<Candle1m> fetchDayFromRest(String symbol, LocalDate day) {
        long start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Candle1m> out = new ArrayList<>();
        long cursor = start;
        while (cursor < end) {
            long requestStart = cursor;
            List<List<Object>> rows = api.get()
                    .uri(b -> b.path("/api/v3/klines")
                            .queryParam("symbol", symbol)
                            .queryParam("interval", "1m")
                            .queryParam("startTime", requestStart)
                            .queryParam("endTime", end - 1)
                            .queryParam("limit", 1000)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<List<Object>>>() {
                    });
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (List<Object> row : rows) {
                out.add(BinanceKlines.fromRestRow(row));
            }
            cursor = out.get(out.size() - 1).openTime().toEpochMilli() + 60_000;
        }
        return out;
    }

    private static List<Candle1m> filter(List<Candle1m> candles, Instant from, Instant to) {
        return candles.stream()
                .filter(c -> !c.openTime().isBefore(from) && c.openTime().isBefore(to))
                .toList();
    }

    private static String unzipFirstEntry(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            if (entry == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("바이낸스 zip 읽기 실패", e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
