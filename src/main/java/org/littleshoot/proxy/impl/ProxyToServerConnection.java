package org.littleshoot.proxy.impl;

import static org.littleshoot.proxy.impl.ConnectionState.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.SSLSession;

import org.apache.commons.lang3.StringUtils;
import org.littleshoot.dnssec4j.VerifiedAddressFactory;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolError;
import org.slf4j.spi.LocationAwareLogger;

/**
 * <p>
 * Represents a connection from our proxy to a server on the web.
 * ProxyConnections are reused fairly liberally, and can go from disconnected to
 * connected, back to disconnected and so on.
 * </p>
 * 
 * <p>
 * Connecting a {@link ProxyToServerConnection} can involve more than just
 * connecting the underlying {@link Channel}. In particular, the connection may
 * use encryption (i.e. TLS) and it may also establish an HTTP CONNECT tunnel.
 * The various steps involved in fully establishing a connection are
 * encapsulated in the property {@link #connectionFlow}, which is initialized in
 * {@link #initializeConnectionFlow()}.
 * </p>
 */
@Sharable
public class ProxyToServerConnection extends ProxyConnection<HttpResponse> {
	
	private final ClientToProxyConnection clientConnection;
    private final ProxyToServerConnection serverConnection = this;
    private volatile TransportProtocol transportProtocol;
    private volatile InetSocketAddress remoteAddress;
    private volatile InetSocketAddress localAddress;
    private final String serverHostAndPort;
    private volatile ChainedProxy chainedProxy;
    private final Queue<ChainedProxy> availableChainedProxies;

    /**
     * The filters to apply to response/chunks received from server.
     */
    private volatile HttpFilters currentFilters;

    /**
     * Encapsulates the flow for establishing a connection, which can vary
     * depending on how things are configured.
     */
    private volatile ConnectionFlow connectionFlow;

    /**
     * While we're in the process of connecting, it's possible that we'll
     * receive a new message to write. This lock helps us synchronize and wait
     * for the connection to be established before writing the next message.
     */
    private final Object connectLock = new Object();

    /**
     * This is the initial request received prior to connecting. We keep track
     * of it so that we can process it after connection finishes.
     */
    private volatile HttpRequest initialRequest;

    /**
     * Keeps track of HttpRequests that have been issued so that we can
     * associate them with responses that we get back
     */
    private final Queue<HttpRequest> issuedRequests = new LinkedList<HttpRequest>();

    /**
     * While we're doing a chunked transfer, this keeps track of the HttpRequest
     * to which we're responding.
     */
    private volatile HttpRequest currentHttpRequest;

    /**
     * While we're doing a chunked transfer, this keeps track of the initial
     * HttpResponse object for our transfer (which is useful for its headers).
     */
    private volatile HttpResponse currentHttpResponse;

    /**
     * Create a new ProxyToServerConnection.
     * 
     * @param proxyServer
     * @param clientConnection
     * @param serverHostAndPort
     * @param currentFilters
     * @param initialHttpRequest
     * @return
     * @throws UnknownHostException
     */
    static ProxyToServerConnection create(DefaultHttpProxyServer proxyServer,
            ClientToProxyConnection clientConnection,
            String serverHostAndPort,
            HttpRequest initialHttpRequest)
            throws UnknownHostException {
        Queue<ChainedProxy> chainedProxies = new ConcurrentLinkedQueue<ChainedProxy>();
        ChainedProxyManager chainedProxyManager = proxyServer
                .getChainProxyManager();
        if (chainedProxyManager != null) {
            chainedProxyManager.lookupChainedProxies(initialHttpRequest,
                    chainedProxies);
        }
        return new ProxyToServerConnection(proxyServer, clientConnection,
                serverHostAndPort, chainedProxies.poll(), chainedProxies);
    }

