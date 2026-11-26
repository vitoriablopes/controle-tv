package br.vitoria.controletv.core;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Conexão de controle (porta 6466). A TV manda um "ping" a cada ~5 s;
 * se passar {@link #READ_TIMEOUT_MS} sem nada, a conexão é dada como perdida.
 * As respostas obrigatórias (configuração, ativação, ping) são enviadas aqui
 * mesmo, na thread de leitura.
 */
public final class RemoteClient implements Closeable {
    public static final int DEFAULT_PORT = 6466;
    public static final int READ_TIMEOUT_MS = 16_000;

    public static final int DIRECTION_START_LONG = 1;
    public static final int DIRECTION_END_LONG = 2;
    public static final int DIRECTION_SHORT = 3;

    // recursos que pedimos: PING | KEY | POWER | VOLUME | APP_LINK
    private static final int FEATURES = 1 | 2 | 32 | 64 | 512;

    // campos de RemoteMessage
    private static final int F_CONFIGURE = 1, F_SET_ACTIVE = 2, F_PING_REQUEST = 8, F_PING_RESPONSE = 9,
            F_KEY_INJECT = 10, F_IME_KEY_INJECT = 20, F_START = 40, F_SET_VOLUME = 50, F_APP_LINK = 90;

    public interface Listener {
        /** A TV aceitou a conexão e já dá para mandar comandos. */
        void onReady();
        void onPowerChanged(boolean on);
        void onVolumeChanged(int level, int max, boolean muted);
        void onCurrentApp(String packageName);
        /**
         * A conexão terminou. {@code authProblem} indica que a TV recusou o
         * certificado (provavelmente esqueceu o pareamento).
         */
        void onClosed(Exception cause, boolean authProblem);
    }

    /** A TV não aceitou o nosso certificado: precisa parear de novo. */
    public static final class AuthException extends IOException {
        public AuthException(Throwable cause) { super("A TV pediu um novo pareamento", cause); }
    }

    private final ClientIdentity identity;
    private final String model;
    private final String vendor;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean ready = false;
    private volatile boolean receivedAny = false;
    private volatile int activeFeatures = FEATURES;
    private SSLSocket socket;
    private OutputStream out;

    public RemoteClient(ClientIdentity identity, String model, String vendor, Listener listener) {
        this.identity = identity;
        this.model = model;
        this.vendor = vendor;
        this.listener = listener;
    }

    /**
     * Abre a conexão (bloqueia até o TLS terminar) e inicia a thread de leitura.
     * Falhas depois disso chegam por {@link Listener#onClosed}.
     */
    public void connect(String host, int port, int connectTimeoutMs) throws IOException, GeneralSecurityException {
        SSLSocketFactory factory = identity.socketFactory();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            socket = (SSLSocket) factory.createSocket(raw, host, port, true);
            socket.setSoTimeout(READ_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(raw);
            throw e;
        }
        try {
            socket.startHandshake();
            out = socket.getOutputStream();
        } catch (IOException e) {
            // A TCP conectou mas o TLS foi recusado: a TV não reconhece o nosso certificado.
            closeQuietly(socket);
            throw new AuthException(e);
        }
        final InputStream in = new BufferedInputStream(socket.getInputStream());
        Thread t = new Thread(new Runnable() {
            @Override public void run() { readLoop(in); }
        }, "tv-remote-reader");
        t.setDaemon(true);
        t.start();
    }

    public boolean isReady() {
        return ready && !closed.get();
    }

    private void readLoop(InputStream in) {
        Exception cause = null;
        boolean auth = false;
        try {
            while (!closed.get()) {
                byte[] frame = Proto.readFrame(in);
                receivedAny = true;
                handle(Proto.Message.parse(frame));
            }
        } catch (java.net.SocketTimeoutException e) {
            cause = e;
        } catch (IOException e) {
            cause = e;
            // Com TLS 1.3, a recusa do certificado só aparece na primeira leitura:
            // a TV fecha a conexão antes de mandar qualquer coisa.
            auth = !receivedAny;
        } catch (Exception e) {
            cause = e;
        }
        finish(cause, auth);
    }

    private void handle(Proto.Message msg) throws IOException {
        if (msg.has(F_CONFIGURE)) {
            Proto.Message cfg = msg.getMessage(F_CONFIGURE);
            int supported = cfg.getInt(1, FEATURES);
            int active = FEATURES & supported;
            if ((active & 2) == 0) active = FEATURES; // TV não informou direito; pede o padrão
            activeFeatures = active;
            send(new Proto.Writer().message(F_CONFIGURE, new Proto.Writer()
                    .varint(1, active)
                    .message(2, new Proto.Writer()
                            .string(1, model)
                            .string(2, vendor)
                            .varint(3, 1)
                            .string(4, "1")
                            .string(5, "atvremote")
                            .string(6, "1.0.0"))));
        } else if (msg.has(F_SET_ACTIVE)) {
            send(new Proto.Writer().message(F_SET_ACTIVE, new Proto.Writer().varint(1, activeFeatures)));
        } else if (msg.has(F_PING_REQUEST)) {
            int val = msg.getMessage(F_PING_REQUEST).getInt(1, 0);
            send(new Proto.Writer().message(F_PING_RESPONSE, new Proto.Writer().varint(1, val)));
        } else if (msg.has(F_START)) {
            boolean on = msg.getMessage(F_START).getBool(1);
            if (!ready) {
                ready = true;
                listener.onReady();
            }
            listener.onPowerChanged(on);
        } else if (msg.has(F_SET_VOLUME)) {
            Proto.Message v = msg.getMessage(F_SET_VOLUME);
            listener.onVolumeChanged(v.getInt(7, 0), v.getInt(6, 0), v.getBool(8));
        } else if (msg.has(F_IME_KEY_INJECT)) {
            Proto.Message appInfo = msg.getMessage(F_IME_KEY_INJECT).getMessage(1);
            if (appInfo != null) {
                String pkg = appInfo.getString(12);
                if (pkg != null && !pkg.isEmpty()) listener.onCurrentApp(pkg);
            }
        }
        // demais mensagens (erros, teclado, voz) são ignoradas
    }

    /** Aperta uma tecla do controle (códigos de tecla do Android). */
    public void sendKey(int keyCode, int direction) throws IOException {
        send(new Proto.Writer().message(F_KEY_INJECT, new Proto.Writer()
                .varint(1, keyCode)
                .varint(2, direction)));
    }

    /** Abre um app ou link na TV, ex.: "market://launch?id=com.netflix.ninja". */
    public void launch(String appLink) throws IOException {
        send(new Proto.Writer().message(F_APP_LINK, new Proto.Writer().string(1, appLink)));
    }

    private synchronized void send(Proto.Writer msg) throws IOException {
        if (closed.get() || out == null) throw new IOException("desconectado");
        try {
            Proto.writeFrame(out, msg.toByteArray());
        } catch (IOException e) {
            finish(e, false);
            throw e;
        }
    }

    private void finish(Exception cause, boolean authProblem) {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(socket);
            listener.onClosed(cause, authProblem);
        }
    }

    private static void closeQuietly(Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        finish(null, false);
    }
}
