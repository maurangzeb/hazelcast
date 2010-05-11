/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.nio;

import javax.crypto.Cipher;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static com.hazelcast.nio.IOUtil.copyToDirectBuffer;

public final class WriteHandler extends AbstractSelectionHandler implements Runnable {

    private final Queue<Packet> writeQueue = new ConcurrentLinkedQueue<Packet>();

    private final AtomicBoolean informSelector = new AtomicBoolean(true);

    private final ByteBuffer socketBB = ByteBuffer.allocateDirect(SEND_SOCKET_BUFFER_SIZE);

    private boolean ready = false;

    private Packet lastPacket = null;

    private final PacketWriter packetWriter;

    WriteHandler(final Connection connection) {
        super(connection);
        boolean symmetricEncryptionEnabled = CipherHelper.isSymmetricEncryptionEnabled(node);
        boolean asymmetricEncryptionEnabled = CipherHelper.isAsymmetricEncryptionEnabled(node);
        if (asymmetricEncryptionEnabled || symmetricEncryptionEnabled) {
            if (asymmetricEncryptionEnabled && symmetricEncryptionEnabled) {
                if (true) {
                    logger.log(Level.INFO, "Incorrect encryption configuration.");
                    logger.log(Level.INFO, "You can enable either SymmetricEncryption or AsymmetricEncryption.");
                    throw new RuntimeException();
                }
                packetWriter = new ComplexCipherPacketWriter();
                logger.log(Level.INFO, "Writer started with ComplexEncryption");
            } else if (symmetricEncryptionEnabled) {
                packetWriter = new SymmetricCipherPacketWriter();
                logger.log(Level.INFO, "Writer started with SymmetricEncryption");
            } else {
                packetWriter = new AsymmetricCipherPacketWriter();
                logger.log(Level.INFO, "Writer started with AsymmetricEncryption");
            }
        } else {
            packetWriter = new DefaultPacketWriter();
        }
    }

    public void enqueuePacket(final Packet packet) {
        packet.write();
        writeQueue.offer(packet);
        if (informSelector.compareAndSet(true, false)) {
            outSelector.addTask(this);
            if (true || packet.currentCallCount < 2) {
                outSelector.selector.wakeup();
            }
        }
    }

    public void handle() {
        if (lastPacket == null) {
            lastPacket = writeQueue.poll();
            if (lastPacket == null && socketBB.position() == 0) {
                ready = true;
                return;
            }
        }
        if (!connection.live())
            return;
        try {
            while (socketBB.hasRemaining()) {
                if (lastPacket == null) {
                    lastPacket = writeQueue.poll();
                }
                if (lastPacket != null) {
                    boolean packetDone = packetWriter.writePacket(lastPacket);
                    if (packetDone) {
                        node.getPacketPool().release(lastPacket);
                        lastPacket = null;
                    } else {
                        if (socketBB.hasRemaining()) {
                            break;
                        }
                    }
                } else {
                    break;
                }
            }
            socketBB.flip();
            try {
                int written = socketChannel.write(socketBB);
            } catch (final Exception e) {
                if (lastPacket != null) {
                    node.getPacketPool().release(lastPacket);
                    lastPacket = null;
                }
                handleSocketException(e);
                return;
            }
            if (socketBB.hasRemaining()) {
                socketBB.compact();
            } else {
                socketBB.clear();
            }
        } catch (final Throwable t) {
            logger.log(Level.SEVERE, "Fatal Error at WriteHandler for endPoint: " + connection.getEndPoint(), t);
            t.printStackTrace();
        } finally {
            ready = false;
            registerWrite();
        }
    }

    public void run() {
        informSelector.set(true);
        if (ready) {
            handle();
        } else {
            registerWrite();
        }
        ready = false;
    }

    interface PacketWriter {
        boolean writePacket(Packet packet) throws Exception;
    }

    class DefaultPacketWriter implements PacketWriter {
        public boolean writePacket(Packet packet) {
            return packet.writeToSocketBuffer(socketBB);
        }
    }

