package se.grynna.pager.db;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.LoadedLogLine;

import java.nio.ByteBuffer;

/**
 * MVStore value type for {@link LoadedLogLine} rows: Kryo serialization instead
 * of Java serialization, compressed and encrypted per page by {@link RowCodec}.
 *
 * <p>The bulk {@code write}/{@code read} methods are called once per MVStore
 * page, so a whole page is serialized, compressed and sealed in one go. The
 * single-value methods use the same payload format, which makes both forms
 * readable without a format flag: the payload contains exactly as many Kryo
 * objects as the caller asks for.
 *
 * <p>Kryo (and its {@link Output}/{@link Input}) is not thread-safe, and loads
 * run on virtual threads, so instances come from a {@link Pool} rather than a
 * {@code ThreadLocal} (which would pin one instance per virtual thread).
 */
public final class LoadedLogLineDataType extends BasicDataType<LoadedLogLine> {

    private static final int INITIAL_BUFFER_BYTES = 8 * 1024;
    private static final int POOL_CAPACITY = 16;

    private static final Pool<Kryo> KRYO_POOL = new Pool<>(true, false, POOL_CAPACITY) {
        @Override
        protected Kryo create() {
            Kryo kryo = new Kryo();
            kryo.setRegistrationRequired(true);
            kryo.register(LoadedLogLine.class, new LoadedLogLineSerializer());
            return kryo;
        }

        @Override
        protected void reset(Kryo kryo) {
            kryo.reset();
        }
    };

    // Pooled buffers keep decrypted row bytes after being freed; accepted, heap contents are
    // not in the threat model (unlike RowCodec). Applies to INPUT_POOL too.
    private static final Pool<Output> OUTPUT_POOL = new Pool<>(true, false, POOL_CAPACITY) {
        @Override
        protected Output create() {
            // Unlimited growth: one output buffer holds a whole MVStore page.
            return new Output(INITIAL_BUFFER_BYTES, -1);
        }

        @Override
        protected void reset(Output output) {
            output.reset();
        }
    };

    private static final Pool<Input> INPUT_POOL = new Pool<>(true, false, POOL_CAPACITY) {
        @Override
        protected Input create() {
            return new Input(INITIAL_BUFFER_BYTES);
        }

        @Override
        protected void reset(Input input) {
            input.reset();
        }
    };

    private final RowCodec codec;

    LoadedLogLineDataType(RowCodec codec) {
        this.codec = codec;
    }

    @Override
    public int compare(LoadedLogLine a, LoadedLogLine b) {
        // Values are never compared by MVStore; rows are looked up by their Integer key.
        return 0;
    }

    @Override
    public int getMemory(LoadedLogLine row) {
        // Approximate serialized size for MVStore's page accounting. Strings dominate;
        // a constant would let pages with multi-kilobyte raw events grow too large.
        int size = 128
                + stringMemory(row.getSegmentId())
                + stringMemory(row.getBkt())
                + stringMemory(row.getCb())
                + stringMemory(row.getMessage())
                + stringMemory(row.getRaw());
        if (row.getEventMap() != null) {
            size += 32;
            for (var entry : row.getEventMap().entrySet()) {
                size += 16 + stringMemory(entry.getKey()) + stringMemory(entry.getValue());
            }
        }
        Activity activity = row.getActivity();
        if (activity != null) {
            size += 48
                    + stringMemory(activity.machine())
                    + stringMemory(activity.process())
                    + stringMemory(activity.thread())
                    + stringMemory(activity.request())
                    + stringMemory(activity.origin())
                    + stringMemory(activity.processTerm());
        }
        return size;
    }

    private static int stringMemory(String value) {
        return value == null ? 0 : value.length() + 8;
    }

    @Override
    public LoadedLogLine[] createStorage(int size) {
        return new LoadedLogLine[size];
    }

    @Override
    public void write(WriteBuffer buff, LoadedLogLine row) {
        writeRows(buff, new LoadedLogLine[]{row}, 1);
    }

    @Override
    public void write(WriteBuffer buff, Object storage, int len) {
        writeRows(buff, cast(storage), len);
    }

    @Override
    public LoadedLogLine read(ByteBuffer buff) {
        return readRows(buff, new LoadedLogLine[1], 1)[0];
    }

    @Override
    public void read(ByteBuffer buff, Object storage, int len) {
        readRows(buff, cast(storage), len);
    }

    private void writeRows(WriteBuffer buff, LoadedLogLine[] rows, int len) {
        Output output = OUTPUT_POOL.obtain();
        Kryo kryo = KRYO_POOL.obtain();
        try {
            output.reset();
            for (int i = 0; i < len; i++) {
                kryo.writeObject(output, rows[i]);
            }
            byte[] sealed = codec.seal(output.getBuffer(), output.position());
            buff.putInt(sealed.length);
            buff.put(sealed);
        } finally {
            KRYO_POOL.free(kryo);
            OUTPUT_POOL.free(output);
        }
    }

    private LoadedLogLine[] readRows(ByteBuffer buff, LoadedLogLine[] rows, int len) {
        byte[] sealed = new byte[buff.getInt()];
        buff.get(sealed);
        byte[] payload = codec.open(sealed);
        Input input = INPUT_POOL.obtain();
        Kryo kryo = KRYO_POOL.obtain();
        try {
            input.setBuffer(payload);
            for (int i = 0; i < len; i++) {
                rows[i] = kryo.readObject(input, LoadedLogLine.class);
            }
            return rows;
        } finally {
            KRYO_POOL.free(kryo);
            INPUT_POOL.free(input);
        }
    }
}
