/**
 * Created by Luzius Meisser on 2021-03-10
 * Copyright: Aktionariat AG, Zurich
 * Contact: luzius@aktionariat.com
 *
 * Feel free to reuse this code under the MIT License
 * https://opensource.org/licenses/MIT
 */
package polyset;;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketServerFactory;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.DefaultWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BridgeServer extends WebSocketServer {

	private static final Logger LOG = LoggerFactory.getLogger(BridgeServer.class);

	private static final String NAME = "WalletConnect Bridge Java Edition 0.2";

	private HashMap<String, Bridge> bridges;

	private boolean startAttemptCompleted;
	private BindException error;

	public BridgeServer(int port) throws UnknownHostException {
		super(new InetSocketAddress(port));
		super.setReuseAddr(true);
		this.startAttemptCompleted = false;
		this.bridges = new HashMap<String, Bridge>();
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		LOG.info(conn.getRemoteSocketAddress() + " opened a connection to us");
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		LOG.info(conn.getRemoteSocketAddress() + " closed the connection, status " + code);
	}

	@Override
	public void onWebsocketPong(WebSocket conn, Framedata f) {
		super.onWebsocketPong(conn, f);
		@SuppressWarnings("unchecked")
		Set<Bridge> bridges = (Set<Bridge>) conn.getAttachment();
		if (bridges != null) {
			for (Bridge bridge: bridges) {
				bridge.ack();
			}
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		try {
			LOG.info("Received " + message + " from " + conn.getRemoteSocketAddress());
			if (message.contentEquals("ping")) {
				conn.send("pong"); // custom ping pong for monitoring, not related to ws protocol level ping
			} else {
//				implicitAck(conn);
				WalletConnectMessage msg = WalletConnectMessage.parse(conn, message);
				Bridge bridge = obtainBridge(msg.topic);
				switch (msg.type) {
				case "pub":
					bridge.push(conn, msg);
					break;
				case "sub":
					bridge.sub(conn, msg);
					break;
				case "ack":
					bridge.ack();
					break;
				}
			}
		} catch (IOException e) {
			LOG.info("Error: " + e);
			conn.close();
		}
	}

	private synchronized Bridge obtainBridge(String topic) {
		Bridge b = bridges.get(topic);
		if (b == null) {
			b = new Bridge();
			bridges.put(topic, b);
		}
		return b;
	}

	@Override
	public void onMessage(WebSocket conn, ByteBuffer message) {
		int pos = message.arrayOffset() + message.position();
		int len = message.remaining();
		onMessage(conn, new String(message.array(), pos, len));
	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		if (ex instanceof BindException) {
			synchronized (this) {
				this.startAttemptCompleted = true;
				this.error = (BindException) ex;
				this.notifyAll();
			}
		} else {
			ex.printStackTrace();
			if (conn != null) {
				conn.close(1011, ex.getMessage());
			}
		}
	}

	@Override
	public synchronized void onStart() {
		this.startAttemptCompleted = true;
		this.notifyAll();
	}

	public synchronized void waitForStart() throws BindException, InterruptedException {
		while (!startAttemptCompleted) {
			this.wait();
		}
		if (this.error != null) {
			throw error;
		}
	}

	public synchronized void purgeInactiveConnections() {
		long t0 = System.nanoTime();
		LOG.info("Currently, there are " + this.bridges.size() + " bridges.");
		LOG.info("Purging inactive connections...");
		int count = 0;
		Iterator<Bridge> bridges = this.bridges.values().iterator();
		while (bridges.hasNext()) {
			Bridge bridge = bridges.next();
			if (bridge.isInactive()) {
				bridges.remove();
				count++;
			}
		}
		LOG.info("... purge of " + count + " completed in " + (System.nanoTime() - t0) / 1000 / 1000 + "ms.");
	}

	public static BridgeServer start(WebSocketServerFactory socketFactory, int port) throws UnknownHostException, InterruptedException, BindException {
		BridgeServer server = new BridgeServer(port);
		server.setWebSocketFactory(socketFactory);
		server.start();
		server.waitForStart();
		return server;
	}

	public static void main(String[] argStrings)
			throws InterruptedException, IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException, UnrecoverableKeyException, KeyManagementException {
		Arguments args = new Arguments(argStrings);
		WebSocketServerFactory socketFactory = new DefaultWebSocketServerFactory();
		if (args.hasArgument("-cert")) {
			String keystoreFile = args.get("-cert");
			assert new File(keystoreFile).exists();
			char[] passphrase = args.get("-passphrase").toCharArray();
			KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
			try (FileInputStream fis = new FileInputStream(new File(keystoreFile))) {
				keystore.load(fis, passphrase);
				KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				keyManagerFactory.init(keystore, passphrase);
				TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				trustManagerFactory.init(keystore);
				SSLContext ctx = SSLContext.getInstance("TLS");
				ctx.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
				socketFactory = new DefaultSSLWebSocketServerFactory(ctx);
			}
		}
		int port = args.get("-port", 8080);
		BridgeServer s = start(socketFactory, port);
		LOG.info(NAME + " started on port: " + s.getPort());

		try {
			while (true) {
				Thread.sleep(120 * 1000);
				s.purgeInactiveConnections();
			}
		} finally {
			s.stop();
		}
	}

}
