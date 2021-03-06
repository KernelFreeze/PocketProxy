/*
 *       _   _____            _      _   _          _   
 *      | | |  __ \          | |    | \ | |        | |  
 *      | | | |__) |   __ _  | | __ |  \| |   ___  | |_ 
 *  _   | | |  _  /   / _` | | |/ / | . ` |  / _ \ | __|
 * | |__| | | | \ \  | (_| | |   <  | |\  | |  __/ | |_ 
 *  \____/  |_|  \_\  \__,_| |_|\_\ |_| \_|  \___|  \__|
 *                                                  
 * the MIT License (MIT)
 *
 * Copyright (c) 2016, 2017 MarfGamer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * the above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.  
 */
package me.kernelfreeze.bedrockproxy.raknet.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import me.kernelfreeze.bedrockproxy.raknet.NoListenerException;
import me.kernelfreeze.bedrockproxy.raknet.Packet;
import me.kernelfreeze.bedrockproxy.raknet.RakNet;
import me.kernelfreeze.bedrockproxy.raknet.RakNetPacket;
import me.kernelfreeze.bedrockproxy.raknet.identifier.Identifier;
import me.kernelfreeze.bedrockproxy.raknet.protocol.Reliability;
import me.kernelfreeze.bedrockproxy.raknet.protocol.login.*;
import me.kernelfreeze.bedrockproxy.raknet.protocol.message.CustomPacket;
import me.kernelfreeze.bedrockproxy.raknet.protocol.message.acknowledge.Acknowledge;
import me.kernelfreeze.bedrockproxy.raknet.protocol.status.UnconnectedPing;
import me.kernelfreeze.bedrockproxy.raknet.protocol.status.UnconnectedPong;
import me.kernelfreeze.bedrockproxy.raknet.session.GeminusRakNetPeer;
import me.kernelfreeze.bedrockproxy.raknet.session.RakNetClientSession;
import me.kernelfreeze.bedrockproxy.raknet.session.RakNetState;
import me.kernelfreeze.bedrockproxy.raknet.util.RakNetUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static me.kernelfreeze.bedrockproxy.raknet.protocol.MessageIdentifier.*;

/**
 * Used to easily create servers using the RakNet protocol.
 *
 * @author MarfGamer
 */
public class RakNetServer implements GeminusRakNetPeer, RakNetServerListener {

    // Server data
    private final long guid;
    private final long timestamp;
    private final int port;
    private final int maxConnections;
    private final int maximumTransferUnit;
    // Networking data
    private final Bootstrap bootstrap;
    private final EventLoopGroup group;
    private final RakNetServerHandler handler;
    private final ConcurrentHashMap<InetSocketAddress, RakNetClientSession> sessions;
    private boolean broadcastingEnabled;
    private Identifier identifier;
    // Session data
    private Channel channel;
    private volatile RakNetServerListener listener;
    private volatile boolean running;

    /**
     * Constructs a <code>RakNetServer</code> with the specified port, maximum
     * amount connections, maximum transfer unit, and <code>Identifier</code>.
     *
     * @param port                the server port.
     * @param maxConnections      the maximum amount of connections.
     * @param maximumTransferUnit the maximum transfer unit.
     * @param identifier          the <code>Identifier</code>.
     */
    public RakNetServer(int port, int maxConnections, int maximumTransferUnit, Identifier identifier) {
        // Set server data
        this.guid = new Random().nextLong();
        this.timestamp = System.currentTimeMillis();
        this.port = port;
        this.maxConnections = maxConnections;
        this.maximumTransferUnit = maximumTransferUnit;
        this.broadcastingEnabled = true;
        this.identifier = identifier;

        // Initiate bootstrap data
        this.bootstrap = new Bootstrap();
        this.group = new NioEventLoopGroup();
        this.handler = new RakNetServerHandler(this);

        // Set listener
        this.listener = this;

        // Create session map
        this.sessions = new ConcurrentHashMap<InetSocketAddress, RakNetClientSession>();

        // Check maximum transfer unit
        if (this.maximumTransferUnit < RakNet.MINIMUM_TRANSFER_UNIT) {
            throw new IllegalArgumentException(
                    "Maximum transfer unit can be no smaller than " + RakNet.MINIMUM_TRANSFER_UNIT);
        }
    }

    /**
     * Constructs a <code>RakNetServer</code> with the specified port, maximum
     * amount connections, and maximum transfer unit.
     *
     * @param port                the server port.
     * @param maxConnections      the maximum amount of connections.
     * @param maximumTransferUnit the maximum transfer unit.
     */
    public RakNetServer(int port, int maxConnections, int maximumTransferUnit) {
        this(port, maxConnections, maximumTransferUnit, null);
    }

