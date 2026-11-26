package br.vitoria.controletv.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protobuf mínimo: só o necessário para falar com a TV
 * (varints, strings, bytes e mensagens aninhadas), mais o enquadramento
 * "tamanho em varint + mensagem" usado nas duas conexões.
 */
public final class Proto {
    private Proto() {}

    /** Limite de segurança para uma mensagem vinda da TV. */
    static final int MAX_MESSAGE = 1 << 20;

    // ------------------------------------------------------------------ escrita

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int field, long value) {
            tag(field, 0);
            writeVarint(out, value);
            return this;
        }

        public Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        public Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public Writer bytes(int field, byte[] value) {
            tag(field, 2);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer message(int field, Writer value) {
            return bytes(field, value.toByteArray());
        }

        private void tag(int field, int wireType) {
            writeVarint(out, ((long) field << 3) | wireType);
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    // ------------------------------------------------------------------ leitura

    public static final class Message {
        private final Map<Integer, List<Object>> fields;

        private Message(Map<Integer, List<Object>> fields) {
            this.fields = fields;
        }

        public boolean has(int field) {
            return fields.containsKey(field);
        }

        private Object last(int field) {
            List<Object> list = fields.get(field);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
        }

        public long getLong(int field, long def) {
            Object o = last(field);
            return o instanceof Long ? (Long) o : def;
        }

        public int getInt(int field, int def) {
            return (int) getLong(field, def);
        }

        public boolean getBool(int field) {
            return getLong(field, 0) != 0;
        }

        public byte[] getBytes(int field) {
            Object o = last(field);
            return o instanceof byte[] ? (byte[]) o : null;
        }

        public String getString(int field) {
            byte[] b = getBytes(field);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        /** Mensagem aninhada; vazia se o campo existir sem conteúdo. */
        public Message getMessage(int field) throws IOException {
            byte[] b = getBytes(field);
            return b == null ? null : parse(b);
        }

        public static Message parse(byte[] data) throws IOException {
            Map<Integer, List<Object>> fields = new HashMap<>();
            int[] pos = {0};
            while (pos[0] < data.length) {
                long key = readVarint(data, pos);
                int field = (int) (key >>> 3);
                int wire = (int) (key & 7);
                Object value;
                switch (wire) {
                    case 0:
                        value = readVarint(data, pos);
                        break;
                    case 1:
                        skip(data, pos, 8);
                        continue;
                    case 2: {
                        long len = readVarint(data, pos);
                        if (len < 0 || len > data.length - pos[0]) throw new IOException("tamanho inválido");
                        byte[] b = new byte[(int) len];
                        System.arraycopy(data, pos[0], b, 0, (int) len);
                        pos[0] += (int) len;
                        value = b;
                        break;
                    }
                    case 5:
                        skip(data, pos, 4);
                        continue;
                    default:
                        throw new IOException("tipo de campo desconhecido: " + wire);
                }
                List<Object> list = fields.get(field);
                if (list == null) {
                    list = new ArrayList<>();
                    fields.put(field, list);
                }
                list.add(value);
            }
            return new Message(fields);
        }
    }

    private static void skip(byte[] data, int[] pos, int n) throws IOException {
        if (pos[0] + n > data.length) throw new IOException("mensagem truncada");
        pos[0] += n;
    }

    private static long readVarint(byte[] data, int[] pos) throws IOException {
        long result = 0;
        int shift = 0;
        while (shift < 64) {
            if (pos[0] >= data.length) throw new IOException("varint truncado");
            int b = data[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("varint longo demais");
    }

    // ------------------------------------------------------------ enquadramento

    /** Envia uma mensagem precedida do tamanho (varint). */
    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 5);
        writeVarint(buf, payload.length);
        buf.write(payload, 0, payload.length);
        out.write(buf.toByteArray());
        out.flush();
    }

    /** Lê uma mensagem precedida do tamanho (varint). Bloqueia até chegar. */
    public static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("conexão encerrada pela TV");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("tamanho de mensagem inválido");
        }
        if (len > MAX_MESSAGE) throw new IOException("mensagem grande demais");
        byte[] data = new byte[(int) len];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) throw new EOFException("conexão encerrada pela TV");
            off += n;
        }
        return data;
    }
}
