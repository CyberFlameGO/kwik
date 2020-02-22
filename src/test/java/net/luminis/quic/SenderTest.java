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
package net.luminis.quic;

import net.luminis.quic.frame.*;
import net.luminis.quic.log.Logger;
import net.luminis.quic.log.SysOutLogger;
import net.luminis.quic.packet.PacketInfo;
import net.luminis.quic.packet.QuicPacket;
import net.luminis.quic.packet.RetryPacket;
import net.luminis.quic.packet.VersionNegotiationPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.internal.util.reflection.FieldReader;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SenderTest {

    private static Logger logger;
    private Sender sender;
    private DatagramSocket socket;
    private QuicConnectionImpl connection;

    // Arbitrary Instant value, used by tests to indicate the value does not matter for the test
    private Instant whenever = Instant.now();

    @BeforeAll
    static void initLogger() {
        logger = new SysOutLogger();
        logger.logDebug(true);
    }

    @BeforeEach
    void initSenderUnderTest() throws Exception {
        socket = mock(DatagramSocket.class);
        Logger logger = mock(Logger.class);
        sender = new Sender(socket, 1500, logger, InetAddress.getLoopbackAddress(), 443, connection);
        FieldSetter.setField(sender, sender.getClass().getDeclaredField("rttEstimater"), new RttEstimator(logger, 100));
        connection = mock(QuicConnectionImpl.class);
        FieldSetter.setField(sender, sender.getClass().getDeclaredField("connection"), connection);
    }

    @Test
    void testSingleSend() throws Exception {
        setCongestionWindowSize(1250);
        sender.start(mock(ConnectionSecrets.class));
        sender.send(new MockPacket(0, 1240, "packet 1"), "packet 1", p -> {});
        waitForSender();

        verify(socket, times(1)).send(any(DatagramPacket.class));
    }

    @Test
    void testSenderIsCongestionControlled() throws Exception {
        setCongestionWindowSize(1250);
        sender.start(mock(ConnectionSecrets.class));
        sender.send(new MockPacket(0, 1240, "packet 1"), "packet 1", p -> {});
        sender.send(new MockPacket(1, 1240, "packet 2"), "packet 2", p -> {});

        waitForSender();
        // Because of congestion control, only first packet should have been sent.
        verify(socket, times(1)).send(any(DatagramPacket.class));

        // An ack on first packet
        sender.process(new AckFrame(Version.getDefault(), 0), PnSpace.App, Instant.now());

        waitForSender();
        // Because congestion window is decreased, second packet should now have been sent too.
        verify(socket, times(2)).send(any(DatagramPacket.class));
    }

    @Test
    void testSenderCongestionControlWithUnrelatedAck() throws Exception {
        setCongestionWindowSize(1250);
        sender.start(mock(ConnectionSecrets.class));

        sender.send(new MockPacket(0, 12, EncryptionLevel.Initial,"initial"), "packet 1", p -> {});
        sender.send(new MockPacket(0, 1230, "packet 1"), "packet 1", p -> {});
        sender.send(new MockPacket(1, 1230, "packet 2"), "packet 2", p -> {});

        waitForSender();
        // Because of congestion control, only first 2 packets should have been sent.
        verify(socket, times(2)).send(any(DatagramPacket.class));

        // An ack on initial packet should not decrease the congestion window too much
        sender.process(new AckFrame(Version.getDefault(), 0), PnSpace.Initial, Instant.now());

        waitForSender();
        verify(socket, times(2)).send(any(DatagramPacket.class));
    }

    @Test
    void testSenderCongestionControlWithIncorrectAck() throws Exception {
        setCongestionWindowSize(1250);
        sender.start(mock(ConnectionSecrets.class));

        sender.send(new MockPacket(0, 1240, "packet 1"), "packet 1", p -> {});
        sender.send(new MockPacket(1, 1240, "packet 2"), "packet 2", p -> {});

        waitForSender();
        // Because of congestion control, only first packet should have been sent.
        verify(socket, times(1)).send(any(DatagramPacket.class));

        // An ack on a non-existant packet, shouldn't change anything.
        sender.process(new AckFrame(Version.getDefault(), 0), PnSpace.Handshake, null);

        waitForSender();
        verify(socket, times(1)).send(any(DatagramPacket.class));
    }

    @Test
    void ackOnlyPacketsShouldNotBeRetransmitted() throws Exception {
        sender.start(mock(ConnectionSecrets.class));

        sender.send(new MockPacket(0, 1240, EncryptionLevel.Initial, new AckFrame(), "packet 1"), "packet 1", p -> {});
        waitForSender();
        verify(socket, times(1)).send(argThat(new PacketMatcher(0, EncryptionLevel.Initial)));
        clearInvocations(socket);

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    void receivingPacketLeadsToSendAckPacket() throws IOException  {
        sender.start(mock(ConnectionSecrets.class));
        when(connection.createPacket(any(EncryptionLevel.class), any(QuicFrame.class)))
                .thenReturn(new MockPacket(0, 10, EncryptionLevel.Initial));

        sender.processPacketReceived(new MockPacket(0, 1000, EncryptionLevel.Initial, new CryptoFrame()));
        sender.packetProcessed(EncryptionLevel.Initial);

        waitForSender();

        verify(socket, times(1)).send(any(DatagramPacket.class));  // TODO: would be nice to check send packet actually contains an ack frame...
    }

    @Test
    void receivingVersionNegotationPacke() throws IOException {
        sender.processPacketReceived(new VersionNegotiationPacket());

        waitForSender();

        verify(socket, never()).send(any(DatagramPacket.class));
    }

    @Test
    void whenWaitForCongestionControllerIsInteruptedBecauseOfProcessedPacketWaitingPacketShouldRemainWaiting() throws Exception {
        setCongestionWindowSize(1212);
        sender.start(mock(ConnectionSecrets.class));

        // Send first packet to fill up cwnd
        MockPacket firstPacket = new MockPacket(0, 1200, EncryptionLevel.App, new AckFrame(), "first packet");
        sender.send(firstPacket, "first packet", p -> {});
        waitForSender();
        verify(socket, times(1)).send(argThat(matchesPacket(0, EncryptionLevel.App)));
        reset(socket);

        // Send second packet and third packet, which will both be queued because of cwnd
        sender.send(new MockPacket(1, 1200, EncryptionLevel.App, new AckFrame(), "large packet"), "large packet", p -> {});
        waitForSender();
        sender.send(new MockPacket(2, 120, EncryptionLevel.App, new AckFrame(), "third packet"), "third packet", p -> {});
        waitForSender();

        // Simulate incoming packet; sender will be interrupted because maybe an ack must be sent.
        sender.packetProcessed(EncryptionLevel.App);

        // Now, increase cwnd.
        sender.getCongestionController().registerAcked(List.of(new PacketInfo(whenever, firstPacket, null)));
        waitForSender();
        // The first waiting packet should be sent.
        verify(socket, times(1)).send(argThat(matchesPacket(1, EncryptionLevel.App)));
    }

    @Test
    void whenCwndAlmostReachedProbeShouldNotBeBlocked() throws Exception {
        // Disable Recovery Manager
        RecoveryManager recoveryManager = mock(RecoveryManager.class);
        FieldSetter.setField(sender, sender.getClass().getDeclaredField("recoveryManager"), recoveryManager);

        when(connection.createPacket(any(EncryptionLevel.class), any(QuicFrame.class))).thenAnswer(invocation -> new MockPacket(1, 12, EncryptionLevel.App, new PingFrame(), "ping packet"));
        setCongestionWindowSize(1212);
        sender.start(mock(ConnectionSecrets.class));

        // Send first packet to fill up cwnd
        MockPacket firstPacket = new MockPacket(0, 1200, EncryptionLevel.App, new Padding(), "first packet");
        sender.send(firstPacket, "first packet", p -> {});
        waitForSender();
        verify(socket, times(1)).send(argThat(matchesPacket(0, EncryptionLevel.App)));
        reset(socket);

        sender.sendProbe();
        waitForSender();

        verify(socket, times(1)).send(argThat(matchesPacket(1, EncryptionLevel.App)));
    }

    @Test
    void whenCongestionControllerIsBlockingProbeShouldNotBeBlocked() throws Exception {
        // Disable Recovery Manager
        RecoveryManager recoveryManager = mock(RecoveryManager.class);
        FieldSetter.setField(sender, sender.getClass().getDeclaredField("recoveryManager"), recoveryManager);

        when(connection.createPacket(any(EncryptionLevel.class), any(QuicFrame.class))).thenAnswer(invocation -> new MockPacket(2, 12, EncryptionLevel.App, new PingFrame(), "ping packet"));
        setCongestionWindowSize(1212);
        sender.start(mock(ConnectionSecrets.class));

        // Send first packet to fill up cwnd
        MockPacket firstPacket = new MockPacket(0, 1200, EncryptionLevel.App, new Padding(), "first packet");
        sender.send(firstPacket, "first packet", p -> {});
        waitForSender();
        verify(socket, times(1)).send(argThat(matchesPacket(0, EncryptionLevel.App)));
        reset(socket);

        // Send second packet to exceed cwnd (and make sender wait)
        MockPacket secondPacket = new MockPacket(1, 1200, EncryptionLevel.App, new Padding(), "second packet");
        sender.send(firstPacket, "second packet", p -> {});
        waitForSender();
        verify(socket, never()).send(any(DatagramPacket.class));
        reset(socket);

        sender.sendProbe();
        waitForSender();

        // Whether a special probe or waiting data is sent does not matter, as long as a packet is sent.
        verify(socket, times(1)).send(any(DatagramPacket.class));
    }

    @Test
    void ackOnlyShouldNotBeCongestionControlled() throws Exception {
        setCongestionWindowSize(1212);
        sender.start(mock(ConnectionSecrets.class));
        when(connection.createPacket(any(EncryptionLevel.class), any(QuicFrame.class))).thenAnswer(invocation -> new MockPacket(-11, 12, EncryptionLevel.App, new Padding(10), "empty packet"));

        // Send first packet to fill up cwnd
        MockPacket firstPacket = new MockPacket(0, 1200, EncryptionLevel.App, new Padding(), "first packet");
        sender.send(firstPacket, "first packet", p -> {});
        waitForSender();
        verify(socket, times(1)).send(argThat(matchesPacket(0, EncryptionLevel.App)));
        reset(socket);

        sender.processPacketReceived(new MockPacket(19, 200, EncryptionLevel.App, new MaxDataFrame(1_000_000), "stream frame"));
        sender.packetProcessed(EncryptionLevel.App);
        waitForSender();

        verify(socket, times(1)).send(argThat(matchesPacket(1, EncryptionLevel.App)));
    }

    @Test
    void ackOnlyShouldNotBeCountedAsInFlight() throws Exception {
        sender.start(mock(ConnectionSecrets.class));
        when(connection.createPacket(any(EncryptionLevel.class), any(QuicFrame.class))).thenAnswer(invocation -> new MockPacket(-1, 12, EncryptionLevel.App, "empty packet"));

        sender.processPacketReceived(new MockPacket(19, 200, EncryptionLevel.App, new MaxDataFrame(1_000_000), "stream frame"));
        sender.packetProcessed(EncryptionLevel.App);
        waitForSender();

        verify(socket, times(1)).send(argThat(matchesPacket(0, EncryptionLevel.App)));
        assertThat(sender.getCongestionController().getBytesInFlight()).isEqualTo(0);
    }


    private PacketMatcher matchesPacket(int packetNumber, EncryptionLevel encryptionLevel ) {
        return new PacketMatcher(packetNumber, encryptionLevel);
    }

    void receivingRetryPacket() throws IOException {
        sender.processPacketReceived(new RetryPacket(Version.getDefault()));

        waitForSender();

        verify(socket, never()).send(any(DatagramPacket.class));
    }

    private void waitForSender() {
        // Because sender is asynchronous, test must wait a little to give sender thread a change to execute.
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void setCongestionWindowSize(int cwnd) throws Exception {
        CongestionController congestionController = sender.getCongestionController();
        FieldSetter.setField(congestionController, congestionController.getClass().getSuperclass().getDeclaredField("congestionWindow"), cwnd);
    }

    static class PacketMatcher implements ArgumentMatcher<DatagramPacket> {
        private final long packetNumber;
        private final EncryptionLevel encryptionLevel;

        public PacketMatcher(int packetNumber, EncryptionLevel encryptionLevel) {
            this.packetNumber = packetNumber;
            this.encryptionLevel = encryptionLevel;
        }

        @Override
        public boolean matches(DatagramPacket datagramPacket) {
            ByteBuffer buffer = ByteBuffer.wrap(datagramPacket.getData());
            long sentPn = buffer.getLong();
            int sentLevel = buffer.getInt();
            return sentPn == packetNumber && sentLevel == encryptionLevel.ordinal();
        }
    }
}