    /**
     * Constructs a <code>RakNetServer</code> with the specified port and
     * maximum amount of connections.
     *
     * @param port           the server port.
     * @param maxConnections the maximum amount of connections.
     */
    public RakNetServer(int port, int maxConnections) {
        this(port, maxConnections, RakNetUtils.getMaximumTransferUnit());
    }

    /**
     * Constructs a <code>RakNetServer</code> with the specified port, maximum
     * amount connections, and <code>Identifier</code>.
     *
     * @param port           the server port.
     * @param maxConnections the maximum amount of connections.
     * @param identifier     the <code>Identifier</code>.
     */
    public RakNetServer(int port, int maxConnections, Identifier identifier) {
        this(port, maxConnections);
        this.identifier = identifier;
    }

    /**
     * @return the server's networking protocol version.
     */
    public final int getProtocolVersion() {
        return RakNet.SERVER_NETWORK_PROTOCOL;
    }

    /**
     * @return the server's globally unique ID.
     */
    public final long getGloballyUniqueId() {
        return this.guid;
    }

    /**
     * @return the server's timestamp.
     */
    public final long getTimestamp() {
        return (System.currentTimeMillis() - this.timestamp);
    }

    /**
     * @return the port the server is bound to.
     */
    public final int getPort() {
        return this.port;
    }

    /**
     * @return the maximum amount of connections the server can handle at once.
     */
    public final int getMaxConnections() {
        return this.maxConnections;
    }

    /**
     * @return the maximum transfer unit.
     */
    public final int getMaximumTransferUnit() {
        return this.maximumTransferUnit;
    }

    /**
     * @return true if broadcasting is enabled.
     */
    public final boolean isBroadcastingEnabled() {
        return this.broadcastingEnabled;
    }

    /**
     * Enables/disables server broadcasting.
     *
     * @param enabled whether or not the server will broadcast.
     */
    public final void setBroadcastingEnabled(boolean enabled) {
        this.broadcastingEnabled = enabled;
    }

    /**
     * @return the identifier the server uses for discovery.
     */
    public final Identifier getIdentifier() {
        return this.identifier;
    }

    /**
     * Sets the server's identifier used for discovery.
     *
     * @param identifier the new identifier.
     */
    public final void setIdentifier(Identifier identifier) {
        this.identifier = identifier;
    }

    /**
     * @return the server's listener.
     */
    public final RakNetServerListener getListener() {
        return this.listener;
    }

    /**
     * Sets the server's listener.
     *
     * @param listener the new listener.
     * @return the server.
     */
    public final RakNetServer setListener(RakNetServerListener listener) {
        if (listener == null) {
            throw new NullPointerException();
        }
        this.listener = listener;
        return this;
    }

    /**
     * @return the sessions connected to the server.
     */
    public final RakNetClientSession[] getSessions() {
        synchronized (sessions) {
            return sessions.values().toArray(new RakNetClientSession[sessions.size()]);
        }
    }

    /**
     * @return the amount of sessions connected to the server.
     */
    public final int getSessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    /**
     * @param address the address to check.
     * @return true server has a session with the specified address.
     */
    public final boolean hasSession(InetSocketAddress address) {
        synchronized (sessions) {
            return sessions.containsKey(address);
        }
    }

