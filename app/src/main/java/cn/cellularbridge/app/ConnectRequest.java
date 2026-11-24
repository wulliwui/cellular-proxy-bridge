package cn.cellularbridge.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class ConnectRequest {
    private static final int HEADER_LIMIT = 16 * 1024;
    private static final Pattern FIELD_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern DNS_NAME = Pattern.compile(
            "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.?");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    final String host;
    final int port;

    private ConnectRequest(String host, int port) {
        this.host = host;
        this.port = port;
    }

    static ConnectRequest read(Socket client, BufferedInputStream input,
                               BridgeConfig config, int timeoutMillis) throws IOException, Rejected {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        client.setSoTimeout(timeoutMillis);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int terminator = 0;
        while (header.size() < HEADER_LIMIT) {
            int remaining = remainingMillis(deadline);
            if (input.available() == 0) {
                client.setSoTimeout(remaining);
            }
            int value = input.read();
            if (value == -1) {
                throw new Rejected(400);
            }
            header.write(value);
            terminator = (terminator << 8) | value;
            if (terminator == 0x0d0a0d0a) {
                return parse(header.toString(StandardCharsets.ISO_8859_1), config);
            }
        }
        throw new Rejected(431);
    }

    static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException();
        }
        return (int) Math.max(1L, remaining / 1_000_000L);
    }

    private static ConnectRequest parse(String header, BridgeConfig config) throws Rejected {
        String[] lines = header.substring(0, header.length() - 4).split("\\r\\n", -1);
        String[] request = lines[0].split(" ", -1);
        if (request.length != 3 || !FIELD_NAME.matcher(request[0]).matches()) {
            throw new Rejected(400);
        }
        if (!request[2].equals("HTTP/1.1")) {
            throw new Rejected(505);
        }
        if (!request[0].equals("CONNECT")) {
            throw new Rejected(405);
        }

        String authorization = null;
        boolean hasContentLength = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf(':');
            if (separator < 1 || !FIELD_NAME.matcher(line.substring(0, separator)).matches()) {
                throw new Rejected(400);
            }
            String field = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (value.chars().anyMatch(ch -> (ch < 32 && ch != '\t') || ch == 127)) {
                throw new Rejected(400);
            }
            if (field.equalsIgnoreCase("Proxy-Authorization")) {
                if (authorization != null) {
                    throw new Rejected(400);
                }
                authorization = value.trim();
            }
            if (field.equalsIgnoreCase("Transfer-Encoding")) {
                throw new Rejected(400);
            }
            if (field.equalsIgnoreCase("Content-Length")) {
                if (hasContentLength || !value.trim().equals("0")) {
                    throw new Rejected(400);
                }
                hasContentLength = true;
            }
        }
        if (!config.authenticates(authorization)) {
            throw new Rejected(407);
        }

        String authority = request[1];
        int portSeparator = authority.lastIndexOf(':');
        if (portSeparator < 1) {
            throw new Rejected(400);
        }
        String host = authority.substring(0, portSeparator);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
            if (host.length() > 45 || !host.contains(":") || !IPV6_LITERAL.matcher(host).matches()) {
                throw new Rejected(400);
            }
        } else if (host.length() > 254 || !DNS_NAME.matcher(host).matches()) {
            throw new Rejected(400);
        }

        int port;
        try {
            port = BridgeConfig.parsePort(authority.substring(portSeparator + 1), 1);
        } catch (IllegalArgumentException error) {
            throw new Rejected(400);
        }
        if (!config.targetPorts.contains(port)) {
            throw new Rejected(403);
        }
        return new ConnectRequest(host, port);
    }

    static final class Rejected extends Exception {
        final int status;

        Rejected(int status) {
            this.status = status;
        }
    }
}