    private ProxyToServerConnection(
            DefaultHttpProxyServer proxyServer,
            ClientToProxyConnection clientConnection,
            String serverHostAndPort,
            ChainedProxy chainedProxy,
            Queue<ChainedProxy> availableChainedProxies)
            throws UnknownHostException {
        super(DISCONNECTED, proxyServer, true);
        this.clientConnection = clientConnection;
        this.serverHostAndPort = serverHostAndPort;
        this.chainedProxy = chainedProxy;
        this.availableChainedProxies = availableChainedProxies;
        setupConnectionParameters();
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected void read(Object msg) {
        if (isConnecting()) {
            LOG.debug(
                    "In the middle of connecting, forwarding message to connection flow: {}",
                    msg);
            this.connectionFlow.read(msg);
        } else {
            super.read(msg);
        }
    }

    @Override
    protected ConnectionState readHTTPInitial(HttpResponse httpResponse) {
        LOG.debug("Received raw response: {}", httpResponse);

        rememberCurrentResponse(httpResponse);
        respondWith(httpResponse);

        return ProxyUtils.isChunked(httpResponse) ? AWAITING_CHUNK
                : AWAITING_INITIAL;
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
        respondWith(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        clientConnection.write(buf);
    }

    /**
     * <p>
     * Responses to HEAD requests aren't supposed to have content, but Netty
     * doesn't know that any given response is to a HEAD request, so it needs to
     * be told that there's no content so that it doesn't hang waiting for it.
     * </p>
     * 
     * <p>
     * See the documentation for {@link HttpResponseDecoder} for information
     * about why HEAD requests need special handling.
     * </p>
     * 
     * <p>
     * Thanks to <a href="https://github.com/nataliakoval">nataliakoval</a> for
     * pointing out that with connections being reused as they are, this needs
     * to be sensitive to the current request.
     * </p>
     */
    private class HeadAwareHttpResponseDecoder extends HttpResponseDecoder {

        public HeadAwareHttpResponseDecoder(int maxInitialLineLength,
                int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage httpMessage) {
            if (httpMessage instanceof HttpResponse) {
                // Identify our current request
                identifyCurrentRequest();
            }

            return HttpMethod.HEAD.equals(currentHttpRequest.getMethod()) ?
                    true : super.isContentAlwaysEmpty(httpMessage);
        }
    };

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * Like {@link #write(Object)} and also sets the current filters to the
     * given value.
     * 
     * @param msg
     * @param filters
     */
    void write(Object msg, HttpFilters filters) {
        this.currentFilters = filters;
        write(msg);
    }

    @Override
    void write(Object msg) {
        LOG.debug("Requested write of {}", msg);

        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }

        if (is(DISCONNECTED) && msg instanceof HttpRequest) {
            LOG.debug("Currently disconnected, connect and then write the message");
            connectAndWrite((HttpRequest) msg);
        } else {
            synchronized (connectLock) {
                if (isConnecting()) {
                    LOG.debug("Attempted to write while still in the process of connecting, waiting for connection.");
                    clientConnection.stopReading();
                    try {
                        connectLock.wait(30000);
                    } catch (InterruptedException ie) {
                        LOG.warn("Interrupted while waiting for connect monitor");
                    }
                    if (is(DISCONNECTED)) {
                        LOG.debug("Connection failed while we were waiting for it, don't write");
                        return;
                    }
                }
            }

            LOG.debug("Using existing connection to: {}", remoteAddress);
            doWrite(msg);
        }
    };

    @Override
    protected void writeHttp(HttpObject httpObject) {
        if (chainedProxy != null) {
            chainedProxy.filterRequest(httpObject);
        }
        if (httpObject instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) httpObject;
            // Remember that we issued this HttpRequest for later
            issuedRequests.add(httpRequest);
        }
        super.writeHttp(httpObject);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    @Override
    protected void becameSaturated() {
        super.becameSaturated();
        this.clientConnection.serverBecameSaturated(this);
    }

    @Override
    protected void becameWritable() {
        super.becameWritable();
        this.clientConnection.serverBecameWriteable(this);
    }
    
    @Override
    protected void timedOut() {
        super.timedOut();
        clientConnection.timedOut();
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        if (this.chainedProxy != null) {
            // Let the ChainedProxy know that we disconnected
            try {
                this.chainedProxy.disconnected();
            } catch (Exception e) {
                LOG.error("Unable to record connectionFailed", e);
            }
        }
        clientConnection.serverDisconnected(this);
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        String message = "Caught exception on proxy -> web connection";
        int logLevel = LocationAwareLogger.WARN_INT;
        if (cause != null) {
            String causeMessage = cause.getMessage();
            if (cause instanceof ConnectException) {
                logLevel = LocationAwareLogger.DEBUG_INT;
            } else if (causeMessage != null) {
                if (causeMessage.contains("Connection reset by peer")) {
                    logLevel = LocationAwareLogger.DEBUG_INT;
                } else if (causeMessage.contains("event executor terminated")) {
                    logLevel = LocationAwareLogger.DEBUG_INT;
                }
            }
        }
        LOG.log(logLevel, message, cause);

        if (!is(DISCONNECTED)) {
            LOG.log(logLevel, "Disconnecting open connection");
            disconnect();
        }
        // This can happen if we couldn't make the initial connection due
        // to something like an unresolved address, for example, or a timeout.
        // There will not have been be any requests written on an unopened
        // connection, so there should not be any further action to take here.
    }

    /***************************************************************************
     * State Management
     **************************************************************************/
    public TransportProtocol getTransportProtocol() {
        return transportProtocol;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public boolean hasUpstreamChainedProxy() {
        return getChainedProxyAddress() != null;
    }

    public InetSocketAddress getChainedProxyAddress() {
        return chainedProxy == null ? null : chainedProxy
                .getChainedProxyAddress();
    }

    public ChainedProxy getChainedProxy() {
        return chainedProxy;
    }

    public HttpRequest getInitialRequest() {
        return initialRequest;
    }

    /***************************************************************************
     * Private Implementation
     **************************************************************************/

    /**
     * An HTTP response is associated with a single request, so we can pop the
     * correct request off the queue.
     */
    private void identifyCurrentRequest() {
        LOG.debug("Remembering the current request.");
        // I'm a little unclear as to when the request queue would
        // ever actually be empty, but it is from time to time in practice.
        // We've seen this particularly when behind proxies that govern
        // access control on local networks, likely related to redirects.
        if (!this.issuedRequests.isEmpty()) {
            this.currentHttpRequest = this.issuedRequests.remove();
            if (this.currentHttpRequest == null) {
                LOG.warn("Got null HTTP request object.");
            }
        } else {
            LOG.debug("Request queue is empty!");
        }
    }

    /**
     * Keeps track of the current HttpResponse so that we can associate its
     * headers with future related chunks for this same transfer.
     * 
     * @param response
     */
    private void rememberCurrentResponse(HttpResponse response) {
        LOG.debug("Remembering the current response.");
        // We need to make a copy here because the response will be
        // modified in various ways before we need to do things like
        // analyze response headers for whether or not to close the
        // connection (which may not happen for a while for large, chunked
        // responses, for example).
        currentHttpResponse = ProxyUtils.copyMutableResponseFields(response);
    }

    /**
     * Respond to the client with the given {@link HttpObject}.
     * 
     * @param httpObject
     */
    private void respondWith(HttpObject httpObject) {
        clientConnection.respond(this, currentFilters, currentHttpRequest,
                currentHttpResponse, httpObject);
    }

    /**
     * Connects to the server and then writes out the initial request (or
     * upgrades to an SSL tunnel, depending).
     * 
     * @param initialRequest
     */
    private void connectAndWrite(final HttpRequest initialRequest) {
        LOG.debug("Starting new connection to: {}", remoteAddress);

        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;
        initializeConnectionFlow();
        connectionFlow.start();
    }

    /**
     * This method initializes our {@link ConnectionFlow} based on however this
     * connection has been configured.
     */
    private void initializeConnectionFlow() {
        this.connectionFlow = new ConnectionFlow(clientConnection, this,
                connectLock)
                .then(ConnectChannel);

        if (chainedProxy != null && chainedProxy.requiresEncryption()) {
            connectionFlow.then(serverConnection.EncryptChannel(chainedProxy
                    .newSslEngine()));
        }

        if (ProxyUtils.isCONNECT(initialRequest)) {
            MitmManager mitmManager = proxyServer.getMitmManager();
            boolean isMitmEnabled = mitmManager != null;

            if (isMitmEnabled) {
                connectionFlow.then(serverConnection.EncryptChannel(
                        mitmManager.serverSslEngine()))
                        .then(clientConnection.RespondCONNECTSuccessful)
                        .then(serverConnection.MitmEncryptClientChannel);
            } else {
                // If we're chaining, forward the CONNECT request
                if (hasUpstreamChainedProxy()) {
                    connectionFlow.then(
                            serverConnection.HTTPCONNECTWithChainedProxy);
                }

                connectionFlow.then(serverConnection.StartTunneling)
                        .then(clientConnection.RespondCONNECTSuccessful)
                        .then(clientConnection.StartTunneling);
            }
        }
    }

    /**
     * Opens the socket connection.
     */
    private ConnectionFlowStep ConnectChannel = new ConnectionFlowStep(this,
            CONNECTING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

        @Override
        protected Future<?> execute() {
            Bootstrap cb = new Bootstrap().group(proxyServer
                    .getProxyToServerWorkerFor(transportProtocol));

            switch (transportProtocol) {
            case TCP:
                LOG.debug("Connecting to server with TCP");
                cb.channelFactory(new ChannelFactory<Channel>() {
                    @Override
                    public Channel newChannel() {
                        return new NioSocketChannel();
                    }
                });
                break;
            case UDT:
                LOG.debug("Connecting to server with UDT");
                cb.channelFactory(NioUdtProvider.BYTE_CONNECTOR)
                        .option(ChannelOption.SO_REUSEADDR, true);
                break;
            default:
                throw new UnknownTransportProtocolError(transportProtocol);
            }

            cb.handler(new ChannelInitializer<Channel>() {
                protected void initChannel(Channel ch) throws Exception {
                    initChannelPipeline(ch.pipeline(), initialRequest);
                };
            });
            cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, proxyServer.getConnectTimeout());

            if (localAddress != null) {
                return cb.connect(remoteAddress, localAddress);
            } else {
                return cb.connect(remoteAddress);
            }
        }
    };

    /**
     * Writes the HTTP CONNECT to the server and waits for a 200 response.
     */
    private ConnectionFlowStep HTTPCONNECTWithChainedProxy = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {
        protected Future<?> execute() {
            LOG.debug("Handling CONNECT request through Chained Proxy");
            chainedProxy.filterRequest(initialRequest);
            return writeToChannel(initialRequest);
        }

        void onSuccess(ConnectionFlow flow) {
            // Do nothing, since we want to wait for the CONNECT response to
            // come back
        }

        void read(ConnectionFlow flow, Object msg) {
            // Here we're handling the response from a chained proxy to our
            // earlier CONNECT request
            boolean connectOk = false;
            if (msg instanceof HttpResponse) {
                HttpResponse httpResponse = (HttpResponse) msg;
                int statusCode = httpResponse.getStatus().code();
                if (statusCode >= 200 && statusCode <= 299) {
                    connectOk = true;
                }
            }
            if (connectOk) {
                flow.advance();
            } else {
                flow.fail();
            }
        }
    };

    /**
     * <p>
     * Encrypts the client channel based on our server {@link SSLSession}.
     * </p>
     * 
     * <p>
     * This does not wait for the handshake to finish so that we can go on and
     * respond to the CONNECT request.
     * </p>
     */
    private ConnectionFlowStep MitmEncryptClientChannel = new ConnectionFlowStep(
            this, HANDSHAKING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        @Override
        protected Future<?> execute() {
            return clientConnection
                    .encrypt(proxyServer.getMitmManager()
                            .clientSslEngineFor(sslEngine.getSession()), false)
                    .addListener(
                            new GenericFutureListener<Future<? super Channel>>() {
                                @Override
                                public void operationComplete(
                                        Future<? super Channel> future)
                                        throws Exception {
                                    if (future.isSuccess()) {
                                        clientConnection.setMitming(true);
                                    }
                                }
                            });
        }
    };

    /**
     * <p>
     * Called to let us know that connection failed.
     * </p>
     * 
     * <p>
     * Try connecting to a new address, using a new set of connection
     * parameters.
     * </p>
     * 
     * @param cause
     *            the reason that our attempt to connect failed (can be null)
     * @return true if we are trying to fall back to another connection
     */
    protected boolean connectionFailed(Throwable cause)
            throws UnknownHostException {
        if (this.chainedProxy != null) {
            // Let the ChainedProxy know that we were unable to connect
            try {
                this.chainedProxy.connectionFailed(cause);
            } catch (Exception e) {
                LOG.error("Unable to record connectionFailed", e);
            }
        }
        this.chainedProxy = this.availableChainedProxies.poll();
        if (chainedProxy != null) {
            this.setupConnectionParameters();
            this.connectAndWrite(initialRequest);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Set up our connection parameters based on server address and chained
     * proxies.
     * 
     * @throws UnknownHostException
     */
    private void setupConnectionParameters() throws UnknownHostException {
        if (chainedProxy != null
                && chainedProxy != ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION) {
            this.transportProtocol = chainedProxy.getTransportProtocol();
            this.remoteAddress = chainedProxy.getChainedProxyAddress();
            this.localAddress = chainedProxy.getLocalAddress();
        } else {
            this.transportProtocol = TransportProtocol.TCP;
            this.remoteAddress = addressFor(serverHostAndPort, proxyServer);
            this.localAddress = null;
        }
    }

    /**
     * Initialize our {@link ChannelPipeline}.
     * 
     * @param pipeline
     * @param httpRequest
     */
    private void initChannelPipeline(ChannelPipeline pipeline,
            HttpRequest httpRequest) {
        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("decoder", new HeadAwareHttpResponseDecoder(
                8192,
                8192 * 2,
                8192 * 2));
        pipeline.addLast("responseReadMonitor", responseReadMonitor);

        if (!ProxyUtils.isCONNECT(httpRequest)) {
            // Enable aggregation for filtering if necessary
            int numberOfBytesToBuffer = proxyServer.getFiltersSource()
                    .getMaximumResponseBufferSizeInBytes();
            if (numberOfBytesToBuffer > 0) {
                aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
            }
        }

        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);
        pipeline.addLast("encoder", new HttpRequestEncoder());
        pipeline.addLast("requestWrittenMonitor", requestWrittenMonitor);

        // Set idle timeout
        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, proxyServer
                        .getIdleConnectionTimeout()));

        pipeline.addLast("handler", this);
    }

    /**
     * <p>
     * Do all the stuff that needs to be done after our {@link ConnectionFlow}
     * has succeeded.
     * </p>
     * 
     * @param shouldForwardInitialRequest
     *            whether or not we should forward the initial HttpRequest to
     *            the server after the connection has been established.
     */
    void connectionSucceeded(boolean shouldForwardInitialRequest) {
        become(AWAITING_INITIAL);
        if (this.chainedProxy != null) {
            // Notify the ChainedProxy that we successfully connected
            try {
                this.chainedProxy.connectionSucceeded();
            } catch (Exception e) {
                LOG.error("Unable to record connectionSucceeded", e);
            }
        }
        clientConnection.serverConnectionSucceeded(this,
                shouldForwardInitialRequest);

        if (shouldForwardInitialRequest) {
            LOG.debug("Writing initial request: {}", initialRequest);
            write(initialRequest);
        } else {
            LOG.debug("Dropping initial request: {}", initialRequest);
        }
    }

    /**
     * Build an {@link InetSocketAddress} for the given hostAndPort.
     * 
     * @param hostAndPort
     * @param proxyServer
     *            the current {@link ProxyServer}
     * @return
     * @throws UnknownHostException
     *             if hostAndPort could not be resolved
     */
    private static InetSocketAddress addressFor(String hostAndPort,
            DefaultHttpProxyServer proxyServer)
            throws UnknownHostException {
        String host;
        int port;
        if (hostAndPort.contains(":")) {
            host = StringUtils.substringBefore(hostAndPort, ":");
            String portString = StringUtils.substringAfter(hostAndPort,
                    ":");
            port = Integer.parseInt(portString);
        } else {
            host = hostAndPort;
            port = 80;
        }

        if (proxyServer.isUseDnsSec()) {
            return VerifiedAddressFactory.newInetSocketAddress(host, port,
                    proxyServer.isUseDnsSec());
        } else {
            InetAddress ia = InetAddress.getByName(host);
            String address = ia.getHostAddress();
            return new InetSocketAddress(address, port);
        }
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     * 
     * We track statistics on bytes, requests and responses by adding handlers
     * at the appropriate parts of the pipeline (see initChannelPipeline()).
     **************************************************************************/
    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesReceivedFromServer(flowContext, numberOfBytes);
            }
        }
    };

    private ResponseReadMonitor responseReadMonitor = new ResponseReadMonitor() {
        @Override
        protected void responseRead(HttpResponse httpResponse) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.responseReceivedFromServer(flowContext, httpResponse);
            }
        }
    };

    private BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesSentToServer(flowContext, numberOfBytes);
            }
        }
    };

    private RequestWrittenMonitor requestWrittenMonitor = new RequestWrittenMonitor() {
        @Override
        protected void requestWritten(HttpRequest httpRequest) {
            FullFlowContext flowContext = new FullFlowContext(clientConnection,
                    ProxyToServerConnection.this);
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.requestSentToServer(flowContext, httpRequest);
            }
        }
    };
}