    /**
     * @param guid the globally unique ID to check.
     * @return true if the server has a session with the specified globally
     * unique ID.
     */
    public final boolean hasSession(long guid) {
        synchronized (sessions) {
            for (RakNetClientSession session : sessions.values()) {
                if (session.getGloballyUniqueId() == guid) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param address the address of the session.
     * @return a session connected to the server by their address.
     */
    public final RakNetClientSession getSession(InetSocketAddress address) {
        synchronized (sessions) {
            return sessions.get(address);
        }
    }

    /**
     * @param guid the globally unique ID of the session.
     * @return a session connected to the server by their address.
     */
    public final RakNetClientSession getSession(long guid) {
        synchronized (sessions) {
            for (RakNetClientSession session : sessions.values()) {
                if (session.getGloballyUniqueId() == guid) {
                    return session;
                }
            }
        }
        return null;
    }

    @Override
    public final void sendMessage(long guid, Reliability reliability, int channel, Packet packet) {
        if (this.hasSession(guid)) {
            this.getSession(guid).sendMessage(reliability, channel, packet);
        }
    }

    /**
     * Removes a session from the server with the specified reason.
     *
     * @param address the address of the session.
     * @param reason  the reason the session was removed.
     */
    public final void removeSession(InetSocketAddress address, String reason) {
        synchronized (sessions) {
            if (sessions.containsKey(address)) {
                RakNetClientSession session = sessions.get(address);
                if (session.getState() == RakNetState.CONNECTED) {
                    listener.onClientDisconnect(session, reason);
                } else {
                    listener.onClientPreDisconnect(address, reason);
                }
                session.sendMessage(Reliability.UNRELIABLE, ID_DISCONNECTION_NOTIFICATION);
                sessions.remove(address);
            }
        }
    }

    /**
     * Removes a session from the server.
     *
     * @param address the address of the session.
     */
    public final void removeSession(InetSocketAddress address) {
        this.removeSession(address, "Disconnected from server");
    }

    /**
     * Removes a session from the server with the specified reason.
     *
     * @param session the session to remove.
     * @param reason  the reason the session was removed.
     */
    public final void removeSession(RakNetClientSession session, String reason) {
        this.removeSession(session.getAddress(), reason);
    }

    /**
     * Removes a session from the server.
     *
     * @param session the session to remove.
     */
    public final void removeSession(RakNetClientSession session) {
        this.removeSession(session, "Disconnected from server");
    }

    /**
     * Blocks the address and disconnects all the clients on the address with
     * the specified reason for the specified amount of time.
     *
     * @param address the address to block.
     * @param reason  the reason the address was blocked.
     * @param time    how long the address will blocked in milliseconds.
     */
    public final void blockAddress(InetAddress address, String reason, long time) {
        synchronized (sessions) {
            for (InetSocketAddress clientAddress : sessions.keySet()) {
                if (clientAddress.getAddress().equals(address)) {
                    this.removeSession(clientAddress, reason);
                }
            }
        }
        handler.blockAddress(address, reason, time);
    }

    /**
     * Blocks the address and disconnects all the clients on the address for the
     * specified amount of time.
     *
     * @param address the address to block.
     * @param time    how long the address will blocked in milliseconds.
     */
    public final void blockAddress(InetAddress address, long time) {
        this.blockAddress(address, "Blocked", time);
    }

    /**
     * Unblocks the specified address.
     *
     * @param address the address to unblock.
     */
    public final void unblockAddress(InetAddress address) {
        handler.unblockAddress(address);
    }

    /**
     * @param address the address to check.
     * @return true if the specified address is blocked.
     */
    public final boolean addressBlocked(InetAddress address) {
        return handler.addressBlocked(address);
    }

    /**
     * Called whenever the handler catches an exception in Netty.
     *
     * @param address the address that caused the exception.
     * @param cause   the exception caught by the handler.
     */
    protected final void handleHandlerException(InetSocketAddress address, Throwable cause) {
        if (this.hasSession(address)) {
            this.removeSession(address, cause.getClass().getName());
        }
        listener.onHandlerException(address, cause);
    }

    /**
     * Handles a packet received by the handler.
     *
     * @param packet the packet to handle.
     * @param sender the address of the sender.
     */
    protected final void handleMessage(RakNetPacket packet, InetSocketAddress sender) {
        short packetId = packet.getId();

        if (packetId == ID_UNCONNECTED_PING || packetId == ID_UNCONNECTED_PING_OPEN_CONNECTIONS) {
            UnconnectedPing ping = new UnconnectedPing(packet);
            ping.decode();

            // Make sure parameters match and that broadcasting is enabled
            synchronized (sessions) {
                if ((packetId == ID_UNCONNECTED_PING || sessions.size() < this.maxConnections)
                        && this.broadcastingEnabled == true) {
                    ServerPing pingEvent = new ServerPing(sender, identifier);
                    listener.handlePing(pingEvent);

                    if (ping.magic == true && pingEvent.getIdentifier() != null) {
                        UnconnectedPong pong = new UnconnectedPong();
                        pong.pingId = ping.timestamp;
                        pong.pongId = this.getTimestamp();
                        pong.identifier = pingEvent.getIdentifier();

                        pong.encode();
                        this.sendNettyMessage(pong, sender);
                    }
                }
            }
        } else if (packetId == ID_OPEN_CONNECTION_REQUEST_1) {
            OpenConnectionRequestOne connectionRequestOne = new OpenConnectionRequestOne(packet);
            connectionRequestOne.decode();

            synchronized (sessions) {
                if (sessions.containsKey(sender)) {
                    if (sessions.get(sender).getState().equals(RakNetState.CONNECTED)) {
                        this.removeSession(sender, "Client re-instantiated connection");
                    }
                }
            }

            if (connectionRequestOne.magic == true) {
                // Are there any problems?
                RakNetPacket errorPacket = this.validateSender(sender);
                if (errorPacket == null) {
                    if (connectionRequestOne.protocolVersion != this.getProtocolVersion()) {
                        // Incompatible protocol
                        IncompatibleProtocol incompatibleProtocol = new IncompatibleProtocol();
                        incompatibleProtocol.networkProtocol = this.getProtocolVersion();
                        incompatibleProtocol.serverGuid = this.guid;
                        incompatibleProtocol.encode();
                        this.sendNettyMessage(incompatibleProtocol, sender);
                    } else {
                        // Everything passed, one last check...
                        if (connectionRequestOne.maximumTransferUnit <= this.maximumTransferUnit) {
                            OpenConnectionResponseOne connectionResponseOne = new OpenConnectionResponseOne();
                            connectionResponseOne.serverGuid = this.guid;
                            connectionResponseOne.maximumTransferUnit = connectionRequestOne.maximumTransferUnit;
                            connectionResponseOne.encode();
                            this.sendNettyMessage(connectionResponseOne, sender);
                        }
                    }
                } else {
                    this.sendNettyMessage(errorPacket, sender);
                }
            }
        } else if (packetId == ID_OPEN_CONNECTION_REQUEST_2) {
            OpenConnectionRequestTwo connectionRequestTwo = new OpenConnectionRequestTwo(packet);
            connectionRequestTwo.decode();

            if (!connectionRequestTwo.failed() && connectionRequestTwo.magic == true) {
                // Are there any problems?
                RakNetPacket errorPacket = this.validateSender(sender);
                if (errorPacket == null) {
                    if (this.hasSession(connectionRequestTwo.clientGuid)) {
                        // This client is already connected
                        this.sendNettyMessage(ID_ALREADY_CONNECTED, sender);
                    } else {
                        // Everything passed, one last check...
                        if (connectionRequestTwo.maximumTransferUnit <= this.maximumTransferUnit) {
                            // Create response
                            OpenConnectionResponseTwo connectionResponseTwo = new OpenConnectionResponseTwo();
                            connectionResponseTwo.serverGuid = this.guid;
                            connectionResponseTwo.clientAddress = sender;
                            connectionResponseTwo.maximumTransferUnit = connectionRequestTwo.maximumTransferUnit;
                            connectionResponseTwo.encryptionEnabled = false;
                            connectionResponseTwo.encode();

                            if (!connectionResponseTwo.failed()) {
                                // Call event
                                this.getListener().onClientPreConnect(sender);

                                // Create session
                                synchronized (sessions) {
                                    RakNetClientSession clientSession = new RakNetClientSession(this,
                                            System.currentTimeMillis(), connectionRequestTwo.clientGuid,
                                            connectionRequestTwo.maximumTransferUnit, channel, sender);
                                    sessions.put(sender, clientSession);
                                }

                                // Send response, we are ready for login
                                this.sendNettyMessage(connectionResponseTwo, sender);
                            }
                        }
                    }
                } else {
                    this.sendNettyMessage(errorPacket, sender);
                }
            }
        } else if (packetId >= ID_CUSTOM_0 && packetId <= ID_CUSTOM_F) {
            synchronized (sessions) {
                if (sessions.containsKey(sender)) {
                    CustomPacket custom = new CustomPacket(packet);
                    custom.decode();

                    RakNetClientSession session = sessions.get(sender);
                    session.handleCustom(custom);
                }
            }
        } else if (packetId == Acknowledge.ACKNOWLEDGED || packetId == Acknowledge.NOT_ACKNOWLEDGED) {
            synchronized (sessions) {
                if (sessions.containsKey(sender)) {
                    Acknowledge acknowledge = new Acknowledge(packet);
                    acknowledge.decode();

                    RakNetClientSession session = sessions.get(sender);
                    session.handleAcknowledge(acknowledge);
                }
            }
        }
    }

    /**
     * Validates the sender during login to make sure there are no problems.
     *
     * @param sender the address of the packet sender.
     * @return the packet to respond with if there was an error.
     */
    private final RakNetPacket validateSender(InetSocketAddress sender) {
        // Checked throughout all login
        if (this.hasSession(sender)) {
            return new RakNetPacket(ID_ALREADY_CONNECTED);
        } else if (this.getSessionCount() >= this.maxConnections) {
            // We have no free connections
            return new RakNetPacket(ID_NO_FREE_INCOMING_CONNECTIONS);
        } else if (this.addressBlocked(sender.getAddress())) {
            // Address is blocked
            ConnectionBanned connectionBanned = new ConnectionBanned();
            connectionBanned.serverGuid = this.guid;
            connectionBanned.encode();
            return connectionBanned;
        }

        // there were no errors
        return null;
    }

    /**
     * Sends a raw message to the specified address. Be careful when using this
     * method, because if it is used incorrectly it could break server sessions
     * entirely! If you are wanting to send a message to a session, you are
     * probably looking for the
     * {@link me.kernelfreeze.bedrockproxy.raknet.session.RakNetSession#sendMessage(me.kernelfreeze.bedrockproxy.raknet.protocol.Reliability, me.kernelfreeze.bedrockproxy.raknet.Packet)
     * sendMessage} method.
     *
     * @param buf     the buffer to send.
     * @param address the address to send the buffer to.
     */
    public final void sendNettyMessage(ByteBuf buf, InetSocketAddress address) {
        channel.writeAndFlush(new DatagramPacket(buf, address));
    }

    /**
     * Sends a raw message to the specified address. Be careful when using this
     * method, because if it is used incorrectly it could break server sessions
     * entirely! If you are wanting to send a message to a session, you are
     * probably looking for the
     * {@link me.kernelfreeze.bedrockproxy.raknet.session.RakNetSession#sendMessage(me.kernelfreeze.bedrockproxy.raknet.protocol.Reliability, me.kernelfreeze.bedrockproxy.raknet.Packet)
     * sendMessage} method.
     *
     * @param packet  the buffer to send.
     * @param address the address to send the buffer to.
     */
    public final void sendNettyMessage(Packet packet, InetSocketAddress address) {
        this.sendNettyMessage(packet.buffer(), address);
    }

    /**
     * Sends a raw message to the specified address. Be careful when using this
     * method, because if it is used incorrectly it could break server sessions
     * entirely! If you are wanting to send a message to a session, you are
     * probably looking for the
     * {@link me.kernelfreeze.bedrockproxy.raknet.session.RakNetSession#sendMessage(me.kernelfreeze.bedrockproxy.raknet.protocol.Reliability, int)
     * sendMessage} method.
     *
     * @param packetId the ID of the packet to send.
     * @param address  the address to send the packet to.
     */
    public final void sendNettyMessage(int packetId, InetSocketAddress address) {
        this.sendNettyMessage(new RakNetPacket(packetId), address);
    }

    /**
     * Starts the server.
     *
     * @throws NoListenerException if the listener has not yet been set.
     */
    public final void start() throws NoListenerException {
        // Make sure we have an adapter
        if (listener == null) {
            throw new NoListenerException();
        }

        // Create bootstrap and bind the channel
        try {
            bootstrap.channel(NioDatagramChannel.class).group(group).handler(handler);
            bootstrap.option(ChannelOption.SO_BROADCAST, true).option(ChannelOption.SO_REUSEADDR, false);
            this.channel = bootstrap.bind(port).sync().channel();
            this.running = true;
        } catch (InterruptedException e) {
            e.printStackTrace();
            this.running = false;
        }

        // Notify API
        listener.onServerStart();

        // Update system
        while (this.running == true) {
            synchronized (sessions) {
                for (RakNetClientSession session : sessions.values()) {
                    try {
                        // Update session and make sure it isn't DOSing us
                        session.update();
                        if (session.getPacketsReceivedThisSecond() >= RakNet.MAX_PACKETS_PER_SECOND) {
                            this.blockAddress(session.getInetAddress(), "Too many packets",
                                    RakNet.MAX_PACKETS_PER_SECOND_BLOCK);
                        }
                    } catch (Throwable throwable) {
                        // An error related to the session occurred, remove it
                        listener.onSessionException(session, throwable);
                        this.removeSession(session, throwable.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Starts the server on it's own <code>Thread</code>.
     *
     * @return the <code>Thread</code> the server is running on.
     */
    public final synchronized Thread startThreaded() {
        // Give the thread a reference
        RakNetServer server = this;

        // Create thread and start it
        Thread thread = new Thread() {
            @Override
            public synchronized void run() {
                try {
                    server.start();
                } catch (Throwable throwable) {
                    server.getListener().onThreadException(throwable);
                }
            }
        };
        thread.start();

        // Return the thread so it can be modified
        return thread;
    }

    /**
     * Stops the server.
     */
    public final void shutdown() {
        this.running = false;
        synchronized (sessions) {
            for (RakNetClientSession session : sessions.values()) {
                this.removeSession(session, "Server shutdown");
            }
            sessions.clear();
        }
        listener.onServerShutdown();
    }

}
