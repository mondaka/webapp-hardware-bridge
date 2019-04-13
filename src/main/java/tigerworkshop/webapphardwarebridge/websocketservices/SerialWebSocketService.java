package tigerworkshop.webapphardwarebridge.websocketservices;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServerInterface;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServiceInterface;
import tigerworkshop.webapphardwarebridge.utils.ThreadUtil;

import java.nio.charset.StandardCharsets;

public class SerialWebSocketService implements WebSocketServiceInterface {
    private final String portName;
    private final String mappingKey;
    private final SerialPort serialPort;
    private final Thread writeThread;
    private final Thread readThread;
    private byte[] writeBuffer = {};

    private Logger logger = LoggerFactory.getLogger(getClass());
    private WebSocketServerInterface server = null;

    public SerialWebSocketService(String portName, String mappingKey) {
        logger.info("Starting SerialWebSocketService on " + portName);

        this.portName = portName;
        this.mappingKey = mappingKey;
        this.serialPort = SerialPort.getCommPort(portName);

        this.readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                logger.trace("Serial Read Thread started for " + portName);

                while (true) {
                    try {
                        if (serialPort.isOpen()) {
                            if (serialPort.bytesAvailable() == 0) {
                                // No data coming from COM portName
                                ThreadUtil.silentSleep(10);
                                continue;
                            } else if (serialPort.bytesAvailable() == -1) {
                                // Check if portName closed unexpected (e.g. Unplugged)
                                serialPort.closePort();
                                logger.warn("Serial unplugged!");
                                continue;
                            }

                            byte[] receivedData = new byte[1];
                            serialPort.readBytes(receivedData, 1);

                            if (server != null) {
                                server.onDataReceived(getChannel(), new String(receivedData, StandardCharsets.UTF_8));
                            }
                        } else {
                            logger.trace("Trying to connect the serial @ " + serialPort.getSystemPortName());
                            serialPort.openPort();
                        }
                    } catch (Exception e) {
                        logger.warn("Error: " + e.getMessage());
                        ThreadUtil.silentSleep(1000);
                    }
                }
            }
        });

        this.writeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                logger.trace("Serial Write Thread started for " + portName);

                while (true) {
                    if (serialPort.isOpen()) {
                        try {
                            if (writeBuffer.length > 0) {
                                serialPort.writeBytes(writeBuffer, writeBuffer.length);
                                writeBuffer = new byte[]{};
                            }
                            ThreadUtil.silentSleep(10);
                        } catch (Exception e) {
                            logger.warn("Error: " + e.getMessage());
                            ThreadUtil.silentSleep(1000);
                        }
                    }
                }
            }
        });

        this.readThread.start();
        this.writeThread.start();
    }

    @Override
    public void onDataReceived(String message) {
        send(message.getBytes());
    }

    @Override
    public void setServer(WebSocketServerInterface server) {
        this.server = server;
        server.subscribe(this, getChannel());
    }

    private void send(byte[] message) {
        writeBuffer = message;
    }

    private String getChannel() {
        return "/serial/" + mappingKey;
    }
}
