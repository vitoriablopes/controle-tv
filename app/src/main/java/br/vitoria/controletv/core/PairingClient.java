package br.vitoria.controletv.core;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pareamento (porta 6467). Fluxo:
 * start() → a TV mostra um código de 6 caracteres → finish(código).
 * Todas as chamadas bloqueiam: use fora da thread principal.
 */
public final class PairingClient implements Closeable {
    public static final int DEFAULT_PORT = 6467;

    private static final int STATUS_OK = 200;
    private static final int STATUS_BAD_SECRET = 402;
    private static final int ROLE_INPUT = 1;
    private static final int ENCODING_HEX = 3;
    private static final int TIMEOUT_MS = 10_000;

    // campos de OuterMessage
    private static final int F_VERSION = 1, F_STATUS = 2, F_REQUEST = 10, F_REQUEST_ACK = 11,
            F_OPTIONS = 20, F_CONFIGURATION = 30, F_CONFIGURATION_ACK = 31, F_SECRET = 40, F_SECRET_ACK = 41;

    /** O código digitado não confere com o que a TV mostrou. */
    public static final class WrongCodeException extends Exception {
        public WrongCodeException(String msg) { super(msg); }
    }

    private final ClientIdentity identity;
    private final String clientName;
    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;

    public PairingClient(ClientIdentity identity, String clientName) {
        this.identity = identity;
        this.clientName = clientName;
    }

    /** Conecta e pede o pareamento. Quando retorna, o código já está na tela da TV. */
    public void start(String host, int port) throws IOException, GeneralSecurityException {
        SSLSocketFactory factory = identity.socketFactory();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), 5_000);
            socket = (SSLSocket) factory.createSocket(raw, host, port, true);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.startHandshake();
        } catch (IOException e) {
            closeQuietly(raw);
            throw e;
        }
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(new Proto.Writer().message(F_REQUEST, new Proto.Writer()
                .string(1, "atvremote")
                .string(2, clientName)));

        while (true) {
            Proto.Message msg = receive();
            if (msg.has(F_REQUEST_ACK)) {
                send(new Proto.Writer().message(F_OPTIONS, new Proto.Writer()
                        .message(1, hexEncoding())
                        .varint(3, ROLE_INPUT)));
            } else if (msg.has(F_OPTIONS)) {
                send(new Proto.Writer().message(F_CONFIGURATION, new Proto.Writer()
                        .message(1, hexEncoding())
                        .varint(2, ROLE_INPUT)));
            } else if (msg.has(F_CONFIGURATION_ACK)) {
                return;
            } else {
                throw new IOException("Resposta inesperada da TV durante o pareamento");
            }
        }
    }

    /**
     * Envia o código mostrado na TV. Se o código estiver errado lança
     * WrongCodeException e a conexão continua aberta para tentar de novo.
     */
    public void finish(String rawCode) throws IOException, WrongCodeException {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[0-9A-F]{6}")) {
            throw new WrongCodeException("O código tem 6 caracteres (números e letras de A a F).");
        }
        byte[] secret = computeSecret(identity.publicKey(), serverKey(), code);
        if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new WrongCodeException("Código incorreto. Confira o que aparece na TV.");
        }
        send(new Proto.Writer().message(F_SECRET, new Proto.Writer().bytes(1, secret)));
        Proto.Message msg;
        try {
            msg = receive();
        } catch (StatusException e) {
            if (e.status == STATUS_BAD_SECRET) throw new WrongCodeException("A TV recusou o código.");
            throw e;
        }
        if (!msg.has(F_SECRET_ACK)) throw new IOException("Resposta inesperada da TV ao confirmar o código");
    }

    /** Cálculo do segredo do protocolo: SHA-256 das chaves + parte final do código. */
    public static byte[] computeSecret(RSAPublicKey client, RSAPublicKey server, String code) {
        try {
            MessageDigest h = MessageDigest.getInstance("SHA-256");
            h.update(unsigned(client.getModulus()));
            h.update(unsigned(client.getPublicExponent()));
            h.update(unsigned(server.getModulus()));
            h.update(unsigned(server.getPublicExponent()));
            h.update(hexToBytes(code.substring(2)));
            return h.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private RSAPublicKey serverKey() throws IOException {
        Certificate[] chain = socket.getSession().getPeerCertificates();
        if (chain.length == 0 || !(chain[0].getPublicKey() instanceof RSAPublicKey)) {
            throw new IOException("Certificado da TV não reconhecido");
        }
        return (RSAPublicKey) chain[0].getPublicKey();
    }

    private static Proto.Writer hexEncoding() {
        return new Proto.Writer().varint(1, ENCODING_HEX).varint(2, 6);
    }

    private void send(Proto.Writer body) throws IOException {
        Proto.Writer outer = new Proto.Writer().varint(F_VERSION, 2).varint(F_STATUS, STATUS_OK);
        byte[] b = body.toByteArray();
        byte[] o = outer.toByteArray();
        byte[] all = new byte[o.length + b.length];
        System.arraycopy(o, 0, all, 0, o.length);
        System.arraycopy(b, 0, all, o.length, b.length);
        Proto.writeFrame(out, all);
    }

    private static final class StatusException extends IOException {
        final int status;
        StatusException(int status) {
            super("A TV respondeu com erro " + status);
            this.status = status;
        }
    }

    private Proto.Message receive() throws IOException {
        Proto.Message msg = Proto.Message.parse(Proto.readFrame(in));
        int status = msg.getInt(F_STATUS, 0);
        if (status != STATUS_OK) throw new StatusException(status);
        return msg;
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static void closeQuietly(Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }
}
