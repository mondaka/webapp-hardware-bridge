package tigerworkshop.webapphardwarebridge;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServerInterface;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServiceInterface;
import tigerworkshop.webapphardwarebridge.services.SettingService;
import tigerworkshop.webapphardwarebridge.utils.ConnectionAttachment;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class BridgeWebSocketServer extends WebSocketServer implements WebSocketServerInterface {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private HashMap<String, ArrayList<WebSocket>> socketChannelSubscriptions = new HashMap<>();
    private HashMap<String, ArrayList<WebSocketServiceInterface>> serviceChannelSubscriptions = new HashMap<>();

    private SettingService settingService = SettingService.getInstance();

    public BridgeWebSocketServer(String address, int port) {
        super(new InetSocketAddress(address, port));
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        try {
            String descriptor = handshake.getResourceDescriptor();

            URI uri = new URI(descriptor);
            String channel = uri.getPath();
            List<NameValuePair> params = URLEncodedUtils.parse(uri, Charset.forName("UTF-8"));
            String token = getToken(params);

            if (settingService.getTokenAuthenticationEnabled() && (token == null || !token.equals(settingService.getToken()))) {
                connection.close(CloseFrame.REFUSE, "Token Mismatch");
                return;
            }

            connection.setAttachment(new ConnectionAttachment(channel, params, token));
            addSocketToChannel(channel, connection);

            logger.info(connection.getRemoteSocketAddress().toString() + " connected to " + channel);
        } catch (URISyntaxException e) {
            logger.error(connection.getRemoteSocketAddress().toString() + " error", e);
            connection.close();
        }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        if (connection.getAttachment() != null) {
            removeSocketFromChannel(((ConnectionAttachment) connection.getAttachment()).getChannel(), connection);
        }
        logger.debug(connection.getRemoteSocketAddress().toString() + " disconnected, reason: " + reason);
    }

    /*
     * Server to Service communication
     */

    @Override
    public void onMessage(WebSocket connection, String message) {
        logger.trace("onMessage: " + connection.getRemoteSocketAddress() + ": " + message);

        String channel = ((ConnectionAttachment) connection.getAttachment()).getChannel();

        ArrayList<WebSocketServiceInterface> services = getServiceListForChannel(channel);
        for (WebSocketServiceInterface service : services) {
            logger.trace("Attempt to send: " + message + " to channel: " + channel);
            service.onDataReceived(message);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.error(ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        logger.info("BridgeWebSocketServer started");
        setConnectionLostTimeout(30);
    }

    /*
     * Service to Server listener
     */
    @Override
    public void onDataReceived(String channel, String message) {
        logger.trace("Received data from channel: " + channel + ", Data: " + message);

        ArrayList<WebSocket> connectionList = socketChannelSubscriptions.get(channel);

        if (connectionList == null) {
            logger.trace("connectionList is null, ignoring the message");
            return;
        }

        for (Iterator<WebSocket> it = connectionList.iterator(); it.hasNext(); ) {
            WebSocket conn = it.next();
            try {
                conn.send(message);
            } catch (WebsocketNotConnectedException e) {
                logger.warn("WebsocketNotConnectedException: Removing connection from list");
                it.remove();
            }
        }
    }

    @Override
    public void subscribe(WebSocketServiceInterface service, String channel) {
        addServiceToChannel(channel, service);
    }

    @Override
    public void unsubscribe(WebSocketServiceInterface service, String channel) {
        removeServiceFromChannel(channel, service);
    }

    private String getToken(List<NameValuePair> params) {
        for (NameValuePair pair : params) {
            if (pair.getName().equals("access_token")) {
                return pair.getValue();
            }
        }
        return null;
    }

    private ArrayList<WebSocket> getSocketListForChannel(String channel) {
        ArrayList<WebSocket> socketList = socketChannelSubscriptions.get(channel);
        if (socketList == null) {
            return new ArrayList<>();
        }
        return socketList;
    }

    private void addSocketToChannel(String channel, WebSocket socket) {
        ArrayList<WebSocket> connectionList = getSocketListForChannel(channel);
        connectionList.add(socket);
        socketChannelSubscriptions.put(channel, connectionList);
    }

    private void removeSocketFromChannel(String channel, WebSocket socket) {
        ArrayList<WebSocket> connectionList = getSocketListForChannel(channel);
        connectionList.remove(socket);
        socketChannelSubscriptions.put(channel, connectionList);
    }

    private ArrayList<WebSocketServiceInterface> getServiceListForChannel(String channel) {
        ArrayList<WebSocketServiceInterface> socketList = serviceChannelSubscriptions.get(channel);
        if (socketList == null) {
            return new ArrayList<>();
        }
        return socketList;
    }

    private void addServiceToChannel(String channel, WebSocketServiceInterface socket) {
        ArrayList<WebSocketServiceInterface> serviceList = getServiceListForChannel(channel);
        serviceList.add(socket);
        serviceChannelSubscriptions.put(channel, serviceList);
    }

    private void removeServiceFromChannel(String channel, WebSocketServiceInterface socket) {
        ArrayList<WebSocketServiceInterface> serviceList = getServiceListForChannel(channel);
        serviceList.remove(socket);
        serviceChannelSubscriptions.put(channel, serviceList);
    }
}