    class ComplexCipherPacketWriter implements PacketWriter {
        boolean joinPartDone = false;
        AsymmetricCipherPacketWriter apw = new AsymmetricCipherPacketWriter();
        SymmetricCipherPacketWriter spw = new SymmetricCipherPacketWriter();
        int joinPartTotalWrites = 0;
        final int maxJoinWrite;

        ComplexCipherPacketWriter() {
            maxJoinWrite = 2280;
        }

        public boolean writePacket(Packet packet) throws Exception {
            boolean result = false;
            if (!joinPartDone) {
                int left = maxJoinWrite - joinPartTotalWrites;
                if (socketBB.remaining() > left) {
                    socketBB.limit(socketBB.position() + left);
                }
                int currentPosition = socketBB.position();
                result = apw.writePacket(packet);
                joinPartTotalWrites += (socketBB.position() - currentPosition);
                socketBB.limit(socketBB.capacity());
                if (joinPartTotalWrites == maxJoinWrite) {
//                    apw.cipherBuffer.flip();
//                    socketBB.put (apw.cipherBuffer);
//                    System.out.println("LEFT " + apw.cipherBuffer.position());
                    joinPartDone = true;
                    apw = null;
                    writePacket(packet);
                }
            } else {
                result = spw.writePacket(packet);
            }
            return result;
        }
    }

    class AsymmetricCipherPacketWriter implements PacketWriter {
        final ByteBuffer cipherBuffer = ByteBuffer.allocate(2 * SEND_SOCKET_BUFFER_SIZE);
        final Cipher cipher;
        final int writeBlockSize;

        boolean aliasWritten = false;

        AsymmetricCipherPacketWriter() {
            Cipher c = null;
            try {
                c = CipherHelper.createAsymmetricWriterCipher(node);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Asymmetric Cipher for WriteHandler cannot be initialized.", e);
                cipher = null;
                writeBlockSize = 0;
                CipherHelper.handleCipherException(e, connection);
                return;
            }
            cipher = c;
            writeBlockSize = cipher.getBlockSize();
        }

        public boolean writePacket(Packet packet) throws Exception {
            if (!aliasWritten) {
                String localAlias = CipherHelper.getKeyAlias(node);
                byte[] localAliasBytes = localAlias.getBytes();
                socketBB.putInt(localAliasBytes.length);
                socketBB.put(localAliasBytes);
                aliasWritten = true;
            }
            boolean complete = encryptAndWrite(packet);
            if (complete) {
                aliasWritten = false;
            }
            return complete;
        }

        public final boolean encryptAndWrite(Packet packet) throws Exception {
            if (cipherBuffer.position() > 0 && socketBB.hasRemaining()) {
                cipherBuffer.flip();
                copyToDirectBuffer(cipherBuffer, socketBB);
                if (cipherBuffer.hasRemaining()) {
                    cipherBuffer.compact();
                } else {
                    cipherBuffer.clear();
                }
            }
            packet.totalWritten += encryptAndWriteToSocket(packet.bbSizes);
            packet.totalWritten += encryptAndWriteToSocket(packet.bbHeader);
            if (packet.getKey() != null && packet.getKey().size() > 0 && socketBB.hasRemaining()) {
                packet.totalWritten += encryptAndWriteToSocket(packet.getKey().buffer);
            }
            if (packet.getValue() != null && packet.getValue().size() > 0 && socketBB.hasRemaining()) {
                packet.totalWritten += encryptAndWriteToSocket(packet.getValue().buffer);
            }
            return packet.totalWritten >= packet.totalSize;
        }

        private int encryptAndWriteToSocket(ByteBuffer src) throws Exception {
            int remaining = src.remaining();
            if (src.hasRemaining()) {
                doCipherUpdate(src);
                cipherBuffer.flip();
                copyToDirectBuffer(cipherBuffer, socketBB);
                if (cipherBuffer.hasRemaining()) {
                    cipherBuffer.compact();
                } else {
                    cipherBuffer.clear();
                }
                return remaining - src.remaining();
            }
            return 0;
        }

