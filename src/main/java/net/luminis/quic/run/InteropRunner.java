/*
 * Copyright © 2019, 2020 Peter Doornbosch
 *
 * This file is part of Kwik, a QUIC client Java library
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.quic.run;

import net.luminis.quic.QuicSessionTicket;
import net.luminis.quic.QuicConnection;
import net.luminis.quic.QuicConnectionImpl;
import net.luminis.quic.log.FileLogger;
import net.luminis.quic.log.Logger;
import net.luminis.quic.log.SysOutLogger;
import net.luminis.quic.Version;
import net.luminis.quic.stream.QuicStream;
import net.luminis.tls.NewSessionTicket;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class InteropRunner extends KwikCli {

    public static final String TC_TRANSFER = "transfer";
    public static final String TC_RESUMPTION = "resumption";
    public static final String TC_MULTI = "multiconnect";
    public static final String TC_0RTT = "zerortt";
    public static List TESTCASES = List.of(TC_TRANSFER, TC_RESUMPTION, TC_MULTI, TC_0RTT);

    private static File outputDir;
    private static Logger logger;


    public static void main(String[] args) {
        File logDir = new File("/logs");   // Interop runner runs in docker container and is expected to write logs to /logs
        String logFile = (logDir.exists()? "/logs/": "./") + "kwik_client.log";
        try {
            logger = new FileLogger(new File(logFile));
            logger.logInfo(true);
        } catch (IOException e) {
            System.out.println("Cannot open log file " + logFile);
            System.exit(1);
        }

        if (args.length < 2) {
            System.out.println("Expected at least 3 arguments: <downloadDir> <testcase> <URL>");
            System.exit(1);
        }

        outputDir = new File(args[0]);
        if (! outputDir.isDirectory()) {
            outputDir.mkdir();
        }

        String testCase = args[1];
        if (! TESTCASES.contains(testCase)) {
            System.out.println("Invalid argument; test case '" + testCase + "' not known.");
            System.out.println("Available test cases: " + TESTCASES);
        }

        int i = -1;
        try {
            List<URL> downloadUrls = new ArrayList<>();
            for (i = 2; i < args.length; i++) {
                downloadUrls.add(new URL(args[i]));
            }

            QuicConnectionImpl.Builder builder = QuicConnectionImpl.newBuilder();
            builder.uri(downloadUrls.get(0).toURI());
            builder.logger(logger);
            builder.initialRtt(100);

            if (testCase.equals(TC_TRANSFER)) {
                testTransfer(downloadUrls, builder);
            }
            else if (testCase.equals(TC_RESUMPTION)) {
                testResumption(downloadUrls, builder);
            }
            else if (testCase.equals(TC_MULTI)) {
                testMultiConnect(downloadUrls, builder);
            }
            else if (testCase.equals(TC_0RTT)) {
                testZeroRtt(downloadUrls, builder);
            }
        } catch (MalformedURLException | URISyntaxException e) {
            System.out.println("Invalid argument: cannot parse URL '" + args[i] + "'");
        } catch (IOException e) {
            System.out.println("I/O Error: " + e);
        }
    }

    private static void testTransfer(List<URL> downloadUrls, QuicConnectionImpl.Builder builder) throws IOException, URISyntaxException {
        URL url1 = downloadUrls.get(0);
        // logger.logPackets(true);
        logger.logCongestionControl(true);
        logger.logRecovery(true);

        QuicConnection connection = builder.build();
        connection.connect(5_000);

        ForkJoinPool myPool = new ForkJoinPool(Integer.min(100, downloadUrls.size()));
        try {
            myPool.submit(() ->
                    downloadUrls.parallelStream()
                            .forEach(url -> {
                                try {
                                    doHttp09Request(connection, url.getPath(), outputDir.getAbsolutePath());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }))
                    .get(5, TimeUnit.MINUTES);
            logger.info("Downloaded " + downloadUrls);
        } catch (InterruptedException e) {
            logger.error("download tasks interrupted", e);
        } catch (ExecutionException e) {
            logger.error("download tasks failed", e);
        } catch (TimeoutException e) {
            logger.error("download tasks timed out...", e);
        }

        connection.close();
    }

    private static void testResumption(List<URL> downloadUrls, QuicConnectionImpl.Builder builder) throws IOException, URISyntaxException {
        if (downloadUrls.size() != 2) {
            throw new IllegalArgumentException("expected 2 download URLs");
        }
        URL url1 = downloadUrls.get(0);
        URL url2 = downloadUrls.get(1);

        QuicConnection connection = builder.build();
        connection.connect(5_000);

        doHttp09Request(connection, url1.getPath(), outputDir.getAbsolutePath());
        logger.info("Downloaded " + url1);

        List<QuicSessionTicket> newSessionTickets = connection.getNewSessionTickets();

        connection.close();

        if (newSessionTickets.isEmpty()) {
            logger.info("Server did not provide any new session tickets.");
            System.exit(1);
        }

        builder = QuicConnectionImpl.newBuilder();
        builder.uri(url2.toURI());
        builder.logger(logger);
        builder.sessionTicket(newSessionTickets.get(0));
        QuicConnection connection2 = builder.build();
        connection2.connect(5_000);
        doHttp09Request(connection2, url2.getPath(), outputDir.getAbsolutePath());
        logger.info("Downloaded " + url2);
        connection2.close();
    }

    private static void testMultiConnect(List<URL> downloadUrls, QuicConnectionImpl.Builder builder) throws URISyntaxException {
        logger.useRelativeTime(true);
        logger.logRecovery(true);
        logger.logCongestionControl(true);
        logger.logPackets(true);

        for (URL download : downloadUrls) {
            try {
                logger.info("Starting download at " + timeNow());

                QuicConnection connection = builder.build();
                connection.connect(275_000);

                doHttp09Request(connection, download.getPath(), outputDir.getAbsolutePath());
                logger.info("Downloaded " + download + " finished at " + timeNow());

                connection.close();
            }
            catch (IOException ioError) {
                logger.error(timeNow() + " Error in client: " + ioError);
            }
        }
    }

    private static void testZeroRtt(List<URL> downloadUrls, QuicConnectionImpl.Builder builder) throws IOException {
        logger.logPackets(true);
        logger.logRecovery(true);
        logger.info("Starting first download at " + timeNow());

        QuicConnection connection = builder.build();
        connection.connect(15_000);

        doHttp09Request(connection, downloadUrls.get(0).getPath(), outputDir.getAbsolutePath());
        logger.info("Downloaded " + downloadUrls.get(0) + " finished at " + timeNow());
        List<QuicSessionTicket> newSessionTickets = connection.getNewSessionTickets();
        if (newSessionTickets.isEmpty()) {
            logger.error("Error: did not get any new session tickets; aborting test.");
            return;
        }
        else {
            logger.info("Got " + newSessionTickets.size() + " new session tickets");
        }
        connection.close();
        logger.info("Connection closed; starting second connection with 0-rtt");

        builder.sessionTicket(newSessionTickets.get(0));
        QuicConnection connection2 = builder.build();

        List<QuicConnection.StreamEarlyData> earlyDataRequests = new ArrayList<>();
        for (int i = 1; i < downloadUrls.size(); i++) {
            String httpRequest = "GET " + downloadUrls.get(i).getPath() + "\r\n";
            earlyDataRequests.add(new QuicConnection.StreamEarlyData(httpRequest.getBytes(), true));
        }
        Version quicVersion = Version.getDefault();
        String alpn = "hq-" + quicVersion.toString().substring(quicVersion.toString().length() - 2);
        List<QuicStream> earlyDataStreams = connection2.connect(15_000, alpn, null, earlyDataRequests);
        for (int i = 0; i < earlyDataRequests.size(); i++) {
            if (earlyDataStreams.get(i) == null) {
                logger.info("Attempting to create new stream after connect, because it failed on 0-rtt");
            }
            else {
                logger.info("Processing response for stream " + earlyDataStreams.get(i));
            }
            doHttp09Request(connection, downloadUrls.get(i+1).getPath(), earlyDataStreams.get(i), outputDir.getAbsolutePath());
        }

        logger.info("Download finished at " + timeNow());
        connection.close();
    }


    static String timeNow() {
        LocalTime localTimeNow = LocalTime.from(Instant.now().atZone(ZoneId.systemDefault()));
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("mm:ss.SSS");
        return timeFormatter.format(localTimeNow);
    }

}