        private void doCipherUpdate(ByteBuffer src) throws Exception {
            while (src.hasRemaining()) {
                int remaining = src.remaining();
                if (remaining > writeBlockSize) {
                    int oldLimit = src.limit();
                    src.limit(src.position() + writeBlockSize);
                    int outputAppendSize = cipher.doFinal(src, cipherBuffer);
                    src.limit(oldLimit);
                } else {
                    int outputAppendSize = cipher.doFinal(src, cipherBuffer);
                }
            }
        }
    }

    class SymmetricCipherPacketWriter implements PacketWriter {
        boolean sizeWritten = false;
        final ByteBuffer cipherBuffer = ByteBuffer.allocate(SEND_SOCKET_BUFFER_SIZE);
        final Cipher cipher;

        SymmetricCipherPacketWriter() {
            Cipher c = null;
            try {
                c = CipherHelper.createSymmetricWriterCipher(node);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Symmetric Cipher for WriteHandler cannot be initialized.", e);
                CipherHelper.handleCipherException(e, connection);
            }
            cipher = c;
        }

        public boolean writePacket(Packet packet) throws Exception {
            if (cipherBuffer.position() > 0 && socketBB.hasRemaining()) {
                cipherBuffer.flip();
                copyToDirectBuffer(cipherBuffer, socketBB);
                if (cipherBuffer.hasRemaining()) {
                    cipherBuffer.compact();
                } else {
                    cipherBuffer.clear();
                }
            }
            if (!sizeWritten) {
                int cipherSize = cipher.getOutputSize(packet.totalSize);
                socketBB.putInt(cipherSize);
                sizeWritten = true;
            }
            packet.totalWritten += encryptAndWriteToSocket(packet.bbSizes);
            packet.totalWritten += encryptAndWriteToSocket(packet.bbHeader);
            if (packet.getKey() != null && packet.getKey().size() > 0 && socketBB.hasRemaining()) {
                packet.totalWritten += encryptAndWriteToSocket(packet.getKey().buffer);
            }
            if (packet.getValue() != null && packet.getValue().size() > 0 && socketBB.hasRemaining()) {
                packet.totalWritten += encryptAndWriteToSocket(packet.getValue().buffer);
            }
            boolean complete = packet.totalWritten >= packet.totalSize;
            if (complete) {
                if (socketBB.remaining() >= cipher.getOutputSize(0)) {
                    sizeWritten = false;
                    socketBB.put(cipher.doFinal());
                } else {
                    return false;
                }
            }
            return complete;
        }

        private int encryptAndWriteToSocket(ByteBuffer src) throws Exception {
            int remaining = src.remaining();
            if (src.hasRemaining() && cipherBuffer.hasRemaining()) {
                int outputSize = cipher.getOutputSize(src.remaining());
                if (outputSize <= cipherBuffer.remaining()) {
                    cipher.update(src, cipherBuffer);
                } else {
                    int min = Math.min(src.remaining(), cipherBuffer.remaining());
                    int len = min / 2;
                    if (len > 0) {
                        int limitOld = src.limit();
                        src.limit(src.position() + len);
                        cipher.update(src, cipherBuffer);
                        src.limit(limitOld);
                    } else {
                        return 0;
                    }
                }
                cipherBuffer.flip();
                copyToDirectBuffer(cipherBuffer, socketBB);
                if (cipherBuffer.hasRemaining()) {
                    cipherBuffer.compact();
                } else {
                    cipherBuffer.clear();
                }
                return remaining - src.remaining();
            }
            return 0;
        }
    }

    private void registerWrite() {
        registerOp(outSelector.selector, SelectionKey.OP_WRITE);
    }

    @Override
    public void shutdown() {
        writeQueue.clear();
    }
}
